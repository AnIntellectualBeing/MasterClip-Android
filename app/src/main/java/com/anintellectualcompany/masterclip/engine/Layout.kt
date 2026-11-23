package com.anintellectualcompany.masterclip.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A box in the source frame, 0..1, y measured from the top. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val area get() = w * h
}

/** Faces found in one sampled frame, at [timeUs] from the clip start. */
data class FaceSample(val timeUs: Long, val faces: List<Box>)

enum class LayoutChoice(val label: String) {
    AUTO("Auto"),
    FACECAM("Face cam on top"),
    FOLLOW("Follow face"),
    FULL("Full video"),
}

enum class LayoutMode { FILL, STACK, FIT_BLUR }

/** Where a crop sits at a point in time; the crop slides smoothly between keys. */
data class Key(val timeUs: Long, val box: Box)

/**
 * How one 9:16 clip is drawn from a landscape (or any) frame.
 * - FILL: [bottom] fills the whole output (its position can move over time to follow a face).
 * - STACK: [top] (the face cam) fills the top [split] of the output, [bottom] (gameplay) the rest.
 * - FIT_BLUR: the whole frame sits in the middle, a blurred zoom of it fills the rest.
 */
data class LayoutPlan(
    val mode: LayoutMode,
    val top: Box = Box(0f, 0f, 1f, 1f),
    val bottom: List<Key> = listOf(Key(0, Box(0f, 0f, 1f, 1f))),
    val split: Float = 0f,
    val outW: Int = OUT_W,
    val outH: Int = OUT_H,
) {
    /** Crop for the main area at [timeUs], eased between keys. */
    fun bottomAt(timeUs: Long): Box {
        if (bottom.size == 1 || timeUs <= bottom.first().timeUs) return bottom.first().box
        if (timeUs >= bottom.last().timeUs) return bottom.last().box
        val i = bottom.indexOfLast { it.timeUs <= timeUs }
        val a = bottom[i]
        val b = bottom[i + 1]
        val t = ((timeUs - a.timeUs).toFloat() / (b.timeUs - a.timeUs)).coerceIn(0f, 1f)
        val e = t * t * (3 - 2 * t) // smoothstep: no jerks
        return Box(a.box.x + (b.box.x - a.box.x) * e, a.box.y + (b.box.y - a.box.y) * e, a.box.w, a.box.h)
    }

    /** Where captions go, as a fraction of the output height from the top. */
    val captionY: Float
        get() = when (mode) {
            LayoutMode.STACK -> split + (1 - split) * 0.12f
            LayoutMode.FIT_BLUR -> 0.74f
            LayoutMode.FILL -> 0.70f
        }

    companion object {
        const val OUT_W = 1080
        const val OUT_H = 1920
    }
}

object LayoutPlanner {
    private const val OUT_ASPECT = LayoutPlan.OUT_W.toFloat() / LayoutPlan.OUT_H // 0.5625
    const val STACK_SPLIT = 0.40f

    /**
     * Picks the layout. The owner's rule: a face-cam layout must always keep the face in view;
     * with no face, the whole video goes in the middle.
     */
    fun plan(srcW: Int, srcH: Int, samples: List<FaceSample>, choice: LayoutChoice): LayoutPlan {
        val srcAspect = srcW.toFloat() / srcH
        if (srcAspect <= OUT_ASPECT * 1.08f) {
            // Already vertical: fill, cropping the little that is left.
            return LayoutPlan(LayoutMode.FILL, bottom = listOf(Key(0, centerCrop(srcW, srcH, OUT_ASPECT))))
        }
        val withFace = samples.filter { it.faces.isNotEmpty() }
        val mainFaces = withFace.map { s -> FaceSample(s.timeUs, listOf(s.faces.maxBy { it.area })) }
        val hitRate = if (samples.isEmpty()) 0f else withFace.size.toFloat() / samples.size
        val face = median(mainFaces.map { it.faces.first() })

        if (choice == LayoutChoice.FULL || face == null || (choice == LayoutChoice.AUTO && hitRate < 0.4f)) {
            return LayoutPlan(LayoutMode.FIT_BLUR)
        }
        val small = face.w < 0.16f
        val offCenter = abs(face.cx - 0.5f) > 0.18f || abs(face.cy - 0.5f) > 0.22f
        // Several people on screen is a podcast/vlog shot, not a streamer cam.
        val multi = withFace.count { spread(it.faces) > 0f } * 2 >= withFace.size
        val wantStack = choice == LayoutChoice.FACECAM || (choice == LayoutChoice.AUTO && small && offCenter && !multi)
        if (wantStack) return stack(srcW, srcH, face)

        // Follow the face. Two people too far apart for one crop -> show everything instead.
        val cropW = (srcH * OUT_ASPECT) / srcW
        if (choice == LayoutChoice.AUTO && withFace.any { spread(it.faces) > cropW * 0.95f }) {
            return LayoutPlan(LayoutMode.FIT_BLUR)
        }
        return follow(srcW, srcH, withFace, cropW)
    }

