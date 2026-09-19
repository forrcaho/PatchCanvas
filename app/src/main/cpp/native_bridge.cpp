#include <jni.h>

#include <algorithm>
#include <string>

#include "audio_engine.h"
#include "nodes.h"
#include "soundfont.h"

namespace {

// One engine for the process, owning one graph.
AudioEngine &engine() {
    static AudioEngine instance;
    return instance;
}

} // namespace

extern "C" {

// ---------------------------------------------------------------- stream lifecycle

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStart(JNIEnv *, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStop(JNIEnv *, jobject) {
    engine().stop();
}

JNIEXPORT void JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetOutputEnabled(JNIEnv *, jobject,
                                                                      jboolean enabled) {
    engine().setOutputEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeAttachPerformanceHint(JNIEnv *, jobject) {
    return engine().attachPerformanceHint() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStartInput(JNIEnv *, jobject) {
    return engine().startInput() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStopInput(JNIEnv *, jobject) {
    engine().stopInput();
}

JNIEXPORT jstring JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeInputStatus(JNIEnv *env, jobject) {
    return env->NewStringUTF(engine().inputStatus().c_str());
}

/** Debug only: arm the rolling capture written when the stream stops. */
JNIEXPORT void JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeArmCapture(JNIEnv *env, jobject,
                                                                 jboolean enabled,
                                                                 jstring path) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    engine().armCapture(enabled == JNI_TRUE, chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
}

JNIEXPORT jstring JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStatus(JNIEnv *env, jobject) {
    return env->NewStringUTF(engine().status().c_str());
}

// ---------------------------------------------------------------- graph commands
//
// All of these run on the UI thread. Node construction happens here, inside postAdd,
// so the only thing that crosses to the audio thread is a pointer.

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeAddNode(JNIEnv *, jobject,
                                                              jlong id, jint type) {
    return engine().graph().postAdd(id, static_cast<NodeType>(type)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeRemoveNode(JNIEnv *, jobject, jlong id) {
    return engine().graph().postRemove(id) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeConnect(JNIEnv *, jobject,
                                                              jlong srcId, jint srcPort,
                                                              jlong dstId, jint dstPort) {
    return engine().graph().postConnect(srcId, srcPort, dstId, dstPort) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeDisconnect(JNIEnv *, jobject,
                                                                 jlong srcId, jint srcPort,
                                                                 jlong dstId, jint dstPort) {
    return engine().graph().postDisconnect(srcId, srcPort, dstId, dstPort) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetParam(JNIEnv *, jobject,
                                                               jlong id, jint index,
                                                               jfloat value) {
    return engine().graph().postSetParam(id, index, value) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetModRange(JNIEnv *, jobject,
                                                                  jlong id, jint index,
                                                                  jfloat low, jfloat high,
                                                                  jboolean exponential) {
    return engine().graph().postSetModRange(id, index, low, high, exponential == JNI_TRUE)
                   ? JNI_TRUE
                   : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeConnectMod(JNIEnv *, jobject,
                                                                 jlong srcId, jint srcPort,
                                                                 jlong dstId, jint index) {
    return engine().graph().postConnectMod(srcId, srcPort, dstId, index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeDisconnectMod(JNIEnv *, jobject,
                                                                    jlong srcId, jint srcPort,
                                                                    jlong dstId, jint index) {
    return engine().graph().postDisconnectMod(srcId, srcPort, dstId, index) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetStep(JNIEnv *, jobject,
                                                              jlong id, jint index,
                                                              jint degree, jboolean gate) {
    return engine().graph().postSetStep(id, index, degree, gate == JNI_TRUE) ? JNI_TRUE
                                                                            : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetDot(JNIEnv *, jobject, jlong id,
                                                             jint slot, jint step, jint degree,
                                                             jint length) {
    return engine().graph().postSetDot(id, slot, step, degree, length) ? JNI_TRUE : JNI_FALSE;
}

/**
 * The patch's scales, flattened: every entry's degrees end to end, and per entry its
 * degree count, period and length in beats.
 *
 * Built into a ScaleList here, on the UI thread, like a node -- only the finished pointer
 * crosses. Anything past the fixed limits is dropped rather than trusted, since a short
 * degrees array would otherwise be read past its end.
 */
JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetScales(JNIEnv *env, jobject,
                                                                jfloatArray degrees,
                                                                jintArray sizes,
                                                                jfloatArray periods,
                                                                jintArray beats,
                                                                jfloatArray roots) {
    const int32_t available = env->GetArrayLength(degrees);
    const int32_t entries = std::min(std::min(env->GetArrayLength(sizes),
                                              env->GetArrayLength(periods)),
                                     std::min(env->GetArrayLength(beats),
                                              env->GetArrayLength(roots)));

    auto *list = new ScaleList();
    jfloat *d = env->GetFloatArrayElements(degrees, nullptr);
    jint *s = env->GetIntArrayElements(sizes, nullptr);
    jfloat *p = env->GetFloatArrayElements(periods, nullptr);
    jint *b = env->GetIntArrayElements(beats, nullptr);
    jfloat *r = env->GetFloatArrayElements(roots, nullptr);

    list->count = std::min(entries, kMaxScaleEntries);
    int32_t at = 0;
    for (int32_t i = 0; i < list->count; ++i) {
        const int32_t size = std::max(0, static_cast<int32_t>(s[i]));
        ScaleTable &table = list->tables[i];
        table.size = std::min(std::min(size, kMaxDegrees), std::max(0, available - at));
        for (int32_t j = 0; j < table.size; ++j) table.octaves[j] = d[at + j];
        table.period = p[i];
        table.root = r[i];
        list->beats[i] = b[i];
        at += size;
    }
    list->finish();

    env->ReleaseFloatArrayElements(degrees, d, JNI_ABORT);
    env->ReleaseIntArrayElements(sizes, s, JNI_ABORT);
    env->ReleaseFloatArrayElements(periods, p, JNI_ABORT);
    env->ReleaseIntArrayElements(beats, b, JNI_ABORT);
    env->ReleaseFloatArrayElements(roots, r, JNI_ABORT);

    return engine().graph().postSetScales(list) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Parses a SoundFont, off the UI thread -- the interface calls this from its IO
 * dispatcher, since it converts every sample to float. The handle is the SoundFont itself,
 * kept for the life of the process.
 */
JNIEXPORT jlong JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeLoadSoundFont(JNIEnv *env, jobject,
                                                                    jbyteArray data) {
    const jsize size = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return 0;
    SoundFont *font = SoundFont::load(bytes, size);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return reinterpret_cast<jlong>(font);
}

/** "bank<TAB>program<TAB>name" per preset, in the font's own order. */
JNIEXPORT jobjectArray JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSoundFontPresets(JNIEnv *env, jobject,
                                                                       jlong handle) {
    auto *font = reinterpret_cast<SoundFont *>(handle);
    const int32_t count = font != nullptr ? font->presetCount() : 0;
    jobjectArray out = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    for (int32_t i = 0; i < count; ++i) {
        std::string entry = std::to_string(font->presetBank(i)) + "\t" +
                            std::to_string(font->presetProgram(i)) + "\t";
        // Names are fixed 20-byte fields in the file; anything not ASCII is dropped rather
        // than handed to NewStringUTF, which takes modified UTF-8 and nothing else.
        for (const char *c = font->presetName(i); c != nullptr && *c != 0; ++c) {
            if (static_cast<unsigned char>(*c) >= 32 && static_cast<unsigned char>(*c) < 127) entry += *c;
        }
        jstring text = env->NewStringUTF(entry.c_str());
        env->SetObjectArrayElement(out, i, text);
        env->DeleteLocalRef(text);
    }
    return out;
}

/** Builds SF node [id] a synth over font [handle], here, and hands it across. */
JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetNodeFont(JNIEnv *, jobject, jlong id,
                                                                  jlong handle) {
    auto *font = reinterpret_cast<SoundFont *>(handle);
    if (font == nullptr) return JNI_FALSE;
    return engine().graph().postSetResource(id, new SoundFontSynth(*font)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeScaleEntry(JNIEnv *, jobject) {
    return engine().graph().scaleEntry();
}

JNIEXPORT jint JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStepOf(JNIEnv *, jobject, jlong id) {
    return engine().graph().stepOf(id);
}

JNIEXPORT jfloat JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeParamOf(JNIEnv *, jobject, jlong id,
                                                              jint index) {
    return engine().graph().paramOf(id, index);
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetTempo(JNIEnv *, jobject, jfloat bpm) {
    return engine().graph().postSetTempo(bpm) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeResetTransport(JNIEnv *, jobject) {
    return engine().graph().postResetTransport() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeTransportBeat(JNIEnv *, jobject) {
    return engine().graph().transportBeat();
}

/** Frees whatever the audio thread handed back. Deliberately not on the audio thread. */
JNIEXPORT void JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeCollectGarbage(JNIEnv *, jobject) {
    engine().graph().collectGarbage();
}

} // extern "C"
