package com.anintellectualcompany.masterclip.engine

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** Live stream maths and helpers (pure parts are unit tested). */
object LiveMath {
    /** Seconds of video in an HLS media playlist (sum of #EXTINF). 0 when it isn't one. */
    fun windowSeconds(playlist: String): Double {
        if ("#EXT-X-STREAM-INF" in playlist) return 0.0 // a master list, not a playlist of pieces
        var sum = 0.0
        for (line in playlist.lineSequence()) {
            if (line.startsWith("#EXTINF:")) sum += line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() ?: 0.0
        }
        return sum
    }

    /**
     * The last [minutes] of a rewind window that is [window] seconds long, as (start, end) seconds from
     * the window's start. The end stays [margin] seconds short of "now" because that part may not be
     * published yet. Null when there is less than [min] seconds to take.
     */
    fun lastPart(window: Double, minutes: Int, margin: Double = 12.0, min: Double = 60.0): Pair<Double, Double>? {
        val end = window - margin
        val start = max(0.0, end - minutes * 60.0)
        return if (end - start < min) null else start to end
    }

    /** About how many bytes a recording of [seconds] takes (video + sound). */
    fun expectedBytes(videoKbps: Double, seconds: Double): Long =
        (((if (videoKbps > 0) videoKbps else 3500.0) + 130.0) * 1000 / 8 * seconds).toLong()

    /** "3:05" or "1:02:03". */
    fun clock(sec: Int): String {
        val s = max(0, sec)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

object Live {
    /** Length of the rewind window of a live HLS stream, or null when it can't be read. */
    fun windowSeconds(playlistUrl: String): Double? {
        val c = URL(playlistUrl).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            if (c.responseCode != 200) null
            else LiveMath.windowSeconds(c.inputStream.bufferedReader().use { it.readText() }).takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
