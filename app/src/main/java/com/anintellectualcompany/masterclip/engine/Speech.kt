package com.anintellectualcompany.masterclip.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** JNI side: whisper.cpp built with the NDK (see app/src/main/cpp). */
object WhisperLib {
    init { System.loadLibrary("mcwhisper") }
    external fun init(path: String, libDir: String): Long
    external fun free(handle: Long)
    external fun run(handle: Long, pcm: FloatArray, lang: String, prompt: String, threads: Int): ByteArray?
}

/** Speech to text on the phone, so every clip can have captions (live streams, phone videos, videos without subtitles). */
object Speech {
    const val MODEL_FILE = "ggml-tiny-q5_1.bin"
    const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL_FILE"
    const val MODEL_MB = 31
    private const val MIN_MODEL_BYTES = 25_000_000L
    private const val HINGLISH_PROMPT = "Aaj hum cricket ke baare mein baat karenge. Kya haal hai bhai, sab theek hai?"

    private val lock = Mutex()
    private var handle = 0L
    private var jobLang: String? = null

    /** Call at the start of every job: the next job may be in another language. */
    fun newJob() { jobLang = null }

    fun modelFile(context: Context) = File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILE)
    fun hasModel(context: Context) = modelFile(context).let { it.exists() && it.length() > MIN_MODEL_BYTES }

    /** Downloads the model once. [onProgress] gets 0..1. */
    suspend fun ensureModel(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (hasModel(context)) return@withContext
        val dst = modelFile(context)
        val tmp = File(dst.parentFile, "$MODEL_FILE.part")
        val c = URL(MODEL_URL).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) throw FriendlyError("Couldn't get the caption model. Check your internet and try again.")
            val total = c.contentLengthLong
            c.inputStream.use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var tick = 0
                    while (true) {
                        val r = ins.read(buf)
                        if (r < 0) break
                        outs.write(buf, 0, r)
                        done += r
                        if (++tick % 32 == 0) {
                            coroutineContext.ensureActive()
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            if (tmp.length() < MIN_MODEL_BYTES) throw FriendlyError("The caption model download was cut short. Try again.")
            if (!tmp.renameTo(dst)) throw FriendlyError("Couldn't save the caption model.")
        } catch (e: java.io.IOException) {
            tmp.delete()
            throw FriendlyError("Couldn't get the caption model. Check your internet and try again.")
        } finally {
            c.disconnect()
        }
    }

    class Result(val words: List<Word>, val language: String)

    /** Words spoken in [fromMs, toMs) of the sound in [uri]. Times start at 0 at fromMs. */
    suspend fun transcribe(context: Context, uri: Uri, fromMs: Long, toMs: Long): Result {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val pcm = withContext(Dispatchers.IO) { Pcm.decode(context, uri, fromMs, toMs) }
        val tDecode = android.os.SystemClock.elapsedRealtime() - t0
        if (pcm.size < 16000) return Result(emptyList(), "")
        return lock.withLock {
            withContext(Dispatchers.Default) {
                val t1 = android.os.SystemClock.elapsedRealtime()
                if (handle == 0L) {
                    handle = WhisperLib.init(modelFile(context).absolutePath, context.applicationInfo.nativeLibraryDir)
                    if (handle == 0L) {
                        modelFile(context).delete() // a damaged file: fetch it again next time
                        throw FriendlyError("The caption model didn't load. Try again.")
                    }
                }
                val tLoad = android.os.SystemClock.elapsedRealtime() - t1
                val threads = max(2, min(6, Runtime.getRuntime().availableProcessors() - 2))
                val t2 = android.os.SystemClock.elapsedRealtime()
                // The language is found once per job (finding it costs a whole extra pass), then reused.
                val known = jobLang
                var res = parse(WhisperLib.run(handle, pcm, known ?: "auto", if (known == "hi") HINGLISH_PROMPT else "", threads)?.toString(Charsets.UTF_8))
                if (known == null && res.language == "hi" && res.words.any { w -> w.text.any { it in 'ऀ'..'ॿ' } }) {
                    // Hindi: ask again with a Hinglish prompt so it writes in English letters.
                    val again = parse(WhisperLib.run(handle, pcm, "hi", HINGLISH_PROMPT, threads)?.toString(Charsets.UTF_8))
                    if (again.words.isNotEmpty()) res = again
                }
                if (known == null && res.language.isNotBlank() && res.words.size >= 5) jobLang = res.language
                Log.i("MasterClip", "whisper: decode $tDecode ms, load $tLoad ms, run ${android.os.SystemClock.elapsedRealtime() - t2} ms, $threads threads, ${pcm.size / 16000} s of sound")
                res
            }
        }
    }

    private val noise = Regex("""^\[.*]$|^\(.*\)$|^\*.*\*$""")

    /** whisper's "lang\nstart\tend\ttext" lines -> clean words. */
    fun parse(raw: String?): Result {
        if (raw == null) return Result(emptyList(), "")
        val lines = raw.split("\n")
        val lang = lines.firstOrNull()?.trim() ?: ""
        val items = ArrayList<Word>()
        for (line in lines.drop(1)) {
            val p = line.split("\t", limit = 3)
            if (p.size < 3) continue
            val text = p[2].replace(Regex("[♪♫♬]"), "").trim()
            if (text.isEmpty() || noise.matches(text) || text.startsWith("[_")) continue
            if (text.none { it.isLetterOrDigit() }) continue
            val s = p[0].toLongOrNull() ?: continue
            val e = p[1].toLongOrNull() ?: continue
            items += Word(text, s, max(e, s + 120))
        }
        // Keep the words in order and never overlapping.
        val out = ArrayList<Word>()
        for ((i, w) in items.withIndex()) {
            val next = items.getOrNull(i + 1)?.startMs
            val end = if (next != null && next > w.startMs) min(w.endMs, next) else w.endMs
            out += Word(w.text, w.startMs, max(end, w.startMs + 80))
        }
        return Result(out, lang)
    }
}

