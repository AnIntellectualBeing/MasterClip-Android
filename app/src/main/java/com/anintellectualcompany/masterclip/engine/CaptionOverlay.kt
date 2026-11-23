package com.anintellectualcompany.masterclip.engine

import android.graphics.Bitmap
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings

/**
 * Big bold word-by-word captions: white with a black outline, the word being spoken in yellow.
 * A new bitmap is drawn only when the visible words change, so the GPU upload is rare.
 */
@UnstableApi
class CaptionOverlay(
    private val chunks: List<Chunk>,
    captionY: Float,
    private val style: CaptionStyle = CaptionStyle.POP,
    private val position: CaptionPosition = CaptionPosition.AUTO,
) : BitmapOverlay() {
    private val width = LayoutPlan.OUT_W
    private val height = 360
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var lastKey = ""
    private var lastBitmap: Bitmap = empty
    private var firstUs = Long.MIN_VALUE

    // Overlay anchors use -1..1 with +1 at the top.
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, 1f - 2f * CaptionPainter.centerY(position, captionY))
        .setOverlayFrameAnchor(0f, 0f)
        .build()

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val ms = (presentationTimeUs - firstUs) / 1000
        val i = chunks.indices.firstOrNull { ms >= chunks[it].startMs && ms < showUntil(chunks[it], it) } ?: -1
        val chunk = chunks.getOrNull(i) ?: return remember("", empty)
        val active = chunk.words.indexOfLast { ms >= it.startMs }.coerceAtLeast(0)
        val key = "${chunk.startMs}:" + if (style == CaptionStyle.CLEAN) "0" else "$active"
        if (key == lastKey) return lastBitmap
        return remember(key, draw(chunk, active))
    }

    /** A line stays up until the next one starts (at most 1 s after its last word), so it doesn't flicker. */
    private fun showUntil(c: Chunk, index: Int): Long {
        val next = chunks.getOrNull(index + 1)?.startMs ?: Long.MAX_VALUE
        return minOf(next, c.endMs + 1000)
    }

    private fun remember(key: String, bitmap: Bitmap): Bitmap {
        if (key == lastKey) return lastBitmap
        lastKey = key
        lastBitmap = bitmap
        return bitmap
    }

    private fun draw(chunk: Chunk, active: Int): Bitmap =
        CaptionPainter.draw(chunk.words.map { it.text }, active, style, width, height)
}
