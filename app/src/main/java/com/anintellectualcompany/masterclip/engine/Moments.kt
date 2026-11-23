package com.anintellectualcompany.masterclip.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One part of YouTube's "most replayed" graph. value is 0..1. */
data class HeatPoint(val start: Double, val end: Double, val value: Double)

/** A stretch of the source video that becomes one clip. Times are seconds in the source. */
data class Moment(val start: Double, val end: Double, val score: Double) {
    val length get() = end - start
}

/**
 * Picks the best non-overlapping windows from a per-second score.
 * Pure code, so it is unit tested without a phone.
 */
object Moments {
    private const val GAP = 3.0 // seconds kept between two clips so they never repeat each other

    /** YouTube "most replayed" -> best moments. */
    fun fromHeatmap(
        heat: List<HeatPoint>,
        duration: Double,
        count: Int,
        length: Double,
        rangeStart: Double? = null,
        rangeEnd: Double? = null,
    ): List<Moment> {
        val total = duration.toInt().coerceAtLeast(1)
        val perSecond = DoubleArray(total)
        for (p in heat) {
            val a = p.start.toInt().coerceIn(0, total - 1)
            val b = p.end.toInt().coerceIn(a + 1, total)
            for (s in a until b) perSecond[s] = max(perSecond[s], p.value)
        }
        // The first seconds are always "replayed" because people seek from there: trust them less.
        val intro = min(total, max(10, (total * 0.04).toInt()))
        for (s in 0 until intro) perSecond[s] *= 0.5
        return pick(perSecond, count, length, rangeStart, rangeEnd)
    }

    /** Loudness per second (RMS, any scale) -> moments where something happens: loud, and louder than just before. */
    fun fromLoudness(
        rms: FloatArray,
        count: Int,
        length: Double,
        rangeStart: Double? = null,
        rangeEnd: Double? = null,
    ): List<Moment> {
        if (rms.isEmpty()) return emptyList()
        val mean = rms.average()
        var variance = 0.0
        for (v in rms) variance += (v - mean) * (v - mean)
        val sd = sqrt(variance / rms.size).takeIf { it > 1e-9 } ?: 1.0
        val score = DoubleArray(rms.size)
        for (i in rms.indices) {
            val z = (rms[i] - mean) / sd
            var before = 0.0
            var n = 0
            for (j in max(0, i - 10) until i) { before += rms[j]; n++ }
            val jump = if (n > 0) max(0.0, (rms[i] - before / n) / sd) else 0.0
            score[i] = max(0.0, z) + 0.6 * jump
        }
        return pick(score, count, length, rangeStart, rangeEnd)
    }

    /** No data at all: spread the clips evenly. */
    fun spread(duration: Double, count: Int, length: Double, rangeStart: Double? = null, rangeEnd: Double? = null): List<Moment> {
        val a = (rangeStart ?: 0.0).coerceIn(0.0, duration)
        val b = (rangeEnd ?: duration).coerceIn(a, duration)
        val span = b - a
        if (span <= length) return listOf(Moment(a, b, 0.0))
        val n = min(count, max(1, (span / (length + GAP)).toInt()))
        val step = span / n
        return (0 until n).map { i ->
            val mid = a + step * (i + 0.5)
            val s = (mid - length / 2).coerceIn(a, b - length)
            Moment(s, s + length, 0.0)
        }
    }

    /** Greedy best-window search on prefix sums, then sorted by time. */
    fun pick(score: DoubleArray, count: Int, length: Double, rangeStart: Double? = null, rangeEnd: Double? = null): List<Moment> {
        val total = score.size
        if (total == 0 || count <= 0) return emptyList()
        val a = (rangeStart ?: 0.0).toInt().coerceIn(0, total - 1)
        val b = (rangeEnd ?: total.toDouble()).roundToInt().coerceIn(a + 1, total)
        val len = length.roundToInt().coerceAtLeast(5)
        if (b - a <= len) return listOf(Moment(a.toDouble(), b.toDouble(), score.slice(a until b).sum()))

        val prefix = DoubleArray(total + 1)
        for (i in 0 until total) prefix[i + 1] = prefix[i] + score[i]
        val taken = ArrayList<Moment>()
        val gap = GAP.toInt()
        repeat(count) {
            var best = -1.0
            var bestStart = -1
            var s = a
            while (s + len <= b) {
                val e = s + len
                val clash = taken.any { s < it.end + gap && e > it.start - gap }
                if (!clash) {
                    val sum = prefix[e] - prefix[s]
                    if (sum > best) { best = sum; bestStart = s }
                }
                s++
            }
            if (bestStart < 0) return@repeat
            taken += Moment(bestStart.toDouble(), (bestStart + len).toDouble(), best)
        }
        return taken.sortedBy { it.start }
    }
}
