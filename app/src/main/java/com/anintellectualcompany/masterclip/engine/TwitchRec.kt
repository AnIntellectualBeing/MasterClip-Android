package com.anintellectualcompany.masterclip.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.coroutineContext

/** Playlist reading for Twitch live streams: which pieces are the stream and which are stitched-in ads. Pure, unit tested. */
object TwitchPlaylist {
    class Piece(val seq: Long, val url: String, val dur: Double, val ad: Boolean)

    private val attr = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private fun attrs(line: String): Map<String, String> =
        attr.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    private fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (f in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")) {
            try {
                val p = SimpleDateFormat(f, Locale.US)
                p.timeZone = TimeZone.getTimeZone("UTC")
                return p.parse(s)?.time
            } catch (e: Exception) { /* try the next */ }
        }
        return null
    }

    /** True when a DATERANGE line marks an ad break. */
    fun isAdRange(a: Map<String, String>): Boolean =
        a["CLASS"] == "twitch-stitched-ad" || a["ID"].orEmpty().startsWith("stitched-ad") || a.keys.any { it.startsWith("X-TV-TWITCH-AD-") }

    /**
     * The pieces of a media playlist. A piece is an ad when its #EXTINF title is something other than "live",
     * or it falls inside a stitched-ad #EXT-X-DATERANGE (by its #EXT-X-PROGRAM-DATE-TIME). Same rules as streamlink.
     */
    fun parse(playlist: String, baseUrl: String): List<Piece> {
        if ("#EXT-X-STREAM-INF" in playlist) return emptyList()
        val ranges = ArrayList<Pair<Long, Long>>() // ad ranges, epoch ms
        var openEnded = false // an ad range without a known end: everything after its start is an ad
        var seq = 0L
        var dur = -1.0
        var title = ""
        var pdt: Long? = null
        val out = ArrayList<Piece>()
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-DATERANGE:") -> {
                    val a = attrs(line)
                    if (isAdRange(a)) {
                        val s = parseDate(a["START-DATE"])
                        val d = a["DURATION"]?.toDoubleOrNull() ?: a["PLANNED-DURATION"]?.toDoubleOrNull()
                        val e = parseDate(a["END-DATE"])
                        if (s != null) {
                            if (e != null) ranges += s to e
                            else if (d != null) ranges += s to s + (d * 1000).toLong()
                            else ranges += s to Long.MAX_VALUE
                        } else openEnded = true
                    }
                }
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> pdt = parseDate(line.substringAfter(':').trim())
                line.startsWith("#EXTINF:") -> {
                    val body = line.substringAfter(':')
                    dur = body.substringBefore(',').trim().toDoubleOrNull() ?: -1.0
                    title = if (',' in body) body.substringAfter(',').trim() else ""
                }
                line.isNotEmpty() && !line.startsWith("#") && dur >= 0 -> {
                    val url = if (line.startsWith("http")) line else URL(URL(baseUrl), line).toString()
                    val p = pdt
                    val inRange = p != null && ranges.any { p >= it.first && p < it.second }
                    val ad = (title.isNotEmpty() && title != "live") || inRange || openEnded
                    out += Piece(seq++, url, dur, ad)
                    if (p != null) pdt = p + (dur * 1000).toLong()
                    dur = -1.0
                    title = ""
                }
            }
        }
        return out
    }

    /** Picks the best H.264 variant of at most 1080 lines from a master playlist. Null when there is none. */
    fun pickVariant(master: String, baseUrl: String): String? {
        class V(val url: String, val h: Int, val bw: Long, val avc: Boolean)
        val vs = ArrayList<V>()
        val lines = master.lines().map { it.trim() }
        for ((i, l) in lines.withIndex()) {
            if (!l.startsWith("#EXT-X-STREAM-INF:")) continue
            val a = attrs(l)
            val next = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val h = a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: continue
            val codecs = a["CODECS"].orEmpty()
            val url = if (next.startsWith("http")) next else URL(URL(baseUrl), next).toString()
            vs += V(url, h, a["BANDWIDTH"]?.toLongOrNull() ?: 0, codecs.isEmpty() || codecs.contains("avc1"))
        }
        val ok = vs.filter { it.avc && it.h <= 1080 }
        return ok.maxWithOrNull(compareBy<V> { it.h }.thenBy { it.bw })?.url
    }

    /** Channel name from a twitch.tv link. */
    fun channel(url: String): String? =
        Regex("""twitch\.tv/([A-Za-z0-9_]{2,25})(?:[/?#]|$)""").find(url)?.groupValues?.get(1)?.lowercase()
            ?.takeIf { it !in setOf("videos", "directory", "downloads", "settings", "p") }
}

