package com.anintellectualcompany.masterclip.engine

import java.text.Normalizer

/** Same local Hindi spelling rules as the desktop app; preserves English and word timestamps. */
object Hinglish {
    private val words = mapOf(
        "वीडियो" to "video", "विडियो" to "video", "एडिटिंग" to "editing",
        "गेम" to "game", "रेडी" to "ready", "क्लिप" to "clip", "सेव" to "save",
        "राउंड" to "round", "कैप्शन" to "caption", "कैप्शन्स" to "captions",
        "है" to "hai",
        "मैंने" to "maine",
        "तूने" to "tune",
        "उसने" to "usne",
        "किसने" to "kisne",
        "हैं" to "hain",
        "में" to "mein",
        "मैं" to "main",
        "नहीं" to "nahi",
        "नही" to "nahi",
        "क्या" to "kya",
        "हूँ" to "hoon",
        "हूं" to "hoon",
        "यह" to "yeh",
        "वह" to "woh",
        "ये" to "ye",
        "वो" to "wo",
        "और" to "aur",
        "भी" to "bhi",
        "तो" to "toh",
        "कि" to "ki",
        "क्यों" to "kyun",
        "क्यूँ" to "kyun",
        "भाई" to "bhai",
        "अरे" to "arey",
        "चल" to "chal",
        "चलो" to "chalo",
        "देख" to "dekh",
        "देखो" to "dekho",
        "बहुत" to "bahut",
        "अब" to "ab",
        "यार" to "yaar",
        "पर" to "par",
        "से" to "se",
        "को" to "ko",
        "का" to "ka",
        "की" to "ki",
        "के" to "ke",
        "था" to "tha",
        "थी" to "thi",
        "थे" to "the",
        "रहा" to "raha",
        "रही" to "rahi",
        "रहे" to "rahe",
        "कर" to "kar",
        "हो" to "ho",
        "गया" to "gaya",
        "गई" to "gayi",
        "गए" to "gaye",
        "मत" to "mat",
        "एक" to "ek",
        "सब" to "sab",
        "कुछ" to "kuch",
        "हम" to "hum",
        "तुम" to "tum",
        "आप" to "aap",
        "मुझे" to "mujhe",
        "तुझे" to "tujhe",
        "उसे" to "use",
        "इसे" to "ise",
        "अच्छा" to "accha",
        "ठीक" to "theek",
        "पता" to "pata",
        "लिए" to "liye",
        "मेरा" to "mera",
        "मेरी" to "meri",
        "मेरे" to "mere",
        "तेरा" to "tera",
        "तेरी" to "teri",
        "तेरे" to "tere",
        "इधर" to "idhar",
        "उधर" to "udhar",
        "कहाँ" to "kahan",
        "कहां" to "kahan",
        "यहाँ" to "yahan",
        "यहां" to "yahan",
        "वहाँ" to "wahan",
        "वहां" to "wahan",
        "जल्दी" to "jaldi",
        "पैसे" to "paise",
        "गाड़ी" to "gaadi",
        "पुलिस" to "police",
        "ओके" to "okay",
        "हाँ" to "haan",
        "हां" to "haan",
        "ना" to "na",
        "बस" to "bas",
        "वाला" to "wala",
        "वाली" to "wali",
        "वाले" to "wale",
        "क्योंकि" to "kyunki",
        "लेकिन" to "lekin",
        "मार" to "maar",
        "मारो" to "maaro",
        "भागो" to "bhaago",
        "दे" to "de",
        "दो" to "do",
        "ले" to "le",
        "लो" to "lo",
        "आजा" to "aaja",
        "जा" to "ja",
        "जाओ" to "jao"
    )
    private val consonants = mapOf(
        "क" to "k",
        "ख" to "kh",
        "ग" to "g",
        "घ" to "gh",
        "ङ" to "n",
        "च" to "ch",
        "छ" to "chh",
        "ज" to "j",
        "झ" to "jh",
        "ञ" to "n",
        "ट" to "t",
        "ठ" to "th",
        "ड" to "d",
        "ढ" to "dh",
        "ण" to "n",
        "त" to "t",
        "थ" to "th",
        "द" to "d",
        "ध" to "dh",
        "न" to "n",
        "प" to "p",
        "फ" to "ph",
        "ब" to "b",
        "भ" to "bh",
        "म" to "m",
        "य" to "y",
        "र" to "r",
        "ल" to "l",
        "व" to "v",
        "श" to "sh",
        "ष" to "sh",
        "स" to "s",
        "ह" to "h",
        "ळ" to "l",
        "क़" to "q",
        "ख़" to "kh",
        "ग़" to "g",
        "ज़" to "z",
        "फ़" to "f",
        "ड़" to "d",
        "ढ़" to "rh",
        "य़" to "y"
    )
    private val nukta = mapOf(
        "क" to "क़",
        "ख" to "ख़",
        "ग" to "ग़",
        "ज" to "ज़",
        "फ" to "फ़",
        "ड" to "ड़",
        "ढ" to "ढ़",
        "य" to "य़"
    )
    private val vowels = mapOf(
        "अ" to "a",
        "आ" to "aa",
        "इ" to "i",
        "ई" to "ee",
        "उ" to "u",
        "ऊ" to "oo",
        "ए" to "e",
        "ऐ" to "ai",
        "ओ" to "o",
        "औ" to "au",
        "ऋ" to "ri",
        "ऑ" to "o",
        "ॲ" to "e"
    )
    private val signs = mapOf(
        "ा" to "a",
        "ि" to "i",
        "ी" to "i",
        "ु" to "u",
        "ू" to "oo",
        "े" to "e",
        "ै" to "ai",
        "ो" to "o",
        "ौ" to "au",
        "ृ" to "ri",
        "ॉ" to "o",
        "ॅ" to "e"
    )

