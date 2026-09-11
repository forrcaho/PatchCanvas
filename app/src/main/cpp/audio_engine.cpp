#include "audio_engine.h"

#include <android/log.h>
#include <oboe/OboeExtensions.h>

#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <fstream>
#include <cmath>
#include <thread>
#include <sstream>

namespace {

constexpr const char *kTag = "PatchAudio";

constexpr float kMasterGain = 0.6f;

/** Seconds of rolling debug capture. Ten is plenty to find a click and cheap to hold. */
constexpr int kCaptureSeconds = 10;

/**
 * One-pole ramp towards the target gain, so toggling the tone fades over a few
 * milliseconds instead of stepping and clicking. A step discontinuity is broadband --
 * it would be audible on any system, and would misrepresent what the stream sounds like.
 */
constexpr float kGainSmoothing = 0.0008f;

/** Roughly 20ms to inaudible -- fast enough not to delay onPause, slow enough not to click. */
constexpr float kFadeOutSmoothing = 0.01f;
constexpr float kSilent = 1.0e-4f;
constexpr int kFadeWaitMs = 60;

/**
 * faded_ only says the audio thread has *written* silence. Those frames are still in
 * the stream buffer and the hardware pipeline, and requestStop discards whatever has
 * not been consumed -- which truncates the tail of the ramp and clicks anyway. Waiting
 * out one buffer plus the hardware path lets the ramp actually reach the DAC.
 */
constexpr int kDrainMs = 25;

const char *sharingModeName(oboe::SharingMode mode) {
    return mode == oboe::SharingMode::Exclusive ? "EXCLUSIVE" : "SHARED";
}

const char *performanceModeName(oboe::PerformanceMode mode) {
    switch (mode) {
        case oboe::PerformanceMode::LowLatency: return "LOW_LATENCY";
        case oboe::PerformanceMode::PowerSaving: return "POWER_SAVING";
        default: return "NONE";
    }
}

} // namespace

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(streamLock_);
    if (stream_) return true;

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    // Deliberately no setSampleRate: asking for the device's own rate is what avoids a
    // resampler sitting in the path we are trying to measure.

    const oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "openStream failed: %s",
                            oboe::convertToText(result));
        stream_.reset();
        return false;
    }

    sampleRate_ = stream_->getSampleRate();
    channelCount_ = stream_->getChannelCount();
    framesPerBurst_ = stream_->getFramesPerBurst();
    graph_.setSampleRate(sampleRate_);

    // Two bursts is the documented starting point: enough to absorb scheduling jitter,
    // small enough to stay in the low-latency regime.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);

    if (captureArmed_.load(std::memory_order_acquire)) {
        captureCapacity_ =
                static_cast<std::size_t>(sampleRate_) * kCaptureSeconds * channelCount_;
        capture_ = std::make_unique<float[]>(captureCapacity_);
        captureWrite_ = 0;
        captureWrapped_ = false;
    }

    fadingOut_.store(false, std::memory_order_relaxed);
    faded_.store(false, std::memory_order_relaxed);

    const oboe::Result started = stream_->requestStart();
    if (started != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "requestStart failed: %s",
                            oboe::convertToText(started));
        stream_->close();
        stream_.reset();
        return false;
    }

    running_.store(true, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", statusLocked().c_str());
    return true;
}

