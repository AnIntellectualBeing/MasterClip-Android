package com.anintellectualcompany.masterclip.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchRecTest {
    private val base = "https://video-weaver.example.net/v1/playlist/abc.m3u8"

    @Test fun livePiecesAreKept() {
        val pl = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:00.000Z
            #EXTINF:2.000,live
            https://cdn/100.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:02.000Z
            #EXTINF:2.000,live
            https://cdn/101.ts
            #EXT-X-TWITCH-PREFETCH:https://cdn/102.ts
        """.trimIndent()
        val p = TwitchPlaylist.parse(pl, base)
        assertEquals(listOf(100L, 101L), p.map { it.seq })
        assertTrue(p.none { it.ad })
    }

    @Test fun titleOtherThanLiveIsAnAd() {
        val pl = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:5
            #EXTINF:2.000,live
            a.ts
            #EXTINF:2.000,Amazon|123
            b.ts
            #EXTINF:2.000,live
            c.ts
        """.trimIndent()
        val p = TwitchPlaylist.parse(pl, base)
        assertEquals(listOf(false, true, false), p.map { it.ad })
        assertEquals("https://video-weaver.example.net/v1/playlist/a.ts", p[0].url)
    }

    @Test fun daterangeMarksTheAdPieces() {
        val pl = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:10
            #EXT-X-DATERANGE:ID="stitched-ad-1",CLASS="twitch-stitched-ad",START-DATE="2026-11-23T14:00:02.000Z",DURATION=4.000,X-TV-TWITCH-AD-ROLL-TYPE="PREROLL"
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:00.000Z
            #EXTINF:2.000,live
            a.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:02.000Z
            #EXTINF:2.000,live
            b.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:04.000Z
            #EXTINF:2.000,live
            c.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:06.000Z
            #EXTINF:2.000,live
            d.ts
        """.trimIndent()
        val p = TwitchPlaylist.parse(pl, base)
        assertEquals(listOf(false, true, true, false), p.map { it.ad })
    }

    @Test fun adRangeWithEndDateAndStitchedId() {
        val pl = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-DATERANGE:ID="stitched-ad-77",START-DATE="2026-11-23T14:00:00.000Z",END-DATE="2026-11-23T14:00:02.000Z"
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:00.000Z
            #EXTINF:2.000,live
            a.ts
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:02.000Z
            #EXTINF:2.000,live
            b.ts
        """.trimIndent()
        assertEquals(listOf(true, false), TwitchPlaylist.parse(pl, base).map { it.ad })
    }

    @Test fun otherDaterangesAreNotAds() {
        val pl = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-DATERANGE:ID="playlist-creation-1",CLASS="timestamp",START-DATE="2026-11-23T14:00:00.000Z"
            #EXT-X-PROGRAM-DATE-TIME:2026-11-23T14:00:00.000Z
            #EXTINF:2.000,live
            a.ts
        """.trimIndent()
        assertFalse(TwitchPlaylist.parse(pl, base).single().ad)
    }

    @Test fun masterListGivesNoPieces() {
        assertTrue(TwitchPlaylist.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nx.m3u8", base).isEmpty())
    }

    @Test fun picksBestH264UpTo1080() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,CODECS="avc1.64002A,mp4a.40.2",VIDEO="chunked"
            https://v/1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720,CODECS="avc1.4D401F,mp4a.40.2",VIDEO="720p60"
            https://v/720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=12000000,RESOLUTION=2560x1440,CODECS="avc1.640033,mp4a.40.2",VIDEO="1440"
            https://v/1440.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L120,mp4a.40.2",VIDEO="hevc"
            https://v/hevc.m3u8
        """.trimIndent()
        assertEquals("https://v/1080.m3u8", TwitchPlaylist.pickVariant(master, base))
        assertNull(TwitchPlaylist.pickVariant("#EXTM3U\n", base))
    }

    @Test fun channelFromLink() {
        assertEquals("montanablack88", TwitchPlaylist.channel("https://www.twitch.tv/MontanaBlack88?sr=a"))
        assertEquals("shroud", TwitchPlaylist.channel("twitch.tv/shroud"))
        assertNull(TwitchPlaylist.channel("https://www.twitch.tv/videos/123"))
    }
}
