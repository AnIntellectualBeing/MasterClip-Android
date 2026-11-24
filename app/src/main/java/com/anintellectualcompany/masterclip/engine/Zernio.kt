package com.anintellectualcompany.masterclip.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** The one place the app talks to the network for posting, so tests can swap it for a fake. */
interface Http {
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply
    /** Streams a file (big video) with PUT. [onProgress] gets 0..1. */
    fun put(url: String, file: File, contentType: String, onProgress: (Float) -> Unit): HttpReply
}

class HttpReply(val code: Int, val body: String, val headers: Map<String, String> = emptyMap())

class UrlHttp(private val connectMs: Int = 20_000, private val readMs: Int = 120_000) : Http {
    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpReply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            return reply(c)
        } finally {
            c.disconnect()
        }
    }

    override fun put(url: String, file: File, contentType: String, onProgress: (Float) -> Unit): HttpReply {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.connectTimeout = 20_000
            c.readTimeout = 600_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.setFixedLengthStreamingMode(file.length())
            val total = file.length().coerceAtLeast(1)
            file.inputStream().use { ins ->
                c.outputStream.use { outs ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var tick = 0
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        outs.write(buf, 0, r)
                        done += r
                        if (++tick % 32 == 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            return reply(c)
        } finally {
            c.disconnect()
        }
    }

    private fun reply(c: HttpURLConnection): HttpReply {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val heads = HashMap<String, String>()
        c.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) heads[k.lowercase()] = v.first() }
        return HttpReply(code, text, heads)
    }
}

class ZernioError(message: String, val status: Int? = null, val retryable: Boolean = false) : Exception(message)

data class ZernioAccount(val id: String, val platform: String, val name: String)

/** What the phone sends to Zernio. Same calls as the desktop app (zernio.py). */
class Zernio(key: String, private val http: Http = UrlHttp(), private val api: String = API) {
    private val headers = mapOf("Authorization" to "Bearer ${key.trim()}", "User-Agent" to "MasterClipAndroid/1.1")

    init {
        if (key.isBlank()) throw ZernioError("Add your Zernio key first.")
    }

