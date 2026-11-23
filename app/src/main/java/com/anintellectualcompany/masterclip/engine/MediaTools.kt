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
    suspend fun faces(context: Context, uri: Uri, fromMs: Long, toMs: Long, samples: Int = 8): List<FaceSample> {
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
        return out
    }

    /** Loudness (RMS) for every second of the sound track. */
    suspend fun loudness(context: Context, uri: Uri, onProgress: (Float) -> Unit): FloatArray {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val perSecond = ArrayList<Float>()
            var sum = 0.0
            var n = 0L
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            var tick = 0
            while (true) {
                if (++tick % 200 == 0) coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            if (durationUs > 0 && tick % 50 == 0) onProgress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 5000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIdx >= 0) {
                    val buf = c.getOutputBuffer(outIdx)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val shorts = buf.asShortBuffer()
                    val perSec = rate.toLong() * channels
                    while (shorts.hasRemaining()) {
                        val v = shorts.get() / 32768.0
                        sum += v * v
                        if (++n >= perSec) {
                            perSecond += sqrt(sum / n).toFloat()
                            sum = 0.0; n = 0
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (n > 0) perSecond += sqrt(sum / n).toFloat()
            return perSecond.toFloatArray()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "loudness failed", e)
            return FloatArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
