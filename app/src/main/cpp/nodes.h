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

private:
    daisysp::Oscillator osc_;
};

/** State-variable filter, lowpass tap. */
class FilterNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // in, cutoff
    int32_t outputCount() const override { return 1; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;

private:
    daisysp::Svf svf_;
};

class EnvNode : public Node {
public:
    int32_t inputCount() const override { return 1; }  // gate
    int32_t outputCount() const override { return 1; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;

private:
    daisysp::Adsr adsr_;
};

/** Amplitude under control voltage. Closed with no CV, as hardware is. */
class VcaNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // in, cv
    int32_t outputCount() const override { return 1; }
    void process(int32_t frames) override;
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

private:
    int64_t counter_ = 0;
    int64_t period_ = 24000; // 120bpm at 48k, until Phase 5 gives it a parameter
};

/** Eight steps, advanced by a rising edge on its clock input. */
class StepsNode : public Node {
public:
    int32_t inputCount() const override { return 1; }  // clock
    int32_t outputCount() const override { return 2; } // pitch, gate
    void process(int32_t frames) override;

private:
    static constexpr int32_t kSteps = 8;
    int32_t step_ = 0;
    bool wasHigh_ = false;
};

/** Sums its inputs. Necessary because an input takes exactly one source. */
class MixNode : public Node {
public:
    int32_t inputCount() const override { return 4; }
    int32_t outputCount() const override { return 1; }
    void process(int32_t frames) override;
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

private:
    daisysp::DcBlock dcLeft_;
    daisysp::DcBlock dcRight_;
    daisysp::Limiter limitLeft_;
    daisysp::Limiter limitRight_;
};

/** Allocates a node for a type. Never called on the audio thread. */
Node *makeNode(NodeType type);
