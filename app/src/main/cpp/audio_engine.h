#pragma once

#include <oboe/Oboe.h>

#include <atomic>
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

    void setToneEnabled(bool on) { toneEnabled_.store(on, std::memory_order_relaxed); }
    bool toneEnabled() const { return toneEnabled_.load(std::memory_order_relaxed); }

    /** Key=value line describing what the stream actually negotiated. */
    std::string status() const;

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

    mutable std::mutex streamLock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<bool> toneEnabled_{false};

    // Audio-thread only. Not atomic because nothing else touches them while running.
    double phase_ = 0.0;
    double phaseIncrement_ = 0.0;
    float gain_ = 0.0f;

    std::atomic<int32_t> audioThreadTid_{0};
    int32_t sampleRate_ = 0;
    int32_t channelCount_ = 0;
    int32_t framesPerBurst_ = 0;
};
