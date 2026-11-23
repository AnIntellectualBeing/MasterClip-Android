package com.anintellectualcompany.masterclip.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Twitch VODs have a chat replay. Where chat goes wild is where the clip is, and reading chat is
 * far quicker than listening to hours of sound. Same idea as the desktop MasterClip (twitch.py).
 * Uses Twitch's public web GraphQL and the public web client id (the twitch.tv site and yt-dlp use it too).
 * Only the video number is sent.
 */
object Twitch {
    private const val TAG = "MasterClip"
    private const val GQL = "https://gql.twitch.tv/gql"
    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    private val vodRe = Regex("""twitch\.tv/(?:videos/|[A-Za-z0-9_]+/v(?:ideo)?/)(\d{1,15})""")
    private const val QUERY = "query(\$id:ID!,\$off:Int){ video(id:\$id){ comments(contentOffsetSeconds:\$off){ edges{ node{ contentOffsetSeconds } } } } }"

    fun vodId(url: String): String? = vodRe.find(url)?.groupValues?.get(1)

    /** The message times (seconds into the video) of one GraphQL answer. */
    fun parseTimes(json: String): List<Double> {
        val edges = JSONObject(json).optJSONObject("data")?.optJSONObject("video")
            ?.optJSONObject("comments")?.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<Double>(edges.length())
        for (i in 0 until edges.length()) {
            val t = edges.optJSONObject(i)?.optJSONObject("node")?.optDouble("contentOffsetSeconds", Double.NaN) ?: continue
            if (!t.isNaN()) out += t
        }
        return out
    }

    private fun page(id: String, offset: Int): List<Double> {
        val c = URL(GQL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Client-ID", CLIENT_ID)
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("query", QUERY).put("variables", JSONObject().put("id", id).put("off", offset)).toString()
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) return emptyList()
            return parseTimes(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            return emptyList()
        } finally {
            c.disconnect()
        }
    }

    /**
     * Per-second score from chat speed, or null when Twitch gives too little to go on (chat off, quiet VOD).
     * Round one looks at the whole video; round two looks closer around the busiest places.
     * Stops after [deadlineMs] and uses what it has.
     */
    suspend fun chatScore(id: String, duration: Double, from: Double, to: Double, wanted: Int, deadlineMs: Long): DoubleArray? {
        val t0 = System.currentTimeMillis()
        val span = to - from
        if (span < 60) return null
        val total = duration.toInt().coerceAtLeast(1)
        val points = java.util.Collections.synchronizedList(ArrayList<Moments.RatePoint>())
        val sem = Semaphore(8)
        var requests = 0

        suspend fun look(offsets: List<Int>) = coroutineScope {
            offsets.map { off ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        if (System.currentTimeMillis() - t0 > deadlineMs) return@withPermit
                        val times = page(id, off)
                        synchronized(this@Twitch) { requests++ }
                        Moments.ratePointOf(times)?.let { points += it }
                    }
                }
            }.forEach { it.await() }
        }

        val step1 = max(20.0, span / 160.0)
        val first = generateSequence(from) { it + step1 }.takeWhile { it < to }.map { it.toInt() }.toList()
        look(first)
        coroutineContext.ensureActive()
        if (points.size < 8) {
            Log.i(TAG, "chat: only ${points.size} usable samples of ${first.size}; not enough")
            return null
        }
        // Round two: finer samples around the busiest places.
        val sorted = points.sortedByDescending { it.rate }
        val chosen = ArrayList<Double>()
        for (p in sorted) {
            if (chosen.size >= max(4, wanted * 3)) break
            if (chosen.none { kotlin.math.abs(it - p.time) < step1 * 1.5 }) chosen += p.time
        }
        val fine = chosen.flatMap { c ->
            val a = max(from, c - step1)
            val b = min(to, c + step1)
            generateSequence(a) { it + 7.0 }.takeWhile { it < b }.map { it.toInt() }.toList()
        }
        look(fine)
        coroutineContext.ensureActive()
        Log.i(TAG, "chat: ${points.size} samples from $requests requests in ${System.currentTimeMillis() - t0} ms (video ${total}s)")
        val rates = points.map { it.rate }
        if (rates.max() <= 0.0) return null
        return Moments.rateScore(points.toList(), total)
    }
}
