package com.anintellectualcompany.masterclip.ui

import android.content.Context
import com.anintellectualcompany.masterclip.engine.CaptionPosition
import com.anintellectualcompany.masterclip.engine.CaptionStyle
import com.anintellectualcompany.masterclip.engine.LayoutChoice
import com.anintellectualcompany.masterclip.engine.LiveMode

/** Remembers the last-used look so the next clip starts from it. */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("choices", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    fun load(context: Context): Choices {
        val p = prefs(context)
        val d = Choices()
        return d.copy(
            count = p.getInt("count", d.count),
            length = p.getInt("length", d.length),
            layout = enumOf(p.getString("layout", null), d.layout),
            captions = p.getBoolean("captions", d.captions),
            captionStyle = enumOf(p.getString("captionStyle", null), d.captionStyle),
            captionPosition = enumOf(p.getString("captionPosition", null), d.captionPosition),
            liveMinutes = p.getInt("liveMinutes", d.liveMinutes),
            liveMode = enumOf(p.getString("liveMode", null), d.liveMode),
        )
    }

    fun save(context: Context, c: Choices) {
        prefs(context).edit()
            .putInt("count", c.count)
            .putInt("length", c.length)
            .putString("layout", c.layout.name)
            .putBoolean("captions", c.captions)
            .putString("captionStyle", c.captionStyle.name)
            .putString("captionPosition", c.captionPosition.name)
            .putInt("liveMinutes", c.liveMinutes)
            .putString("liveMode", c.liveMode.name)
            .apply()
    }
}
