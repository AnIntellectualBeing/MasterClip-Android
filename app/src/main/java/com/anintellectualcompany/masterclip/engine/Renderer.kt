package com.anintellectualcompany.masterclip.engine

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Turns a piece of video into the finished 9:16 clip with Media3 (hardware encoder, GPU effects). */
@UnstableApi
object Renderer {

    suspend fun render(
        context: Context,
        input: Uri,
        clipFromMs: Long?,
        clipToMs: Long?,
        plan: LayoutPlan,
        chunks: List<Chunk>,
        out: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        out.parentFile?.mkdirs()
        out.delete()
        val item = MediaItem.Builder().setUri(input).apply {
            if (clipFromMs != null && clipToMs != null) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clipFromMs)
                        .setEndPositionMs(clipToMs)
                        .build()
                )
            }
        }.build()
        val effects = ArrayList<Effect>()
        effects += LayoutEffect(plan)
        if (chunks.isNotEmpty()) effects += OverlayEffect(ImmutableList.of<androidx.media3.effect.TextureOverlay>(CaptionOverlay(chunks, plan.captionY)))
        val edited = EditedMediaItem.Builder(item)
            .setEffects(Effects(ImmutableList.of(), ImmutableList.copyOf(effects)))
            .build()

        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            lateinit var transformer: Transformer
            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                    handler.postDelayed(this, 400)
                }
            }
            transformer = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(DefaultEncoderFactory.Builder(context.applicationContext).setEnableFallback(true).build())
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        android.util.Log.e("MasterClip", "export failed", exportException)
                        if (cont.isActive) cont.resumeWithException(
                            FriendlyError("This clip couldn't be made on this phone (${exportException.errorCodeName}).")
                        )
                    }
                })
                .build()
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    runCatching { transformer.cancel() }
                    out.delete()
                }
            }
            transformer.start(edited, out.absolutePath)
            handler.post(poll)
        }
    }
}