/**
 * Records a Twitch live stream without the ads. yt-dlp keeps everything the stream sends, and for people who
 * aren't signed in Twitch stitches ad breaks in (with time jumps), so clips came out showing the ad.
 * Here the playlist is read every couple of seconds and only the pieces that are the stream are kept.
 */
object TwitchRec {
    private const val TAG = "MasterClip"
    private const val GQL = "https://gql.twitch.tv/gql"
    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    /** What the recorder is doing now; read by the progress ticker. */
    class Status {
        @Volatile var liveSec = 0.0
        @Volatile var adSkipped = 0
        @Volatile var waitingAd = false
    }

    @Volatile private var cur: HttpURLConnection? = null

    private fun open(url: String, post: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", UA)
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Client-ID", CLIENT_ID)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        cur = c
        return c
    }

    private fun text(url: String, post: String? = null): String {
        val c = open(url, post)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun bytes(url: String): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** The playlist of the stream's best picture, from Twitch's usher with a fresh token. */
    fun mediaPlaylist(login: String): String {
        val q = "query(\$l:String!){ streamPlaybackAccessToken(channelName:\$l, params:{platform:\"web\",playerBackend:\"mediaplayer\",playerType:\"site\"}){ value signature } }"
        val body = JSONObject().put("query", q).put("variables", JSONObject().put("l", login)).toString()
        val tok = JSONObject(text(GQL, body)).optJSONObject("data")?.optJSONObject("streamPlaybackAccessToken")
            ?: throw FriendlyError("Twitch didn't hand out a stream. The channel may have gone offline.")
        val usher = "https://usher.ttvnw.net/api/channel/hls/$login.m3u8" +
            "?sig=${tok.getString("signature")}&token=${URLEncoder.encode(tok.getString("value"), "UTF-8")}" +
            "&allow_source=true&allow_audio_only=false&player=twitchweb&supported_codecs=avc1&fast_bread=true&p=${(Math.random() * 1_000_000).toInt()}"
        val master = text(usher)
        return TwitchPlaylist.pickVariant(master, usher) ?: throw FriendlyError("Twitch has no picture MasterClip can read for this stream.")
    }

    /**
     * Records [seconds] of the stream (ads don't count) into [out] (.ts). Gives up after [seconds] + 5 minutes
     * of real time and keeps what it has. Returns the seconds of stream recorded.
     */
    suspend fun record(login: String, seconds: Double, out: File, status: Status): Double = withContext(Dispatchers.IO) {
      coroutineScope {
        val parent = coroutineContext.job
        // Cancel must stop a download that is in progress, not wait for it.
        val watcher = launch { try { awaitCancellation() } finally { if (parent.isCancelled) runCatching { cur?.disconnect() } } }
        out.delete()
        var playlistUrl = mediaPlaylist(login)
        val started = System.currentTimeMillis()
        val deadline = started + ((seconds + 300) * 1000).toLong()
        var last = -1L
        var failures = 0
        var wrote = 0
        out.outputStream().buffered().use { os ->
            while (status.liveSec < seconds && System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                val t0 = System.currentTimeMillis()
                try {
                    val pieces = TwitchPlaylist.parse(text(playlistUrl), playlistUrl)
                    failures = 0
                    // First look: begin 3 pieces from the live edge. After that: only pieces not seen yet.
                    val fresh = if (last < 0) pieces.takeLast(3) else pieces.filter { it.seq > last }
                    var adNow = false
                    for (p in fresh) {
                        coroutineContext.ensureActive()
                        last = p.seq
                        if (p.ad) { adNow = true; status.adSkipped++; continue }
                        os.write(bytes(p.url))
                        wrote++
                        status.liveSec += p.dur
                        if (status.liveSec >= seconds) break
                    }
                    status.waitingAd = adNow || (fresh.isEmpty() && status.waitingAd)
                    if (fresh.any { !it.ad }) status.waitingAd = false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    if (++failures >= 8) {
                        if (wrote > 0) break
                        throw FriendlyError("The Twitch recording was interrupted (${e.message}).")
                    }
                    Log.w(TAG, "twitch poll failed (${e.message}); retrying")
                    // The token may have expired or the stream restarted: get a fresh playlist address.
                    if (failures % 3 == 0) runCatching { playlistUrl = mediaPlaylist(login) }
                }
                val took = System.currentTimeMillis() - t0
                if (status.liveSec < seconds) delay((2000 - took).coerceAtLeast(300))
            }
        }
        Log.i(TAG, "twitch rec: ${status.liveSec.toInt()}s live in ${(System.currentTimeMillis() - started) / 1000}s, $wrote pieces, ${status.adSkipped} ad pieces skipped, ${out.length() / 1024} KB")
        if (wrote == 0) throw FriendlyError("The Twitch stream didn't give any picture. It may have gone offline.")
        watcher.cancel()
        status.liveSec
      }
    }
}
