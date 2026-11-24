package com.anintellectualcompany.masterclip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
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