/** Decodes a stretch of a file's sound to 16 kHz mono floats (what whisper wants). */
object Pcm {
    suspend fun decode(context: Context, uri: Uri, fromMs: Long, toMs: Long): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val base = ex.sampleTime.coerceAtLeast(0)
            val fromUs = fromMs * 1000
            val toUs = toMs * 1000
            if (fromUs > 0) ex.seekTo(base + fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val chunksOut = ArrayList<FloatArray>()
            var total = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var ended = false
            var acc = 0.0 // position, in source frames, of the next 16 kHz sample inside the current buffer
            var first = true
            var cur = FloatArray(16000 * 10)
            var curN = 0
            fun push(v: Float) {
                if (curN == cur.size) { chunksOut += cur; total += curN; cur = FloatArray(16000 * 10); curN = 0 }
                cur[curN++] = v
            }
            var ticks = 0
            while (!ended) {
                if (++ticks % 50 == 0) coroutineContext.ensureActive()
                var progressed = false
                while (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    val t = ex.sampleTime
                    val size = if (t < 0 || t - base > toUs) -1 else ex.readSampleData(c.getInputBuffer(inIdx)!!, 0)
                    if (size < 0) {
                        c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(inIdx, 0, size, t, 0)
                        ex.advance()
                    }
                    progressed = true
                }
                while (true) {
                    val outIdx = c.dequeueOutputBuffer(info, if (progressed) 0 else 3000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        progressed = true
                    } else if (outIdx >= 0) {
                        progressed = true
                        val shorts = c.getOutputBuffer(outIdx)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val bufStartUs = info.presentationTimeUs - base
                        val frames = shorts.remaining() / channels
                        val tmp = ShortArray(shorts.remaining())
                        shorts.get(tmp)
                        val step = rate / 16000.0
                        if (first) {
                            // the first buffer starts at or before the clip start: skip the part before it
                            acc = (fromUs - bufStartUs).coerceAtLeast(0) * rate / 1_000_000.0
                            first = false
                        }
                        while (true) {
                            val pos = acc.toLong()
                            if (pos >= frames) break
                            var s = 0f
                            for (ch in 0 until channels) s += tmp[(pos * channels + ch).toInt()] / 32768f
                            push(s / channels)
                            acc += step
                        }
                        acc -= frames
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { ended = true; break }
                    } else break
                }
            }
            chunksOut += cur.copyOf(curN)
            total += curN
            val maxSamples = (toMs - fromMs) * 16 + 16000
            val all = FloatArray(min(total.toLong(), maxSamples).toInt())
            var o = 0
            for (a in chunksOut) {
                val n = min(a.size, all.size - o)
                if (n <= 0) break
                System.arraycopy(a, 0, all, o, n)
                o += n
            }
            return all
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MasterClip", "pcm decode failed", e)
            return FloatArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
