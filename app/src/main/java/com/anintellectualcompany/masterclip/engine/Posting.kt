package com.anintellectualcompany.masterclip.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The Zernio key (encrypted, never logged) and the posting choices. */
object PostingStore {
    private const val TAG = "MasterClip"

    private fun secure(c: Context): SharedPreferences {
        val master = MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            c, "posting_secure", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun prefs(c: Context) = c.getSharedPreferences("posting", Context.MODE_PRIVATE)

    fun key(c: Context): String? = try {
        secure(c).getString("zernio_key", null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the saved key (${e.javaClass.simpleName})")
        null
    }

    fun setKey(c: Context, key: String?) {
        try {
            secure(c).edit().apply { if (key.isNullOrBlank()) remove("zernio_key") else putString("zernio_key", key.trim()) }.apply()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't save the key (${e.javaClass.simpleName})")
            throw ZernioError("Couldn't save the key safely on this phone.")
        }
    }

    fun selected(c: Context): List<ZernioAccount> {
        val arr = try { JSONArray(prefs(c).getString("accounts", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            ZernioAccount(o.optString("id"), o.optString("platform"), o.optString("name")).takeIf { it.id.isNotBlank() }
        }
    }

    fun setSelected(c: Context, list: List<ZernioAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("platform", it.platform).put("name", it.name)) }
        prefs(c).edit().putString("accounts", arr.toString()).apply()
    }

    fun hashtags(c: Context): String = prefs(c).getString("hashtags", PostText.DEFAULT_TAGS) ?: PostText.DEFAULT_TAGS
    fun setHashtags(c: Context, v: String) = prefs(c).edit().putString("hashtags", v.take(300)).apply()

    fun autoPost(c: Context) = prefs(c).getBoolean("auto", false)
    fun setAutoPost(c: Context, v: Boolean) = prefs(c).edit().putBoolean("auto", v).apply()

    fun gapMinutes(c: Context) = prefs(c).getInt("gap", 60)
    fun setGapMinutes(c: Context, v: Int) = prefs(c).edit().putInt("gap", v).apply()

    /** When the next auto-posted clip may go out (epoch ms). */
    fun takeSlot(c: Context, nowMs: Long = System.currentTimeMillis()): Long {
        val p = prefs(c)
        val slot = maxOf(nowMs + 10 * 60_000L, p.getLong("nextSlot", 0))
        p.edit().putLong("nextSlot", slot + gapMinutes(c) * 60_000L).apply()
        return slot
    }

    fun ready(c: Context) = key(c) != null && selected(c).isNotEmpty()

    /** Local wall-clock ISO (no offset) for Zernio's scheduledFor. */
    fun isoLocal(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

/** Posts one clip from the background, even when the app is closed. Zernio holds scheduled posts, so the phone can be off afterwards. */
class PostWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val path = inputData.getString("path") ?: return Result.failure()
        val caption = inputData.getString("caption") ?: return Result.failure()
        val atMs = inputData.getLong("at", 0)
        val idem = inputData.getString("idem") ?: UUID.randomUUID().toString()
        val file = File(path)
        if (!file.exists()) return Result.failure()
        val key = PostingStore.key(applicationContext) ?: return Result.failure()
        val accounts = PostingStore.selected(applicationContext)
        if (accounts.isEmpty()) return Result.failure()
        return try {
            val zone = ZoneId.systemDefault()
            val z = Zernio(key)
            val url = z.upload(file)
            val whenLocal = if (atMs > System.currentTimeMillis() + 2 * 60_000L) PostingStore.isoLocal(atMs, zone) else null
            z.post(url, caption, accounts, whenLocal, zone.id, idem)
            Log.i("MasterClip", "auto-post sent for ${file.name}")
            Result.success()
        } catch (e: ZernioError) {
            Log.w("MasterClip", "auto-post failed: ${e.message}")
            if (e.retryable && runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun enqueue(context: Context, file: File, caption: String, atMs: Long) {
            val req = OneTimeWorkRequestBuilder<PostWorker>()
                .setInputData(workDataOf("path" to file.absolutePath, "caption" to caption, "at" to atMs, "idem" to UUID.randomUUID().toString()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("post:${file.absolutePath}", ExistingWorkPolicy.KEEP, req)
        }

        /** Called for every finished clip: queues it when "Auto-post every clip" is on. */
        fun auto(context: Context, file: File) {
            try {
                if (!PostingStore.autoPost(context) || !PostingStore.ready(context)) return
                val caption = PostText.caption(PostText.titleFromFile(file.name), PostingStore.hashtags(context))
                enqueue(context, file, caption, PostingStore.takeSlot(context))
            } catch (e: Exception) {
                Log.w("MasterClip", "auto-post not queued: ${e.message}")
            }
        }
    }
}
