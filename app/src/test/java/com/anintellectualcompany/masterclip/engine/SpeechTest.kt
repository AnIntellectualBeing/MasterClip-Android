package com.anintellectualcompany.masterclip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test fun commonEnglishTermsWrittenInHindiUseTheirEnglishSpelling() {
        assertEquals("video editing game ready clip save round captions",
            Speech.parse("hi\n0\t4000\tवीडियो एडिटिंग गेम रेडी क्लिप सेव राउंड कैप्शन्स\n").words.single().text)
    }

    @Test fun closelySpacedHindiWordsDoNotOverlap() {
        val words = Speech.parse("hi\n2500\t2580\tहम\n2550\t3050\tvideo\n").words
        assertEquals(2550L, words.first().endMs)
        assertTrue(words.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test fun identicalWordTimestampsStayInOneHighlightGroup() {
        val words = Speech.parse("hi\n5160\t5280\tयह\n5160\t5740\tvideo\n5740\t6250\tबहुत\n").words
        assertEquals(listOf("yeh video", "bahut"), words.map { it.text })
        assertTrue(words.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test fun conversionHandlesUnknownWordsNuktaDigitsAndPunctuation() {
        assertEquals("namaste, karna samajhna film 123.",
            Speech.parse("hi\n0\t2000\tनमस्ते, करना समझना फ़िल्म १२३।\n").words.single().text)
    }

    @Test fun existingRomanHinglishAndEnglishStayUnchanged() {
        assertEquals("Bhai GTA 6 is amazing!", Speech.parse("en\n0\t1000\tBhai GTA 6 is amazing!\n").words.single().text)
    }

    @Test fun hindiSpeechBecomesHinglishWithoutChangingEnglishOrTimes() {
        val result = Speech.parse("hi\n100\t500\tभाई\n500\t900\tआज\n900\t1300\tvideo\n1300\t1800\tअच्छा\n1800\t2200\tहै।\n")
        assertEquals(listOf("bhai", "aaj", "video", "accha", "hai."), result.words.map { it.text })
        assertEquals(100L, result.words.first().startMs)
        assertEquals(2200L, result.words.last().endMs)
        assertEquals("hi", result.language)
    }

    @Test fun hindiScriptIsConvertedEvenWhenLanguageWasDetectedAsEnglish() {
        assertEquals("main ready hoon!", Speech.parse("en\n0\t900\tमैं ready हूँ!\n").words.single().text)
    }

    @Test fun wordsAreParsedAndNoiseIsDropped() {
        val raw = "en\n0\t400\t Hello\n400\t900\t [Music]\n900\t1300\t world.\n1300\t1500\t (laughs)\n1500\t1800\t ♪\n1800\t2000\t [_TT_50]\n"
        val r = Speech.parse(raw)
        assertEquals("en", r.language)
        assertEquals(listOf("Hello", "world."), r.words.map { it.text })
        assertEquals(0L, r.words[0].startMs)
    }

    @Test fun wordsNeverOverlap() {
        val raw = "en\n0\t900\tone\n500\t1200\ttwo\n1200\t1300\tthree\n"
        val w = Speech.parse(raw).words
        assertTrue(w.zipWithNext().all { (a, b) -> a.endMs <= b.startMs })
    }

    @Test fun nullAndEmptyGiveNoWords() {
        assertTrue(Speech.parse(null).words.isEmpty())
        assertTrue(Speech.parse("en\n").words.isEmpty())
    }
}
