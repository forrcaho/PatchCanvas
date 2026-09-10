#include "audio_engine.h"

#include <android/log.h>
#include <oboe/OboeExtensions.h>

#include <unistd.h>

#include <cmath>
#include <sstream>

namespace {

constexpr const char *kTag = "PatchAudio";

constexpr double kTwoPi = 6.283185307179586;
constexpr double kToneHz = 220.0;
constexpr float kToneGain = 0.18f;

/**
 * One-pole ramp towards the target gain, so toggling the tone fades over a few
 * milliseconds instead of stepping and clicking. A step discontinuity is broadband --
 * it would be audible on any system, and would misrepresent what the stream sounds like.
 */
constexpr float kGainSmoothing = 0.0008f;

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
    phaseIncrement_ = kToneHz * kTwoPi / static_cast<double>(sampleRate_);

    // Two bursts is the documented starting point: enough to absorb scheduling jitter,
    // small enough to stay in the low-latency regime.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);

    const oboe::Result started = stream_->requestStart();
    if (started != oboe::Result::OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "requestStart failed: %s",
                            oboe::convertToText(started));
        stream_->close();
        stream_.reset();
        return false;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", statusLocked().c_str());
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(streamLock_);
    if (!stream_) return;
    __android_log_print(ANDROID_LOG_INFO, kTag, "closing: %s", statusLocked().c_str());
    stream_->requestStop();
    stream_->close();
    stream_.reset();
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream * /*stream*/,
                                                   void *audioData,
                                                   int32_t numFrames) {
    // Published once, for the performance hint. Relaxed is enough: the reader only
    // needs to eventually see a non-zero value, and never races with a second writer.
    if (audioThreadTid_.load(std::memory_order_relaxed) == 0) {
        audioThreadTid_.store(gettid(), std::memory_order_relaxed);
    }

    auto *out = static_cast<float *>(audioData);
    const float target = toneEnabled_.load(std::memory_order_relaxed) ? kToneGain : 0.0f;

    for (int32_t frame = 0; frame < numFrames; ++frame) {
        gain_ += (target - gain_) * kGainSmoothing;

        const auto sample = static_cast<float>(std::sin(phase_)) * gain_;
        phase_ += phaseIncrement_;
        if (phase_ >= kTwoPi) phase_ -= kTwoPi;

        for (int32_t channel = 0; channel < channelCount_; ++channel) {
            *out++ = sample;
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result result) {
    // Routing changes (headphones in or out) close the stream from underneath us.
    __android_log_print(ANDROID_LOG_WARN, kTag, "stream closed by system: %s",
                        oboe::convertToText(result));
    {
        std::lock_guard<std::mutex> lock(streamLock_);
        stream_.reset();
    }
    audioThreadTid_.store(0, std::memory_order_relaxed);
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
