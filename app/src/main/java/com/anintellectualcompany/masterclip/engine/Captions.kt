package com.anintellectualcompany.masterclip.engine

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class Word(val text: String, val startMs: Long, val endMs: Long)

/** A few words shown together on screen. */
data class Chunk(val words: List<Word>) {
    val startMs get() = words.first().startMs
    val endMs get() = words.last().endMs
}

/** YouTube captions (json3 with word timings, or plain WebVTT) -> timed words -> short on-screen chunks. */
object Captions {
    /** Prefer subtitle words for this clip, otherwise listen to the clip's own sound. */
    suspend fun forClip(words: List<Word>, fromMs: Long, toMs: Long, speech: suspend () -> List<Chunk>): List<Chunk> {
        val selected = slice(words, fromMs, toMs)
        val result = if (selected.isEmpty()) speech() else chunks(selected)
        return chunks(result.flatMap { it.words }.map { it.copy(text = Hinglish.romanize(it.text)) })
    }

    private val noise = Regex("""^\[.*]$|^\(.*\)$""") // [Music], [Applause], (laughs)

    fun parseJson3(text: String): List<Word> {
        val root = JSONObject(text)
        val events = root.optJSONArray("events") ?: return emptyList()
        val raw = ArrayList<Pair<String, Long>>() // text, start
        val eventEnds = ArrayList<Long>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val segs = ev.optJSONArray("segs") ?: continue
            val t0 = ev.optLong("tStartMs", 0)
            val dur = ev.optLong("dDurationMs", 0)
            for (j in 0 until segs.length()) {
                val seg = segs.optJSONObject(j) ?: continue
                val words = seg.optString("utf8", "").replace('\n', ' ').trim()
                if (words.isEmpty()) continue
                val start = t0 + seg.optLong("tOffsetMs", 0)
                // Manual captions put a whole line in one seg: share its time between the words.
                val parts = words.split(Regex("\\s+")).filter { it.isNotBlank() }
                val each = if (parts.size > 1 && dur > 0) dur / parts.size else 0
                parts.forEachIndexed { k, w -> raw += w to (start + each * k); eventEnds += t0 + dur }
            }
        }
        return finish(raw, eventEnds)
    }

    fun parseVtt(text: String): List<Word> {
        val raw = ArrayList<Pair<String, Long>>()
        val ends = ArrayList<Long>()
        val lines = text.replace("\r", "").split("\n")
        var i = 0
        val seen = HashSet<String>()
        while (i < lines.size) {
            val line = lines[i]
            if ("-->" in line) {
                val (a, b) = line.split("-->").map { parseTime(it.trim().split(" ").first()) }
                val body = ArrayList<String>()
                i++
                while (i < lines.size && lines[i].isNotBlank()) { body += lines[i]; i++ }
                // YouTube's auto VTT repeats the previous line in every cue: keep only new lines.
                val cleanLines = body.map { it.replace(Regex("<[^>]+>"), "").trim() }.filter { it.isNotEmpty() }
                val fresh = cleanLines.filter { it !in seen }
                seen.clear(); seen.addAll(cleanLines)
                val words = fresh.joinToString(" ").split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.isNotEmpty()) {
                    val each = max(1L, (b - a) / words.size)
                    words.forEachIndexed { k, w -> raw += w to (a + each * k); ends += b }
                }
            }
            i++
        }
        return finish(raw, ends)
    }

    private fun parseTime(s: String): Long {
        val p = s.split(":")
        val sec = p.last().replace(',', '.').toDouble()
        val min = if (p.size >= 2) p[p.size - 2].toLong() else 0
        val hr = if (p.size >= 3) p[p.size - 3].toLong() else 0
        return ((hr * 3600 + min * 60) * 1000 + sec * 1000).toLong()
    }

    private fun finish(raw: List<Pair<String, Long>>, ends: List<Long>): List<Word> {
        val sorted = raw.indices.sortedBy { raw[it].second }
        val out = ArrayList<Word>()
        for ((n, idx) in sorted.withIndex()) {
            val (rawText, start) = raw[idx]
            val text = rawText.replace(Regex("[♪♫♬]"), "").trim()
            if (text.isEmpty() || noise.matches(text)) continue
            val nextStart = if (n + 1 < sorted.size) raw[sorted[n + 1]].second else start + 600
            // A word lasts until the next word, but never past its caption line or 1.2 s.
            val end = min(min(nextStart, ends[idx].takeIf { it > start } ?: Long.MAX_VALUE), start + 1200)
            out += Word(text, start, max(end, start + 120))
        }
        return out
    }

    /** Words inside [fromMs, toMs), shifted so fromMs becomes 0. */
    fun slice(words: List<Word>, fromMs: Long, toMs: Long): List<Word> =
        words.filter { it.endMs > fromMs && it.startMs < toMs }
            .map { Word(it.text, max(it.startMs, fromMs) - fromMs, min(it.endMs, toMs) - fromMs) }

    /** Groups of up to [maxWords] words / [maxChars] letters; a pause of 0.7 s also starts a new group. */
    fun chunks(words: List<Word>, maxWords: Int = 3, maxChars: Int = 18): List<Chunk> {
        val out = ArrayList<Chunk>()
        var cur = ArrayList<Word>()
        var chars = 0
        for (w in words) {
            val pause = cur.isNotEmpty() && w.startMs - cur.last().endMs > 700
            val endsSentence = cur.isNotEmpty() && cur.last().text.last() in ".?!"
            if (cur.isNotEmpty() && (cur.size >= maxWords || chars + w.text.length > maxChars || pause || endsSentence)) {
                out += Chunk(cur); cur = ArrayList(); chars = 0
            }
            cur += w
            chars += w.text.length + 1
        }
        if (cur.isNotEmpty()) out += Chunk(cur)
        return out
    }

    /**
     * Which caption track to ask yt-dlp for. Manual captions in the video's language win, then
     * YouTube's own speech-to-text ("-orig"), then English. Returns null when there is none.
     */
    fun pickTrack(language: String?, manual: Set<String>, auto: Set<String>): String? {
        val lang = language?.lowercase()?.substringBefore('-')
        if (lang != null) {
            manual.firstOrNull { it.lowercase() == lang || it.lowercase().startsWith("$lang-") }?.let { return it }
            auto.firstOrNull { it.lowercase() == "$lang-orig" }?.let { return it }
            auto.firstOrNull { it.lowercase() == lang }?.let { return it }
        }
        auto.firstOrNull { it.lowercase().endsWith("-orig") }?.let { return it }
        if (manual.size == 1) return manual.first()
        manual.firstOrNull { it.lowercase().startsWith("en") }?.let { return it }
        auto.firstOrNull { it.lowercase() == "en" }?.let { return it }
        return null
    }
}
