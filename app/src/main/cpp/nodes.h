#pragma once

#include <cstdint>

#include "node.h"

#include "adsr.h"
#include "dcblock.h"
#include "limiter.h"
#include "oscillator.h"
#include "svf.h"

/**
 * Node types, mirrored by NodeType.kt. The numbering is part of the JNI contract, so
 * append rather than reorder.
 */
enum class NodeType : int32_t {
    Unknown = 0,
    Osc = 1,
    Filter = 2,
    Env = 3,
    Steps = 4,
    Out = 5,
    In = 6,
    Vca = 7,
    Clock = 8,
    Mix = 9,
};

/**
 * Pitch is 1V/oct in the Eurorack sense, expressed in octaves: 0 is middle C, 1.0 is an
 * octave up. Using octaves rather than volts keeps the arithmetic to exp2 and avoids
 * pretending there is a voltage anywhere in here.
 */
constexpr float kMiddleC = 261.6256f;

/** Anything not yet implemented: right shape, silent. */
class NullNode : public Node {
public:
    NullNode(int32_t inputs, int32_t outputs) : inputs_(inputs), outputs_(outputs) {}
    int32_t inputCount() const override { return inputs_; }
    int32_t outputCount() const override { return outputs_; }
    void process(int32_t frames) override;

private:
    int32_t inputs_;
    int32_t outputs_;
};

/** Band-limited oscillator. A naive saw aliases audibly; PolyBLEP does not. */
class OscNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // pitch, fm
    int32_t outputCount() const override { return 1; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    daisysp::Oscillator osc_;
    float tuneSemitones_ = 0.0f;
};

/** State-variable filter, lowpass tap. */
class FilterNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // in, cutoff
    int32_t outputCount() const override { return 1; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    daisysp::Svf svf_;
    float cutoffHz_ = 1000.0f;
};

class EnvNode : public Node {
public:
    int32_t inputCount() const override { return 1; }  // gate
    int32_t outputCount() const override { return 1; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    daisysp::Adsr adsr_;
};

/** Amplitude under control voltage. Closed with no CV, as hardware is. */
class VcaNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // in, cv
    int32_t outputCount() const override { return 1; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    /** Added to the control voltage, so a VCA with nothing patched can still be open. */
    float bias_ = 0.0f;
};

/**
 * The clock, and the reason the sequencer lives down here at all.
 *
 * It counts frames. That makes it sample-accurate by construction and gives it exactly
 * the stability of the audio device's own crystal -- which is the most stable thing
 * available. A tick originating from a Handler or a coroutine would jitter by up to a
 * buffer no matter what sat underneath it.
 */
class ClockNode : public Node {
public:
    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 1; } // gate
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    int64_t counter_ = 0;
    int64_t period_ = 24000;
    float bpm_ = 120.0f;
};

/** A sequence, advanced by a rising edge on its clock input. */
class StepsNode : public Node {
public:
    static constexpr int32_t kSteps = 16;

    StepsNode();

    int32_t inputCount() const override { return 1; }  // clock
    int32_t outputCount() const override { return 2; } // pitch, gate
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void setStep(int32_t index, float pitch, bool gate) override;
    int32_t position() const override { return step_; }

private:
    int32_t step_ = 0;
    int32_t length_ = 8;
    float transpose_ = 0.0f;
    bool wasHigh_ = false;
    /** Octaves from the root, which is what every pitch on this boundary means. */
    float pitch_[kSteps] = {};
    bool gate_[kSteps] = {};
};

/** Sums its inputs. Necessary because an input takes exactly one source. */
class MixNode : public Node {
public:
    int32_t inputCount() const override { return 4; }
    int32_t outputCount() const override { return 1; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    float level_[4] = {1.0f, 1.0f, 1.0f, 1.0f};
};

/**
 * The sink. DC blocked and limited, in that order.
 *
 * The limiter is not polish: a feedback patch reaches full scale instantly, and this is
 * an instrument played on headphones.
 */
class OutNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // L, R
    int32_t outputCount() const override { return 2; } // mirrored, for the engine to read
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    float level_ = 1.0f;
    daisysp::DcBlock dcLeft_;
    daisysp::DcBlock dcRight_;
    daisysp::Limiter limitLeft_;
    daisysp::Limiter limitRight_;
};

/**
 * The live microphone, pinned left.
 *
 * Its source is handed to it by the engine each block rather than read here: the input
 * stream is drained once per callback, and a node has no business knowing about streams.
 * With no source it is silent, which is what the rail does when the mic is switched off.
 */
class InNode : public Node {
public:
    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 2; } // L, R
    void process(int32_t frames) override;

    /** Audio thread, before process(). Null means silence. */
    void setSource(const float *mono) { source_ = mono; }
    void setParam(int32_t index, float value) override;

private:
    const float *source_ = nullptr;
    /**
     * Unprocessed input is raw by request, so a phone mic at talking distance arrives
     * far below what an oscillator produces. This was a constant; it is a knob because
     * the right amount depends on the room.
     */
    float gain_ = 8.0f;
};

/** Allocates a node for a type. Never called on the audio thread. */
Node *makeNode(NodeType type);
