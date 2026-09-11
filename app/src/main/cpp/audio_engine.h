#pragma once

#include <android/performance_hint.h>
#include <oboe/Oboe.h>

#include "graph.h"

#include <atomic>
#include <cstddef>
#include <memory>
#include <mutex>
#include <string>

/**
 * Phase 2 spike.
 *
 * One sine, no graph. The point is not music: it is to find out whether this device
 * actually grants an exclusive-mode MMAP stream, and what the real latency is, before
 * Phase 3 builds a graph bridge on the assumption that it does. `aaudio.mmap_policy`
 * reporting AUTO means permitted, not granted.
 *
 * The callback is already written to the rules the bridge will need: no allocation, no
 * locks, no JNI into the JVM. Phase 3 replaces the body, not the discipline.
 */
class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    bool start();
    void stop();

    /** Master output gate, ramped rather than switched. The Out rail drives it. */
    void setOutputEnabled(bool on) { outputEnabled_.store(on, std::memory_order_relaxed); }
    bool outputEnabled() const { return outputEnabled_.load(std::memory_order_relaxed); }

    Graph &graph() { return graph_; }

    /** Key=value line describing what the stream actually negotiated. */
    std::string status() const;

    /**
     * Debug capture of exactly what reaches the stream -- post master gain, post
     * limiter, the samples the converter actually receives. A rolling window of the last
     * few seconds, written out when the stream stops, so "play, then background the app"
     * captures whatever you just heard.
     *
     * Armed before start() and disarmed after stop(), so the buffer is allocated and
     * freed only while no callback can be running. That is what makes it safe without a
     * lock: the audio thread's only involvement is a memcpy into memory that already
     * exists.
     */
    void armCapture(bool enabled, const std::string &path);

    /**
     * Opens the microphone as a second stream, read from inside the output callback.
     *
     * Deliberately not Oboe's FullDuplexStream, which installs itself as the output's
     * data callback -- that would restructure the engine around a feature that is off by
     * default. Reading the input here keeps the microphone strictly additive: with it
     * off, nothing about the output path differs.
     *
     * Uses the built-in microphone, and never the headset's. Asking for the headset mic
     * on a Bluetooth Classic link forces the connection from A2DP to SCO, which drops
     * everything the user is monitoring to 8 or 16kHz. The device's own mic is a separate
     * path that leaves the link alone.
     */
    bool startInput();
    void stopInput();
    bool inputRunning() const { return inputStream_ != nullptr; }
    std::string inputStatus() const;

    /**
     * Creates the ADPF session aimed at the audio thread. Called from the main thread a
     * moment after start, once the first callback has published its tid -- deliberately
     * not from the audio thread, since creating a session is not a realtime operation.
     */
    bool attachPerformanceHint();

    /** 0 until the first callback has run. Needed to aim a performance hint at it. */
    int32_t audioThreadTid() const { return audioThreadTid_.load(std::memory_order_relaxed); }
    int32_t sampleRate() const { return sampleRate_; }
    int32_t framesPerBurst() const { return framesPerBurst_; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result result) override;

private:
    /**
     * Guards stream_ only. Oboe delivers error callbacks on its own thread, so the
     * shared_ptr is reset from there while status() and stop() read it from the main
     * thread. The audio callback never touches stream_, so this lock can never be
     * contended by the realtime thread -- which is the only reason a mutex is
     * acceptable anywhere near this class.
     */
    /** Caller already holds streamLock_. */
    std::string statusLocked() const;

    void writeCaptureWav();

    /**
     * Audio thread. A pair of stores into memory that already exists -- no allocation,
     * no lock, no branch worth worrying about. The ring overwrites its oldest samples so
     * the window is always the most recent few seconds.
     */
    inline void captureFrame(float left, float right) {
        if (captureCapacity_ == 0) return;
        capture_[captureWrite_++] = left;
        if (captureWrite_ >= captureCapacity_) { captureWrite_ = 0; captureWrapped_ = true; }
        capture_[captureWrite_++] = right;
        if (captureWrite_ >= captureCapacity_) { captureWrite_ = 0; captureWrapped_ = true; }
    }

    /** Ramps the gain down and waits, briefly, for the audio thread to get there. */
    void fadeOutAndWait();

    mutable std::mutex streamLock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::unique_ptr<float[]> inputScratch_;
    std::size_t inputScratchFrames_ = 0;
    int32_t inputChannels_ = 0;

    /**
     * Published only while the input stream and its scratch are fully built, and cleared
     * before either is torn down. The audio thread reads the raw pointer solely under
     * this flag, which is what lets it avoid touching the shared_ptr the UI thread owns.
     * stopInput waits well past a callback period after clearing it, so nothing can
     * still be inside read() when the stream closes.
     */
    std::atomic<bool> inputActive_{false};
    oboe::AudioStream *inputForCallback_ = nullptr;
    Graph graph_;
    std::atomic<bool> outputEnabled_{false};

    /**
     * Closing the stream with the gain still up ends the last buffer on an arbitrary
     * non-zero sample, and the step to silence is broadband -- an audible pop. stop()
     * therefore asks the audio thread to ramp down and waits for it to land before
     * closing, rather than cutting mid-waveform.
     */
    std::atomic<bool> fadingOut_{false};
    std::atomic<bool> faded_{false};
    std::atomic<bool> running_{false};

    // Audio-thread only. Not atomic because nothing else touches it while running.
    float gain_ = 0.0f;

    // Debug capture. captureWrite_ and captureWrapped_ are audio-thread only.
    std::atomic<bool> captureArmed_{false};
    std::string capturePath_;
    std::unique_ptr<float[]> capture_;
    std::size_t captureCapacity_ = 0;
    std::size_t captureWrite_ = 0;
    bool captureWrapped_ = false;

    std::atomic<int32_t> audioThreadTid_{0};

    /**
     * Read by the audio thread every callback, written by the main thread once.
     * APerformanceHint_reportActualWorkDuration is a plain C call into libandroid, so
     * the audio thread can make it without attaching to the JVM -- which is the whole
     * reason minSdk is 33. Attaching would expose this thread to GC suspension, and a
     * thread parked at a safepoint is not filling the buffer.
     */
    std::atomic<APerformanceHintSession *> hintSession_{nullptr};
    int32_t sampleRate_ = 0;
    int32_t channelCount_ = 0;
    int32_t framesPerBurst_ = 0;
};
