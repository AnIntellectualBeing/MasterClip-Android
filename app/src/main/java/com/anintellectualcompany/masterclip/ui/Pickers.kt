package com.anintellectualcompany.masterclip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anintellectualcompany.masterclip.engine.CaptionPainter
import com.anintellectualcompany.masterclip.engine.CaptionPosition
import com.anintellectualcompany.masterclip.engine.CaptionStyle
import com.anintellectualcompany.masterclip.engine.LayoutChoice
import com.anintellectualcompany.masterclip.engine.LiveMode

private val cardShape = RoundedCornerShape(14.dp)

/** Horizontal row of cards, each with a live preview drawn by the same painter the renderer uses. */
@Composable
fun CaptionStylePicker(selected: CaptionStyle, onPick: (CaptionStyle) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (s in CaptionStyle.entries) {
            val on = s == selected
            Column(
                Modifier
                    .width(150.dp)
                    .clip(cardShape)
                    .background(if (on) Ink.brandSoft else Ink.soft)
                    .border(2.dp, if (on) Ink.brand else Color.Transparent, cardShape)
                    .clickable(role = Role.RadioButton) { onPick(s) }
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CaptionPreview(s)
                Text(
                    s.label, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = if (on) Ink.brandDark else Ink.text, modifier = Modifier.heightIn(min = 24.dp),
                )
            }
        }
    }
}

@Composable
fun CaptionPreview(style: CaptionStyle) {
    val bmp = remember(style) { CaptionPainter.draw(listOf("THIS", "IS", "FIRE"), 2, style, 420, 140).asImageBitmap() }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF3A4660), Color(0xFF1B2230)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(bmp, contentDescription = "${style.label} caption preview", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun PositionChips(selected: CaptionPosition, onPick: (CaptionPosition) -> Unit) =
    Pills(CaptionPosition.entries, selected, { it.label }, onPick)

/** Visual cards with a small 9:16 diagram of each layout. */
@Composable
fun TemplatePicker(selected: LayoutChoice, onPick: (LayoutChoice) -> Unit) {
    for (row in LayoutChoice.entries.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (l in row) {
                val on = l == selected
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 104.dp)
                        .clip(cardShape)
                        .background(if (on) Ink.brandSoft else Ink.soft)
                        .border(2.dp, if (on) Ink.brand else Color.Transparent, cardShape)
                        .clickable(role = Role.RadioButton) { onPick(l) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TemplateDiagram(l, on)
                    Column(Modifier.weight(1f)) {
                        Text(templateName(l), fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp, color = if (on) Ink.brandDark else Ink.text)
                        Text(templateHelp(l), fontSize = 12.sp, color = Ink.muted, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
}

fun templateName(l: LayoutChoice) = when (l) {
    LayoutChoice.AUTO -> "Auto (we pick)"
    LayoutChoice.FACECAM -> "Face cam + gameplay"
    LayoutChoice.FOLLOW -> "Follow the face"
    LayoutChoice.FULL -> "Full video + blur"
}

fun templateHelp(l: LayoutChoice) = when (l) {
    LayoutChoice.AUTO -> "Best look for each clip"
    LayoutChoice.FACECAM -> "Cam on top, game below"
    LayoutChoice.FOLLOW -> "Full screen, tracks you"
    LayoutChoice.FULL -> "Whole video, blurred sides"
}

@Composable
private fun TemplateDiagram(l: LayoutChoice, on: Boolean) {
    val line = if (on) Ink.brand else Ink.muted
    val face = Color(0xFFF2C28B)
    Canvas(Modifier.size(width = 40.dp, height = 71.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2F3A))) {
        val w = size.width
        val h = size.height
        when (l) {
            LayoutChoice.AUTO -> {
                drawRect(Color(0xFF3A4660), Offset(w * 0.12f, h * 0.10f), Size(w * 0.76f, h * 0.38f))
                drawRect(Color(0xFF2F6F5A), Offset(w * 0.12f, h * 0.54f), Size(w * 0.76f, h * 0.36f))
                drawRoundRect(line, Offset(w * 0.04f, h * 0.04f), Size(w * 0.92f, h * 0.92f), CornerRadius(6f, 6f),
                    style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f))))
            }
            LayoutChoice.FACECAM -> {
                drawRect(Color(0xFF3A4660), Offset.Zero, Size(w, h * 0.40f))
                drawCircle(face, w * 0.16f, Offset(w / 2, h * 0.20f))
                drawRect(Color(0xFF2F6F5A), Offset(0f, h * 0.40f), Size(w, h * 0.60f))
                drawRect(Color(0xFF7BD0A8), Offset(w * 0.2f, h * 0.60f), Size(w * 0.6f, h * 0.06f))
                drawRect(Color(0xFF7BD0A8), Offset(w * 0.35f, h * 0.74f), Size(w * 0.3f, h * 0.06f))
                drawLine(Color.White, Offset(0f, h * 0.40f), Offset(w, h * 0.40f), 2f)
            }
            LayoutChoice.FOLLOW -> {
                drawRect(Color(0xFF3A4660))
                drawCircle(face, w * 0.26f, Offset(w / 2, h * 0.40f))
                drawRoundRect(Color(0xFF6B7A99), Offset(w * 0.18f, h * 0.58f), Size(w * 0.64f, h * 0.42f), CornerRadius(14f, 14f))
                val b = w * 0.14f
                for ((cx, cy, dx, dy) in listOf(listOf(0.1f, 0.2f, 1f, 1f), listOf(0.9f, 0.2f, -1f, 1f), listOf(0.1f, 0.62f, 1f, -1f), listOf(0.9f, 0.62f, -1f, -1f))) {
                    val x = w * cx; val y = h * cy
                    drawLine(Color.White, Offset(x, y), Offset(x + b * dx, y), 3f)
                    drawLine(Color.White, Offset(x, y), Offset(x, y + b * dy), 3f)
                }
            }
            LayoutChoice.FULL -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF6A4F8F), Color(0xFF3A6F8F), Color(0xFF6A4F8F))))
                drawRect(Color(0xFF1B2230), Offset(0f, h * 0.36f), Size(w, h * 0.28f))
                drawRect(Color(0xFF7BD0A8), Offset(w * 0.1f, h * 0.43f), Size(w * 0.4f, h * 0.05f))
                drawRect(Color(0xFFF2C28B), Offset(w * 0.1f, h * 0.52f), Size(w * 0.25f, h * 0.05f))
            }
        }
    }
}

