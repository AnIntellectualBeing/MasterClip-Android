package com.anintellectualcompany.masterclip.engine

/** "1:02:03", "12:30", "90" -> seconds. Null when it isn't a time. */
fun parseClock(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(':', '.')
    if (parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
    var sec = 0.0
    for (p in parts) sec = sec * 60 + p.toDouble()
    return sec
}

/** Seconds -> "1:02:03" or "2:05". */
fun clock(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

/** Seconds left -> words. */
fun etaText(sec: Int?): String = when {
    sec == null -> "Working out how long this takes…"
    sec < 20 -> "Almost done"
    sec < 60 -> "Less than a minute left"
    sec < 3600 -> "About ${(sec + 30) / 60} min left"
    else -> "About ${sec / 3600} h ${(sec % 3600) / 60} min left"
}

private val urlRegex = Regex("""https?://\S+""")

/** The first web link in shared text ("Check this out https://youtu.be/x" -> the link). */
fun findLink(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', '"') }
