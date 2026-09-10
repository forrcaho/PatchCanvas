#include <jni.h>

#include <memory>

#include "audio_engine.h"

namespace {

// One engine for the process. Phase 3 will hand it a graph; for now it owns a sine.
AudioEngine &engine() {
    static AudioEngine instance;
    return instance;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStart(JNIEnv *, jobject) {
    return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStop(JNIEnv *, jobject) {
    engine().stop();
}

JNIEXPORT void JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeSetToneEnabled(JNIEnv *, jobject,
                                                                     jboolean enabled) {
    engine().setToneEnabled(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeAttachPerformanceHint(JNIEnv *, jobject) {
    return engine().attachPerformanceHint() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeStatus(JNIEnv *env, jobject) {
    return env->NewStringUTF(engine().status().c_str());
}

} // extern "C"
