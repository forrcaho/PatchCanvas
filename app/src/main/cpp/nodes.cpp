#include "nodes.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

/** Clamp without pulling in <algorithm>'s iterator machinery at every call site. */
inline float clampf(float value, float low, float high) {
    return value < low ? low : (value > high ? high : value);
}

/** A gate is high above half scale; anything below is off. */
inline bool gateHigh(float value) { return value > 0.5f; }

/**
 * A pentatonic figure, in octaves. There is nowhere to edit a sequence until Phase 5
 * gives modules parameters, so this is what Steps plays until then -- chosen to be
 * obviously musical, so a wrong clock or a dead gate is audible rather than ambiguous.
 */
constexpr float kPattern[8] = {
        0.0f, 3.0f / 12.0f, 7.0f / 12.0f, 10.0f / 12.0f,
        12.0f / 12.0f, 10.0f / 12.0f, 7.0f / 12.0f, 3.0f / 12.0f,
};

} // namespace

void NullNode::process(int32_t frames) {
    for (int32_t port = 0; port < outputs_; ++port) {
        std::memset(out(port), 0, static_cast<size_t>(frames) * sizeof(float));
    }
}

// ---------------------------------------------------------------- Osc

void OscNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    osc_.Init(static_cast<float>(sampleRate));
    osc_.SetWaveform(daisysp::Oscillator::WAVE_POLYBLEP_SAW);
    osc_.SetAmp(1.0f);
}

void OscNode::process(int32_t frames) {
    float *o = out(0);
    const float *pitch = input(0);
    const float *fm = input(1);

    for (int32_t i = 0; i < frames; ++i) {
        // Per sample rather than per block, deliberately: signal types are advisory, so
        // patching audio into an FM input is allowed and is a real technique. Updating
        // only at block rate would quantise that to the control rate and ruin it.
        const float octaves = clampf(pitch[i] + fm[i] + tuneSemitones_ / 12.0f, -6.0f, 6.0f);
        osc_.SetFreq(kMiddleC * std::exp2(octaves));
        o[i] = osc_.Process();
    }
}

void OscNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: tuneSemitones_ = clampf(value, -24.0f, 24.0f); break;
        case 1: {
            // Discrete, so the knob lands on a waveform rather than between two.
            const auto wave = static_cast<uint8_t>(clampf(value, 0.0f, 3.0f) + 0.5f);
            static const uint8_t kWaves[4] = {
                    daisysp::Oscillator::WAVE_POLYBLEP_SAW,
                    daisysp::Oscillator::WAVE_POLYBLEP_SQUARE,
                    daisysp::Oscillator::WAVE_POLYBLEP_TRI,
                    daisysp::Oscillator::WAVE_SIN,
            };
            osc_.SetWaveform(kWaves[wave]);
            break;
        }
        default: break;
    }
}

// ---------------------------------------------------------------- Filter

void FilterNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    svf_.Init(static_cast<float>(sampleRate));
    svf_.SetRes(0.3f);
    svf_.SetDrive(0.0f);
}

void FilterNode::process(int32_t frames) {
    float *o = out(0);
    const float *in = input(0);
    const float *cutoff = input(1);

    for (int32_t i = 0; i < frames; ++i) {
        // Per sample, like the oscillator. This was once per block, on the grounds that
        // Svf::SetFreq calls sinf and powf where Oscillator::SetFreq is a multiply --
        // true, but measured at 0.098% of a core against 0.047%, which is twice almost
        // nothing. The block-rate read was the engine's only control-rate behaviour, and
        // it quietly meant audio-rate filter modulation did not work while audio-rate FM
        // did. There is no control rate here; this was the one place pretending there was.
        const float octaves = clampf(cutoff[i], -6.0f, 6.0f);
        svf_.SetFreq(clampf(cutoffHz_ * std::exp2(octaves), 20.0f, 18000.0f));
        svf_.Process(in[i]);
        o[i] = svf_.Low();
    }
}

void FilterNode::setParam(int32_t index, float value) {
    switch (index) {
        // The knob sets where a cable's zero sits; the cable moves it in octaves from
        // there, which is how a cutoff input behaves on hardware.
        case 0: cutoffHz_ = clampf(value, 20.0f, 18000.0f); break;
        case 1: svf_.SetRes(clampf(value, 0.0f, 0.95f)); break;
        default: break;
    }
}

// ---------------------------------------------------------------- Env

void EnvNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    adsr_.Init(static_cast<float>(sampleRate));
    // Fixed until Phase 5. A short attack and a moderate decay make a plucked shape,
    // which is the one that shows most clearly whether a gate is arriving.
    adsr_.SetAttackTime(0.005f);
    adsr_.SetDecayTime(0.12f);
    adsr_.SetSustainLevel(0.6f);
    adsr_.SetReleaseTime(0.25f);
}

void EnvNode::process(int32_t frames) {
    float *o = out(0);
    const float *gate = input(0);
    for (int32_t i = 0; i < frames; ++i) {
        o[i] = adsr_.Process(gateHigh(gate[i]));
    }
}

void EnvNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: adsr_.SetAttackTime(clampf(value, 0.001f, 5.0f)); break;
        case 1: adsr_.SetDecayTime(clampf(value, 0.001f, 5.0f)); break;
        case 2: adsr_.SetSustainLevel(clampf(value, 0.0f, 1.0f)); break;
        case 3: adsr_.SetReleaseTime(clampf(value, 0.001f, 10.0f)); break;
        default: break;
    }
}

// ---------------------------------------------------------------- VCA

