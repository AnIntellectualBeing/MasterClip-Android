package com.anintellectualcompany.masterclip.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** A pretend network: records every call and answers from a script. */
private class FakeHttp(val answers: MutableList<HttpReply>) : Http {
    class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
    val calls = ArrayList<Call>()
    var putFile: File? = null
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
        calls += Call(method, url, headers, body?.toString(Charsets.UTF_8))
        return answers.removeAt(0)
    }
    override fun put(url: String, file: File, contentType: String, onProgress: (Float) -> Unit): HttpReply {
        calls += Call("PUT", url, mapOf("Content-Type" to contentType), null)
        putFile = file
        onProgress(1f)
        return answers.removeAt(0)
    }
}

private fun ok(json: String) = HttpReply(200, json)

class ZernioTest {
    private val yt = ZernioAccount("y1", "youtube", "mychannel")
    private val ig = ZernioAccount("i1", "instagram", "myinsta")

    @Test fun accountsListKeepsOnlyInstagramAndYoutube() {
        val http = FakeHttp(mutableListOf(ok("""{"accounts":[
            {"_id":"a1","platform":"instagram","username":"me_ig"},
            {"_id":"a2","platform":"youtube","displayName":"My Channel"},
            {"_id":"a3","platform":"tiktok","username":"x"},
            {"_id":"a4","platform":"youtube","username":"off","isActive":false}]}""")))
        val list = Zernio(" key123 ", http).accounts()
        assertEquals(listOf("me_ig", "My Channel"), list.map { it.name })
        assertEquals("GET", http.calls[0].method)
        assertEquals("https://zernio.com/api/v1/accounts", http.calls[0].url)
        assertEquals("Bearer key123", http.calls[0].headers["Authorization"])
    }

    @Test fun uploadAsksForALinkThenPutsTheFile() {
        val http = FakeHttp(mutableListOf(ok("""{"uploadUrl":"https://up.example/x","publicUrl":"https://cdn.example/x.mp4"}"""), HttpReply(200, "")))
        val f = File.createTempFile("clip", ".mp4").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val url = Zernio("k", http).upload(f)
        assertEquals("https://cdn.example/x.mp4", url)
        val presign = JSONObject(http.calls[0].body!!)
        assertEquals("POST", http.calls[0].method)
        assertTrue(http.calls[0].url.endsWith("/media/presign"))
        assertEquals("video/mp4", presign.getString("contentType"))
        assertEquals(10, presign.getInt("size"))
        assertEquals("PUT", http.calls[1].method)
        assertEquals("https://up.example/x", http.calls[1].url)
        assertEquals(f, http.putFile)
    }

    @Test fun postNowBodyForBothPlatforms() {
        val b = Zernio.postBody("https://cdn/x.mp4", "Great clip\n\n#fyp", listOf(yt, ig), null, "Asia/Kolkata")
        assertTrue(b.getBoolean("publishNow"))
        assertFalse(b.has("scheduledFor"))
        assertEquals("Asia/Kolkata", b.getString("timezone"))
        assertEquals("video", b.getJSONArray("mediaItems").getJSONObject(0).getString("type"))
        val p = b.getJSONArray("platforms")
        val y = p.getJSONObject(0)
        assertEquals("youtube", y.getString("platform"))
        assertEquals("y1", y.getString("accountId"))
        val data = y.getJSONObject("platformSpecificData")
        assertEquals("Great clip #Shorts", data.getString("title"))
        assertEquals("public", data.getString("visibility"))
        assertFalse(data.getBoolean("madeForKids"))
        assertTrue(y.getString("customContent").contains("#Shorts"))
        val i = p.getJSONObject(1)
        assertEquals("instagram", i.getString("platform"))
        assertEquals("Great clip\n\n#fyp", i.getString("customContent"))
        assertFalse(i.has("platformSpecificData"))
    }

    @Test fun scheduledBodyUsesLocalTimeAndZone() {
        val b = Zernio.postBody("https://cdn/x.mp4", "Hi", listOf(ig), "2026-11-25T18:30", "Asia/Kolkata")
        assertEquals("2026-11-25T18:30", b.getString("scheduledFor"))
        assertFalse(b.has("publishNow"))
    }

    @Test fun hashtagOnlyFirstLineGivesPlainYoutubeTitle() {
        val b = Zernio.postBody("u", "#fyp #viral", listOf(yt), null, "UTC")
        val title = b.getJSONArray("platforms").getJSONObject(0).getJSONObject("platformSpecificData").getString("title")
        assertEquals("Clip #Shorts", title)
    }

    @Test fun postSendsIdempotencyKeyAndReturnsId() {
        val http = FakeHttp(mutableListOf(ok("""{"post":{"_id":"p9"}}""")))
        val id = Zernio("k", http).post("https://cdn/x.mp4", "Hi", listOf(ig), null, "UTC", "idem-1")
        assertEquals("p9", id)
        assertEquals("idem-1", http.calls[0].headers["Idempotency-Key"])
        assertEquals("application/json", http.calls[0].headers["Content-Type"])
        assertTrue(http.calls[0].url.endsWith("/posts"))
    }

    @Test fun wrongKeyGivesPlainWords() {
        val http = FakeHttp(mutableListOf(HttpReply(401, """{"error":"Unauthorized"}""")))
        try {
            Zernio("fake-key", http).accounts()
            fail("should have thrown")
        } catch (e: ZernioError) {
            assertEquals(401, e.status)
            assertTrue(e.message!!.contains("didn't accept that key"))
            assertFalse(e.message!!.contains("fake-key"))
        }
    }

    @Test fun serverTroubleIsRetryableAndQuotaMessageIsKept() {
        val http = FakeHttp(mutableListOf(HttpReply(503, ""), HttpReply(429, """{"error":"Daily upload limit reached for this account","platform":"youtube"}""")))
        val z = Zernio("k", http)
        try { z.accounts(); fail() } catch (e: ZernioError) { assertTrue(e.retryable) }
        try { z.accounts(); fail() } catch (e: ZernioError) { assertEquals("Daily upload limit reached for this account", e.message) }
    }

    @Test fun blankKeyIsRefused() {
        try { Zernio("  ", FakeHttp(mutableListOf())); fail() } catch (e: ZernioError) { assertEquals("Add your Zernio key first.", e.message) }
    }

    @Test fun clipNamesBecomeTitles() {
        assertEquals("Al Jazeera English Live", PostText.titleFromFile("MasterClip_Al_Jazeera_English_Live_2026_11_24_10_44_3.mp4"))
        assertEquals("Your video", PostText.titleFromFile("MasterClip_Your_video_1.mp4"))
        assertEquals("Clip", PostText.titleFromFile("MasterClip_1.mp4"))
        assertEquals("Hi\n\n#a #b", PostText.caption("Hi", "#a #b"))
    }

}
