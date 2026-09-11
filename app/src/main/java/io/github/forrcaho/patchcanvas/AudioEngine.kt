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

    /** Master output gate. Ramped in the engine, so this never clicks. */
    fun setOutputEnabled(enabled: Boolean) {
        if (available && started) nativeSetOutputEnabled(enabled)
    }

    // ---- graph commands. All from the UI thread; node construction happens natively
    // inside addNode, so only a pointer ever crosses to the audio thread.

    fun addNode(id: Long, type: NodeType): Boolean =
        available && started && nativeAddNode(id, type.id)

    fun removeNode(id: Long): Boolean = available && started && nativeRemoveNode(id)

    fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int): Boolean =
        available && started && nativeConnect(srcId, srcPort, dstId, dstPort)

    fun disconnect(dstId: Long, dstPort: Int): Boolean =
        available && started && nativeDisconnect(dstId, dstPort)

    /** Frees nodes the audio thread retired. Cheap, and never on the audio thread. */
    fun collectGarbage() {
        if (available && started) nativeCollectGarbage()
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

    /**
     * Opens the microphone. Requires RECORD_AUDIO to have been granted already; without
     * it the stream simply fails to open, which is reported rather than thrown.
     */
    fun startInput(): Boolean {
        if (!available || !started) {
            Log.w(TAG, "input refused: engine not running (available=$available started=$started)")
            return false
        }
        return nativeStartInput()
    }

    fun stopInput() {
        if (available && started) nativeStopInput()
    }

    fun inputStatus(): String = if (available) nativeInputStatus() else "state=UNAVAILABLE"

    fun logInputStatus() = Log.i(TAG, "input " + inputStatus())

    /**
     * Arms a rolling capture of exactly what reaches the stream, written out when the
     * stream stops. Debug builds only -- it holds a few megabytes for the ring, and a
     * release build has no business recording the user without being asked.
     */
    fun armCapture(enabled: Boolean, path: String) {
        if (available) nativeArmCapture(enabled, path)
    }

    /** What the stream actually negotiated, as key=value pairs. */
    fun status(): String = if (available) nativeStatus() else "state=UNAVAILABLE"

    fun logStatus() = Log.i(TAG, status())

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeSetOutputEnabled(enabled: Boolean)
    private external fun nativeAddNode(id: Long, type: Int): Boolean
    private external fun nativeRemoveNode(id: Long): Boolean
    private external fun nativeConnect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int): Boolean
    private external fun nativeDisconnect(dstId: Long, dstPort: Int): Boolean
    private external fun nativeCollectGarbage()
    private external fun nativeStatus(): String
    private external fun nativeArmCapture(enabled: Boolean, path: String)
    private external fun nativeStartInput(): Boolean
    private external fun nativeStopInput()
    private external fun nativeInputStatus(): String
    private external fun nativeAttachPerformanceHint(): Boolean
}
