package com.anintellectualcompany.masterclip.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Platform(
    val id: String,
    val name: String,
    val url: String,
    val pays: String?,
    val payoutMethod: String?,
    val minPayout: String?,
    val note: String?,
)

data class Source(val name: String, val link: String?)

data class Campaign(
    val id: String,
    val title: String,
    val brand: String?,
    val url: String?,
    val poster: String?,
    val payoutPer1k: Double?,
    val budget: String?,
    val minViews: Long?,
    val platforms: List<String>,
    val sources: List<Source>,
    val clipLenMin: Int?,
    val clipLenMax: Int?,
    val rules: List<String>,
    val ends: String?,
)

data class Feed(val updated: String?, val platforms: List<Platform>, val campaigns: List<Campaign>, val source: String = "bundled")

data class Submission(
    val id: String,
    val campaignId: String,
    val clipName: String,
    val postedUrl: String,
    val postedAt: String,
    val views: Long,
    val updatedAt: String,
)

/** Which campaign the clip screen is making clips for (shown as a banner). */
data class CampaignRef(val id: String, val title: String)

object Campaigns {
    const val FEED_URL = "https://anintellectualcompany.com/campaigns.json"
    private const val CACHE = "campaigns-cache.json"
    private const val SEED = "campaigns-seed.json"

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private fun JSONObject.web(key: String): String? = str(key)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** Returns null when the text is not a feed at all. Hidden (active=false) and half-empty entries are dropped. */
    fun parse(text: String, source: String = "bundled"): Feed? {
        val root = try { JSONObject(text) } catch (_: Exception) { return null }
        val plat = root.optJSONArray("platforms") ?: JSONArray()
        val camp = root.optJSONArray("campaigns") ?: JSONArray()
        val platforms = (0 until plat.length()).mapNotNull { i ->
            val p = plat.optJSONObject(i) ?: return@mapNotNull null
            val id = p.str("id"); val name = p.str("name"); val url = p.web("url")
            if (id == null || name == null || url == null) return@mapNotNull null
            Platform(id, name, url, p.str("pays"), p.str("payout_method"), p.str("min_payout"), p.str("note"))
        }
        val campaigns = (0 until camp.length()).mapNotNull { i ->
            val c = camp.optJSONObject(i) ?: return@mapNotNull null
            val id = c.str("id"); val title = c.str("title")
            if (id == null || title == null || (c.has("active") && !c.optBoolean("active", true))) return@mapNotNull null
            val len = c.optJSONArray("clip_len")
            val rate = if (c.has("payout_per_1k") && c.opt("payout_per_1k") is Number) c.getDouble("payout_per_1k").takeIf { it >= 0 } else null
            Campaign(
                id = id, title = title, brand = c.str("brand"), url = c.web("url"), poster = c.web("poster"),
                payoutPer1k = rate, budget = c.str("budget"),
                minViews = if (c.opt("min_views") is Number) c.getLong("min_views") else null,
                platforms = c.optJSONArray("platforms")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                sources = c.optJSONArray("sources")?.let { a ->
                    (0 until a.length()).mapNotNull { k ->
                        val s = a.optJSONObject(k) ?: return@mapNotNull null
                        val name = s.str("name") ?: return@mapNotNull null
                        Source(name, s.web("link"))
                    }
                } ?: emptyList(),
                clipLenMin = if (len != null && len.length() == 2) len.optInt(0) else null,
                clipLenMax = if (len != null && len.length() == 2) len.optInt(1) else null,
                rules = c.optJSONArray("rules")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                ends = c.str("ends"),
            )
        }
        return Feed(root.str("updated"), platforms, campaigns, source)
    }

    /** views / 1000 x payout per 1K views, to cents. Null when the campaign has no known rate. */
    fun estimate(views: Long, payoutPer1k: Double?): Double? =
        if (payoutPer1k == null || views < 0) null else Math.round(views / 1000.0 * payoutPer1k * 100.0) / 100.0

    fun total(subs: List<Submission>, campaigns: List<Campaign>): Double {
        val rates = campaigns.associate { it.id to it.payoutPer1k }
        return subs.sumOf { estimate(it.views, rates[it.campaignId]) ?: 0.0 }
    }

    /** Clip length for "Clip for this": 30 s if the campaign allows it, otherwise the nearest allowed length. */
    fun lengthFor(c: Campaign): Int {
        val lo = c.clipLenMin ?: 15
        val hi = c.clipLenMax ?: 60
        return 30.coerceIn(minOf(lo, hi), maxOf(lo, hi)).coerceIn(5, 180)
    }

    /** Live feed, else the saved copy in [dir], else [seed]. Never throws. */
    fun load(dir: File, seed: () -> String?, fetch: () -> String?): Feed {
        val cache = File(dir, CACHE)
        val raw = try { fetch() } catch (_: Exception) { null }
        val live = raw?.let { parse(it, "live") }
        if (live != null) {
            try { cache.parentFile?.mkdirs(); cache.writeText(raw) } catch (_: Exception) {}
            return live
        }
        val saved = try { if (cache.exists()) parse(cache.readText(), "cache") else null } catch (_: Exception) { null }
        if (saved != null) return saved
        return seed()?.let { parse(it, "bundled") } ?: Feed(null, emptyList(), emptyList(), "bundled")
    }

    fun loadFromNetwork(context: Context): Feed {
        val dir = context.filesDir
        val http = UrlHttp(connectMs = 8_000, readMs = 8_000)
        return load(
            dir,
            seed = { try { context.assets.open(SEED).bufferedReader().use { it.readText() } } catch (_: Exception) { null } },
            fetch = {
                val r = http.send("GET", FEED_URL, mapOf("Accept" to "application/json"), null)
                if (r.code in 200..299) r.body else null
            },
        )
    }
}

/** My submissions: a small JSON file in the app's private folder. Views are typed by hand. */
class SubmissionStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "campaign-submissions.json"))

    @Synchronized
    fun all(): List<Submission> = try {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Submission(o.optString("id"), o.optString("campaign_id"), o.optString("clip_name"), o.optString("posted_url"),
                o.optString("posted_at"), o.optLong("views"), o.optString("updated_at"))
        }
    } catch (_: Exception) { emptyList() }

    private fun save(list: List<Submission>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("campaign_id", it.campaignId).put("clip_name", it.clipName)
                .put("posted_url", it.postedUrl).put("posted_at", it.postedAt).put("views", it.views).put("updated_at", it.updatedAt))
        }
        file.parentFile?.mkdirs()
        file.writeText(a.toString())
    }

    private fun now() = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    @Synchronized
    fun add(campaignId: String, clipName: String, postedUrl: String, views: Long): Submission {
        val t = now()
        val s = Submission(UUID.randomUUID().toString().take(10), campaignId, clipName.trim(), postedUrl.trim(), t.take(10), views.coerceAtLeast(0), t)
        save(all() + s)
        return s
    }

    @Synchronized
    fun setViews(id: String, views: Long) = save(all().map { if (it.id == id) it.copy(views = views.coerceAtLeast(0), updatedAt = now()) else it })

    @Synchronized
    fun delete(id: String) = save(all().filter { it.id != id })
}
