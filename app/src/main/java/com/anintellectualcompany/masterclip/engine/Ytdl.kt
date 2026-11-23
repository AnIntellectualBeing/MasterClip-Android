package com.anintellectualcompany.masterclip.engine

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.UUID
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
)

class FriendlyError(message: String) : Exception(message)

/** yt-dlp (with Python, ffmpeg and QuickJS bundled by youtubedl-android). */
object Ytdl {
    private const val TAG = "MasterClip"
    @Volatile private var ready = false
    /** Downloads hold the read side; replacing yt-dlp needs the write side, so it never swaps mid-download. */
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /** H.264 first (every phone decodes it in hardware), never above 1080p. */
    private const val FORMAT =
        "bv*[vcodec^=avc1][height<=1080]+ba[ext=m4a]/bv*[vcodec^=avc1][height<=1080]+ba/" +
            "b[ext=mp4][height<=1080]/bv*[height<=1080]+ba/b[height<=1080]/b"

    @Synchronized
    fun ensure(context: Context) {
        if (ready) return
        try {
            YoutubeDL.init(context.applicationContext)
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

    private fun run(request: YoutubeDLRequest, id: String, onProgress: ((Float) -> Unit)? = null): String {
        current = id
        try {
            val res = lock.read { YoutubeDL.execute(request, id) { pct, _, _ -> if (pct >= 0) onProgress?.invoke(pct / 100f) } }
            return res.out
        } catch (e: YoutubeDL.CanceledException) {
            throw kotlinx.coroutines.CancellationException("cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp failed: ${e.message}")
            throw FriendlyError(explain(e.message ?: ""))
        } finally {
            current = null
        }
    }

    @Volatile private var current: String? = null

    fun cancel() {
        current?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
    }

    fun info(url: String): VideoMeta {
        val req = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--no-warnings")
        }
        val out = run(req, "info-" + UUID.randomUUID())
        val json = JSONObject(out.substring(out.indexOf('{')))
        if (json.optString("_type") == "playlist") throw FriendlyError("That's a playlist. Paste the link of one video.")
        return parseMeta(url, json)
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
        return VideoMeta(
            url = json.optString("webpage_url", url).ifBlank { url },
            title = json.optString("title", "Video"),
            duration = json.optDouble("duration", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            isLive = json.optBoolean("is_live", false),
            heatmap = heat,
            language = json.optString("language", "").ifBlank { null },
            manualSubs = keys("subtitles"),
            autoSubs = keys("automatic_captions"),
            extractor = json.optString("extractor_key", ""),
        )
    }

    /** Downloads only [start, end] of the video. Returns the mp4. */
    fun section(url: String, start: Double, end: Double, dir: File, name: String, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith("$name.") }?.forEach { it.delete() }
        val req = YoutubeDLRequest(url).apply {
            addOption("-f", FORMAT)
            addOption("--download-sections", "*%.2f-%.2f".format(java.util.Locale.US, start, end))
            addOption("--merge-output-format", "mp4")
            addOption("--no-playlist")
            addOption("--concurrent-fragments", "4")
            addOption("--no-mtime")
            addOption("-o", File(dir, "$name.%(ext)s").absolutePath)
        }
        run(req, "sec-" + UUID.randomUUID(), onProgress)
        return dir.listFiles { f -> f.name.startsWith("$name.") && !f.name.endsWith(".part") && f.length() > 0 }
            ?.maxByOrNull { it.length() }
            ?: throw FriendlyError("This part of the video didn't download. Try again, or pick another video.")
    }

    /** Small audio-only copy, used to find loud moments when YouTube has no "most replayed" data. */
    fun audio(url: String, dir: File, rangeStart: Double?, rangeEnd: Double?, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        val req = YoutubeDLRequest(url).apply {
            addOption("-f", "ba[abr<=96]/wa/ba/w")
            addOption("--no-playlist")
            addOption("--concurrent-fragments", "4")
            if (rangeStart != null || rangeEnd != null) {
                addOption("--download-sections", "*%.0f-%s".format(java.util.Locale.US, rangeStart ?: 0.0, rangeEnd?.let { "%.0f".format(java.util.Locale.US, it) } ?: "inf"))
            }
            addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
        }
        run(req, "aud-" + UUID.randomUUID(), onProgress)
        return dir.listFiles { f -> f.name.startsWith("audio.") && !f.name.endsWith(".part") }?.maxByOrNull { it.length() }
            ?: throw FriendlyError("Couldn't read the sound of this video.")
    }

    /** The caption track as json3 (word timings) or vtt. Null when YouTube gives none. */
    fun captions(url: String, track: String, dir: File): File? {
        dir.mkdirs()
        val req = YoutubeDLRequest(url).apply {
            addOption("--skip-download")
            addOption("--write-subs")
            addOption("--write-auto-subs")
            addOption("--sub-langs", track)
            addOption("--sub-format", "json3/vtt/best")
            addOption("--no-playlist")
            addOption("-o", File(dir, "subs.%(ext)s").absolutePath)
        }
        return try {
            run(req, "sub-" + UUID.randomUUID())
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
            listOf("no address associated", "errno 7", "name resolution", "failed to resolve", "getaddrinfo",
                "network is unreachable", "connection refused", "timed out", "connection reset").any { it in e } ->
                "Couldn't reach the internet. Check your Wi-Fi or mobile data, then tap Try again."
            "no space" in e -> "Your phone is out of space. Free some space and try again."
            "http error 429" in e || "too many requests" in e -> "The site is busy. Wait a minute and try again."
            else -> "Couldn't open this video. " + (err.lineSequence().lastOrNull { it.contains("ERROR") }
                ?.substringAfter("ERROR:")?.trim()?.take(160) ?: "Check the link and your internet, then try again.")
        }
    }
}
