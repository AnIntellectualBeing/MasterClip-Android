package com.anintellectualcompany.masterclip.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings

/**
 * Big bold word-by-word captions: white with a black outline, the word being spoken in yellow.
 * A new bitmap is drawn only when the visible words change, so the GPU upload is rare.
 */
@UnstableApi
class CaptionOverlay(private val chunks: List<Chunk>, captionY: Float) : BitmapOverlay() {
    private val width = LayoutPlan.OUT_W
    private val height = 360
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private var lastKey = ""
    private var lastBitmap: Bitmap = empty
    private var firstUs = Long.MIN_VALUE

    // Overlay anchors use -1..1 with +1 at the top.
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, 1f - 2f * captionY)
        .setOverlayFrameAnchor(0f, 0f)
        .build()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 92f
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val stroke = Paint(fill).apply {
        style = Paint.Style.STROKE
        strokeWidth = 16f
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val shadow = Paint(fill).apply {
        color = Color.argb(110, 0, 0, 0)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val ms = (presentationTimeUs - firstUs) / 1000
        val i = chunks.indices.firstOrNull { ms >= chunks[it].startMs && ms < showUntil(chunks[it], it) } ?: -1
        val chunk = chunks.getOrNull(i) ?: return remember("", empty)
        val active = chunk.words.indexOfLast { ms >= it.startMs }.coerceAtLeast(0)
        val key = "${chunk.startMs}:$active"
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

    private fun draw(chunk: Chunk, active: Int): Bitmap {
        val words = chunk.words.map { it.text.uppercase() }
        // Shrink long lines so they always fit inside the frame with a margin.
        var size = 92f
        fun lineWidth(): Float {
            fill.textSize = size
            return words.sumOf { fill.measureText("$it ").toDouble() }.toFloat() - fill.measureText(" ")
        }
        while (lineWidth() > width * 0.88f && size > 40f) size -= 4f
        stroke.textSize = size
        shadow.textSize = size
        stroke.strokeWidth = size * 0.17f

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        var x = (width - lineWidth()) / 2
        val y = height / 2f + size * 0.36f
        for ((i, w) in words.withIndex()) {
            c.drawText(w, x + 5f, y + 7f, shadow)
            c.drawText(w, x, y, stroke)
            fill.color = if (i == active) Color.rgb(255, 214, 10) else Color.WHITE
            c.drawText(w, x, y, fill)
            x += fill.measureText("$w ")
        }
        fill.color = Color.WHITE
        return bmp
    }
}
