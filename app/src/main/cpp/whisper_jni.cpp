#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"
#include "ggml-backend.h"

#define TAG "MasterClipWhisper"

static void logCb(enum ggml_log_level level, const char *text, void *) {
    __android_log_print(ANDROID_LOG_INFO, "MasterClipW", "%s", text);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_anintellectualcompany_masterclip_engine_WhisperLib_init(JNIEnv *env, jobject, jstring path, jstring libDir) {
#ifdef GGML_BACKEND_DL
    const char *d = env->GetStringUTFChars(libDir, nullptr);
    ggml_backend_load_all_from_path(d);
    env->ReleaseStringUTFChars(libDir, d);
#endif
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_log_set(logCb, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    __android_log_print(ANDROID_LOG_INFO, "MasterClip", "whisper system: %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_anintellectualcompany_masterclip_engine_WhisperLib_free(JNIEnv *, jobject, jlong h) {
    if (h) whisper_free(reinterpret_cast<whisper_context *>(h));
}

/** Returns "lang\n" then one "startMs\tendMs\ttext" line per word; null on failure. */
JNIEXPORT jbyteArray JNICALL
Java_com_anintellectualcompany_masterclip_engine_WhisperLib_run(JNIEnv *env, jobject, jlong h, jfloatArray pcm,
                                                               jstring lang, jstring prompt, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(h);
    if (!ctx) return nullptr;
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    const char *l = env->GetStringUTFChars(lang, nullptr);
    const char *pr = env->GetStringUTFChars(prompt, nullptr);
    std::string language = l, initial = pr;
    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseStringUTFChars(prompt, pr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    wp.beam_search.beam_size = 5;
    wp.n_threads = threads;
    wp.language = language.c_str();
    wp.detect_language = false;
    wp.translate = false;
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.no_context = true;
    wp.token_timestamps = true;
    wp.max_len = 1;
    wp.split_on_word = true;
    wp.suppress_blank = true;
    wp.temperature_inc = 0.0f;
    if (!initial.empty()) wp.initial_prompt = initial.c_str();
    __android_log_print(ANDROID_LOG_INFO, TAG, "transcribe language=%s translate=%d samples=%d", wp.language, wp.translate, n);

    if (whisper_full(ctx, wp, samples.data(), (int) samples.size()) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "whisper_full failed");
        return nullptr;
    }
    whisper_print_timings(ctx);
    std::string out = std::string(whisper_lang_str(whisper_full_lang_id(ctx))) + "\n";
    int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        out += std::to_string(whisper_full_get_segment_t0(ctx, i) * 10) + "\t" +
               std::to_string(whisper_full_get_segment_t1(ctx, i) * 10) + "\t" + t + "\n";
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

}
