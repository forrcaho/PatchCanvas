package io.github.forrcaho.patchcanvas

import android.content.Context
import android.os.PerformanceHintManager
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
    private var hintSession: PerformanceHintManager.Session? = null

    fun start(): Boolean {
        if (!available || started) return started
        started = nativeStart()
        if (!started) Log.e(TAG, "engine failed to start")
        return started
    }

    fun stop() {
        if (!available || !started) return
        hintSession?.close()
        hintSession = null
        nativeStop()
        started = false
    }

    fun setToneEnabled(enabled: Boolean) {
        if (available && started) nativeSetToneEnabled(enabled)
    }

    /**
     * Aims a performance hint at the audio thread.
     *
     * Tensor's governor is aggressive about parking work on little cores, and this is
     * the supported way to say the thread has a deadline. The tid only exists once the
     * first callback has run, so this is called a moment after start rather than during
     * it.
     *
     * Only the session is created here. The other half of ADPF -- reporting each
     * callback's actual duration -- has to happen on the audio thread, and the Java API
     * would mean a JNI call into the JVM from that thread, which is the one thing the
     * realtime path must never do. The NDK's APerformanceHint does it without JNI but
     * needs API 33; revisit if minSdk ever rises.
     */
    fun attachPerformanceHint(context: Context): Boolean {
        if (!available || !started || hintSession != null) return hintSession != null

        val tid = nativeAudioThreadTid()
        val rate = nativeSampleRate()
        val burst = nativeFramesPerBurst()
        if (tid == 0 || rate <= 0 || burst <= 0) return false

        // One burst is the deadline: the callback must return before the next is due.
        val targetNanos = burst.toLong() * 1_000_000_000L / rate.toLong()

        return try {
            val manager = context.getSystemService(PerformanceHintManager::class.java)
            hintSession = manager?.createHintSession(intArrayOf(tid), targetNanos)
            if (hintSession != null) {
                Log.i(TAG, "performance hint attached tid=$tid targetNs=$targetNanos")
            }
            hintSession != null
        } catch (e: Exception) {
            Log.w(TAG, "could not attach performance hint", e)
            false
        }
    }

    /** What the stream actually negotiated, as key=value pairs. */
    fun status(): String = if (available) nativeStatus() else "state=UNAVAILABLE"

    fun logStatus() = Log.i(TAG, status())

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeSetToneEnabled(enabled: Boolean)
    private external fun nativeStatus(): String
    private external fun nativeAudioThreadTid(): Int
    private external fun nativeSampleRate(): Int
    private external fun nativeFramesPerBurst(): Int
}