    private fun stack(srcW: Int, srcH: Int, face: Box): LayoutPlan {
        // Top band (1080 x 768) shows the cam, bottom band (1080 x 1152) the middle of the game.
        val topAspect = LayoutPlan.OUT_W / (LayoutPlan.OUT_H * STACK_SPLIT)
        val bottomAspect = LayoutPlan.OUT_W / (LayoutPlan.OUT_H * (1 - STACK_SPLIT))
        val fwPx = face.w * srcW
        var camWPx = max(fwPx * 3.2f, srcW * 0.16f)
        camWPx = min(camWPx, srcW * 0.5f)
        var camHPx = camWPx / topAspect
        if (camHPx > srcH) { camHPx = srcH.toFloat(); camWPx = camHPx * topAspect }
        val cxPx = face.cx * srcW
        val cyPx = face.cy * srcH + camHPx * 0.08f // face a little above the middle of the cam band
        val x = (cxPx - camWPx / 2).coerceIn(0f, srcW - camWPx)
        val y = (cyPx - camHPx / 2).coerceIn(0f, srcH - camHPx)
        val cam = Box(x / srcW, y / srcH, camWPx / srcW, camHPx / srcH)
        val game = centerCrop(srcW, srcH, bottomAspect)
        return LayoutPlan(LayoutMode.STACK, top = cam, bottom = listOf(Key(0, game)), split = STACK_SPLIT)
    }

    private fun follow(srcW: Int, srcH: Int, faces: List<FaceSample>, cropW: Float): LayoutPlan {
        val crop = centerCrop(srcW, srcH, OUT_ASPECT)
        // Centre on the group when everyone fits, else on the biggest face.
        val xs = faces.map { s ->
            val sp = spread(s.faces)
            if (sp > 0f && sp <= cropW * 0.95f) {
                val big = s.faces.maxOf { it.w }
                val people = s.faces.filter { it.w >= big * 0.5f }
                (people.minOf { it.x } + people.maxOf { it.x + it.w }) / 2
            } else s.faces.maxBy { it.area }.cx
        }
        // Median of 3 removes one-frame mistakes before we pan.
        val smooth = xs.indices.map { i -> listOf(xs[max(0, i - 1)], xs[i], xs[min(xs.size - 1, i + 1)]).sorted()[1] }
        val keys = ArrayList<Key>()
        for ((i, s) in faces.withIndex()) {
            val x = (smooth[i] - crop.w / 2).coerceIn(0f, 1f - crop.w)
            val last = keys.lastOrNull()
            // Small moves are ignored so the picture stays calm.
            if (last == null) {
                keys += Key(s.timeUs, crop.copy(x = x))
            } else if (abs(last.box.x - x) > crop.w * 0.12f) {
                // Hold still, then pan quickly (0.7 s) when the face moves.
                val holdUntil = max(last.timeUs, s.timeUs - 700_000L)
                if (holdUntil > last.timeUs) keys += Key(holdUntil, last.box)
                keys += Key(s.timeUs, crop.copy(x = x))
            }
        }
        if (keys.isEmpty()) keys += Key(0, crop)
        if (keys.first().timeUs > 0) keys.add(0, Key(0, keys.first().box))
        return LayoutPlan(LayoutMode.FILL, bottom = keys)
    }

    /** The biggest box of [aspect] (w/h in pixels) centred in the frame, as 0..1. */
    fun centerCrop(srcW: Int, srcH: Int, aspect: Float): Box {
        val srcAspect = srcW.toFloat() / srcH
        return if (srcAspect > aspect) {
            val w = aspect / srcAspect
            Box((1 - w) / 2, 0f, w, 1f)
        } else {
            val h = srcAspect / aspect
            Box(0f, (1 - h) / 2, 1f, h)
        }
    }

    /** Width taken by the real people in a frame (tiny faces in the background don't count). */
    private fun spread(faces: List<Box>): Float {
        if (faces.size < 2) return 0f
        val big = faces.maxOf { it.w }
        val people = faces.filter { it.w >= big * 0.5f }
        return if (people.size < 2) 0f else people.maxOf { it.x + it.w } - people.minOf { it.x }
    }

    private fun median(boxes: List<Box>): Box? {
        if (boxes.isEmpty()) return null
        fun med(f: (Box) -> Float) = boxes.map(f).sorted()[boxes.size / 2]
        return Box(med { it.x }, med { it.y }, med { it.w }, med { it.h })
    }
}
