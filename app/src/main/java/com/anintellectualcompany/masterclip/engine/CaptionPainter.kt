package com.anintellectualcompany.masterclip.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Draws one caption frame in any [CaptionStyle]. Used by the renderer (1080 px wide) and by the
 * style picker (smaller [width], same look). Sizes are written for 1080 and scaled by width / 1080.
 */
object CaptionPainter {
    const val FULL_W = 1080
    const val FULL_H = 360

    private val YELLOW = Color.rgb(255, 214, 10)
    private val GREEN = Color.rgb(57, 255, 20)
    private val CYAN = Color.rgb(0, 229, 255)
    private val PINK = Color.rgb(255, 45, 170)

    /** Centre of the caption box as a fraction of frame height; the Shorts/Reels buttons own the very top and bottom. */
    fun centerY(position: CaptionPosition, autoY: Float): Float = when (position) {
        CaptionPosition.AUTO -> autoY
        CaptionPosition.TOP -> 0.17f
        CaptionPosition.MIDDLE -> 0.50f
        CaptionPosition.BOTTOM -> 0.70f
    }

    /** Which words of the chunk are on screen: BEAST shows two at a time, the rest show the whole line. */
    fun visible(style: CaptionStyle, count: Int, active: Int): IntRange =
        if (style == CaptionStyle.BEAST) {
            val first = (active / 2) * 2
            first..minOf(first + 1, count - 1)
        } else 0 until count

    private fun bold(face: String = "sans-serif", style: Int = Typeface.BOLD) = Typeface.create(face, style)

    private fun paint(size: Float, face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        textAlign = Paint.Align.LEFT
    }

    fun draw(rawWords: List<String>, active: Int, style: CaptionStyle, width: Int = FULL_W, height: Int = FULL_H): Bitmap {
        val k = width / FULL_W.toFloat()
        val upper = style != CaptionStyle.CLEAN && style != CaptionStyle.BOXED
        val range = visible(style, rawWords.size, active)
        val idx = range.toList()
        val words = idx.map { if (upper) rawWords[it].uppercase() else rawWords[it] }
        val face = when (style) {
            CaptionStyle.BEAST -> bold("sans-serif-black", Typeface.NORMAL)
            CaptionStyle.CLEAN -> bold("sans-serif-medium", Typeface.NORMAL)
            else -> bold()
        }
        var size = when (style) {
            CaptionStyle.POP -> 92f
            CaptionStyle.BEAST -> 168f
            CaptionStyle.BOXED -> 74f
            CaptionStyle.NEON -> 86f
            CaptionStyle.CLEAN -> 64f
            CaptionStyle.KARAOKE -> 84f
        } * k
        val p = paint(size, face)
        val maxW = width * (if (style == CaptionStyle.BOXED) 0.80f else 0.88f)
        val space = { p.measureText(" ") }
        fun lineW() = words.sumOf { p.measureText(it).toDouble() }.toFloat() + space() * (words.size - 1)
        while (lineW() > maxW && size > 28f * k) { size -= 4f * k; p.textSize = size }

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        var x = (width - lineW()) / 2
        val y = height / 2f + size * 0.36f
        val local = active - idx.first()

        val outline = Paint(p).apply { this.style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = Color.BLACK }

        if (style == CaptionStyle.BOXED) {
            val pad = 26f * k
            val box = RectF(x - pad, y - size * 0.92f - pad * 0.1f, x + lineW() + pad, y + size * 0.30f + pad * 0.5f)
            c.drawRoundRect(box, 30f * k, 30f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; setShadowLayer(18f * k, 0f, 6f * k, Color.argb(120, 0, 0, 0)) })
        }

        for ((i, w) in words.withIndex()) {
            val ww = p.measureText(w)
            when (style) {
                CaptionStyle.POP -> {
                    outline.strokeWidth = size * 0.17f
                    c.drawText(w, x + 5f * k, y + 7f * k, Paint(p).apply { color = Color.argb(110, 0, 0, 0) })
                    c.drawText(w, x, y, outline)
                    c.drawText(w, x, y, Paint(p).apply { color = if (i == local) YELLOW else Color.WHITE })
                }
                CaptionStyle.BEAST -> {
                    outline.strokeWidth = size * 0.20f
                    c.drawText(w, x + 6f * k, y + 10f * k, Paint(p).apply { color = Color.argb(140, 0, 0, 0) })
                    c.drawText(w, x, y, outline)
                    c.drawText(w, x, y, Paint(p).apply { color = if (i == local) GREEN else Color.WHITE })
                }
                CaptionStyle.BOXED -> {
                    if (i == local) {
                        val hl = RectF(x - 12f * k, y - size * 0.86f, x + ww + 12f * k, y + size * 0.25f)
                        c.drawRoundRect(hl, 18f * k, 18f * k, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = YELLOW })
                    }
                    c.drawText(w, x, y, Paint(p).apply { color = Color.rgb(20, 20, 20) })
                }
                CaptionStyle.NEON -> {
                    val glow = if (i == local) PINK else CYAN
                    for (r in listOf(0.34f, 0.16f)) {
                        c.drawText(w, x, y, Paint(p).apply { color = glow; maskFilter = BlurMaskFilter(size * r, BlurMaskFilter.Blur.NORMAL) })
                    }
                    c.drawText(w, x, y, Paint(p).apply { color = glow; this.style = Paint.Style.STROKE; strokeWidth = size * 0.07f; strokeJoin = Paint.Join.ROUND })
                    c.drawText(w, x, y, Paint(p).apply { color = Color.rgb(255, 245, 252) })
                }
                CaptionStyle.CLEAN -> {
                    c.drawText(w, x, y, Paint(p).apply { color = Color.WHITE; setShadowLayer(12f * k, 0f, 4f * k, Color.argb(200, 0, 0, 0)) })
                }
                CaptionStyle.KARAOKE -> {
                    outline.strokeWidth = size * 0.15f
                    c.drawText(w, x, y, outline)
                    c.drawText(w, x, y, Paint(p).apply { color = if (i <= local) YELLOW else Color.WHITE })
                }
            }
            x += ww + space()
        }
        return bmp
    }
}
