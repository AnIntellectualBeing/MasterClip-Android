package com.anintellectualcompany.masterclip.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

data class VideoSize(val width: Int, val height: Int, val durationMs: Long)

object MediaTools {
    private const val TAG = "MasterClip"

    fun probe(context: Context, uri: Uri): VideoSize {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            if (w <= 0 || h <= 0) throw FriendlyError("That file has no video in it.")
            return VideoSize(w, h, d)
        } catch (e: FriendlyError) {
            throw e
        } catch (e: Exception) {
            throw FriendlyError("This video file can't be opened on this phone.")
        } finally {
            runCatching { r.release() }
        }
    }

    fun probe(file: File): VideoSize = probeFile(file)

    private fun probeFile(file: File): VideoSize {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            return VideoSize(w, h, d)
        } catch (e: Exception) {
            throw FriendlyError("A downloaded part couldn't be read. Try again.")
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Looks for faces in a few frames between [fromMs] and [toMs]. Times in the result are
     * relative to [fromMs], which is how the layout effect counts time.
     */
    suspend fun faces(context: Context, uri: Uri, fromMs: Long, toMs: Long, samples: Int = 8): List<FaceSample> =
        scan(context, uri, fromMs, toMs, samples).faces

    /** Faces per sampled frame, and the edge map of those frames (to find the face-cam panel's border). */
    class Scan(val faces: List<FaceSample>, val edges: EdgeMap?)

    suspend fun scan(context: Context, uri: Uri, fromMs: Long, toMs: Long, samples: Int = 8): Scan {
        val edgeMaps = ArrayList<ByteArray>()
        var ew = 0
        var eh = 0
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.04f)
                .build()
        )
        val r = MediaMetadataRetriever()
        val out = ArrayList<FaceSample>()
        try {
            r.setDataSource(context, uri)
            val span = (toMs - fromMs).coerceAtLeast(1)
            for (i in 0 until samples) {
                coroutineContext.ensureActive()
                val rel = span * (i + 0.5) / samples
                val us = ((fromMs + rel) * 1000).toLong()
                val frame: Bitmap = (if (android.os.Build.VERSION.SDK_INT >= 27)
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
                else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) ?: continue
                try {
                    val found = Tasks.await(detector.process(InputImage.fromBitmap(frame, 0)))
                    val w = frame.width.toFloat()
                    val h = frame.height.toFloat()
                    if (edgeMaps.isEmpty() || (frame.width == ew && frame.height == eh)) {
                        ew = frame.width; eh = frame.height
                        val px = IntArray(ew * eh)
                        frame.getPixels(px, 0, ew, 0, 0, ew, eh)
                        for (k in px.indices) {
                            val c = px[k]
                            px[k] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
                        }
                        edgeMaps += PanelRefine.edgesOf(px, ew, eh)
                    }
                    out += FaceSample((rel * 1000).toLong(), found.map {
                        val b = it.boundingBox
                        Box(b.left / w, b.top / h, b.width() / w, b.height() / h)
                    })
                } finally {
                    frame.recycle()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "face scan failed", e)
        } finally {
            runCatching { r.release() }
            detector.close()
        }
        return Scan(out, PanelRefine.mean(edgeMaps, ew, eh))
    }

    /** Loudness (RMS) for every second of the sound track (or of [fromSec]..[toSec] of it). */
    suspend fun loudness(context: Context, uri: Uri, onProgress: (Float) -> Unit, fromSec: Double = 0.0, toSec: Double? = null): FloatArray =
        loudnessRanges(context, uri, listOf(fromSec to (toSec ?: Double.MAX_VALUE)), onProgress).firstOrNull() ?: FloatArray(0)

    /**
     * Loudness (RMS per second) of several stretches of the sound, given as seconds from the start of the file,
     * in time order. The file is read once from the top: parts between the stretches are skipped without
     * decoding (decoding is the slow part: roughly 20x real time on a phone), so sampling a few seconds
     * out of every minute is cheap. Seconds count from the start of each stretch.
     */
    suspend fun loudnessRanges(context: Context, uri: Uri, ranges: List<Pair<Double, Double>>, onProgress: (Float) -> Unit = {}): List<FloatArray> {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val result = ArrayList<FloatArray>()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val base = ex.sampleTime.coerceAtLeast(0) // the file may count from 0 or from where the piece was cut
            val info = MediaCodec.BufferInfo()
            var pcm = ShortArray(65536)
            var ticks = 0
            for ((ri, r) in ranges.withIndex()) {
                coroutineContext.ensureActive()
                val fromUs = (r.first * 1_000_000).toLong()
                val toUs = if (r.second >= Double.MAX_VALUE / 2) Long.MAX_VALUE else (r.second * 1_000_000).toLong()
                // Skip, without decoding, up to the start of this stretch.
                while (ex.sampleTime >= 0 && ex.sampleTime - base < fromUs) ex.advance()
                if (ex.sampleTime < 0) break
                val perSecond = ArrayList<Float>()
                var sum = 0.0
                var n = 0L
                var inputDone = false
                var ended = false
                while (!ended) {
                    if (++ticks % 100 == 0) coroutineContext.ensureActive()
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
                            if (ranges.size == 1 && ticks % 10 == 0 && toUs != Long.MAX_VALUE) onProgress(((t - base - fromUs).toFloat() / (toUs - fromUs)).coerceIn(0f, 1f))
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
                            val perSec = rate.toLong() * channels
                            val count = shorts.remaining()
                            if (pcm.size < count) pcm = ShortArray(count)
                            shorts.get(pcm, 0, count)
                            for (k in 0 until count) {
                                val v = pcm[k] / 32768.0
                                sum += v * v
                                if (++n >= perSec) {
                                    perSecond += sqrt(sum / n).toFloat()
                                    sum = 0.0; n = 0
                                }
                            }
                            c.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { ended = true; break }
                        } else break
                    }
                }
                if (n > 0) perSecond += sqrt(sum / n).toFloat()
                result += perSecond.toFloatArray()
                if (ri < ranges.size - 1) {
                    runCatching { c.stop() }
                    c.release()
                    c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                    codec = c
                    c.configure(format, null, null, 0)
                    c.start()
                }
            }
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "loudness failed", e)
            return result
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
