package com.anintellectualcompany.masterclip.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Fast piece download for YouTube.
 *
 * yt-dlp cuts a piece out of a YouTube video with ffmpeg, and YouTube slows ffmpeg's plain download
 * to about playback speed (about 2 minutes for a 45 second clip). Asking for exact byte ranges with a
 * few connections at once is not slowed. YouTube's "DASH" files are fragmented mp4 with an index (sidx),
 * so we read the index, fetch just the fragments that cover the wanted seconds, and join the init part
 * to them. The result is a normal fragmented mp4 that Android can read (see [Remux] for joining video and sound).
 */
object Dash {
    private const val TAG = "MasterClip"
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val CHUNK = 2L shl 20

    class Track(val url: String, val kbps: Double, val height: Int)

    /** Fragment sizes and lengths from the sidx box, plus where the file's index ends. */
    class Index(
        val initEnd: Long,
        val dataStart: Long,
        val timescale: Long,
        val firstTick: Long,
        val sizes: LongArray,
        val ticks: LongArray,
    )

    /** What to download: bytes [from, to) of the track, covering seconds [startSec, endSec] of the video. */
    class Cut(val from: Long, val to: Long, val startSec: Double, val endSec: Double)

    // ------------------------------------------------------------ choosing tracks (pure)

    /** H.264 mp4 video over plain https, the tallest up to [maxHeight]. */
    fun pickVideo(formats: JSONArray, maxHeight: Int = 1080): Track? {
        var best: Track? = null
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val h = f.optInt("height", 0)
            if (f.optString("protocol") != "https" || f.optString("ext") != "mp4") continue
            if (!f.optString("vcodec", "none").startsWith("avc1") || h <= 0 || h > maxHeight) continue
            if (f.optString("url").isBlank()) continue
            if (best == null || h > best.height) best = Track(f.optString("url"), f.optDouble("tbr", 0.0), h)
        }
        return best
    }

    /** AAC m4a over plain https. [small] = the lightest (to find loud moments); else the best one up to 200 kbit/s. */
    fun pickAudio(formats: JSONArray, small: Boolean): Track? {
        var best: Track? = null
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (f.optString("protocol") != "https" || f.optString("ext") != "m4a") continue
            if (!f.optString("acodec", "none").startsWith("mp4a") || f.optString("url").isBlank()) continue
            val kbps = f.optDouble("abr", f.optDouble("tbr", 0.0))
            if (!small && kbps > 200) continue
            val better = best == null || (if (small) kbps < best.kbps else kbps > best.kbps)
            if (better) best = Track(f.optString("url"), kbps, 0)
        }
        return best
    }

    // ------------------------------------------------------------ the index (pure)

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)

    /** Parses a whole sidx box (header included) that sits at byte [at] of the file. */
    fun parseSidx(box: ByteArray, at: Long, initEnd: Long = at): Index? {
        if (box.size < 32 || String(box, 4, 4, Charsets.ISO_8859_1) != "sidx") return null
        val size = u32(box, 0).let { if (it == 1L) u64(box, 8) else it }
        val version = box[8].toInt()
        var p = 12
        p += 4 // reference id
        val timescale = u32(box, p); p += 4
        val first: Long
        val offset: Long
        if (version == 0) {
            first = u32(box, p); offset = u32(box, p + 4); p += 8
        } else {
            first = u64(box, p); offset = u64(box, p + 8); p += 16
        }
        p += 2
        val n = ((box[p].toInt() and 0xff) shl 8) or (box[p + 1].toInt() and 0xff); p += 2
        if (timescale <= 0 || p + n * 12 > box.size) return null
        val sizes = LongArray(n)
        val ticks = LongArray(n)
        for (i in 0 until n) {
            sizes[i] = u32(box, p) and 0x7fffffffL
            ticks[i] = u32(box, p + 4)
            p += 12
        }
        return Index(initEnd, at + size + offset, timescale, first, sizes, ticks)
    }

    /** The fragments that cover [start, end] seconds. Null when the video is shorter than [start]. */
    fun plan(index: Index, start: Double, end: Double): Cut? {
        var tick = index.firstTick
        var pos = index.dataStart
        var from = -1L
        var startSec = 0.0
        var to = -1L
        var endSec = 0.0
        for (i in index.sizes.indices) {
            val t0 = tick.toDouble() / index.timescale
            val t1 = (tick + index.ticks[i]).toDouble() / index.timescale
            if (from < 0 && t1 > start) { from = pos; startSec = t0 }
            pos += index.sizes[i]
            tick += index.ticks[i]
            if (from >= 0) { to = pos; endSec = t1; if (t1 >= end) break }
        }
        return if (from < 0) null else Cut(from, to, startSec, endSec)
    }

    // ------------------------------------------------------------ network

    private val indexes = ConcurrentHashMap<String, Index>()

    fun forgetIndexes() = indexes.clear()

    private fun get(url: String, from: Long, toInclusive: Long): ByteArray {
        var last: Exception? = null
        repeat(3) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Range", "bytes=$from-$toInclusive")
                val code = c.responseCode
                if (code != 206 && code != 200) throw java.io.IOException("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                last = e
            } finally {
                c.disconnect()
            }
        }
        throw FriendlyError("The download was interrupted (${last?.message}).")
    }

    /** Reads the box list at the start of the file until the sidx. */
    fun index(url: String): Index {
        indexes[url]?.let { return it }
        var off = 0L
        repeat(8) {
            val h = get(url, off, off + 15)
            val size = u32(h, 0).let { if (it == 1L) u64(h, 8) else it }
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            if (size < 8) throw FriendlyError("This video's index can't be read.")
            if (type == "sidx") {
                val box = get(url, off, off + size - 1)
                val idx = parseSidx(box, off) ?: throw FriendlyError("This video's index can't be read.")
                indexes[url] = idx
                return idx
            }
            off += size
        }
        throw FriendlyError("This video's index can't be read.")
    }

    /**
     * Writes init + the fragments covering [start, end] seconds to [out] (a fragmented mp4).
     * Returns the real first and last second of what was written.
     */
    suspend fun fetch(url: String, start: Double, end: Double, out: File, onProgress: (Float) -> Unit = {}): Pair<Double, Double> =
        withContext(Dispatchers.IO) {
            val idx = index(url)
            val cut = plan(idx, start, end) ?: throw FriendlyError("That part of the video can't be downloaded.")
            out.parentFile?.mkdirs()
            out.delete()
            val init = get(url, 0, idx.initEnd - 1)
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(init.size + (cut.to - cut.from))
                val ch = raf.channel
                ch.write(ByteBuffer.wrap(init), 0)
                val chunks = generateSequence(cut.from) { it + CHUNK }.takeWhile { it < cut.to }.toList()
                var done = 0L
                val total = cut.to - cut.from
                val sem = Semaphore(3)
                coroutineScope {
                    chunks.map { a ->
                        async {
                            sem.withPermit {
                                coroutineContext.ensureActive()
                                val b = min(a + CHUNK, cut.to)
                                val bytes = get(url, a, b - 1)
                                ch.write(ByteBuffer.wrap(bytes), init.size + (a - cut.from))
                                synchronized(this@Dash) { done += bytes.size }
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }.forEach { it.await() }
                }
            }
            Log.i(TAG, "dash fetch ${"%.1f".format(cut.startSec)}-${"%.1f".format(cut.endSec)}s: ${(cut.to - cut.from) / 1024} KB")
            cut.startSec to cut.endSec
        }
}
