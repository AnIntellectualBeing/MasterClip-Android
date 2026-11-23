package com.anintellectualcompany.masterclip.engine

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.read
import kotlin.concurrent.write

data class VideoMeta(
    val url: String,
    val title: String,
    val duration: Double,
    val isLive: Boolean,
    val heatmap: List<HeatPoint>,
    val language: String?,
    val manualSubs: Set<String>,
    val autoSubs: Set<String>,
    val extractor: String,
    /** HLS playlist of a <=720p video format; only filled for live streams. */
    val liveManifest: String? = null,
    /** Bit rate (kbit/s) of the video format chosen for live recording; used to guess the file size. */
    val liveKbps: Double = 0.0,
)

class FriendlyError(message: String) : Exception(message)

/** yt-dlp (with Python, ffmpeg and QuickJS bundled by youtubedl-android). */
object Ytdl {
    private const val TAG = "MasterClip"
    @Volatile private var ready = false
    /** yt-dlp keeps what it worked out about YouTube's player here, so the next video does not redo it. */
    @Volatile private var cacheDir: File? = null
    /** Downloads hold the read side; replacing yt-dlp needs the write side, so it never swaps mid-download. */
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /** H.264 first (every phone decodes it in hardware), never above 1080p. */
    private const val FORMAT =
        "bv*[vcodec^=avc1][height<=1080]+ba[ext=m4a]/bv*[vcodec^=avc1][height<=1080]+ba/" +
            "b[ext=mp4][height<=1080]/bv*[height<=1080]+ba/b[height<=1080]/b"

    /** Live: 720p keeps the recording small enough for a phone (about 33 MB a minute at the top). */
    private const val FORMAT_LIVE =
        "bv*[vcodec^=avc1][height<=720]+ba/b[vcodec^=avc1][height<=720]/bv*[height<=720]+ba/b[height<=720]/b/w"

