package com.anintellectualcompany.masterclip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CampaignsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val feed = """
    {"updated":"2026-11-24",
     "platforms":[{"id":"p1","name":"Plat","url":"https://example.com","pays":null,"min_payout":null},
                  {"id":"bad","name":"No link","url":"x"}],
     "campaigns":[
       {"id":"c1","title":"One","payout_per_1k":2,"url":"https://example.com/c1","clip_len":[15,45],
        "sources":[{"name":"Streamer","link":"https://youtu.be/x"}],"active":true},
       {"id":"c2","title":"Hidden","payout_per_1k":1,"active":false},
       {"id":"c3","title":"No rate","payout_per_1k":"lots"}]}
    """.trimIndent()

    @Test fun parsesAndCleans() {
        val f = Campaigns.parse(feed)!!
        assertEquals(listOf("p1"), f.platforms.map { it.id })
        assertNull(f.platforms[0].pays)
        assertEquals(listOf("c1", "c3"), f.campaigns.map { it.id })
        assertEquals(2.0, f.campaigns[0].payoutPer1k!!, 0.0)
        assertNull(f.campaigns[1].payoutPer1k)
        assertEquals("https://youtu.be/x", f.campaigns[0].sources[0].link)
        assertEquals(15, f.campaigns[0].clipLenMin)
    }

    @Test fun garbageIsNotAFeed() {
        assertNull(Campaigns.parse("nope"))
        assertTrue(Campaigns.parse("{}")!!.campaigns.isEmpty())
    }

    @Test fun estimate() {
        assertEquals(25.0, Campaigns.estimate(12500, 2.0)!!, 0.0)
        assertEquals(1.5, Campaigns.estimate(999, 1.5)!!, 0.0)
        assertEquals(0.0, Campaigns.estimate(0, 3.0)!!, 0.0)
        assertNull(Campaigns.estimate(1000, null))
    }

    @Test fun lengthFollowsCampaign() {
        val f = Campaigns.parse(feed)!!
        assertEquals(30, Campaigns.lengthFor(f.campaigns[0]))
        val long = f.campaigns[0].copy(clipLenMin = 45, clipLenMax = 90)
        assertEquals(45, Campaigns.lengthFor(long))
        val short = f.campaigns[0].copy(clipLenMin = 10, clipLenMax = 20)
        assertEquals(20, Campaigns.lengthFor(short))
    }

    @Test fun liveThenCacheThenSeed() {
        val dir = tmp.newFolder()
        val live = Campaigns.load(dir, seed = { null }, fetch = { feed })
        assertEquals("live", live.source)
        assertTrue(File(dir, "campaigns-cache.json").exists())
        val cached = Campaigns.load(dir, seed = { null }, fetch = { throw java.io.IOException("offline") })
        assertEquals("cache", cached.source)
        assertEquals(2, cached.campaigns.size)
        val seeded = Campaigns.load(tmp.newFolder(), seed = { feed }, fetch = { null })
        assertEquals("bundled", seeded.source)
        val none = Campaigns.load(tmp.newFolder(), seed = { null }, fetch = { "garbage" })
        assertTrue(none.campaigns.isEmpty() && none.platforms.isEmpty())
    }

    @Test fun submissionsStore() {
        val file = File(tmp.newFolder(), "subs.json")
        val store = SubmissionStore(file)
        val a = store.add("c1", "clip A", "https://t.co/1", 5000)
        val b = store.add("c3", "clip B", "", 100)
        assertEquals(2, store.all().size)
        store.setViews(a.id, 10000)
        val campaigns = Campaigns.parse(feed)!!.campaigns
        assertEquals(20.0, Campaigns.total(store.all(), campaigns), 0.0)
        store.delete(b.id)
        assertEquals(1, SubmissionStore(file).all().size)
        assertNotNull(SubmissionStore(file).all().first().postedAt)
        store.setViews(a.id, -5)
        assertEquals(0L, store.all().first().views)
    }
}
