package io.github.forrcaho.patchcanvas

import android.util.Log

/**
 * Kotlin face of the Oboe engine.
 *
 * Phase 2 only proves the stream opens on the low-latency path and measures what it
 * negotiated. Nothing here touches the audio thread; the tone flag crosses as a relaxed
 * atomic on the C++ side, which is the same shape the Phase 3 command queue will take.
 *
 * Native failure is survivable by design: a device that cannot load the library gets a
 * silent patch editor rather than a crash on launch.
 */
object AudioEngine {

    private const val TAG = "PatchAudio"

    val available: Boolean = try {
        System.loadLibrary("patchcanvas")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "native audio unavailable", e)
        false
    }

    private var started = false

    fun start(): Boolean {
        if (!available || started) return started
        started = nativeStart()
        if (!started) Log.e(TAG, "engine failed to start")
        return started
    }

    fun stop() {
        if (!available || !started) return
        nativeStop()
        started = false
    }

    fun setToneEnabled(enabled: Boolean) {
        if (available && started) nativeSetToneEnabled(enabled)
    }

    /**
     * Aims a performance hint at the audio thread.
     *
     * Entirely native. Creating the session is not realtime work so it happens here, on
     * the main thread, a moment after start -- the audio thread's id only exists once
     * the first callback has run. Reporting each callback's duration then happens on the
     * audio thread through the NDK's plain C entry point, with no JVM attachment, which
     * is what minSdk 33 buys: attaching the realtime thread to the JVM would expose it
     * to GC suspension, and a thread parked at a safepoint is not filling the buffer.
     *
     * False is a normal answer, not an error -- a device whose power HAL lacks ADPF
     * simply does not get the hint.
     */
    fun attachPerformanceHint(): Boolean =
        if (available && started) nativeAttachPerformanceHint() else false

    /** What the stream actually negotiated, as key=value pairs. */
    fun status(): String = if (available) nativeStatus() else "state=UNAVAILABLE"

    fun logStatus() = Log.i(TAG, status())

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeSetToneEnabled(enabled: Boolean)
    private external fun nativeStatus(): String
    private external fun nativeAttachPerformanceHint(): Boolean
}