void AudioEngine::fadeOutAndWait() {
    if (!running_.load(std::memory_order_acquire)) return;

    fadingOut_.store(true, std::memory_order_relaxed);

    bool landed = false;
    for (int waited = 0; waited < kFadeWaitMs && !landed; ++waited) {
        landed = faded_.load(std::memory_order_acquire);
        if (!landed) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    // If it never landed the stream is probably already dead, and there is nothing to
    // drain. Otherwise let the silence we just wrote travel to the DAC before stopping.
    if (landed) {
        std::this_thread::sleep_for(std::chrono::milliseconds(kDrainMs));
    }
}

void AudioEngine::stop() {
    // Before the lock, because it only touches atomics and must not block status().
    fadeOutAndWait();
    running_.store(false, std::memory_order_release);

    std::lock_guard<std::mutex> lock(streamLock_);
    if (!stream_) return;
    __android_log_print(ANDROID_LOG_INFO, kTag, "closing: %s", statusLocked().c_str());
    stream_->requestStop();
    stream_->close();
    stream_.reset();

    // Ordered deliberately: the stream is closed first, so no callback can still be
    // holding the session pointer when it is freed.
    if (auto *session = hintSession_.exchange(nullptr, std::memory_order_acq_rel)) {
        APerformanceHint_closeSession(session);
    }
    audioThreadTid_.store(0, std::memory_order_relaxed);

    // Safe only here: the stream is closed, so no callback can be inside the graph.
    // Rebuilding from scratch on the next start beats trying to reconcile a graph that
    // outlived its stream.
    graph_.reset();

    // Also safe only here, and for the same reason: nothing can be writing into it.
    writeCaptureWav();
    capture_.reset();
    captureCapacity_ = 0;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream * /*stream*/,
                                                   void *audioData,
                                                   int32_t numFrames) {
    // Published once, for the performance hint. Relaxed is enough: the reader only
    // needs to eventually see a non-zero value, and never races with a second writer.
    if (audioThreadTid_.load(std::memory_order_relaxed) == 0) {
        audioThreadTid_.store(gettid(), std::memory_order_relaxed);
    }

    const auto began = std::chrono::steady_clock::now();

    // Drained once per callback rather than per block: applying a command is cheap,
    // but rebuilding the evaluation order is not, and doing it three times for one
    // callback would buy nothing a burst of latency does not already cost.
    graph_.applyCommands();

    auto *out = static_cast<float *>(audioData);
    const bool fading = fadingOut_.load(std::memory_order_relaxed);
    const float target =
            (!fading && outputEnabled_.load(std::memory_order_relaxed)) ? kMasterGain : 0.0f;
    const float smoothing = fading ? kFadeOutSmoothing : kGainSmoothing;

    int32_t done = 0;
    while (done < numFrames) {
        const int32_t block = std::min(numFrames - done, kBlockSize);
        graph_.process(block);

        const float *left = graph_.outputL();
        const float *right = graph_.outputR();

        for (int32_t i = 0; i < block; ++i) {
            gain_ += (target - gain_) * smoothing;
            if (channelCount_ >= 2) {
                const float l = left[i] * gain_;
                const float r = right[i] * gain_;
                *out++ = l;
                *out++ = r;
                captureFrame(l, r);
            } else {
                const float m = (left[i] + right[i]) * 0.5f * gain_;
                *out++ = m;
                captureFrame(m, m);
            }
        }
        done += block;
    }

    if (fading && gain_ < kSilent) {
        faded_.store(true, std::memory_order_release);
    }

    // The half of ADPF that makes it work: without a measured duration the governor is
    // guessing, and it guesses badly for audio -- a thread that wakes, does a short
    // burst and sleeps looks idle, so clocks drop and work migrates to little cores,
    // and the next callback misses its deadline.
    if (auto *session = hintSession_.load(std::memory_order_acquire)) {
        const auto elapsed = std::chrono::steady_clock::now() - began;
        APerformanceHint_reportActualWorkDuration(
                session,
                std::chrono::duration_cast<std::chrono::nanoseconds>(elapsed).count());
    }

    return oboe::DataCallbackResult::Continue;
}

bool AudioEngine::attachPerformanceHint() {
    if (hintSession_.load(std::memory_order_acquire) != nullptr) return true;

    const int32_t tid = audioThreadTid_.load(std::memory_order_relaxed);
    if (tid == 0 || sampleRate_ <= 0 || framesPerBurst_ <= 0) return false;

    // minSdk 33 makes the API callable, not the feature present: a device whose power
    // HAL does not implement ADPF still returns null here, and that is not an error.
    APerformanceHintManager *manager = APerformanceHint_getManager();
    if (manager == nullptr) {
        __android_log_print(ANDROID_LOG_INFO, kTag, "no ADPF manager on this device");
        return false;
    }

    // One burst is the deadline: the callback must return before the next one is due.
    const int64_t targetNanos =
            static_cast<int64_t>(framesPerBurst_) * 1000000000LL / sampleRate_;

    int32_t threads[] = {tid};
    APerformanceHintSession *session =
            APerformanceHint_createSession(manager, threads, 1, targetNanos);
    if (session == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "ADPF session refused");
        return false;
    }

    hintSession_.store(session, std::memory_order_release);
    __android_log_print(ANDROID_LOG_INFO, kTag, "ADPF attached tid=%d targetNs=%lld",
                        tid, static_cast<long long>(targetNanos));
    return true;
}

void AudioEngine::armCapture(bool enabled, const std::string &path) {
    capturePath_ = path;
    captureArmed_.store(enabled, std::memory_order_release);
}

