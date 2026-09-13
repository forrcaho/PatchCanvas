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
    // 8 was Clock, retired when the transport replaced it. Left unused rather than
    // reassigned, so nothing can mistake an old id for a new module.
    Mix = 9,
};

/**
 * The note lengths a clocked module can step at. Mirrored by INTERVALS in
 * PatchCanvas.kt and indexed by the parameter that chooses one, so append rather than
 * reorder.
 */
constexpr Interval kIntervals[] = {
        {4, 1}, // 1/1
        {2, 1}, // 1/2
        {1, 1}, // 1/4
        {1, 2}, // 1/8
        {1, 4}, // 1/16
        {1, 8}, // 1/32
        {2, 3}, // 1/4 triplet
        {1, 3}, // 1/8 triplet
        {1, 6}, // 1/16 triplet
};
constexpr int32_t kIntervalCount = static_cast<int32_t>(sizeof(kIntervals) / sizeof(kIntervals[0]));
constexpr int32_t kDefaultInterval = 3; // 1/8

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
    /** Cents, so a tuning with no semitone in it is still expressible. */
    float tuneCents_ = 0.0f;
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
 * A sequence, stepped by the transport at the interval it is set to.
 *
 * It has no clock input. Its step is the transport's count of intervals, modulo the loop
 * length, so two sequencers at different intervals cannot drift apart and resetting the
 * transport puts every one of them back on its first step.
 */
class StepsNode : public Node {
public:
    static constexpr int32_t kSteps = 16;

    StepsNode();

    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 2; } // pitch, gate
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void setStep(int32_t index, int32_t degree, bool gate) override;
    int32_t position() const override { return step_; }
    Interval interval() const override { return kIntervals[intervalIndex_]; }
    void tick(int32_t offset, int64_t count) override;

private:
    /** The sounding note's pitch: its degree, in the scale of the beat it started on. */
    float voicedOctaves() const;

    /** More than one interval boundary per block would need an interval under a millisecond. */
    static constexpr int32_t kMaxPending = 4;

    Tick pending_[kMaxPending] = {};
    int32_t pendingCount_ = 0;

    /** -1 until the first tick, which is what keeps a stopped sequencer from drawing a playhead. */
    int32_t step_ = -1;
    /** The last step that actually sounded; what the pitch output holds through a rest. */
    int32_t voiced_ = 0;
    /**
     * The whole beat that note started on. Kept, not recomputed, so a note held through a
     * scale change keeps the scale it started in.
     */
    int64_t voicedBeat_ = 0;
    int32_t length_ = 8;
    int32_t intervalIndex_ = kDefaultInterval;
    /** Frames of gate left on the note that last started. Counts only while the transport runs. */
    int64_t gateRemaining_ = 0;
    /** Cents. See OscNode::tuneCents_. */
    float transposeCents_ = 0.0f;
    /** Degrees of the patch's scale; which scale is decided when each note starts. */
    int32_t degree_[kSteps] = {};
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