    private val devanagari = Regex("[\u0900-\u0963\u0966-\u097f]+")
    private data class UnitSound(val kind: Char, val latin: String, var vowel: String = "", var implicit: Boolean = false)

    fun romanize(text: String): String {
        if (text.none { it in '\u0900'..'\u097f' }) return text
        return devanagari.replace(Normalizer.normalize(text, Normalizer.Form.NFD)) { word(it.value) }
            .replace('\u0964', '.').replace('\u0965', '.')
    }

    private fun word(word: String): String {
        words[word]?.let { return it }
        val units = ArrayList<UnitSound>()
        var i = 0
        while (i < word.length) {
            var ch = word[i].toString()
            val next = word.getOrNull(i + 1)
            when {
                ch in consonants -> {
                    if (next == '\u093c' && ch in nukta) { ch = nukta.getValue(ch); i++ }
                    units += UnitSound('c', consonants.getValue(ch), "a", true)
                }
                ch == "\u093c" -> Unit
                ch in signs && units.lastOrNull()?.kind == 'c' -> {
                    units.last().vowel = signs.getValue(ch); units.last().implicit = false
                }
                ch == "\u094d" && units.lastOrNull()?.kind == 'c' -> {
                    units.last().vowel = ""; units.last().implicit = false
                }
                ch in vowels -> units += UnitSound('v', vowels.getValue(ch))
                ch == "\u0902" || ch == "\u0901" -> units += UnitSound('x', if (next in listOf('\u092a', '\u092b', '\u092c', '\u092d', '\u092e')) "m" else "n")
                ch == "\u0903" -> units += UnitSound('x', "h")
                word[i] in '\u0966'..'\u096f' -> units += UnitSound('x', (word[i] - '\u0966').toString())
                else -> units += UnitSound('x', ch)
            }
            i++
        }
        val letters = units.filter { it.kind == 'c' || it.kind == 'v' }
        if (letters.size > 1 && letters.last().kind == 'c' && letters.last().implicit) letters.last().vowel = ""
        for (k in units.indices.reversed()) {
            val unit = units[k]
            if (unit.kind != 'c' || !unit.implicit || unit.vowel.isEmpty()) continue
            val before = units.getOrNull(k - 1)
            val after = units.getOrNull(k + 1)
            val voicedBefore = before != null && (before.kind == 'v' || (before.kind == 'c' && before.vowel.isNotEmpty()))
            if (voicedBefore && after?.kind == 'c' && after.vowel.isNotEmpty()) unit.vowel = ""
        }
        val result = units.joinToString("") { it.latin + it.vowel }
        return if (word.endsWith("\u0947\u0902") && !result.endsWith("ein")) result.dropLast(2) + "ein" else result
    }
}