void VcaNode::process(int32_t frames) {
    float *o = out(0);
    const float *in = input(0);
    const float *cv = input(1);
    for (int32_t i = 0; i < frames; ++i) {
        o[i] = in[i] * clampf(cv[i] + bias_, 0.0f, 1.0f);
    }
}

void VcaNode::setParam(int32_t index, float value) {
    if (index == 0) bias_ = clampf(value, 0.0f, 1.0f);
}

// ---------------------------------------------------------------- Clock

void ClockNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    period_ = static_cast<int64_t>(sampleRate) * 60 / 120; // 120bpm
    counter_ = 0;
}

void ClockNode::process(int32_t frames) {
    float *o = out(0);
    const int64_t high = period_ / 4; // a quarter-length gate
    for (int32_t i = 0; i < frames; ++i) {
        o[i] = counter_ < high ? 1.0f : 0.0f;
        if (++counter_ >= period_) counter_ = 0;
    }
}

void ClockNode::setParam(int32_t index, float value) {
    if (index != 0) return;
    bpm_ = clampf(value, 20.0f, 300.0f);
    period_ = static_cast<int64_t>(static_cast<float>(sampleRate_) * 60.0f / bpm_);
    if (period_ < 2) period_ = 2;
    if (counter_ >= period_) counter_ = 0;
}

// ---------------------------------------------------------------- Steps

void StepsNode::process(int32_t frames) {
    float *pitch = out(0);
    float *gate = out(1);
    const float *clock = input(0);

    for (int32_t i = 0; i < frames; ++i) {
        const bool high = gateHigh(clock[i]);
        if (high && !wasHigh_) {
            step_ = (step_ + 1) % (length_ > 0 ? length_ : 1);
        }
        wasHigh_ = high;

        pitch[i] = kPattern[step_] + transpose_ / 12.0f;
        gate[i] = high ? 1.0f : 0.0f;
    }
}

void StepsNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: {
            length_ = static_cast<int32_t>(clampf(value, 1.0f, static_cast<float>(kSteps)) + 0.5f);
            if (step_ >= length_) step_ = 0;
            break;
        }
        case 1: transpose_ = clampf(value, -24.0f, 24.0f); break;
        default: break;
    }
}

// ---------------------------------------------------------------- Mix

void MixNode::process(int32_t frames) {
    float *o = out(0);
    const float *a = input(0);
    const float *b = input(1);
    const float *c = input(2);
    const float *d = input(3);
    for (int32_t i = 0; i < frames; ++i) {
        // Summed, not averaged: an unused input contributes silence, and averaging would
        // make a patch quieter simply for having spare inputs. The limiter catches the
        // rest.
        o[i] = a[i] * level_[0] + b[i] * level_[1] + c[i] * level_[2] + d[i] * level_[3];
    }
}

void MixNode::setParam(int32_t index, float value) {
    if (index >= 0 && index < 4) level_[index] = clampf(value, 0.0f, 2.0f);
}

// ---------------------------------------------------------------- Out

void OutNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    dcLeft_.Init(static_cast<float>(sampleRate));
    dcRight_.Init(static_cast<float>(sampleRate));
    limitLeft_.Init();
    limitRight_.Init();
}

void OutNode::process(int32_t frames) {
    float *left = out(0);
    float *right = out(1);
    const float *inLeft = input(0);
    const float *inRight = input(1);

    // DC first, then limit. A blocked offset would otherwise eat the limiter's headroom
    // while being inaudible itself.
    for (int32_t i = 0; i < frames; ++i) {
        left[i] = dcLeft_.Process(inLeft[i] * level_);
        right[i] = dcRight_.Process(inRight[i] * level_);
    }
    // DaisySP's Limiter multiplies everything by a fixed 0.7 whether it is loud or not,
    // which is seven decibels given away before any limiting has happened -- a fader,
    // not a limiter. Compensating that in pre_gain makes the stage transparent below
    // threshold and leaves it to act only where it is meant to. It also brings the knee
    // in at about 0.7 rather than 1.0, so the saturation stays gentle.
    constexpr float kMakeUp = 1.0f / 0.7f;
    limitLeft_.ProcessBlock(left, static_cast<size_t>(frames), kMakeUp);
    limitRight_.ProcessBlock(right, static_cast<size_t>(frames), kMakeUp);
}

void OutNode::setParam(int32_t index, float value) {
    if (index == 0) level_ = clampf(value, 0.0f, 2.0f);
}

// ---------------------------------------------------------------- In

void InNode::process(int32_t frames) {
    float *left = out(0);
    float *right = out(1);

    if (source_ == nullptr) {
        std::memset(left, 0, static_cast<size_t>(frames) * sizeof(float));
        std::memset(right, 0, static_cast<size_t>(frames) * sizeof(float));
        return;
    }

    // The device microphone is mono, so both rails carry the same signal. Spreading it
    // would be inventing a stereo image that is not there.
    for (int32_t i = 0; i < frames; ++i) {
        const float sample = source_[i] * gain_;
        left[i] = sample;
        right[i] = sample;
    }
}

void InNode::setParam(int32_t index, float value) {
    if (index == 0) gain_ = clampf(value, 0.0f, 64.0f);
}

// ---------------------------------------------------------------- factory

Node *makeNode(NodeType type) {
    switch (type) {
        case NodeType::Osc: return new OscNode();
        case NodeType::Filter: return new FilterNode();
        case NodeType::Env: return new EnvNode();
        case NodeType::Vca: return new VcaNode();
        case NodeType::Clock: return new ClockNode();
        case NodeType::Steps: return new StepsNode();
        case NodeType::Mix: return new MixNode();
        case NodeType::Out: return new OutNode();
        case NodeType::In: return new InNode();
        default: return new NullNode(1, 1);
    }
}
