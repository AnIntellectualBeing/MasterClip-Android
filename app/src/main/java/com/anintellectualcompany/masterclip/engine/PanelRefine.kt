package com.anintellectualcompany.masterclip.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How often each pixel was an "edge" across the sampled frames (0..1). Straight borders that stay put score high. */
class EdgeMap(val w: Int, val h: Int, val data: FloatArray) {
    operator fun get(x: Int, y: Int) = data[y * w + x]
}

/**
 * Finds the real edges of the face-cam panel around a face, so the crop stays inside it and never
 * shows a strip of the game or an overlay (follower counters, viewer numbers) at its edge.
 * A port of refine_to_panel in the desktop MasterClip (facecam.py).
 */
object PanelRefine {
    /** 1 where the picture changes sharply (brightness), 0 elsewhere. [gray] is w*h values 0..255. */
    fun edgesOf(gray: IntArray, w: Int, h: Int, threshold: Int = 60): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val i = y * w + x
                val g = abs(gray[i + 1] - gray[i]) + abs(gray[i + w] - gray[i])
                if (g > threshold) out[i] = 1
            }
        }
        return out
    }

    fun mean(maps: List<ByteArray>, w: Int, h: Int): EdgeMap? {
        if (maps.isEmpty()) return null
        val sum = FloatArray(w * h)
        for (m in maps) for (i in sum.indices) sum[i] += m[i]
        for (i in sum.indices) sum[i] /= maps.size
        return EdgeMap(w, h, sum)
    }

    /** Positions (in the order given, nearest to the face first) where the score marks an edge line. */
    private fun peaks(scores: FloatArray, positions: IntProgression): List<Int> {
        val pos = positions.filter { it in scores.indices }
        if (pos.isEmpty()) return emptyList()
        val values = pos.map { scores[it] }.sorted()
        val median = values[values.size / 2]
        val threshold = max(0.2f, 4 * median)
        val merged = ArrayList<Int>()
        for (p in pos) {
            if (scores[p] < threshold) continue
            if (merged.isEmpty() || abs(p - merged.last()) > 3) merged += p
        }
        return merged
    }

    /**
     * The cam panel around [face] (0..1 of the frame), as a 0..1 box, or null when no clear panel is found.
     * The box is already stepped a little inside the border.
     */
    fun refine(edges: EdgeMap, face: Box, aspect: Float = 16f / 9f): Box? {
        val fw0 = edges.w
        val fh0 = edges.h
        val fx = face.x * fw0
        val fy = face.y * fh0
        val fw = face.w * fw0
        val fh = face.h * fh0
        val reachX = 5 * fw
        val reachY = 4 * fh

        val c0 = max(0, (fx - 0.5f * fw).toInt())
        val c1 = min(fw0, (fx + 1.5f * fw).toInt())
        if (c1 <= c0) return null
        val rowScores = FloatArray(fh0) { y ->
            var s = 0f
            for (x in c0 until c1) s += edges[x, y]
            s / (c1 - c0)
        }
        val tops = peaks(rowScores, fy.toInt() - 2 downTo (fy - reachY).toInt())
        val top = tops.firstOrNull()?.toFloat() ?: (if (fy - reachY <= 0) 0f else fy - 1.2f * fh)
        val bottoms = peaks(rowScores, (fy + fh).toInt() + 2 until (fy + fh + reachY).toInt())
        val bottom = bottoms.firstOrNull()?.toFloat() ?: (if (fy + fh + reachY >= fh0) fh0.toFloat() else fy + fh + 1.5f * fh)

        val r0 = max(0, (top + 3).toInt())
        val r1 = min(fh0, (bottom - 3).toInt())
        if (r1 <= r0) return null
        val colScores = FloatArray(fw0) { x ->
            var s = 0f
            for (y in r0 until r1) s += edges[x, y]
            s / (r1 - r0)
        }
        val lefts = peaks(colScores, fx.toInt() - 2 downTo (fx - reachX).toInt()).toMutableList()
        val rights = peaks(colScores, (fx + fw).toInt() + 2 until (fx + fw + reachX).toInt()).toMutableList()
        if (fx - reachX <= 0) lefts += 0
        if (fx + fw + reachX >= fw0) rights += fw0
        var height = bottom - top
        if (lefts.isEmpty() || rights.isEmpty() || height <= 0) return null

        var bestL = -1
        var bestR = -1
        var bestScore = Float.MAX_VALUE
        for (l in lefts) for (r in rights) {
            if (r - l < 1.4f * fw) continue
            val score = abs((r - l) / height - aspect)
            if (score < bestScore) { bestScore = score; bestL = l; bestR = r }
        }
        if (bestL < 0 || (bestR - bestL) / height !in 1.1f..2.6f) return null

        var left = max(0f, bestL.toFloat())
        var topF = max(0f, top)
        var right = min(fw0.toFloat(), bestR.toFloat())
        var bottomF = min(fh0.toFloat(), bottom)
        val width = right - left
        height = bottomF - topF
        if (width < 1.4f * fw || height < 1.2f * fh || width > fw0 * 0.6f || height > fh0 * 0.7f) return null
        // Step inside the border: cam frames, follower counters and name tags sit right on the edge.
        val insetY = max(3f, 0.045f * height)
        val insetX = max(3f, 0.02f * width)
        left += insetX; right -= insetX; topF += insetY; bottomF -= insetY
        if (right <= left || bottomF <= topF) return null
        return Box(left / fw0, topF / fh0, (right - left) / fw0, (bottomF - topF) / fh0)
    }
}
