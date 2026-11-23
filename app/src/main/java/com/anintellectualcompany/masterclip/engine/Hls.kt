package com.anintellectualcompany.masterclip.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * "Clip what already happened" on a live stream. yt-dlp/ffmpeg fetch the stream's pieces one after
 * the other, which only runs about as fast as the stream plays (10 minutes of history = 10 minutes).
 * The pieces are plain files, so we fetch several at once and join them; [Remux] makes the mp4.
 */
object Hls {
    private const val TAG = "MasterClip"
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    class Seg(val seq: Long, val url: String, val dur: Double)

    /** The pieces listed in an HLS media playlist, numbered by #EXT-X-MEDIA-SEQUENCE. */
    fun parse(playlist: String, baseUrl: String): List<Seg> {
        if ("#EXT-X-STREAM-INF" in playlist) return emptyList()
        var seq = 0L
        var dur = -1.0
        val out = ArrayList<Seg>()
        for (raw in playlist.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXTINF:") -> dur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: -1.0
                line.isNotEmpty() && !line.startsWith("#") && dur >= 0 -> {
                    val url = if (line.startsWith("http")) line else URL(URL(baseUrl), line).toString()
                    out += Seg(seq++, url, dur)
                    dur = -1.0
                }
            }
        }
        return out
    }

    /** The pieces for the last [seconds] of the list, leaving [margin] seconds off the end (the newest pieces may not be ready). */
    fun lastPieces(segs: List<Seg>, seconds: Double, margin: Double = 12.0): List<Seg> {
        if (segs.isEmpty()) return emptyList()
        var end = segs.size
        var drop = 0.0
        while (end > 1 && drop + segs[end - 1].dur <= margin) { drop += segs[end - 1].dur; end-- }
        var start = end
        var got = 0.0
        while (start > 0 && got < seconds) { start--; got += segs[start].dur }
        return segs.subList(start, end)
    }

    private fun text(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) throw FriendlyError("The live stream's list of pieces can't be read (HTTP ${c.responseCode}).")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun bytes(url: String): ByteArray {
        var last: Exception? = null
        repeat(3) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", UA)
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                return c.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                last = e
            } finally {
                c.disconnect()
            }
        }
        throw FriendlyError("The live download was interrupted (${last?.message}).")
    }

    /** Pieces and playlist length (seconds) of the playlist at [url] as it is right now. */
    fun load(url: String): List<Seg> = parse(text(url), url)

    /**
     * Downloads the last [seconds] of a live stream: picture from [videoUrl], sound from [audioUrl] (both
     * HLS playlists), into [videoOut] (.ts) and [audioOut] (.aac). Pieces are matched by number, so
     * picture and sound start together. Returns the seconds actually covered.
     */
    suspend fun fetchLast(videoUrl: String, audioUrl: String, seconds: Double, videoOut: File, audioOut: File): Double = withContext(Dispatchers.IO) {
        val v = load(videoUrl)
        val a = load(audioUrl)
        val vp = lastPieces(v, seconds)
        if (vp.isEmpty()) throw FriendlyError("The live stream has nothing to download yet.")
        val aBySeq = a.associateBy { it.seq }
        val ap = vp.mapNotNull { aBySeq[it.seq] }
        if (ap.size < vp.size) throw FriendlyError("The live stream's picture and sound don't line up.")
        suspend fun get(pieces: List<Seg>, out: File) = coroutineScope {
            out.delete()
            val sem = Semaphore(4)
            out.outputStream().buffered().use { os ->
                // In small batches, so the phone never holds more than a few pieces in memory.
                for (batch in pieces.chunked(8)) {
                    val jobs = batch.map { p -> async { sem.withPermit { coroutineContext.ensureActive(); bytes(p.url) } } }
                    for (j in jobs) os.write(j.await())
                }
            }
        }
        val t = System.currentTimeMillis()
        coroutineScope {
            val x = async { get(vp, videoOut) }
            val y = async { get(ap, audioOut) }
            x.await(); y.await()
        }
        Log.i(TAG, "hls fetched ${vp.size} pieces in ${System.currentTimeMillis() - t} ms (${(videoOut.length() + audioOut.length()) / 1024} KB)")
        vp.sumOf { it.dur }
    }
}
