package com.anintellectualcompany.masterclip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val json = """
        WARNING: noise first
        {"_type":"playlist","entries":[
         {"_type":"url","ie_key":"Youtube","id":"9BYuRGLTdoI","url":"https://www.youtube.com/watch?v=9BYuRGLTdoI","title":"Live now","channel":"BLV Tumi","duration":null,"live_status":"is_live"},
         {"_type":"url","ie_key":"Youtube","id":"UC8CX0LD98EDXl4UYX1MDCXg","url":"https://www.youtube.com/channel/UC8CX0LD98EDXl4UYX1MDCXg","title":"A channel"},
         {"_type":"url","ie_key":"Youtube","id":"IahJZCTzGEI","url":"https://www.youtube.com/watch?v=IahJZCTzGEI","title":"Old stream","uploader":"TenZ","duration":10768,"live_status":"was_live"},
         {"_type":"url","ie_key":"Youtube","id":"IahJZCTzGEI","url":"https://www.youtube.com/watch?v=IahJZCTzGEI","title":"Duplicate","duration":5}
        ]}
    """.trimIndent()

    @Test fun parsesVideosAndSkipsChannelsAndDuplicates() {
        val hits = Search.parse(json)
        assertEquals(2, hits.size)
        assertTrue(hits[0].live)
        assertNull(hits[0].durationSec)
        assertEquals("BLV Tumi", hits[0].channel)
        assertFalse(hits[1].live)
        assertEquals(10768, hits[1].durationSec)
        assertEquals("TenZ", hits[1].channel)
        assertEquals("https://i.ytimg.com/vi/IahJZCTzGEI/mqdefault.jpg", hits[1].thumb)
    }

    @Test fun missingStatusCountsAsLiveOnlyForLiveSearch() {
        val j = """{"entries":[{"id":"abcdefghijk","url":"https://www.youtube.com/watch?v=abcdefghijk","title":"x","duration":null}]}"""
        assertFalse(Search.parse(j)[0].live)
        assertTrue(Search.parse(j, assumeLive = true)[0].live)
    }

    @Test fun emptyOrBrokenOutput() {
        assertTrue(Search.parse("").isEmpty())
        assertTrue(Search.parse("""{"entries":[]}""").isEmpty())
    }

    @Test fun targets() {
        assertEquals("ytsearch20:valorant", Search.target("valorant", SearchFilter.ALL))
        assertEquals("ytsearch20:valorant stream", Search.target(" valorant ", SearchFilter.PAST))
        assertEquals("https://www.youtube.com/results?search_query=valorant+clutch&sp=EgJAAQ%3D%3D", Search.target("valorant clutch", SearchFilter.LIVE))
    }

    @Test fun liveLinks() {
        assertTrue(Search.looksLive("https://www.twitch.tv/shroud"))
        assertTrue(Search.looksLive("https://twitch.tv/shroud/"))
        assertFalse(Search.looksLive("https://www.twitch.tv/videos/12345"))
        assertFalse(Search.looksLive("https://www.twitch.tv/shroud/clip/Abc"))
        assertTrue(Search.looksLive("https://www.youtube.com/@TenZ/live"))
        assertTrue(Search.looksLive("https://www.youtube.com/live/9BYuRGLTdoI"))
        assertFalse(Search.looksLive("https://www.youtube.com/watch?v=9BYuRGLTdoI"))
        assertFalse(Search.looksLive(null))
    }

    @Test fun twitchChannelFromQuery() {
        assertEquals("shroud", Search.twitchChannel("shroud"))
        assertEquals("shroud", Search.twitchChannel("twitch.tv/shroud"))
        assertEquals("shroud", Search.twitchChannel("https://www.twitch.tv/shroud"))
        assertNull(Search.twitchChannel("valorant clutch"))
        assertNull(Search.twitchChannel("https://www.twitch.tv/videos/123"))
        assertNull(Search.twitchChannel("https://youtube.com/watch?v=x"))
    }

    @Test fun captionPositions() {
        assertEquals(0.7f, CaptionPainter.centerY(CaptionPosition.AUTO, 0.7f), 0f)
        assertTrue(CaptionPainter.centerY(CaptionPosition.TOP, 0.7f) < CaptionPainter.centerY(CaptionPosition.MIDDLE, 0.7f))
        assertTrue(CaptionPainter.centerY(CaptionPosition.BOTTOM, 0.7f) < 0.78f)
        assertEquals(2..3, CaptionPainter.visible(CaptionStyle.BEAST, 4, 3))
        assertEquals(0..2, CaptionPainter.visible(CaptionStyle.POP, 3, 1))
    }
}
