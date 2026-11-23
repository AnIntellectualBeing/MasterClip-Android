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

    /**
     * A live recording keeps the stream's own clock (Twitch: hours since the stream began), so players and the
     * renderer think it is hours long. Rewrites it to start at 0, no re-encode.
     */
    fun rebase(file: File, out: File): String = merge(file, 0.0, file, 0.0, out)

    private const val JUMP_US = 1_000_000L

    /** Per-track timeline state for closing up jumps. [step] = the last normal gap between samples. */
    private class Smooth(var step: Long) {
        var lastIn = Long.MIN_VALUE
        var lastOut = 0L
        var shift = 0L
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
            // Same file for both: keep the file's own picture/sound sync and just move everything to start at 0.
            val aShift = if (audio != null && audio.absolutePath == video.absolutePath) -vBase
                else ((audioStartSec - videoStartSec) * 1_000_000).toLong() - aBase
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

            // Live recordings can jump in time (Twitch ad breaks, clock resets). When rebasing one file, a jump of
            // more than a second is closed up so the clip timeline stays continuous. B-frame reordering is smaller.
            val fixJumps = audio != null && audio.absolutePath == video.absolutePath
            val vT = Smooth(33_333)
            val aT = Smooth(21_333)
            fun outTime(t: Smooth, raw: Long): Long {
                if (!fixJumps || t.lastIn == Long.MIN_VALUE) return raw + t.shift
                val d = raw - t.lastIn
                return if (d < -JUMP_US || d > JUMP_US) t.lastOut + t.step else raw + t.shift
            }
            fun commit(t: Smooth, raw: Long, out: Long) {
                if (t.lastIn != Long.MIN_VALUE) { val d = raw - t.lastIn; if (d in 1..JUMP_US) t.step = d }
                t.shift = out - raw; t.lastIn = raw; t.lastOut = out
            }

            // Interleave by time so the mp4 plays and seeks smoothly.
            while (true) {
                val vt = vEx.sampleTime
                if (vt < 0) break // the picture ended; sound past it is dropped
                val vRaw = vt - vBase
                val vPts = outTime(vT, vRaw)
                var aRaw = audioTime()
                while (aOut >= 0 && aRaw != Long.MAX_VALUE && aRaw < 0 && (!fixJumps || aT.lastIn == Long.MIN_VALUE)) { aEx!!.advance(); aRaw = audioTime() } // sound before the first picture
                val at = if (aRaw == Long.MAX_VALUE) Long.MAX_VALUE else outTime(aT, aRaw)
                if (vPts <= at) { write(vEx, vOut, vPts); commit(vT, vRaw, vPts); vCount++ }
                else { write(aEx!!, aOut, at.coerceAtLeast(0)); commit(aT, aRaw, at); aCount++ }
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
