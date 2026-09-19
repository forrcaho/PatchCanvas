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

    /** Both ends, because a note input takes several sources and only one is going. */
    fun disconnect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int): Boolean =
        available && started && nativeDisconnect(srcId, srcPort, dstId, dstPort)

    /** A knob moved. Real units, not normalized -- the node owns no mapping. */
    fun setParam(id: Long, index: Int, value: Float): Boolean =
        available && started && nativeSetParam(id, index, value)

    /** What an exposed parameter sweeps between, in its own units, and whether geometrically. */
    fun setModRange(id: Long, index: Int, low: Float, high: Float, exponential: Boolean): Boolean =
        available && started && nativeSetModRange(id, index, low, high, exponential)

    /** A modulator onto parameter [index] of [dstId]. One per parameter, so this replaces. */
    fun connectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int): Boolean =
        available && started && nativeConnectMod(srcId, srcPort, dstId, index)

    /** Unpatches a parameter's modulator; the engine fades it back to the knob. */
    fun disconnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int): Boolean =
        available && started && nativeDisconnectMod(srcId, srcPort, dstId, index)

    /** One step of a sequence, as a degree of whichever scale is sounding when it plays. */
    fun setStep(id: Long, index: Int, degree: Int, gate: Boolean): Boolean =
        available && started && nativeSetStep(id, index, degree, gate)

    /**
     * Parses a SoundFont and returns its handle, or 0 if it is not one this build reads.
     *
     * Needs the library but not a running stream: fonts outlive the engine's starts and
     * stops, and are loaded on a background thread before any node wants one.
     */
    fun loadSoundFont(bytes: ByteArray): Long = if (available) nativeLoadSoundFont(bytes) else 0L

    /** A loaded font's instruments. */
    fun soundFontPresets(handle: Long): List<SoundFontPreset> =
        if (!available || handle == 0L) emptyList()
        else nativeSoundFontPresets(handle).mapNotNull { entry ->
            val parts = entry.split('\t', limit = 3)
            if (parts.size < 3) null
            else SoundFontPreset(parts[0].toIntOrNull() ?: return@mapNotNull null,
                parts[1].toIntOrNull() ?: return@mapNotNull null, parts[2].trim())
        }

    /**
     * Gives SF node [id] a synth over font [handle]. The synth is built natively on this
     * thread -- it allocates -- and only its pointer crosses.
     */
    fun setNodeFont(id: Long, handle: Long): Boolean =
        available && started && handle != 0L && nativeSetNodeFont(id, handle)

    /**
     * The patch's scales, in order, with each entry's length in beats.
     *
     * Flattened into arrays because that is what JNI copies cheaply. The engine builds its
     * list from them off the audio thread and swaps the whole thing in at once.
     */
    fun setScales(entries: List<ScaleEntry>, beatsPerBar: Int): Boolean {
        if (!available || !started) return false
        val kept = entries.take(MAX_SCALE_ENTRIES)
        return nativeSetScales(
            kept.flatMap { it.scale.degrees.take(MAX_DEGREES) }.toFloatArray(),
            kept.map { minOf(it.scale.size, MAX_DEGREES) }.toIntArray(),
            kept.map { it.scale.period }.toFloatArray(),
            kept.map { it.lengthInBeats(beatsPerBar) }.toIntArray(),
            // Octaves, like every pitch that crosses this boundary.
            kept.map { it.rootCents / 1200f }.toFloatArray(),
        )
    }

    /**
     * Which entry of the scale list is sounding.
     *
     * From the engine rather than worked out here, so the grid and the sound cannot
     * disagree about when a switch happened.
     */
    fun scaleEntry(): Int = if (available && started) nativeScaleEntry() else 0

    /**
     * Which step a sequencer is on, or -1 if it is not running.
     *
     * Polled per frame while a sequencer's panel is open, and nowhere else -- it reads
     * one atomic the audio thread publishes, so it is cheap, but it is still a JNI hop
     * per call and there is no reason to make it when nothing is watching.
     */
    fun stepOf(id: Long): Int =
        if (available && started) nativeStepOf(id) else -1

    /**
     * Where a modulated parameter has got to, or null when the engine cannot say.
     *
     * Polled per frame while its panel is open and something is patched to it, and nowhere
     * else -- the same shape as [stepOf], for the same reason.
     */
    fun paramOf(id: Long, index: Int): Float? {
        if (!available || !started) return null
        return nativeParamOf(id, index).takeIf { !it.isNaN() }
    }

    /** The transport's rate, in beats per minute. The engine clamps it to what it supports. */
    fun setTempo(bpm: Float): Boolean = available && started && nativeSetTempo(bpm)

    /** Sends the transport back to the start of bar one. Not part of the patch, so not undoable. */
    fun resetTransport(): Boolean = available && started && nativeResetTransport()

    /**
     * Where the transport has got to, in beats from its start.
     *
     * Polled per frame while the transport card is open, and nowhere else -- the same
     * shape as [stepOf], for the same reason.
     */
    fun transportBeat(): Double = if (available && started) nativeTransportBeat() else 0.0

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
    private external fun nativeDisconnect(
        srcId: Long, srcPort: Int, dstId: Long, dstPort: Int,
    ): Boolean
    private external fun nativeSetParam(id: Long, index: Int, value: Float): Boolean
    private external fun nativeSetModRange(
        id: Long, index: Int, low: Float, high: Float, exponential: Boolean,
    ): Boolean
    private external fun nativeConnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int): Boolean
    private external fun nativeDisconnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int): Boolean
    private external fun nativeSetStep(id: Long, index: Int, degree: Int, gate: Boolean): Boolean
    private external fun nativeSetScales(
        degrees: FloatArray,
        sizes: IntArray,
        periods: FloatArray,
        beats: IntArray,
        roots: FloatArray,
    ): Boolean
    private external fun nativeScaleEntry(): Int
    private external fun nativeLoadSoundFont(bytes: ByteArray): Long
    private external fun nativeSoundFontPresets(handle: Long): Array<String>
    private external fun nativeSetNodeFont(id: Long, handle: Long): Boolean
    private external fun nativeStepOf(id: Long): Int
    private external fun nativeParamOf(id: Long, index: Int): Float
    private external fun nativeSetTempo(bpm: Float): Boolean
    private external fun nativeResetTransport(): Boolean
    private external fun nativeTransportBeat(): Double
    private external fun nativeCollectGarbage()
    private external fun nativeStatus(): String
    private external fun nativeArmCapture(enabled: Boolean, path: String)
    private external fun nativeStartInput(): Boolean
    private external fun nativeStopInput()
    private external fun nativeInputStatus(): String
    private external fun nativeAttachPerformanceHint(): Boolean
}
