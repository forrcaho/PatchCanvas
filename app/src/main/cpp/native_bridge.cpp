#include <jni.h>

#include "audio_engine.h"
#include "nodes.h"

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
                                                                 jlong dstId, jint dstPort) {
    return engine().graph().postDisconnect(dstId, dstPort) ? JNI_TRUE : JNI_FALSE;
}

/** Frees whatever the audio thread handed back. Deliberately not on the audio thread. */
JNIEXPORT void JNICALL
Java_io_github_forrcaho_patchcanvas_AudioEngine_nativeCollectGarbage(JNIEnv *, jobject) {
    engine().graph().collectGarbage();
}

} // extern "C"
