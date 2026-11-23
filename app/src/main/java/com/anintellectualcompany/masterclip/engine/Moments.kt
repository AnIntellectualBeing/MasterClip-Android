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
    ): List<Moment> = pick(loudnessScore(rms), count, length, rangeStart, rangeEnd)

    /** Mean and standard deviation. */
    fun stats(v: FloatArray): Pair<Double, Double> {
        if (v.isEmpty()) return 0.0 to 0.0
        val mean = v.average()
        var variance = 0.0
        for (x in v) variance += (x - mean) * (x - mean)
        return mean to sqrt(variance / v.size)
    }

    /** True when the cheap frame-size signal actually moves (a constant bit rate has nothing to tell). */
    fun hasVariation(v: FloatArray): Boolean {
        val (m, sd) = stats(v)
        return v.size >= 30 && m > 0 && sd / m > 0.08
    }

    /** Per-second "something happens" score from loudness. */
    fun loudnessScore(rms: FloatArray, meanOverride: Double? = null, sdOverride: Double? = null): DoubleArray {
        if (rms.isEmpty()) return DoubleArray(0)
        val mean = meanOverride ?: rms.average()
        val sd = (sdOverride ?: stats(rms).second).takeIf { it > 1e-9 } ?: 1.0
        val score = DoubleArray(rms.size)
        for (i in rms.indices) {
            val z = (rms[i] - mean) / sd
            var before = 0.0
            var n = 0
            for (j in max(0, i - 10) until i) { before += rms[j]; n++ }
            val jump = if (n > 0) max(0.0, (rms[i] - before / n) / sd) else 0.0
            score[i] = max(0.0, z) + 0.6 * jump
        }
        return score
    }

    /**
     * Long video, no heatmap: which pieces to listen to. Short enough -> all of [a]..[b];
     * otherwise [sliceSec]-long slices spread evenly, adding up to about [capSec].
     */
    fun scanSlices(a: Double, b: Double, capSec: Double, sliceSec: Double = 300.0): List<Pair<Double, Double>> {
        val span = b - a
        if (span <= 0) return emptyList()
        if (span <= capSec * 1.1) return listOf(a to b)
        val n = max(2, (capSec / sliceSec).toInt())
        val step = span / n
        return (0 until n).map { i ->
            val s = a + step * i + (step - sliceSec) / 2
            s to (s + sliceSec)
        }
    }

    /**
     * Short stretches ([burst] seconds each) spread evenly over [a]..[b], adding up to about [budget]
     * seconds. A video that fits in the budget is listened to whole.
     */
    fun bursts(a: Double, b: Double, burst: Double, budget: Double): List<Pair<Double, Double>> {
        val span = b - a
        if (span <= 0) return emptyList()
        if (span <= budget || span <= burst * 2) return listOf(a to b)
        val n = min(max(1, (budget / burst).toInt()), max(1, (span / burst).toInt()))
        val step = span / n
        return (0 until n).map { i ->
            val s = a + step * i + (step - burst) / 2
            s to (s + burst)
        }
    }

    /** Puts the loudness score of each scanned slice at its place in a whole-video score (the rest stays 0). */
    fun placeSlices(total: Int, parts: List<Pair<Double, DoubleArray>>): DoubleArray {
        val out = DoubleArray(total)
        for ((start, score) in parts) {
            val s0 = start.roundToInt()
            for (i in score.indices) {
                val at = s0 + i
                if (at in 0 until total) out[at] = score[i]
            }
        }
        return out
    }

    /** One look at the chat: how many messages per second around [time] (seconds in the video). */
    data class RatePoint(val time: Double, val rate: Double)

    /**
     * Chat speed samples -> per-second score. Chat reacts a few seconds after the event,
     * so the score is shifted [lag] seconds earlier. Between samples the speed is interpolated.
     */
    fun rateScore(points: List<RatePoint>, total: Int, lag: Int = 6): DoubleArray {
        val out = DoubleArray(total)
        val p = points.sortedBy { it.time }
        if (p.isEmpty() || total <= 0) return out
        var j = 0
        for (s in 0 until total) {
            val t = (s + lag).toDouble()
            while (j + 1 < p.size && p[j + 1].time <= t) j++
            out[s] = when {
                t <= p.first().time -> p.first().rate
                t >= p.last().time -> p.last().rate
                else -> {
                    val a = p[j]
                    val b = p[j + 1]
                    val f = if (b.time > a.time) (t - a.time) / (b.time - a.time) else 0.0
                    a.rate + (b.rate - a.rate) * f
                }
            }
        }
        return out
    }

    /** Message times of one chat page -> a rate point (null when the page is too thin to say anything). */
    fun ratePointOf(times: List<Double>): RatePoint? {
        if (times.size < 3) return null
        val a = times.min()
        val b = times.max()
        val span = max(3.0, b - a)
        return RatePoint((a + b) / 2, times.size / span)
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
