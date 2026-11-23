package com.anintellectualcompany.masterclip.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.anintellectualcompany.masterclip.ClipService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

data class ClipRequest(
    val url: String? = null,
    val localUri: String? = null,
    val count: Int = 5,
    val lengthSec: Int = 45,
    val layout: LayoutChoice = LayoutChoice.AUTO,
    val captions: Boolean = true,
    val rangeStart: Double? = null,
    val rangeEnd: Double? = null,
    val captionStyle: CaptionStyle = CaptionStyle.POP,
    val captionPosition: CaptionPosition = CaptionPosition.AUTO,
    /** Live streams: how many minutes to record / look back. */
    val liveMinutes: Int = 10,
    val liveMode: LiveMode = LiveMode.RECORD_NEXT,
)

enum class Phase { IDLE, WORKING, DONE, FAILED }

data class ClipInfo(val path: String, val start: Double, val end: Double, val layout: LayoutMode)

data class EngineState(
    val phase: Phase = Phase.IDLE,
    val title: String = "",
    val step: String = "",
    val progress: Float = 0f,
    val etaSec: Int? = null,
    val total: Int = 0,
    val clips: List<ClipInfo> = emptyList(),
    val notes: List<String> = emptyList(),
    val error: String? = null,
)

/** Runs one clip job at a time, in the background, and tells the screens what is happening. */
@UnstableApi
object ClipEngine {
    private const val TAG = "MasterClip"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state
    private var job: Job? = null
    private var startedAt = 0L
    private var smoothEta = -1.0

