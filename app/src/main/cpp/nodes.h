#pragma once

#include <cstdint>

#include "node.h"

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
};

/** Anything not yet implemented: right shape, silent. Phase 4 replaces these. */
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

/** Naive sine. Phase 4 swaps in DaisySP's PolyBLEP oscillator. */
class OscNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // pitch, fm
    int32_t outputCount() const override { return 1; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;

private:
    double phase_ = 0.0;
    double baseHz_ = 220.0;
};

/** One-pole lowpass, enough to prove ordering. Phase 4 swaps in DaisySP's Svf. */
class FilterNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // in, cutoff
    int32_t outputCount() const override { return 1; }
    void process(int32_t frames) override;

private:
    float state_ = 0.0f;
};

/** The sink. Its inputs are what the engine hands to the stream. */
class OutNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // L, R
    int32_t outputCount() const override { return 2; } // mirrored, so the engine can read
    void process(int32_t frames) override;
};

/** Allocates a node for a type. Never called on the audio thread. */
Node *makeNode(NodeType type);
