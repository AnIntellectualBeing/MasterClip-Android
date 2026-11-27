package com.anintellectualcompany.masterclip.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentsTest {
    @Test fun heatmapPeaksBecomeClipsWithoutOverlap() {
        val heat = (0 until 100).map { i ->
            val v = when (i) { in 40..42 -> 1.0; in 70..71 -> 0.8; else -> 0.1 }
            HeatPoint(i * 6.0, i * 6.0 + 6, v)
        }
        val m = Moments.fromHeatmap(heat, 600.0, 3, 30.0)
        assertEquals(3, m.size)
        assertTrue("sorted by time", m.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertTrue("first peak (240-258 s) is covered", m.any { it.start <= 246 && it.end >= 252 })
        assertTrue("second peak (420-432 s) is covered", m.any { it.start <= 424 && it.end >= 428 })
        m.forEach { assertEquals(30.0, it.length, 0.01) }
    }

    @Test fun rangeIsRespected() {
        val heat = (0 until 100).map { HeatPoint(it * 36.0, it * 36.0 + 36, if (it == 10) 1.0 else 0.2) }
        val m = Moments.fromHeatmap(heat, 3600.0, 4, 45.0, rangeStart = 1800.0, rangeEnd = 2400.0)
        assertTrue(m.isNotEmpty())
        m.forEach { assertTrue(it.start >= 1800 && it.end <= 2400) }
    }

    @Test fun shortVideoGivesOneWholeClip() {
        val m = Moments.fromHeatmap(listOf(HeatPoint(0.0, 20.0, 1.0)), 20.0, 5, 45.0)
        assertEquals(1, m.size)
        assertEquals(0.0, m[0].start, 0.01)
        assertEquals(20.0, m[0].end, 0.01)
    }

    @Test fun loudMomentIsFound() {
        val rms = FloatArray(300) { 0.05f }
        for (i in 200..206) rms[i] = 0.6f
        val m = Moments.fromLoudness(rms, 1, 30.0)
        assertTrue(m[0].start <= 200 && m[0].end >= 206)
    }

    @Test fun spreadNeverOverlaps() {
        val m = Moments.spread(600.0, 5, 45.0)
        assertEquals(5, m.size)
        assertTrue(m.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test fun moreClipsThanFitStopsCleanly() {
        val m = Moments.pick(DoubleArray(100) { 1.0 }, 10, 30.0)
        assertTrue(m.size in 2..3)
        assertTrue(m.zipWithNext().all { (a, b) -> b.start - a.end >= 3 })
    }
}

class CaptionsTest {
    @Test fun downloadedHindiCaptionsBecomeHinglishAndKeepClipTiming() = kotlinx.coroutines.runBlocking {
        val words = Captions.parseVtt("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nभाई यह video अच्छा है।\n")
        val result = Captions.forClip(words, 1000, 3000) { error("Subtitles are available") }.flatMap { it.words }
        assertEquals(listOf("bhai", "yeh", "video", "accha", "hai."), result.map { it.text })
        assertEquals(0L, result.first().startMs)
        assertEquals(2000L, result.last().endMs)
    }

    @Test fun sliceKeepsSpeechAlreadyInProgressAtClipStart() {
        val words = listOf(Word("hello", 900, 1300), Word("there", 1400, 1800))
        assertEquals(listOf(Word("hello", 0, 300), Word("there", 400, 800)),
            Captions.slice(words, 1000, 2000))
    }

    @Test fun missingCaptionsForSelectedClipUseSpeechRecognition() = kotlinx.coroutines.runBlocking {
        val words = listOf(Word("intro", 0, 500))
        val chunks = Captions.forClip(words, 1000, 2000) { listOf(Chunk(listOf(Word("spoken", 0, 500)))) }
        assertEquals(listOf("spoken"), chunks.flatMap { it.words }.map { it.text })
    }

    @Test fun availableClipCaptionsDoNotRunSpeechRecognition() = kotlinx.coroutines.runBlocking {
        val chunks = Captions.forClip(listOf(Word("hello", 1200, 1700)), 1000, 2000) {
            error("Speech recognition is unnecessary when the clip has captions")
        }
        assertEquals(listOf(Word("hello", 200, 700)), chunks.flatMap { it.words })
    }

    private val json3 = """
        {"events":[
          {"tStartMs":0,"dDurationMs":5000,"id":1},
          {"tStartMs":1000,"dDurationMs":3000,"segs":[{"utf8":"this"},{"utf8":" is","tOffsetMs":400},{"utf8":" really","tOffsetMs":800},{"utf8":" crazy","tOffsetMs":1300}]},
          {"tStartMs":2500,"dDurationMs":10,"aAppend":1,"segs":[{"utf8":"\n"}]},
          {"tStartMs":4000,"dDurationMs":2000,"segs":[{"utf8":"[Music]"}]},
          {"tStartMs":7000,"dDurationMs":2000,"segs":[{"utf8":"bro"},{"utf8":" what","tOffsetMs":300}]}
        ]}
    """.trimIndent()

    @Test fun json3WordsKeepTheirTimes() {
        val w = Captions.parseJson3(json3)
        assertEquals(listOf("this", "is", "really", "crazy", "bro", "what"), w.map { it.text })
        assertEquals(1400, w[1].startMs)
        assertEquals(1800, w[1].endMs)
        assertTrue(w.all { it.endMs > it.startMs })
        assertTrue("a word never runs into the next line", w[3].endMs <= 4000)
    }

    @Test fun chunksAreShortAndBreakOnPauses() {
        val c = Captions.chunks(Captions.parseJson3(json3))
        assertEquals(listOf("this is really", "crazy", "bro what"), c.map { ch -> ch.words.joinToString(" ") { it.text } })
    }

    @Test fun sliceShiftsToClipTime() {
        val s = Captions.slice(Captions.parseJson3(json3), 7000, 10000)
        assertEquals(listOf("bro", "what"), s.map { it.text })
        assertEquals(0, s[0].startMs)
    }

    @Test fun vttDropsRepeatedRollingLines() {
        val vtt = """
            WEBVTT

            00:00:01.000 --> 00:00:02.000
            hello there

            00:00:02.000 --> 00:00:03.500
            hello there
            general kenobi
        """.trimIndent()
        assertEquals(listOf("hello", "there", "general", "kenobi"), Captions.parseVtt(vtt).map { it.text })
    }

    @Test fun trackPicking() {
        assertEquals("hi", Captions.pickTrack("hi", setOf("hi", "en"), setOf("en")))
        assertEquals("en-orig", Captions.pickTrack("en", emptySet(), setOf("en", "en-orig", "fr")))
        assertEquals("hi", Captions.pickTrack("hi", emptySet(), setOf("hi", "en")))
        assertEquals("ko-orig", Captions.pickTrack(null, emptySet(), setOf("ko-orig", "en")))
        assertEquals("en", Captions.pickTrack(null, emptySet(), setOf("en", "fr")))
        assertNull(Captions.pickTrack("ja", emptySet(), emptySet()))
    }
}

class LayoutTest {
    private fun samples(vararg boxes: Box?) = boxes.mapIndexed { i, b -> FaceSample(i * 1_000_000L, listOfNotNull(b)) }

    @Test fun noFaceMeansWholeVideoInTheMiddle() {
        val p = LayoutPlanner.plan(1920, 1080, samples(null, null, null, null), LayoutChoice.AUTO)
        assertEquals(LayoutMode.FIT_BLUR, p.mode)
    }

    @Test fun smallCornerFaceIsAFaceCamAndStaysInView() {
        val face = Box(0.06f, 0.70f, 0.07f, 0.12f) // bottom-left streamer cam
        val p = LayoutPlanner.plan(1920, 1080, samples(face, face, face, face), LayoutChoice.AUTO)
        assertEquals(LayoutMode.STACK, p.mode)
        val cam = p.top
        assertTrue("face inside the cam crop", face.x >= cam.x && face.x + face.w <= cam.x + cam.w && face.y >= cam.y && face.y + face.h <= cam.y + cam.h)
        assertTrue("crop inside the frame", cam.x >= 0 && cam.y >= 0 && cam.x + cam.w <= 1.0001f && cam.y + cam.h <= 1.0001f)
        // Cam band is 1080x768: the crop must have that shape in pixels.
        assertEquals(1080f / 768f, cam.w * 1920 / (cam.h * 1080), 0.02f)
    }

    @Test fun bigFaceIsFollowedAndKeptInView() {
        val a = Box(0.15f, 0.2f, 0.2f, 0.35f)
        val b = Box(0.62f, 0.2f, 0.2f, 0.35f)
        val p = LayoutPlanner.plan(1920, 1080, samples(a, a, a, b, b, b), LayoutChoice.AUTO)
        assertEquals(LayoutMode.FILL, p.mode)
        val start = p.bottomAt(0)
        val end = p.bottomAt(5_000_000)
        assertTrue(a.x >= start.x && a.x + a.w <= start.x + start.w)
        assertTrue(b.x >= end.x && b.x + b.w <= end.x + end.w)
        assertEquals(1080f / 1920f, start.w * 1920 / 1080f, 0.01f)
    }

    @Test fun twoPeopleFarApartShowEveryone() {
        val s = (0 until 4).map { FaceSample(it * 1_000_000L, listOf(Box(0.05f, 0.3f, 0.12f, 0.2f), Box(0.80f, 0.3f, 0.12f, 0.2f))) }
        assertEquals(LayoutMode.FIT_BLUR, LayoutPlanner.plan(1920, 1080, s, LayoutChoice.AUTO).mode)
    }

    @Test fun verticalSourceJustFills() {
        assertEquals(LayoutMode.FILL, LayoutPlanner.plan(1080, 1920, samples(null), LayoutChoice.FACECAM).mode)
    }

    @Test fun choicesAreObeyed() {
        val face = Box(0.45f, 0.3f, 0.12f, 0.2f)
        assertEquals(LayoutMode.FIT_BLUR, LayoutPlanner.plan(1920, 1080, samples(face, face), LayoutChoice.FULL).mode)
        assertEquals(LayoutMode.STACK, LayoutPlanner.plan(1920, 1080, samples(face, face), LayoutChoice.FACECAM).mode)
        assertEquals(LayoutMode.FILL, LayoutPlanner.plan(1920, 1080, samples(face, face), LayoutChoice.FOLLOW).mode)
    }
}

class TextTest {
    @Test fun clocks() {
        assertEquals(4800.0, parseClock("1:20:00")!!, 0.0)
        assertEquals(2730.0, parseClock("45:30")!!, 0.0)
        assertEquals(90.0, parseClock("90")!!, 0.0)
        assertNull(parseClock("abc"))
        assertNull(parseClock("1::2"))
        assertEquals("1:20:00", clock(4800.0))
        assertEquals("2:05", clock(125.0))
    }

    @Test fun links() {
        assertEquals("https://youtu.be/abc123", findLink("Watch this! https://youtu.be/abc123"))
        assertNull(findLink("no link here"))
    }

    @Test fun eta() {
        assertEquals("About 3 min left", etaText(170))
        assertTrue(etaText(null).startsWith("Working"))
    }

    @Test fun errorsAreHuman() {
        assertTrue(Ytdl.explain("ERROR: [youtube] x: Sign in to confirm you’re not a bot").contains("robot"))
        assertTrue(Ytdl.explain("ERROR: [youtube] x: Private video").contains("private"))
        assertTrue(Ytdl.explain("ERROR: Unsupported URL: https://x.com").contains("isn't a video"))
        assertTrue(Ytdl.explain("ERROR: [youtube] x: Unable to download API page: [Errno 7] No address associated with hostname").contains("internet"))
    }

    @Test fun metaParsing() {
        val json = JSONObject(
            """{"title":"Big stream","duration":3600.5,"is_live":false,"language":"en","extractor_key":"Youtube",
               "webpage_url":"https://www.youtube.com/watch?v=x",
               "heatmap":[{"start_time":0,"end_time":36,"value":0.4},{"start_time":36,"end_time":72,"value":1.0}],
               "subtitles":{"live_chat":[]},"automatic_captions":{"en":[],"en-orig":[]}}"""
        )
        val m = Ytdl.parseMeta("u", json)
        assertEquals("Big stream", m.title)
        assertEquals(2, m.heatmap.size)
        assertTrue(m.manualSubs.isEmpty())
        assertEquals(setOf("en", "en-orig"), m.autoSubs)
        assertNotNull(Captions.pickTrack(m.language, m.manualSubs, m.autoSubs))
    }
}
