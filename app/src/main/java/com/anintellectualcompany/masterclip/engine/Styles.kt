package com.anintellectualcompany.masterclip.engine

/**
 * Caption looks the buyer can pick. The UI shows [label]; CaptionOverlay draws each one.
 * Shared contract between the engine and the screens: add looks here, never rename the enum names
 * (they are saved in settings).
 */
enum class CaptionStyle(val label: String) {
    POP("Bold pop"),        // white bold, outline, active word yellow + slight scale (current look)
    BEAST("Big & loud"),    // huge uppercase, 1-2 words at a time, active word green
    BOXED("White box"),     // black text on a rounded white box, active word highlighted
    NEON("Neon"),           // glowing cyan/pink text
    CLEAN("Clean"),         // smaller sentence-case white text with soft shadow, no word highlight
    KARAOKE("Karaoke"),     // whole line shown, words fill with colour as they are spoken
}

/** Where captions sit on the 9:16 frame. AUTO = the layout decides (below the cam split, etc.). */
enum class CaptionPosition(val label: String) { AUTO("Auto"), TOP("Top"), MIDDLE("Middle"), BOTTOM("Bottom") }

/** What to do with a stream that is live right now. */
enum class LiveMode(val label: String) {
    RECORD_NEXT("Record the next few minutes"),
    LAST_PART("Clip what already happened"),
}
