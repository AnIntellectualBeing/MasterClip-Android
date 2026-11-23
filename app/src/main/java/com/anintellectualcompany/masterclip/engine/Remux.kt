package com.anintellectualcompany.masterclip.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins a downloaded video piece and sound piece into one ordinary mp4 whose time starts at 0.
 * Nothing is re-encoded, so it takes a second or two.
 *
 * Android's reader may or may not keep the pieces' original clock, so we don't depend on it: the
 * caller tells us which second of the original video each piece starts at, and we line them up from that.
 */
object Remux {
    /** A transport stream hands over SPS and PPS in one csd-0 buffer; the mp4 writer wants them as csd-0 and csd-1. */
    private fun splitParameterSets(f: MediaFormat) {
        if (f.containsKey("csd-1") || !f.containsKey("csd-0")) return
        val b = f.getByteBuffer("csd-0") ?: return
        val bytes = ByteArray(b.remaining()).also { b.duplicate().get(it) }
        val starts = ArrayList<Int>()
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) starts += i
        }
        if (starts.size < 2) return
        f.setByteBuffer("csd-0", ByteBuffer.wrap(bytes.copyOfRange(starts[0], starts[1])))
        f.setByteBuffer("csd-1", ByteBuffer.wrap(bytes.copyOfRange(starts[1], bytes.size)))
    }

    fun merge(video: File, videoStartSec: Double, audio: File?, audioStartSec: Double, out: File): String {
        var vCount = 0
        var aCount = 0
        out.delete()
        val vEx = MediaExtractor()
        val aEx = if (audio != null) MediaExtractor() else null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vEx.setDataSource(video.absolutePath)
            val vTrack = (0 until vEx.trackCount).firstOrNull { vEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw FriendlyError("A downloaded part has no picture. Try again.")
            vEx.selectTrack(vTrack)
            vEx.sampleTime // reading the first piece lets a transport stream fill in its codec details
            val vFormat = vEx.getTrackFormat(vTrack)
            splitParameterSets(vFormat)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = muxer.addTrack(vFormat)

            var aOut = -1
            if (aEx != null && audio != null) {
                aEx.setDataSource(audio.absolutePath)
                val aTrack = (0 until aEx.trackCount).firstOrNull { aEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                if (aTrack != null) {
                    aEx.selectTrack(aTrack)
                    aOut = muxer.addTrack(aEx.getTrackFormat(aTrack))
                }
            }
            muxer.start()
            started = true

            val vBase = vEx.sampleTime.coerceAtLeast(0)
            val aBase = if (aOut >= 0) aEx!!.sampleTime.coerceAtLeast(0) else 0L
            // Where the sound's time 0 sits on the picture's clock (microseconds).
            val aShift = ((audioStartSec - videoStartSec) * 1_000_000).toLong() - aBase
            // Long.MAX_VALUE = no sound left, so the picture always goes first.
            fun audioTime(): Long = if (aOut >= 0) aEx!!.sampleTime.let { if (it < 0) Long.MAX_VALUE else it + aShift } else Long.MAX_VALUE
            val buf = ByteBuffer.allocateDirect(6 shl 20)
            val info = MediaCodec.BufferInfo()

            fun write(ex: MediaExtractor, track: Int, pts: Long) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size >= 0) {
                    info.set(0, size, pts, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, buf, info)
                }
                ex.advance()
            }

            // Interleave by time so the mp4 plays and seeks smoothly.
            while (true) {
                val vt = vEx.sampleTime
                if (vt < 0) break // the picture ended; sound past it is dropped
                val vPts = vt - vBase
                var at = audioTime()
                while (aOut >= 0 && at < 0) { aEx!!.advance(); at = audioTime() } // sound before the first picture
                if (vPts <= at) { write(vEx, vOut, vPts); vCount++ } else { write(aEx!!, aOut, at); aCount++ }
            }
            return "$vCount picture and $aCount sound samples"
        } catch (e: FriendlyError) {
            throw e
        } catch (e: Exception) {
            throw FriendlyError("A downloaded part couldn't be put together (${e.message}).")
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { vEx.release() }
            runCatching { aEx?.release() }
        }
    }
}
