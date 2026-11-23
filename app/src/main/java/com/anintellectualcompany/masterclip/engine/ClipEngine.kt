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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
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

/** Everything one clip needs to be drawn, ready before the (single) renderer gets to it. */
private class Prepared(
    val uri: Uri,
    val fromMs: Long?,
    val toMs: Long?,
    val plan: LayoutPlan,
    val chunks: List<Chunk>,
    val start: Double,
    val end: Double,
    val temp: File?,
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
    private var jobStart = 0L
    private var smoothEta = -1.0
    private var etaFrom = 0f

    /** Longest stretch of a video we listen to when there is no heatmap. Past this we listen to spread-out slices. */
    private const val SCAN_CAP_SEC = 90 * 60.0
    private const val SCAN_CAP_SLOW_SEC = 20 * 60.0
    private const val CHAT_DEADLINE_MS = 60_000L
    /** About this many seconds of sound are decoded to find moments (decoding runs about 20x real time on a phone). */
    private const val BURST_BUDGET_SEC = 720.0

    fun start(context: Context, requestIn: ClipRequest) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val request = requestIn
        _state.value = EngineState(phase = Phase.WORKING, step = "Getting ready", total = request.count)
        startedAt = SystemClock.elapsedRealtime()
        jobStart = startedAt
        smoothEta = -1.0
        etaFrom = 0f
        Ytdl.beginJob()
        Log.i(TAG, "job start: ${request.url ?: "phone video"} count=${request.count} len=${request.lengthSec}s live=${request.liveMode}/${request.liveMinutes}min")
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
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "out of memory", e)
                fail("The phone ran out of memory. Close other apps, or pick fewer or shorter clips, then try again.")
            } catch (e: Throwable) {
                Log.e(TAG, "job crashed", e)
                fail("Something went wrong: ${e.message ?: e.javaClass.simpleName}. Please try again.")
            } finally {
                lap("job end")
                File(app.filesDir, "work").deleteRecursively()
            }
        }
    }

    /** Stops everything at once: yt-dlp, ffmpeg, the recording, the renderer. */
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

    /** One timing line per stage: how long since Make clips was tapped. */
    private fun lap(what: String) {
        Log.i(TAG, "T+%.1fs %s".format((SystemClock.elapsedRealtime() - jobStart) / 1000.0, what))
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun progress(p: Float) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        var eta: Int? = null
        val done = p - etaFrom
        if (done > 0.05f && elapsed > 4) {
            val raw = elapsed * (1 - p) / done
            smoothEta = if (smoothEta < 0) raw else smoothEta * 0.8 + raw * 0.2
            eta = smoothEta.roundToInt()
        }
        _state.update { it.copy(progress = max(it.progress, p.coerceIn(0f, 1f)), etaSec = eta ?: it.etaSec) }
    }

    /** Progress inside one stage, mapped onto [from]..[to] of the whole job. */
    private fun stage(from: Float, to: Float): (Float) -> Unit = { f -> progress(from + (to - from) * f.coerceIn(0f, 1f)) }

    private fun clipsDir(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        runCatching { Library.prune(context) }
        return File(context.filesDir, "clips/$stamp").apply { mkdirs() }
    }

    // ---------------------------------------------------------------- YouTube / Twitch link

    private suspend fun urlJob(context: Context, req: ClipRequest) {
        val url = req.url!!.trim()
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        if (cm?.activeNetwork == null) throw FriendlyError("You're offline. Turn on Wi-Fi or mobile data, then tap Try again.")
        step("Starting the clip engine (first time takes a little longer)")
        io { Ytdl.ensure(context) }
        lap("engine ready")
        progress(0.03f)

        step("Reading the video")
        val work = File(context.filesDir, "work").apply { deleteRecursively(); mkdirs() }
        val meta = try {
            io { Ytdl.info(url, work) }
        } catch (e: FriendlyError) {
            // Sites change; a fresh yt-dlp often fixes it. Try once (pointless without internet).
            if ("internet" in (e.message ?: "")) throw e
            step("Updating the video reader")
            if (io { Ytdl.updateIfDue(context, force = true) }) io { Ytdl.info(url, work) } else throw e
        }
        lap("info read (${meta.extractor}, ${meta.duration.roundToInt()}s, live=${meta.isLive}, heatmap=${meta.heatmap.size})")
        _state.update { it.copy(title = meta.title) }
        if (meta.isLive) {
            liveJob(context, req, meta, url, work)
            return
        }
        if (meta.duration <= 0) throw FriendlyError("Couldn't tell how long this video is. Try another link.")
        progress(0.06f)

        val (rs, re) = range(req, meta.duration)

        // Captions download while we look for moments.
        val wordsJob: Deferred<List<Word>> = scope.async(Dispatchers.IO) {
            if (!req.captions) return@async emptyList()
            val t = SystemClock.elapsedRealtime()
            val track = Captions.pickTrack(meta.language, meta.manualSubs, meta.autoSubs)
            val file = track?.let { Ytdl.captions(url, it, File(work, "subs")) }
            val words = file?.let {
                runCatching { if (it.name.endsWith(".json3")) Captions.parseJson3(it.readText()) else Captions.parseVtt(it.readText()) }
                    .getOrDefault(emptyList())
            } ?: emptyList()
            lap("captions done in ${SystemClock.elapsedRealtime() - t} ms (${words.size} words)")
            words
        }

        val moments: List<Moment>
        try {
            moments = findMoments(context, url, meta, req, work, rs, re)
        } catch (e: Throwable) {
            wordsJob.cancel()
            throw e
        }
        lap("moments found: ${moments.joinToString { "%.0f".format(it.start) }}")
        coroutineContext.ensureActive()
        _state.update { it.copy(total = moments.size) }
        progress(0.2f)

        val out = clipsDir(context)
        runClips(context, req, out, meta.title, moments, 0.2f) { i, m, report ->
            val n = i + 1
            val part = fetchPart(url, meta, m, work, n, report)
            val size = MediaTools.probe(part.file)
            // The file starts at part.realStart; the clip is the piece of it from m.start.
            val from = max(0.0, m.start - part.realStart)
            val to = min(from + m.length, size.durationMs / 1000.0)
            val fromMs = (from * 1000).toLong()
            val toMs = (to * 1000).toLong()
            val partUri = Uri.fromFile(part.file)
            val scan = MediaTools.scan(context, partUri, fromMs, toMs)
            val plan = LayoutPlanner.plan(size.width, size.height, scan.faces, req.layout, scan.edges)
            lap("clip $n ready to render: ${size.width}x${size.height}, faces ${scan.faces.count { it.faces.isNotEmpty() }}/${scan.faces.size} -> ${plan.mode}, panel=${scan.edges != null}")
            val words = wordsJob.await()
            val chunks = if (words.isEmpty()) emptyList() else Captions.chunks(
                Captions.slice(words, (m.start * 1000).toLong(), (m.start * 1000).toLong() + (toMs - fromMs))
            )
            if (n == 1 && req.captions && words.isEmpty()) note("This video has no captions on YouTube, so the clips have none.")
            Prepared(partUri, fromMs, toMs, plan, chunks, m.start, m.start + (to - from), part.file)
        }
    }

    /** Where the best moments are. Heatmap, or Twitch chat, or listening to the sound. */
    private suspend fun findMoments(context: Context, url: String, meta: VideoMeta, req: ClipRequest, work: File, rs: Double?, re: Double?): List<Moment> {
        val length = req.lengthSec.toDouble()
        if (meta.heatmap.isNotEmpty()) {
            step("Finding the most replayed moments")
            return Moments.fromHeatmap(meta.heatmap, meta.duration, req.count, length, rs, re)
        }
        val a = rs ?: 0.0
        val b = re ?: meta.duration
        val total = meta.duration.toInt().coerceAtLeast(1)

        if (meta.extractor.startsWith("Twitch")) {
            val id = Twitch.vodId(url) ?: Twitch.vodId(meta.url)
            if (id != null) {
                step("Reading the chat for the best moments")
                val t = SystemClock.elapsedRealtime()
                val score = Twitch.chatScore(id, meta.duration, a, b, req.count, CHAT_DEADLINE_MS)
                lap("twitch chat scan ${SystemClock.elapsedRealtime() - t} ms ok=${score != null}")
                if (score != null) {
                    val picked = Moments.pick(score, req.count, length, rs, re)
                    if (picked.isNotEmpty()) return picked
                }
            }
        }

        step("Listening for the best moments")
        val t = SystemClock.elapsedRealtime()
        val dashAudio = Ytdl.formats?.takeIf { meta.extractor == "Youtube" }?.let { Dash.pickAudio(it, small = true) }
        val cap = if (dashAudio != null) SCAN_CAP_SEC else SCAN_CAP_SLOW_SEC
        val slices = Moments.scanSlices(a, b, cap)
        if (slices.size > 1) note("This is a long video, so MasterClip listened to ${slices.size} spread-out parts of it to find the best moments.")
        val wholeFile = slices.size == 1 && slices[0].first <= a + 0.5 && slices[0].second >= b - 0.5 && rs == null && re == null
        val sounds = ArrayList<Sound>()
        try {
            coroutineScope {
                val net = Semaphore(2)
                var done = 0
                slices.mapIndexed { i, sl ->
                    async {
                        net.withPermit {
                            coroutineContext.ensureActive()
                            val tf = SystemClock.elapsedRealtime()
                            val got: Pair<File, Double> = if (dashAudio != null) {
                                val f = File(work, "scan$i.m4a")
                                f to Dash.fetch(dashAudio.url, sl.first, sl.second, f).first
                            } else {
                                io { Ytdl.audio(url, work, "scan$i", if (wholeFile) null else sl.first, if (wholeFile) null else sl.second) { } } to sl.first
                            }
                            Log.i(TAG, "scan slice $i fetched in ${SystemClock.elapsedRealtime() - tf} ms (${got.first.length() / 1024} KB)")
                            synchronized(sounds) { sounds += Sound(Uri.fromFile(got.first), got.second, sl.second - sl.first, got.first); done++ }
                            progress(0.06f + 0.06f * done / slices.size)
                        }
                    }
                }.forEach { it.await() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listening failed", e)
        }
        lap("audio fetched ${SystemClock.elapsedRealtime() - t} ms, ${sounds.size}/${slices.size} slices")
        val picked = try {
            if (sounds.isEmpty()) emptyList() else listen(context, sounds, total, length, req.count, rs, re, stage(0.12f, 0.2f))
        } finally {
            sounds.forEach { it.file?.delete() }
        }
        lap("audio scan total ${SystemClock.elapsedRealtime() - t} ms -> ${picked.size} moments")
        if (picked.isEmpty()) {
            note("Couldn't listen to this video, so the clips are spread across it.")
            return Moments.spread(meta.duration, req.count, length, rs, re)
        }
        return picked
    }

    /** A piece of sound to listen to: where it is, which second of the video it starts at, and how long it is. */
    private class Sound(val uri: Uri, val start: Double, val dur: Double, val file: File?)

    /**
     * Finds the busiest moments in some sound. Decoding sound on a phone is slow (about 20x real time),
     * so it is done in two steps: first a few seconds out of every half minute or so (about 12 minutes of
     * sound in all, however long the video is), then the sound around the best places in full.
     */
    private suspend fun listen(
        context: Context,
        sounds: List<Sound>,
        total: Int,
        length: Double,
        count: Int,
        rs: Double?,
        re: Double?,
        onProgress: (Float) -> Unit,
    ): List<Moment> {
        val t0 = SystemClock.elapsedRealtime()
        val cpu = Semaphore(3)
        val all = sounds.sumOf { it.dur }.coerceAtLeast(1.0)

        // Step 1: short bursts spread over everything.
        val coarse = ArrayList<Pair<Sound, FloatArray>>() // per-second loudness guess, by interpolation between bursts
        var done = 0
        coroutineScope {
            sounds.map { s ->
                async {
                    cpu.withPermit {
                        val bursts = Moments.bursts(0.0, s.dur, 8.0, BURST_BUDGET_SEC * s.dur / all)
                        val rms = MediaTools.loudnessRanges(context, s.uri, bursts)
                        val points = rms.mapIndexedNotNull { i, v -> if (v.isEmpty()) null else Moments.RatePoint((bursts[i].first + bursts[i].second) / 2, v.average()) }
                        val guess = Moments.rateScore(points, s.dur.toInt().coerceAtLeast(1), 0)
                        synchronized(coarse) { coarse += s to guess.map { it.toFloat() }.toFloatArray(); done++ }
                        onProgress(0.5f * done / sounds.size)
                    }
                }
            }.forEach { it.await() }
        }
        coroutineContext.ensureActive()
        lap("coarse listen ${SystemClock.elapsedRealtime() - t0} ms")
        val (cm, csd) = Moments.stats(coarse.flatMap { it.second.asList() }.toFloatArray())
        val coarseScore = Moments.placeSlices(total, coarse.map { (s, v) -> s.start to Moments.loudnessScore(v, cm, csd) })

        // Step 2: the best places, in full.
        val windows = Moments.pick(coarseScore, max(count * 3, 6), length, rs, re).map { (it.start - 10).coerceAtLeast(0.0) to it.end }
        val exact = ArrayList<Pair<Double, FloatArray>>()
        done = 0
        coroutineScope {
            val bySound = windows.groupBy { w -> sounds.firstOrNull { w.first >= it.start - 1 && w.first < it.start + it.dur } }
            bySound.entries.filter { it.key != null }.map { (s, ws) ->
                async {
                    cpu.withPermit {
                        val sorted = ws.sortedBy { it.first }.map { (a, b) -> max(0.0, a - s!!.start) to (b - s.start) }
                        val rms = MediaTools.loudnessRanges(context, s!!.uri, sorted)
                        synchronized(exact) { rms.forEachIndexed { i, v -> exact += (s.start + sorted[i].first) to v }; done += ws.size }
                        onProgress(0.5f + 0.5f * done / windows.size)
                    }
                }
            }.forEach { it.await() }
        }
        lap("exact loudness on ${windows.size} windows, ${SystemClock.elapsedRealtime() - t0} ms in all")
        if (exact.isEmpty()) return emptyList()
        // One scale for all windows, so they can be compared with each other.
        val (mean, sd) = Moments.stats(exact.flatMap { it.second.asList() }.toFloatArray())
        val score = Moments.placeSlices(total, exact.map { (s, v) -> s to Moments.loudnessScore(v, mean, sd) })
        return Moments.pick(score, count, length, rs, re)
    }

    private class Part(val file: File, val realStart: Double)

    /** The video piece around a moment: fast direct download for YouTube, yt-dlp for everything else (and as the safety net). */
    private suspend fun fetchPart(url: String, meta: VideoMeta, m: Moment, work: File, n: Int, report: (Float) -> Unit): Part {
        val t = SystemClock.elapsedRealtime()
        val formats = Ytdl.formats
        val v = formats?.takeIf { meta.extractor == "Youtube" }?.let { Dash.pickVideo(it) }
        val a = formats?.takeIf { meta.extractor == "Youtube" }?.let { Dash.pickAudio(it, small = false) }
        if (v != null && a != null) {
            val vf = File(work, "v$n.mp4")
            val af = File(work, "a$n.m4a")
            try {
                val (vStart, aStart) = coroutineScope {
                    val x = async { Dash.fetch(v.url, m.start, m.end, vf) { report(0.9f * it) } }
                    val y = async { Dash.fetch(a.url, m.start - 8, m.end + 8, af) }
                    x.await().first to y.await().first
                }
                val td = SystemClock.elapsedRealtime()
                val out = File(work, "part$n.mp4")
                io { Remux.merge(vf, vStart, af, aStart, out) }
                val realStart = vStart
                lap("clip $n downloaded: dash ${td - t} ms + remux ${SystemClock.elapsedRealtime() - td} ms (${out.length() / 1024} KB, ${v.height}p)")
                report(1f)
                return Part(out, realStart)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "fast download failed for clip $n (${e.message}); using yt-dlp", e)
            } finally {
                vf.delete(); af.delete()
            }
        }
        val part = try {
            io { Ytdl.section(url, m.start, m.end, work, "part$n", report) }
        } catch (e: FriendlyError) {
            if ("internet" in (e.message ?: "")) throw e
            io { Ytdl.section(url, m.start, m.end, work, "part$n", report) }
        }
        val size = MediaTools.probe(part)
        // Cuts land on the keyframe just before the start, so the file can begin a little early.
        val extra = max(0.0, size.durationMs / 1000.0 - m.length)
        lap("clip $n downloaded: yt-dlp ${SystemClock.elapsedRealtime() - t} ms")
        return Part(part, max(0.0, m.start - extra))
    }

    // ---------------------------------------------------------------- the clip pipeline

    /**
     * Gets clip N+1 (and N+2) ready while clip N is being drawn. Never more than two downloads at once,
     * and no more than three clips waiting, so the phone's storage and memory stay calm.
     */
    private suspend fun runClips(
        context: Context,
        req: ClipRequest,
        out: File,
        title: String,
        moments: List<Moment>,
        base: Float,
        prepare: suspend (Int, Moment, (Float) -> Unit) -> Prepared,
    ) = coroutineScope {
        val n = moments.size
        if (n == 0) return@coroutineScope
        val prep = FloatArray(n)
        val rend = FloatArray(n)
        fun publish() = progress(base + (1f - base) * (prep.sum() * 0.4f + rend.sum() * 0.6f) / n)
        val downloads = Semaphore(2)
        val jobs = arrayOfNulls<Deferred<Prepared?>>(n)
        var started = 0
        fun startUpTo(k: Int) {
            while (started < min(n, k)) {
                val i = started++
                jobs[i] = async {
                    try {
                        downloads.withPermit {
                            val t = SystemClock.elapsedRealtime()
                            prepare(i, moments[i]) { f -> prep[i] = f * 0.9f; publish() }.also {
                                prep[i] = 1f; publish()
                                lap("clip ${i + 1} prepared in ${SystemClock.elapsedRealtime() - t} ms")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "clip ${i + 1} failed to prepare", e)
                        note("Clip ${i + 1} couldn't be made: ${(e as? FriendlyError)?.message ?: e.message}")
                        prep[i] = 1f; rend[i] = 1f; publish()
                        null
                    }
                }
            }
        }
        startUpTo(3)
        for (i in 0 until n) {
            coroutineContext.ensureActive()
            val pending = jobs[i]!!
            if (!pending.isCompleted) step("Clip ${i + 1} of $n: downloading")
            val p = pending.await()
            startUpTo(i + 4)
            if (p == null) continue
            try {
                step("Clip ${i + 1} of $n: making the vertical video")
                val file = File(out, "MasterClip_${safeName(title)}_${i + 1}.mp4")
                val t = SystemClock.elapsedRealtime()
                Renderer.render(
                    context, p.uri, p.fromMs, p.toMs, p.plan, p.chunks, file,
                    { f -> rend[i] = f; publish() },
                    req.captionStyle, req.captionPosition,
                )
                lap("clip ${i + 1} rendered in ${SystemClock.elapsedRealtime() - t} ms (${file.length() / 1024} KB)")
                Library.saveToGallery(context, file)
                _state.update { it.copy(clips = it.clips + ClipInfo(file.absolutePath, p.start, p.end, p.plan.mode)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "clip ${i + 1} failed", e)
                note("Clip ${i + 1} couldn't be made: ${(e as? FriendlyError)?.message ?: e.message}")
            } finally {
                p.temp?.delete()
                rend[i] = 1f; prep[i] = 1f
                publish()
            }
        }
    }

    // ---------------------------------------------------------------- live streams

    private suspend fun liveJob(context: Context, req: ClipRequest, meta: VideoMeta, url: String, work: File) {
        val minutes = req.liveMinutes.coerceIn(1, 120)
        var section: Pair<Double, Double>? = null
        if (req.liveMode == LiveMode.LAST_PART) {
            step("Looking at what this stream kept")
            val window = meta.liveManifest?.let { io { Live.windowSeconds(it) } }
            val last = window?.let { LiveMath.lastPart(it, minutes) }
            lap("live window ${window?.roundToInt()}s -> ${last?.let { "%.0f-%.0f".format(it.first, it.second) }}")
            if (last == null) {
                note("This stream can't be rewound, so MasterClip recorded the next $minutes minutes instead.")
            } else {
                section = last
                val got = (last.second - last.first) / 60
                if (got < minutes - 0.2) note("The stream only kept the last ${"%.0f".format(got)} minutes, so that's what was clipped.")
            }
        }
        val seconds = section?.let { (it.second - it.first).roundToInt() } ?: (minutes * 60)
        val noun = if (section == null) "Recording the live stream" else "Downloading the last ${LiveMath.clock(seconds)}"
        val recStart = SystemClock.elapsedRealtime()
        val expected = LiveMath.expectedBytes(meta.liveKbps, seconds.toDouble())
        val processing = 20 * req.count + 30 // seconds of work after the recording, a rough guess for the countdown

        val ticker = scope.launch {
            while (true) {
                val el = (SystemClock.elapsedRealtime() - recStart) / 1000.0
                val frac: Double
                val left: Int
                if (section == null) {
                    frac = el / seconds
                    left = max(0.0, seconds - el).roundToInt()
                    step("$noun: ${LiveMath.clock(el.toInt())} of ${LiveMath.clock(seconds)} (${LiveMath.clock(left)} left)")
                } else {
                    val bytes = work.listFiles { f -> f.name.startsWith("live") }?.sumOf { it.length() } ?: 0L
                    frac = (bytes.toDouble() / expected).coerceIn(0.0, 0.97)
                    left = if (frac > 0.03) (el * (1 - frac) / frac).roundToInt() else seconds / 3
                    step("$noun: ${(frac * 100).roundToInt()}%")
                }
                _state.update { it.copy(progress = max(it.progress, (0.06 + 0.54 * frac.coerceIn(0.0, 1.0)).toFloat()), etaSec = left + processing) }
                delay(1000)
            }
        }
        val file = try {
            val fastOk = section != null && meta.liveManifest != null && meta.liveAudioManifest != null
            var made: File? = null
            if (fastOk) {
                // Fetch the pieces several at once (yt-dlp's own way runs only as fast as the stream plays).
                val v = File(work, "live_v.ts")
                val a = File(work, "live_a.aac")
                try {
                    val got = Hls.fetchLast(meta.liveManifest!!, meta.liveAudioManifest!!, seconds.toDouble(), v, a)
                    val out = File(work, "live.mp4")
                    val what = io { Remux.merge(v, 0.0, a, 0.0, out) }
                    Log.i(TAG, "live join: $what")
                    lap("hls last part ${got.roundToInt()}s fetched + joined in ${SystemClock.elapsedRealtime() - recStart} ms")
                    made = out
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "fast last-part download failed (${e.message}); using yt-dlp", e)
                } finally {
                    v.delete(); a.delete()
                }
            }
            made ?: io {
                val raw = Ytdl.live(url, work, "live", seconds, section)
                val fixed = File(work, "live_fixed.mp4")
                try {
                    Log.i(TAG, "live rebase: ${Remux.rebase(raw, fixed)}")
                    raw.delete()
                    fixed
                } catch (e: Exception) {
                    Log.w(TAG, "live rebase failed; using the recording as it is", e)
                    fixed.delete()
                    raw
                }
            }
        } finally {
            ticker.cancel()
        }
        lap("live recording done: ${file.length() / 1024} KB in ${(SystemClock.elapsedRealtime() - recStart) / 1000}s")
        startedAt = SystemClock.elapsedRealtime()
        smoothEta = -1.0
        etaFrom = 0.6f
        progress(0.6f)
        if (req.captions) note("Live streams have no captions to read yet, so these clips have none.")
        val uri = Uri.fromFile(file)
        val size = MediaTools.probe(file)
        Log.i(TAG, "live file: ${size.width}x${size.height} ${size.durationMs} ms")
        clipsFromFile(context, req.copy(captions = false), uri, size, meta.title, 0.6f, null, null)
    }

    // ---------------------------------------------------------------- Video from the phone

    private suspend fun localJob(context: Context, req: ClipRequest) {
        val src = Uri.parse(req.localUri!!)
        step("Getting your video ready")
        val work = File(context.filesDir, "work").apply { deleteRecursively(); mkdirs() }
        _state.update { it.copy(title = Library.displayName(context, src) ?: "Your video") }
        // Use the video where it is: no copy, so a 3 GB video starts at once. Copy only if Android can't read it in place.
        var uri = src
        val size = try {
            MediaTools.probe(context, src)
        } catch (e: FriendlyError) {
            val input = File(work, "input.mp4")
            lap("probe in place failed; copying")
            copy(context, src, input, stage(0f, 0.08f))
            uri = Uri.fromFile(input)
            MediaTools.probe(context, uri)
        }
        lap("local video ${size.width}x${size.height} ${size.durationMs / 1000}s")
        progress(0.06f)
        clipsFromFile(context, req, uri, size, _state.value.title, 0.06f, req.rangeStart, req.rangeEnd)
        if (req.captions) note("Captions come from YouTube, so videos from your phone are made without them.")
    }

    /** The common path for a video file on the phone (saved video or finished live recording). */
    private suspend fun clipsFromFile(
        context: Context,
        req: ClipRequest,
        uri: Uri,
        size: VideoSize,
        title: String,
        base: Float,
        rangeStart: Double?,
        rangeEnd: Double?,
    ) {
        val duration = size.durationMs / 1000.0
        if (duration < 3) throw FriendlyError("That video is too short to clip.")
        val length = req.lengthSec.toDouble()
        val (rs, re) = range(rangeStart, rangeEnd, duration)

        step("Listening for the best moments")
        val t = SystemClock.elapsedRealtime()
        // The whole file at once: the cheap pass reads only the audio index, so even a 3 hour video takes seconds.
        val found = try {
            listen(context, listOf(Sound(uri, 0.0, duration, null)), max(1, duration.toInt()), length, req.count, rs, re, stage(base, 0.2f))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "listening failed", e)
            emptyList()
        }
        lap("listening done ${SystemClock.elapsedRealtime() - t} ms -> ${found.size} moments")
        if (found.isEmpty()) note("Couldn't listen to this video, so the clips are spread across it.")
        val moments = found.ifEmpty { Moments.spread(duration, req.count, length, rs, re) }
        _state.update { it.copy(total = moments.size) }
        progress(0.2f)

        val out = clipsDir(context)
        runClips(context, req, out, title, moments, 0.2f) { i, m, report ->
            val fromMs = (m.start * 1000).toLong()
            val toMs = (m.end * 1000).toLong()
            val scan = MediaTools.scan(context, uri, fromMs, toMs)
            report(1f)
            val plan = LayoutPlanner.plan(size.width, size.height, scan.faces, req.layout, scan.edges)
            lap("clip ${i + 1}: faces ${scan.faces.count { it.faces.isNotEmpty() }}/${scan.faces.size} -> ${plan.mode}")
            Prepared(uri, fromMs, toMs, plan, emptyList(), m.start, m.end, null)
        }
    }

    private fun range(req: ClipRequest, duration: Double): Pair<Double?, Double?> = range(req.rangeStart, req.rangeEnd, duration)

    private fun range(rangeStart: Double?, rangeEnd: Double?, duration: Double): Pair<Double?, Double?> {
        val a = rangeStart?.coerceIn(0.0, duration)
        val b = rangeEnd?.coerceIn(0.0, duration)
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