void AudioEngine::writeCaptureWav() {
    if (!capture_ || captureCapacity_ == 0) return;
    const std::size_t total = captureWrapped_ ? captureCapacity_ : captureWrite_;
    if (total == 0) return;

    std::ofstream file(capturePath_, std::ios::binary | std::ios::trunc);
    if (!file) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "could not open %s", capturePath_.c_str());
        return;
    }

    // 32-bit float WAV: the samples exactly as the stream saw them, with no
    // quantisation of our own to confuse an analysis looking for small discontinuities.
    const uint32_t dataBytes = static_cast<uint32_t>(total * sizeof(float));
    const uint32_t rate = static_cast<uint32_t>(sampleRate_);
    const uint16_t channels = static_cast<uint16_t>(channelCount_);
    const uint16_t bits = 32;
    const uint16_t blockAlign = static_cast<uint16_t>(channels * bits / 8);
    const uint32_t byteRate = rate * blockAlign;

    auto u32 = [&](uint32_t v) { file.write(reinterpret_cast<const char *>(&v), 4); };
    auto u16 = [&](uint16_t v) { file.write(reinterpret_cast<const char *>(&v), 2); };

    file.write("RIFF", 4);
    u32(36 + dataBytes);
    file.write("WAVE", 4);
    file.write("fmt ", 4);
    u32(16);
    u16(3); // IEEE float
    u16(channels);
    u32(rate);
    u32(byteRate);
    u16(blockAlign);
    u16(bits);
    file.write("data", 4);
    u32(dataBytes);

    // Oldest first: the ring starts wherever the write head left off.
    if (captureWrapped_) {
        const std::size_t tail = captureCapacity_ - captureWrite_;
        file.write(reinterpret_cast<const char *>(capture_.get() + captureWrite_),
                   static_cast<std::streamsize>(tail * sizeof(float)));
        file.write(reinterpret_cast<const char *>(capture_.get()),
                   static_cast<std::streamsize>(captureWrite_ * sizeof(float)));
    } else {
        file.write(reinterpret_cast<const char *>(capture_.get()),
                   static_cast<std::streamsize>(total * sizeof(float)));
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "capture written: %s (%u bytes)",
                        capturePath_.c_str(), dataBytes);
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result result) {
    // Plugging headphones in or out closes the stream from underneath us.
    __android_log_print(ANDROID_LOG_WARN, kTag, "stream closed by system: %s",
                        oboe::convertToText(result));
    {
        std::lock_guard<std::mutex> lock(streamLock_);
        stream_.reset();
    }
    audioThreadTid_.store(0, std::memory_order_relaxed);
    if (auto *session = hintSession_.exchange(nullptr, std::memory_order_acq_rel)) {
        APerformanceHint_closeSession(session);
    }

    // The graph is deliberately NOT reset: a route change must not cost you your patch.

    if (result == oboe::Result::ErrorDisconnected && running_.load(std::memory_order_acquire)) {
        // Oboe delivers this on its own error thread, not the audio thread, so opening a
        // stream and even sleeping here is allowed. Without this the instrument goes
        // silent the moment you plug in headphones and stays silent until the app is
        // backgrounded and resumed, which is not a thing an instrument may do.
        if (start()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            attachPerformanceHint();
            __android_log_print(ANDROID_LOG_INFO, kTag, "reopened after route change");
        } else {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "could not reopen after route change");
        }
    }
}

std::string AudioEngine::status() const {
    std::lock_guard<std::mutex> lock(streamLock_);
    return statusLocked();
}

std::string AudioEngine::statusLocked() const {
    std::ostringstream out;
    if (!stream_) {
        out << "state=CLOSED";
        return out.str();
    }

    const auto latency = stream_->calculateLatencyMillis();
    const auto xRuns = stream_->getXRunCount();

    out << "state=OPEN"
        << " api=" << (stream_->usesAAudio() ? "AAudio" : "OpenSL")
        << " mmap=" << (oboe::OboeExtensions::isMMapUsed(stream_.get()) ? "YES" : "NO")
        << " sharing=" << sharingModeName(stream_->getSharingMode())
        << " perf=" << performanceModeName(stream_->getPerformanceMode())
        << " rate=" << stream_->getSampleRate()
        << " channels=" << stream_->getChannelCount()
        << " burst=" << stream_->getFramesPerBurst()
        << " buffer=" << stream_->getBufferSizeInFrames()
        << " capacity=" << stream_->getBufferCapacityInFrames();

    if (latency) {
        out << " latencyMs=" << latency.value();
    } else {
        out << " latencyMs=unavailable";
    }
    out << " xruns=" << (xRuns ? xRuns.value() : -1);
    return out.str();
}