/** Shown when the link is a stream that is live right now. */
@Composable
fun LiveControls(choices: Choices, onChange: (Choices) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This stream is live. What should we do?", color = Ink.muted, fontSize = 13.sp)
        for (m in LiveMode.entries) {
            val on = m == choices.liveMode
            val title = when (m) {
                LiveMode.RECORD_NEXT -> "Record the next ${choices.liveMinutes} min"
                LiveMode.LAST_PART -> "Clip what already happened"
            }
            val help = when (m) {
                LiveMode.RECORD_NEXT -> "Stay with the stream, then cut clips from what we recorded"
                LiveMode.LAST_PART -> "Use the last ${choices.liveMinutes} min of the stream so far"
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(cardShape)
                    .background(if (on) Ink.brandSoft else Ink.soft)
                    .border(2.dp, if (on) Ink.brand else Color.Transparent, cardShape)
                    .clickable(role = Role.RadioButton) { onChange(choices.copy(liveMode = m)) }
                    .padding(12.dp),
            ) {
                Text(title, fontWeight = FontWeight.Bold, color = if (on) Ink.brandDark else Ink.text)
                Text(help, fontSize = 12.sp, color = Ink.muted)
            }
        }
        Text("How many minutes", color = Ink.text, fontWeight = FontWeight.SemiBold)
        Pills(listOf(5, 10, 15, 30), choices.liveMinutes, { "$it min" }) { onChange(choices.copy(liveMinutes = it)) }
    }
}