    private fun call(method: String, path: String, json: JSONObject? = null, extra: Map<String, String> = emptyMap()): JSONObject {
        val h = HashMap(headers)
        h.putAll(extra)
        if (json != null) h["Content-Type"] = "application/json"
        val r = try {
            http.send(method, api.trimEnd('/') + path, h, json?.toString()?.toByteArray(Charsets.UTF_8))
        } catch (e: java.io.IOException) {
            throw ZernioError("Couldn't reach Zernio. Check your internet and try again.", retryable = true)
        }
        if (r.code >= 400) throw ZernioError(friendly(r), r.code, retryable = r.code == 429 || r.code >= 500)
        return try { JSONObject(r.body.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
    }

    fun accounts(): List<ZernioAccount> {
        val r = call("GET", "/accounts")
        val arr = r.optJSONArray("accounts") ?: JSONArray()
        val out = ArrayList<ZernioAccount>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val id = a.optString("_id").ifBlank { a.optString("id") }
            val platform = a.optString("platform").lowercase()
            if (id.isBlank() || platform !in PLATFORMS || a.optBoolean("isActive", true).not()) continue
            val name = listOf("username", "displayName", "name").map { a.optString(it) }.firstOrNull { it.isNotBlank() } ?: platform
            out += ZernioAccount(id, platform, name)
        }
        return out
    }

    /** Uploads the clip and returns its public link. */
    fun upload(file: File, onProgress: (Float) -> Unit = {}): String {
        val body = JSONObject().put("filename", file.name).put("contentType", "video/mp4").put("size", file.length())
        val presign = call("POST", "/media/presign", body)
        val uploadUrl = presign.optString("uploadUrl")
        val publicUrl = presign.optString("publicUrl")
        if (uploadUrl.isBlank() || publicUrl.isBlank()) throw ZernioError("Zernio didn't accept the video. Try again.")
        val put = try {
            http.put(uploadUrl, file, "video/mp4", onProgress)
        } catch (e: java.io.IOException) {
            throw ZernioError("The video upload didn't finish. Check your internet and try again.", retryable = true)
        }
        if (put.code >= 400) throw ZernioError("The video upload didn't finish.", put.code, retryable = put.code == 429 || put.code >= 500)
        return publicUrl
    }

    /**
     * Makes the post. [whenLocal] is the local wall-clock time as ISO (2026-11-24T18:30) in [timezone], or null to post now.
     * Returns Zernio's post id.
     */
    fun post(
        videoUrl: String,
        caption: String,
        accounts: List<ZernioAccount>,
        whenLocal: String?,
        timezone: String,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): String {
        val body = postBody(videoUrl, caption, accounts, whenLocal, timezone)
        val data = call("POST", "/posts", body, mapOf("Idempotency-Key" to idempotencyKey))
        val id = data.optJSONObject("post")?.optString("_id").orEmpty()
        if (id.isBlank()) throw ZernioError("Zernio didn't confirm the post. Check your Zernio account before trying again.", retryable = true)
        return id
    }

    companion object {
        const val API = "https://zernio.com/api/v1"
        val PLATFORMS = listOf("instagram", "youtube")

        /** First line of the caption is the YouTube title. Shorts get #Shorts. */
        fun postBody(videoUrl: String, caption: String, accounts: List<ZernioAccount>, whenLocal: String?, timezone: String): JSONObject {
            val text = caption.trim()
            val platforms = JSONArray()
            for (a in accounts) {
                val entry = JSONObject().put("platform", a.platform).put("accountId", a.id)
                if (a.platform == "youtube") {
                    var title = text.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty().ifBlank { "Clip" }
                    // Hashtag-only first lines make poor titles.
                    if (title.startsWith("#")) title = "Clip"
                    if (!title.lowercase().contains("#shorts")) title = title.take(92).trimEnd() + " #Shorts"
                    var desc = text
                    if (!desc.lowercase().contains("#shorts")) desc = desc.trimEnd() + "\n\n#Shorts"
                    entry.put("customContent", desc.take(4900))
                    entry.put("platformSpecificData", JSONObject().put("title", title.take(100)).put("visibility", "public").put("madeForKids", false))
                } else {
                    entry.put("customContent", text.take(2200))
                }
                platforms.put(entry)
            }
            val body = JSONObject()
                .put("content", text.take(2200))
                .put("mediaItems", JSONArray().put(JSONObject().put("url", videoUrl).put("type", "video")))
                .put("platforms", platforms)
                .put("timezone", timezone)
            if (whenLocal != null) body.put("scheduledFor", whenLocal) else body.put("publishNow", true)
            return body
        }

        fun friendly(r: HttpReply): String {
            val data = try { JSONObject(r.body) } catch (e: Exception) { JSONObject() }
            val detail = data.optString("error").ifBlank { data.optString("message") }
            val low = detail.lowercase()
            if (detail.isNotBlank() && (data.has("platform") || "quota" in low || "limit" in low)) return detail
            return when {
                r.code == 401 || r.code == 403 -> "Zernio didn't accept that key. Copy it again from zernio.com, Settings, API keys."
                r.code == 429 -> detail.ifBlank { "Zernio is limiting requests right now. Try again later." }
                r.code >= 500 -> "Zernio is having trouble right now. Try again in a few minutes."
                detail.isNotBlank() -> "Zernio said: $detail"
                else -> "Zernio returned an error (${r.code})."
            }
        }
    }
}

/** Caption helpers. */
object PostText {
    const val DEFAULT_TAGS = "#shorts #fyp #viral"

    /** "MasterClip_Al_Jazeera_English_Live_2026_11_24_10_44_3.mp4" -> "Al Jazeera English Live". */
    fun titleFromFile(name: String): String {
        val base = name.substringBeforeLast('.').removePrefix("MasterClip_")
        val parts = base.split('_').filter { it.isNotBlank() }
        // Drop the clip number and the date stamp live titles carry.
        val words = parts.dropLastWhile { p -> p.all { it.isDigit() } && p.length <= 4 }
        return words.joinToString(" ").ifBlank { "Clip" }
    }

    fun caption(title: String, hashtags: String): String = (title.trim() + "\n\n" + hashtags.trim()).trim()
}
