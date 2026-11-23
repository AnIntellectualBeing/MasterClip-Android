package com.anintellectualcompany.masterclip.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Same warm light look as MasterClip for Windows. */
object Ink {
    val bg = Color(0xFFFBF6EF)
    val card = Color(0xFFFFFFFF)
    val text = Color(0xFF2A211C)
    val muted = Color(0xFF7C6F66)
    val line = Color(0xFFEFE4D8)
    val soft = Color(0xFFF6EFE6)
    val brand = Color(0xFFEF6A3E)
    val brandDark = Color(0xFFD9562B)
    val brandSoft = Color(0xFFFFE9DF)
    val good = Color(0xFF2E9E6A)
    val goodSoft = Color(0xFFE2F4EA)
    val bad = Color(0xFFC8423B)
    val badSoft = Color(0xFFFCEBEA)
}

@Composable
fun MasterClipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.brand,
            onPrimary = Color.White,
            primaryContainer = Ink.brandSoft,
            onPrimaryContainer = Ink.brandDark,
            secondary = Ink.brandDark,
            background = Ink.bg,
            onBackground = Ink.text,
            surface = Ink.card,
            onSurface = Ink.text,
            surfaceVariant = Ink.soft,
            onSurfaceVariant = Ink.muted,
            outline = Ink.line,
            outlineVariant = Ink.line,
            error = Ink.bad,
        ),
        content = content,
    )
}
