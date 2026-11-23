package com.anintellectualcompany.masterclip.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class FastEngineTest {
    // ---- chat moments

    @Test fun chatBurstBecomesTheMoment() {
        // Quiet chat (1 msg/s) with a burst (12 msg/s) around 600 s; chat lags, so the clip starts a bit earlier.
        val points = (0..1800 step 30).map { t -> Moments.RatePoint(t.toDouble(), if (t in 590..640) 12.0 else 1.0) }
        val score = Moments.rateScore(points, 1800)
        val m = Moments.pick(score, 1, 45.0)
        assertEquals(1, m.size)
        assertTrue("clip ${m[0].start} should cover the burst", m[0].start <= 600 && m[0].end >= 580)
    }

    @Test fun chatPageBecomesARate() {
        val times = (0 until 60).map { 100.0 + it * 0.25 } // 60 msgs in 14.75 s
        val p = Moments.ratePointOf(times)!!
        assertEquals(107.4, p.time, 0.2)
        assertEquals(4.07, p.rate, 0.1)
        assertNull(Moments.ratePointOf(listOf(1.0, 2.0)))
    }

    @Test fun twitchLinksAndPages() {
        assertEquals("2934967396", Twitch.vodId("https://www.twitch.tv/videos/2934967396?t=1h"))
        assertNull(Twitch.vodId("https://www.twitch.tv/ishowspeed"))
        val json = """{"data":{"video":{"comments":{"edges":[{"node":{"contentOffsetSeconds":597}},{"node":{"contentOffsetSeconds":601}}]}}}}"""
        assertEquals(listOf(597.0, 601.0), Twitch.parseTimes(json))
        assertEquals(emptyList<Double>(), Twitch.parseTimes("""{"data":{"video":null}}"""))
    }

    // ---- long videos: which parts to listen to

    @Test fun shortVideoIsListenedToWhole() {
        assertEquals(listOf(0.0 to 3000.0), Moments.scanSlices(0.0, 3000.0, 5400.0))
    }

    @Test fun longVideoIsListenedToInSpreadSlices() {
        val s = Moments.scanSlices(0.0, 20000.0, 5400.0, 300.0)
        assertEquals(18, s.size)
        assertEquals(5400.0, s.sumOf { it.second - it.first }, 0.1)
        assertTrue(s.first().first >= 0 && s.last().second <= 20000.0)
        assertTrue(s.zipWithNext().all { (a, b) -> a.second <= b.first })
    }

    @Test fun burstsSampleLongSoundAndKeepShortWhole() {
        assertEquals(listOf(0.0 to 500.0), Moments.bursts(0.0, 500.0, 8.0, 720.0))
        val b = Moments.bursts(0.0, 5400.0, 8.0, 720.0)
        assertEquals(90, b.size)
        assertEquals(720.0, b.sumOf { it.second - it.first }, 0.1)
        assertTrue(b.zipWithNext().all { (x, y) -> x.second < y.first })
        assertTrue(b.first().first >= 0 && b.last().second <= 5400.0)
    }

    @Test fun slicesLandAtTheirPlace() {
        val score = Moments.placeSlices(100, listOf(10.0 to doubleArrayOf(1.0, 2.0, 3.0), 90.0 to doubleArrayOf(5.0, 6.0, 7.0, 8.0)))
        assertEquals(2.0, score[11], 0.0)
        assertEquals(6.0, score[91], 0.0)
        assertEquals(0.0, score[50], 0.0)
        assertEquals(100, score.size) // the part past the end is dropped
    }

    // ---- live

    @Test fun hlsWindowIsSummed() {
        val pl = "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXTINF:5.0,\nhttps://a/1.ts\n#EXTINF:4.992,\nhttps://a/2.ts\n"
        assertEquals(9.992, LiveMath.windowSeconds(pl), 0.001)
        assertEquals(0.0, LiveMath.windowSeconds("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://a/v.m3u8"), 0.0)
    }

    @Test fun lastPartSectionIsRelativeToNow() {
        // A 1 hour rewind window, last 10 minutes: ends 12 s before the live edge.
        val s = LiveMath.lastPart(3600.0, 10)!!
        assertEquals(3588.0 - 600.0, s.first, 0.01)
        assertEquals(3588.0, s.second, 0.01)
        // Window shorter than asked: take what is there.
        val short = LiveMath.lastPart(300.0, 10)!!
        assertEquals(0.0, short.first, 0.01)
        assertEquals(288.0, short.second, 0.01)
        // Nothing worth taking (Twitch keeps ~a minute).
        assertNull(LiveMath.lastPart(50.0, 10))
    }

    @Test fun liveSizesAndClock() {
        assertTrue(LiveMath.expectedBytes(2000.0, 60.0) in 15_000_000L..17_000_000L)
        assertEquals("3:05", LiveMath.clock(185))
        assertEquals("1:02:03", LiveMath.clock(3723))
    }

    // ---- fast YouTube download

    private fun sidx(timescale: Int, refs: List<Pair<Int, Int>>, firstOffset: Int = 0): ByteArray {
        val b = ByteArrayOutputStream()
        val d = DataOutputStream(b)
        val size = 12 + 4 + 4 + 8 + 2 + 2 + refs.size * 12
        d.writeInt(size); d.writeBytes("sidx"); d.writeInt(0) // version 0
        d.writeInt(1); d.writeInt(timescale)
        d.writeInt(0); d.writeInt(firstOffset)
        d.writeShort(0); d.writeShort(refs.size)
        for ((sz, du) in refs) { d.writeInt(sz); d.writeInt(du); d.writeInt(0x90000000.toInt()) }
        return b.toByteArray()
    }

    @Test fun sidxGivesFragmentsAndTheRightCut() {
        // 10 fragments of 5 s, 1000 bytes each; sidx starts at byte 732.
        val box = sidx(1000, List(10) { 1000 to 5000 })
        val idx = Dash.parseSidx(box, 732)!!
        assertEquals(10, idx.sizes.size)
        assertEquals(732L + box.size, idx.dataStart)
        val cut = Dash.plan(idx, 12.0, 20.0)!!
        assertEquals(10.0, cut.startSec, 0.001) // starts on the fragment that holds second 12
        assertEquals(20.0, cut.endSec, 0.001)
        assertEquals(idx.dataStart + 2000, cut.from)
        assertEquals(idx.dataStart + 4000, cut.to)
        assertNull(Dash.plan(idx, 100.0, 120.0)) // past the end
        // Negative start (sound margin before 0) starts at the first fragment.
        assertEquals(0.0, Dash.plan(idx, -8.0, 10.0)!!.startSec, 0.001)
        assertNull(Dash.parseSidx(ByteArray(40), 0))
    }

    @Test fun trackPickingPrefersH264AndSmallAudio() {
        fun f(id: String, ext: String, v: String, a: String, h: Int, abr: Double, proto: String = "https") = JSONObject()
            .put("format_id", id).put("ext", ext).put("vcodec", v).put("acodec", a).put("height", h).put("abr", abr).put("tbr", abr)
            .put("protocol", proto).put("url", "https://x/$id")
        val formats = JSONArray()
            .put(f("299", "mp4", "avc1.64002a", "none", 1080, 0.0))
            .put(f("303", "webm", "vp9", "none", 1080, 0.0))
            .put(f("399", "mp4", "av01.0.12M.08", "none", 1080, 0.0))
            .put(f("312", "mp4", "avc1.64002a", "none", 1080, 0.0, "m3u8_native"))
            .put(f("400", "mp4", "avc1.640032", "none", 1440, 0.0))
            .put(f("139", "m4a", "none", "mp4a.40.5", 0, 48.7))
            .put(f("140", "m4a", "none", "mp4a.40.2", 0, 129.5))
            .put(f("251", "webm", "none", "opus", 0, 120.0))
        assertEquals("https://x/299", Dash.pickVideo(formats)!!.url)
        assertEquals("https://x/139", Dash.pickAudio(formats, small = true)!!.url)
        assertEquals("https://x/140", Dash.pickAudio(formats, small = false)!!.url)
        assertNull(Dash.pickVideo(JSONArray().put(f("303", "webm", "vp9", "none", 1080, 0.0))))
    }

    // ---- face-cam panel edges

    /** A 320x180 frame: gameplay noise everywhere, plus a still cam panel (x 200..300, y 20..76) with a border. */
    private fun frames(): List<ByteArray> {
        val w = 320
        val h = 180
        val rnd = java.util.Random(1)
        return List(6) {
            val gray = IntArray(w * h) { 60 + rnd.nextInt(10) } // calm game; changes are not edges
            // a moving bright game object that is NOT in the same place each frame
            val ox = rnd.nextInt(150)
            for (y in 100..120) for (x in ox..ox + 20) gray[y * w + x] = 230
            for (y in 20..76) for (x in 200..300) gray[y * w + x] = 120 // cam panel (flat inside)
            // a crisp border line
            for (x in 198..302) { gray[18 * w + x] = 255; gray[17 * w + x] = 255; gray[78 * w + x] = 255; gray[79 * w + x] = 255 }
            for (y in 17..79) { gray[y * w + 198] = 255; gray[y * w + 199] = 255; gray[y * w + 301] = 255; gray[y * w + 302] = 255 }
            // a face-ish patch with contrast inside the panel
            for (y in 32..60) for (x in 235..265) gray[y * w + x] = if ((x + y) % 2 == 0) 200 else 40
            PanelRefine.edgesOf(gray, w, h)
        }
    }

    @Test fun panelBorderIsFoundAndCropStaysInside() {
        val edges = PanelRefine.mean(frames(), 320, 180)!!
        val face = Box(235f / 320, 32f / 180, 30f / 320, 28f / 180)
        val panel = PanelRefine.refine(edges, face)
        assertNotNull(panel)
        val p = panel!!
        // inside the border (x 198..302, y 17..79), but still most of the panel
        assertTrue("left ${p.x * 320}", p.x * 320 in 199.0..215.0)
        assertTrue("right ${(p.x + p.w) * 320}", (p.x + p.w) * 320 in 285.0..301.0)
        assertTrue("top ${p.y * 180}", p.y * 180 in 18.0..30.0)
        assertTrue("bottom ${(p.y + p.h) * 180}", (p.y + p.h) * 180 in 66.0..79.0)
    }

    @Test fun stackCamIsKeptInsideThePanel() {
        val edges = PanelRefine.mean(frames(), 320, 180)!!
        val face = Box(235f / 320, 32f / 180, 30f / 320, 28f / 180)
        val samples = List(8) { FaceSample(it * 1_000_000L, listOf(face)) }
        val plan = LayoutPlanner.plan(1920, 1080, samples, LayoutChoice.FACECAM, edges)
        assertEquals(LayoutMode.STACK, plan.mode)
        val panel = PanelRefine.refine(edges, face)!!
        val c = plan.top
        val eps = 0.002f
        assertTrue(c.x >= panel.x - eps && c.y >= panel.y - eps && c.x + c.w <= panel.x + panel.w + eps && c.y + c.h <= panel.y + panel.h + eps)
        // Without the edge map the crop is bigger than the panel (the old behaviour).
        val old = LayoutPlanner.plan(1920, 1080, samples, LayoutChoice.FACECAM, null).top
        assertTrue(old.w >= c.w)
    }

    @Test fun noPanelMeansTheOldCrop() {
        val flat = EdgeMap(320, 180, FloatArray(320 * 180))
        assertNull(PanelRefine.refine(flat, Box(0.7f, 0.2f, 0.1f, 0.15f)))
    }
}
