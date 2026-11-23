package com.anintellectualcompany.masterclip.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

enum class SearchFilter(val label: String) { ALL("All"), LIVE("Live now"), PAST("Past streams") }

data class SearchHit(
    val id: String,
    val title: String,
    val channel: String,
    val durationSec: Int?,
    val live: Boolean,
    val url: String,
) {
    val thumb: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

/** YouTube search through yt-dlp (flat: no per-video extraction, so it is fast) plus link/live detection. */
object Search {
    private const val LIVE_FILTER = "EgJAAQ%3D%3D" // YouTube's "Live" filter

    private val twitchLink = Regex("""^(?:https?://)?(?:www\.|m\.)?twitch\.tv/([A-Za-z0-9_]{3,25})/?(?:\?.*)?$""", RegexOption.IGNORE_CASE)
    private val twitchName = Regex("""^@?([A-Za-z0-9_]{4,25})$""")
    private val twitchReserved = setOf("directory", "videos", "downloads", "jobs", "turbo", "settings", "login", "p", "store")

    /** The Twitch channel a query points to ("twitch.tv/shroud" or a bare name like "shroud"), else null. */
    fun twitchChannel(query: String): String? {
        val q = query.trim()
        twitchLink.find(q)?.groupValues?.get(1)?.let { return it.takeUnless { n -> n.lowercase() in twitchReserved } }
        if (q.contains("/") || q.contains(".")) return null
        return twitchName.find(q)?.groupValues?.get(1)?.takeUnless { it.lowercase() in twitchReserved }
    }

    fun twitchUrl(channel: String) = "https://www.twitch.tv/$channel"

    /** Cheap check on a pasted link: is it (probably) a stream that is live right now? */
    fun looksLive(link: String?): Boolean {
        val u = link?.trim()?.lowercase() ?: return false
        if (u.isEmpty()) return false
        if (Regex("""youtube\.com/(@[^/?]+|channel/[^/?]+|c/[^/?]+)/live""").containsMatchIn(u)) return true
        if (Regex("""youtube\.com/live/[^/?]+""").containsMatchIn(u)) return true
        val ch = twitchLink.find(u)?.groupValues?.get(1) ?: return false
        return ch.lowercase() !in twitchReserved
    }

    /** The yt-dlp input for a query. */
    fun target(query: String, filter: SearchFilter, count: Int = 20): String {
        val q = query.trim()
        return when (filter) {
            SearchFilter.ALL -> "ytsearch$count:$q"
            SearchFilter.PAST -> "ytsearch$count:$q stream"
            SearchFilter.LIVE -> "https://www.youtube.com/results?search_query=${URLEncoder.encode(q, "UTF-8")}&sp=$LIVE_FILTER"
        }
    }

    /** Parses yt-dlp's flat-playlist JSON. Channels and playlists in the results are skipped. */
    fun parse(json: String, assumeLive: Boolean = false): List<SearchHit> {
        val start = json.indexOf('{')
        if (start < 0) return emptyList()
        val entries = JSONObject(json.substring(start)).optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<SearchHit>()
        val seen = HashSet<String>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.optString("id")
            val url = e.optString("url").ifBlank { "https://www.youtube.com/watch?v=$id" }
            if (id.length != 11 || !url.contains("watch?v=") || !seen.add(id)) continue
            val duration = if (e.isNull("duration")) null else e.optDouble("duration").toInt()
            val status = e.optString("live_status")
            val live = status == "is_live" || (assumeLive && duration == null && status != "is_upcoming" && status != "was_live")
            val channel = e.optString("channel").ifBlank { e.optString("uploader") }
            out += SearchHit(id, e.optString("title").ifBlank { "Untitled" }, channel, duration, live, url)
        }
        return out
    }

    /** Runs the search on the phone. Throws [FriendlyError] with plain words. */
    fun run(context: Context, query: String, filter: SearchFilter): List<SearchHit> {
        Ytdl.ensure(context)
        val req = YoutubeDLRequest(target(query, filter)).apply {
            addOption("--flat-playlist")
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--playlist-end", "20")
            addOption("--socket-timeout", "15")
        }
        val out = try {
            YoutubeDL.execute(req, "search-" + UUID.randomUUID()).out
        } catch (e: Exception) {
            val m = e.message ?: ""
            throw FriendlyError(
                if (m.contains("Unable to download", true) || m.contains("resolve", true) || m.contains("urlopen", true))
                    "Couldn't reach YouTube. Check your internet and try again."
                else "Search didn't work this time. Try again in a moment.",
            )
        }
        return try {
            parse(out, assumeLive = filter == SearchFilter.LIVE)
        } catch (e: Exception) {
            throw FriendlyError("YouTube sent something we couldn't read. Try again in a moment.")
        }
    }

    fun durationText(sec: Int): String = clock(sec.toDouble())
}