    fun start(context: Context, request: ClipRequest) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        _state.value = EngineState(phase = Phase.WORKING, step = "Getting ready", total = request.count)
        startedAt = SystemClock.elapsedRealtime()
        smoothEta = -1.0
        ContextCompat.startForegroundService(app, Intent(app, ClipService::class.java))
        job = scope.launch {
            try {
                if (request.url != null) urlJob(app, request) else localJob(app, request)
                finish()
            } catch (e: CancellationException) {
                val made = _state.value.clips
                _state.update {
                    if (made.isEmpty()) EngineState()
                    else it.copy(phase = Phase.DONE, step = "", notes = it.notes + "Stopped early. ${made.size} clip(s) were finished.")
                }
            } catch (e: FriendlyError) {
                fail(e.message ?: "Something went wrong.")
            } catch (e: Throwable) {
                Log.e(TAG, "job crashed", e)
                fail("Something went wrong: ${e.message ?: e.javaClass.simpleName}. Please try again.")
            } finally {
                File(app.filesDir, "work").deleteRecursively()
            }
        }
    }

    fun cancel() {
        Ytdl.cancel()
        job?.cancel()
    }

    /** Back to the start screen after a finished or failed job. */
    fun reset() {
        if (job?.isActive == true) return
        _state.value = EngineState()
    }

    private fun fail(message: String) {
        val made = _state.value.clips
        _state.update {
            if (made.isNotEmpty()) it.copy(phase = Phase.DONE, step = "", notes = it.notes + message)
            else it.copy(phase = Phase.FAILED, error = message, step = "")
        }
    }

    private fun finish() {
        _state.update {
            if (it.clips.isEmpty()) it.copy(phase = Phase.FAILED, error = it.notes.lastOrNull() ?: "No clips could be made from this video.")
            else it.copy(phase = Phase.DONE, step = "", progress = 1f, etaSec = 0)
        }
    }

    private fun step(text: String) = _state.update { it.copy(step = text) }
    private fun note(text: String) = _state.update { it.copy(notes = it.notes + text) }

    private fun progress(p: Float) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        var eta: Int? = null
        if (p > 0.05f && elapsed > 4) {
            val raw = elapsed * (1 - p) / p
            smoothEta = if (smoothEta < 0) raw else smoothEta * 0.8 + raw * 0.2
            eta = smoothEta.roundToInt()
        }
        _state.update { it.copy(progress = max(it.progress, p.coerceIn(0f, 1f)), etaSec = eta ?: it.etaSec) }
    }

    /** Progress inside one stage, mapped onto [from]..[to] of the whole job. */
    private fun stage(from: Float, to: Float): (Float) -> Unit = { f -> progress(from + (to - from) * f.coerceIn(0f, 1f)) }

    private fun clipsDir(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(context.filesDir, "clips/$stamp").apply { mkdirs() }
    }

    // ---------------------------------------------------------------- YouTube / Twitch link

    private suspend fun urlJob(context: Context, req: ClipRequest) {
        val url = req.url!!.trim()
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm?.activeNetwork == null) throw FriendlyError("You're offline. Turn on Wi-Fi or mobile data, then tap Try again.")
        step("Starting the clip engine (first time takes a little longer)")
        Ytdl.ensure(context)
        progress(0.03f)

        step("Reading the video")
        val meta = try {
            Ytdl.info(url)
        } catch (e: FriendlyError) {
            // Sites change; a fresh yt-dlp often fixes it. Try once (pointless without internet).
            if ("internet" in (e.message ?: "")) throw e
            step("Updating the video reader")
            if (Ytdl.updateIfDue(context, force = true)) Ytdl.info(url) else throw e
        }
        _state.update { it.copy(title = meta.title) }
        if (meta.isLive) throw FriendlyError("This stream is still live. Come back when it has ended, or pick a part with \"Only part of the video\".")
        if (meta.duration <= 0) throw FriendlyError("Couldn't tell how long this video is. Try another link.")
        progress(0.07f)

        val work = File(context.filesDir, "work").apply { mkdirs() }
        val length = req.lengthSec.toDouble()
        val (rs, re) = range(req, meta.duration)

        val moments: List<Moment> = if (meta.heatmap.isNotEmpty()) {
            step("Finding the most replayed moments")
            Moments.fromHeatmap(meta.heatmap, meta.duration, req.count, length, rs, re).also { progress(0.12f) }
        } else {
            step("Listening for the best moments")
            try {
                val audio = Ytdl.audio(url, work, rs, re, stage(0.07f, 0.17f))
                val rms = MediaTools.loudness(context, Uri.fromFile(audio), stage(0.17f, 0.22f))
                audio.delete()
                val offset = rs ?: 0.0
                Moments.fromLoudness(rms, req.count, length).map { Moment(it.start + offset, it.end + offset, it.score) }
                    .ifEmpty { Moments.spread(meta.duration, req.count, length, rs, re) }
            } catch (e: FriendlyError) {
                note("Couldn't listen to this video, so the clips are spread across it.")
                Moments.spread(meta.duration, req.count, length, rs, re)
            }
        }
        coroutineContext.ensureActive()
        _state.update { it.copy(total = moments.size) }

        var words: List<Word> = emptyList()
        if (req.captions) {
            step("Getting captions")
            val track = Captions.pickTrack(meta.language, meta.manualSubs, meta.autoSubs)
            val file = track?.let { Ytdl.captions(url, it, File(work, "subs")) }
            words = file?.let {
                runCatching { if (it.name.endsWith(".json3")) Captions.parseJson3(it.readText()) else Captions.parseVtt(it.readText()) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            if (words.isEmpty()) note("This video has no captions on YouTube, so the clips have none.")
        }
        progress(0.25f)

        val out = clipsDir(context)
        val per = 0.75f / moments.size
        for ((i, m) in moments.withIndex()) {
            coroutineContext.ensureActive()
            val base = 0.25f + per * i
            val n = i + 1
            try {
                step("Clip $n of ${moments.size}: downloading")
                val part = try {
                    Ytdl.section(url, m.start, m.end, work, "part$n", stage(base, base + per * 0.45f))
                } catch (e: FriendlyError) {
                    if ("internet" in (e.message ?: "")) throw e
                    step("Clip $n of ${moments.size}: downloading again")
                    Ytdl.section(url, m.start, m.end, work, "part$n", stage(base, base + per * 0.45f))
                }
                val size = MediaTools.probe(part)
                // Cuts land on the keyframe just before the start, so the file can begin a little early.
                val extra = max(0.0, size.durationMs / 1000.0 - m.length)
                val realStart = max(0.0, m.start - extra)

                step("Clip $n of ${moments.size}: finding faces")
                val partUri = Uri.fromFile(part)
                val faces = MediaTools.faces(context, partUri, 0, size.durationMs)
                val plan = LayoutPlanner.plan(size.width, size.height, faces, req.layout)
                Log.i(TAG, "clip $n: ${size.width}x${size.height}, faces in ${faces.count { it.faces.isNotEmpty() }}/${faces.size} frames ${faces.map { f -> f.faces.map { "%.2f@%.2f".format(it.w, it.cx) } }} -> ${plan.mode}")
                progress(base + per * 0.5f)

                val chunks = if (words.isEmpty()) emptyList() else Captions.chunks(
                    Captions.slice(words, (realStart * 1000).toLong(), (realStart * 1000).toLong() + size.durationMs)
                )
                step("Clip $n of ${moments.size}: making the vertical video")
                val file = File(out, "MasterClip_${safeName(meta.title)}_$n.mp4")
                Renderer.render(context, partUri, null, null, plan, chunks, file, stage(base + per * 0.5f, base + per * 0.97f), req.captionStyle, req.captionPosition)
                part.delete()
                Library.saveToGallery(context, file)
                _state.update { it.copy(clips = it.clips + ClipInfo(file.absolutePath, realStart, realStart + size.durationMs / 1000.0, plan.mode)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "clip $n failed", e)
                note("Clip $n couldn't be made: ${(e as? FriendlyError)?.message ?: e.message}")
            }
            progress(base + per)
        }
    }

    // ---------------------------------------------------------------- Video from the phone

    private suspend fun localJob(context: Context, req: ClipRequest) {
        val src = Uri.parse(req.localUri!!)
        step("Getting your video ready")
        val work = File(context.filesDir, "work").apply { mkdirs() }
        val input = File(work, "input.mp4")
        copy(context, src, input, stage(0f, 0.08f))
        val inputUri = Uri.fromFile(input)
        val size = MediaTools.probe(context, inputUri)
        val duration = size.durationMs / 1000.0
        _state.update { it.copy(title = Library.displayName(context, src) ?: "Your video") }
        if (duration < 3) throw FriendlyError("That video is too short to clip.")

        val length = req.lengthSec.toDouble()
        val (rs, re) = range(req, duration)
        step("Listening for the best moments")
        val rms = MediaTools.loudness(context, inputUri, stage(0.08f, 0.2f))
        val moments = Moments.fromLoudness(rms, req.count, length, rs, re)
            .ifEmpty { Moments.spread(duration, req.count, length, rs, re) }
        _state.update { it.copy(total = moments.size) }
        if (req.captions) note("Captions come from YouTube, so videos from your phone are made without them.")

        val out = clipsDir(context)
        val per = 0.8f / moments.size
        for ((i, m) in moments.withIndex()) {
            coroutineContext.ensureActive()
            val base = 0.2f + per * i
            val n = i + 1
            try {
                val fromMs = (m.start * 1000).toLong()
                val toMs = (m.end * 1000).toLong()
                step("Clip $n of ${moments.size}: finding faces")
                val faces = MediaTools.faces(context, inputUri, fromMs, toMs)
                val plan = LayoutPlanner.plan(size.width, size.height, faces, req.layout)
                progress(base + per * 0.1f)
                step("Clip $n of ${moments.size}: making the vertical video")
                val file = File(out, "MasterClip_${safeName(_state.value.title)}_$n.mp4")
                Renderer.render(context, inputUri, fromMs, toMs, plan, emptyList(), file, stage(base + per * 0.1f, base + per * 0.97f))
                Library.saveToGallery(context, file)
                _state.update { it.copy(clips = it.clips + ClipInfo(file.absolutePath, m.start, m.end, plan.mode)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "clip $n failed", e)
                note("Clip $n couldn't be made: ${(e as? FriendlyError)?.message ?: e.message}")
            }
            progress(base + per)
        }
    }

    private fun range(req: ClipRequest, duration: Double): Pair<Double?, Double?> {
        val a = req.rangeStart?.coerceIn(0.0, duration)
        val b = req.rangeEnd?.coerceIn(0.0, duration)
        if (a != null && b != null && b - a < 10) throw FriendlyError("The part you picked is too short. Make it at least a minute.")
        return a to b
    }

    private suspend fun copy(context: Context, src: Uri, dst: File, onProgress: (Float) -> Unit) {
        val total = Library.size(context, src)
        val input = context.contentResolver.openInputStream(src) ?: throw FriendlyError("Couldn't open that video.")
        input.use { ins ->
            dst.outputStream().use { outs ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var tick = 0
                while (true) {
                    val r = ins.read(buf)
                    if (r < 0) break
                    outs.write(buf, 0, r)
                    done += r
                    if (++tick % 64 == 0) {
                        coroutineContext.ensureActive()
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
    }

    fun safeName(title: String): String =
        title.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40).ifBlank { "clip" }
}
