package com.anintellectualcompany.masterclip.engine

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import java.io.File

/** Real decoder, whisper JNI and GPU export check. Supply a readable app-owned fixture with -e input <path>. */
@UnstableApi
class CaptionSmokeRunner : Instrumentation() {
    private lateinit var inputPath: String
    private var expectedWords = listOf("captions")
    private var language: String? = null
    private var model: String? = null
    private var prompt = ""

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        inputPath = arguments?.getString("input") ?: error("Pass -e input <video path>")
        expectedWords = arguments?.getString("expected")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("captions")
        language = arguments?.getString("language")
        model = arguments?.getString("model")
        prompt = arguments?.getString("prompt") ?: ""
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                val app = targetContext
                val uri = Uri.fromFile(File(inputPath))
                val extractor = android.media.MediaExtractor()
                extractor.setDataSource(app, uri, null)
                for (i in 0 until extractor.trackCount) {
                    if (extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        extractor.selectTrack(i)
                        result.putLong("firstAudioUs", extractor.sampleTime)
                        result.putInt("firstAudioTrack", extractor.sampleTrackIndex)
                        break
                    }
                }
                extractor.release()
                val pcm = Pcm.decode(app, uri, 0, 12000)
                result.putInt("samples", pcm.size)
                result.putFloat("peak", pcm.maxOfOrNull { kotlin.math.abs(it) } ?: 0f)
                check(pcm.size >= 16000) { "Decoder returned only ${pcm.size} samples" }
                Speech.ensureModel(app) { }
                Speech.newJob()
                val speech = if (language == null) Speech.transcribe(app, uri, 0, 12000) else {
                    val handle = WhisperLib.init(model ?: Speech.modelFile(app).absolutePath, app.applicationInfo.nativeLibraryDir)
                    check(handle != 0L)
                    try { Speech.parse(WhisperLib.run(handle, pcm, language!!, prompt, 6)?.toString(Charsets.UTF_8)) }
                    finally { WhisperLib.free(handle) }
                }
                check(speech.words.size >= 5) { "Expected spoken words, got ${speech.words}" }
                val transcript = speech.words.joinToString(" ") { it.text }
                result.putString("language", speech.language)
                result.putString("words", transcript)
                check(expectedWords.all { transcript.contains(it, ignoreCase = true) }) {
                    "Speech recognition returned unexpected text: ${speech.words}"
                }
                val chunks = Captions.chunks(speech.words)
                val output = File(app.getExternalFilesDir(null), "caption-smoke-output.mp4")
                Renderer.render(app, uri, 0, 12000, LayoutPlan(LayoutMode.FIT_BLUR), chunks, output, { })
                check(output.length() > 10000) { "Export was empty" }
                result.putString("output", output.absolutePath)
                result.putString("words", speech.words.joinToString(" ") { it.text })
                result.putString("status", "PASS: model, PCM, JNI, captions and video export")
            }
            finish(-1, result)
        } catch (e: Throwable) {
            result.putString("error", e.stackTraceToString())
            finish(0, result)
        }
    }
}