    /** Smallest sound track: only used to find loud moments. */
    const val FORMAT_AUDIO = "599/139/600/249/audio_only/wa/ba/w"

    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        try {
            YoutubeDL.init(context.applicationContext)
            cacheDir = File(context.applicationContext.filesDir, "ytcache").apply { mkdirs() }
            FFmpeg.init(context.applicationContext)
            ready = true
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            throw FriendlyError("The clip engine couldn't start on this phone. Restart the app and try again.")
        }
    }

    /** Keeps yt-dlp current (YouTube changes often). At most once a day. */
    fun updateIfDue(context: Context, force: Boolean = false): Boolean {
        val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        val last = prefs.getLong("ytdlp_update", 0)
        if (!force && System.currentTimeMillis() - last < 24 * 3600_000L) return false
        return try {
            ensure(context)
            val status = lock.write { YoutubeDL.updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE) }
            prefs.edit().putLong("ytdlp_update", System.currentTimeMillis()).apply()
            Log.i(TAG, "yt-dlp update: $status -> ${YoutubeDL.version(context)}")
            status == YoutubeDL.UpdateStatus.DONE
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
            false
        }
    }

    // ------------------------------------------------------------ running yt-dlp

    /** Ids of every yt-dlp process that is running now, so Cancel can stop all of them. */
    private val running = ConcurrentHashMap.newKeySet<String>()

    private fun run(request: YoutubeDLRequest, id: String, onProgress: ((Float) -> Unit)? = null): String {
        running += id
        try {
            val res = lock.read { YoutubeDL.execute(request, id) { pct, _, _ -> if (pct >= 0) onProgress?.invoke(pct / 100f) } }
            return res.out
        } catch (e: YoutubeDL.CanceledException) {
            throw kotlinx.coroutines.CancellationException("cancelled")
        } catch (e: Exception) {
            if (cancelling) throw kotlinx.coroutines.CancellationException("cancelled")
            Log.w(TAG, "yt-dlp failed: ${e.message?.takeLast(600)}")
            throw FriendlyError(explain(e.message ?: ""))
        } finally {
            running -= id
        }
    }

    @Volatile private var cancelling = false

    /** Stops every yt-dlp (and the ffmpeg it started) at once. */
    fun cancel() {
        cancelling = true
        for (id in running.toList()) runCatching { YoutubeDL.destroyProcessById(id) }
        killFfmpeg()
    }

    /** A new job starts: forget the last job's cancel. */
    fun beginJob() {
        cancelling = false
        infoFile = null
        formats = null
        Dash.forgetIndexes()
    }

    /** yt-dlp's own process can be killed, but the ffmpeg it started may live on: find it in /proc and stop it. */
    private fun killFfmpeg() {
        runCatching {
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                val cmd = runCatching { File(d, "cmdline").readText() }.getOrDefault("")
                if (cmd.contains("libffmpeg") || cmd.contains("/ffmpeg")) android.os.Process.killProcess(pid)
            }
        }
    }

    // ------------------------------------------------------------ the info file

    /**
     * The video's info, written once by [info]. Every later call hands this file to yt-dlp
     * (--load-info-json) so it does not open the YouTube page and solve the signature again.
     */
    @Volatile private var infoFile: File? = null

    /** The formats from the same info (direct file links), for the fast YouTube download in [Dash]. */
    @Volatile var formats: org.json.JSONArray? = null
        private set

    private fun request(url: String, useInfo: Boolean, configure: YoutubeDLRequest.() -> Unit): YoutubeDLRequest {
        val f = infoFile
        val req = if (useInfo && f != null) YoutubeDLRequest(emptyList<String>()).apply { addOption("--load-info-json", f.absolutePath) }
        else YoutubeDLRequest(url)
        cacheDir?.let { req.addOption("--cache-dir", it.absolutePath) }
        req.configure()
        return req
    }

    /** Runs a yt-dlp call from the saved info; if that fails (links expired, odd site), once more from the page. */
    private fun exec(url: String, id: String, onProgress: ((Float) -> Unit)?, configure: YoutubeDLRequest.() -> Unit): String {
        if (infoFile != null) {
            try {
                return run(request(url, true, configure), id, onProgress)
            } catch (e: FriendlyError) {
                if ("internet" in (e.message ?: "")) throw e
                Log.w(TAG, "saved info failed (${e.message}); trying the page again")
            }
        }
        return run(request(url, false, configure), id + "-b", onProgress)
    }

    /**
     * Reads the video's details. For YouTube it first asks as the "visionos" app: the answer has the same
     * formats, subtitles and replay heat map, but needs no JavaScript puzzle solved, which takes about
     * 70 seconds on a phone (QuickJS) against a few seconds. If that doesn't give what we need, it asks the normal way.
     */
    fun info(url: String, workDir: File): VideoMeta {
        workDir.mkdirs()
        val isYoutube = "youtube.com" in url || "youtu.be" in url
        if (isYoutube) {
            try {
                val t = System.currentTimeMillis()
                val (meta, json, text) = readInfo(url, fast = true)
                val usable = if (meta.isLive) meta.liveManifest != null else formats(json)?.let { Dash.pickVideo(it) != null && Dash.pickAudio(it, false) != null } == true
                if (usable) {
                    Log.i(TAG, "info (fast client) in ${System.currentTimeMillis() - t} ms")
                    keep(workDir, json, text)
                    return meta
                }
                Log.w(TAG, "fast client gave too few formats; asking the normal way")
            } catch (e: FriendlyError) {
                if ("internet" in (e.message ?: "") || "playlist" in (e.message ?: "")) throw e
                Log.w(TAG, "fast client failed (${e.message}); asking the normal way")
            }
        }
        val t = System.currentTimeMillis()
        val (meta, json, text) = readInfo(url, fast = false)
        Log.i(TAG, "info (normal) in ${System.currentTimeMillis() - t} ms")
        keep(workDir, json, text)
        return meta
    }

    private fun formats(json: JSONObject) = json.optJSONArray("formats")

    private fun keep(workDir: File, json: JSONObject, text: String) {
        formats = json.optJSONArray("formats")
        runCatching {
            val f = File(workDir, "info.json")
            f.writeText(text)
            infoFile = f
        }
    }

    private fun readInfo(url: String, fast: Boolean): Triple<VideoMeta, JSONObject, String> {
        val req = YoutubeDLRequest(url).apply {
            cacheDir?.let { addOption("--cache-dir", it.absolutePath) }
            if (fast) addOption("--extractor-args", "youtube:player_client=visionos")
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
        }
        val out = run(req, "info-" + UUID.randomUUID())
        val text = out.substring(out.indexOf('{'))
        val json = JSONObject(text)
        if (json.optString("_type") == "playlist") throw FriendlyError("That's a playlist. Paste the link of one video.")
        return Triple(parseMeta(url, json), json, text)
    }

    fun parseMeta(url: String, json: JSONObject): VideoMeta {
        val heat = ArrayList<HeatPoint>()
        json.optJSONArray("heatmap")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                heat += HeatPoint(p.optDouble("start_time"), p.optDouble("end_time"), p.optDouble("value"))
            }
        }
        fun keys(name: String): Set<String> = json.optJSONObject(name)?.keys()?.asSequence()?.filter { it != "live_chat" }?.toSet() ?: emptySet()
        val live = json.optBoolean("is_live", false)
        var manifest: String? = null
        var kbps = 0.0
        if (live) {
            json.optJSONArray("formats")?.let { arr ->
                var bestH = -1
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    val h = f.optInt("height", 0)
                    val vc = f.optString("vcodec", "none")
                    val proto = f.optString("protocol", "")
                    if (h in 1..720 && vc != "none" && proto.startsWith("m3u8") && h > bestH) {
                        bestH = h
                        manifest = f.optString("url").ifBlank { null }
                        kbps = f.optDouble("tbr", 0.0).takeIf { !it.isNaN() } ?: 0.0
                    }
                }
            }
        }
        return VideoMeta(
            url = json.optString("webpage_url", url).ifBlank { url },
            title = json.optString("title", "Video"),
            duration = json.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            isLive = live,
            heatmap = heat,
            language = json.optString("language", "").ifBlank { null },
            manualSubs = keys("subtitles"),
            autoSubs = keys("automatic_captions"),
            extractor = json.optString("extractor_key", ""),
            liveManifest = manifest,
            liveKbps = kbps,
        )
    }

    // ------------------------------------------------------------ downloads

    /** Downloads only [start, end] of the video. Returns the mp4. */
    fun section(url: String, start: Double, end: Double, dir: File, name: String, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("$name.") }?.forEach { it.delete() }
        exec(url, "sec-" + UUID.randomUUID(), onProgress) {
            addOption("-f", FORMAT)
            addOption("--download-sections", "*%.2f-%.2f".format(java.util.Locale.US, start, end))
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--concurrent-fragments", "4")
            addOption("--no-mtime")
            addOption("-o", File(dir, "$name.%(ext)s").absolutePath)
        }
        return dir.listFiles { f -> f.name.startsWith("$name.") && !f.name.endsWith(".part") && f.length() > 0 }
            ?.maxByOrNull { it.length() }
            ?: throw FriendlyError("This part of the video didn't download. Try again, or pick another video.")
    }

    /** Small audio-only copy of [start, end] seconds (or the whole video when null), used to find loud moments. */
    fun audio(url: String, dir: File, name: String, start: Double?, end: Double?, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("$name.") }?.forEach { it.delete() }
        exec(url, "aud-" + UUID.randomUUID(), onProgress) {
            addOption("-f", FORMAT_AUDIO)
            addOption("--no-playlist")
            addOption("--concurrent-fragments", "4")
            addOption("--no-mtime")
            if (start != null || end != null) {
                addOption("--download-sections", "*%.0f-%s".format(java.util.Locale.US, start ?: 0.0, end?.let { "%.0f".format(java.util.Locale.US, it) } ?: "inf"))
            }
            addOption("-o", File(dir, "$name.%(ext)s").absolutePath)
        }
        return dir.listFiles { f -> f.name.startsWith("$name.") && !f.name.endsWith(".part") && f.length() > 0 }?.maxByOrNull { it.length() }
            ?: throw FriendlyError("Couldn't read the sound of this video.")
    }

    /**
     * Live stream to an mp4. [section] null = record from now for [seconds] (ffmpeg stops by itself);
     * otherwise a range of the stream's DVR window, in seconds from the start of that window.
     */
    fun live(url: String, dir: File, name: String, seconds: Int, section: Pair<Double, Double>?): File {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("$name.") }?.forEach { it.delete() }
        exec(url, "live-" + UUID.randomUUID(), null) {
            addOption("-f", FORMAT_LIVE)
            addOption("--no-playlist")
            addOption("--merge-output-format", "mp4")
            addOption("--no-mtime")
            if (section == null) {
                addOption("--downloader", "ffmpeg")
                addOption("--downloader-args", "ffmpeg_o:-t $seconds")
            } else {
                addOption("--download-sections", "*%.1f-%.1f".format(java.util.Locale.US, section.first, section.second))
            }
            addOption("-o", File(dir, "$name.%(ext)s").absolutePath)
        }
        return dir.listFiles { f -> f.name.startsWith("$name.") && f.name.endsWith(".mp4") && f.length() > 0 }?.maxByOrNull { it.length() }
            ?: throw FriendlyError("The live recording didn't come through. Check your internet and try again.")
    }

    /** The caption track as json3 (word timings) or vtt. Null when YouTube gives none. */
    fun captions(url: String, track: String, dir: File): File? {
        dir.mkdirs()
        return try {
            exec(url, "sub-" + UUID.randomUUID(), null) {
                addOption("--skip-download")
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-langs", track)
                addOption("--sub-format", "json3/vtt/best")
                addOption("--no-playlist")
                addOption("-o", File(dir, "subs.%(ext)s").absolutePath)
            }
            dir.listFiles { f -> f.name.startsWith("subs.") && (f.name.endsWith(".json3") || f.name.endsWith(".vtt")) }?.firstOrNull()
        } catch (e: FriendlyError) {
            null
        }
    }

    /** yt-dlp's English -> words a normal person understands. */
    fun explain(err: String): String {
        val e = err.lowercase()
        return when {
            "confirm you" in e && "bot" in e -> "YouTube wants to check this phone isn't a robot. Wait a few minutes, or switch between Wi-Fi and mobile data, then try again."
            "private video" in e -> "That video is private."
            "members-only" in e || "join this channel" in e -> "That video is for channel members only."
            "age" in e && ("restricted" in e || "confirm your age" in e) -> "That video is age-restricted, so it can't be opened without signing in."
            "is not a valid url" in e || "unsupported url" in e -> "That link isn't a video MasterClip can open. Copy the video's link from YouTube or Twitch."
            "video unavailable" in e || "has been removed" in e || "404" in e -> "That video isn't available any more."
            "premieres in" in e || "live event will begin" in e -> "That video hasn't started yet."
            "subscriber" in e && "only" in e -> "That video is for subscribers only."
            "no space" in e -> "Your phone is out of space. Free some space and try again."
            listOf("no address associated", "errno 7", "name resolution", "failed to resolve", "getaddrinfo",
                "network is unreachable", "connection refused", "timed out", "connection reset").any { it in e } ->
                "Couldn't reach the internet. Check your Wi-Fi or mobile data, then tap Try again."
            "http error 429" in e || "too many requests" in e -> "The site is busy. Wait a minute and try again."
            else -> "Couldn't open this video. " + (err.lineSequence().lastOrNull { it.contains("ERROR") }
                ?.substringAfter("ERROR:")?.trim()?.take(160) ?: "Check the link and your internet, then try again.")
        }
    }
}
