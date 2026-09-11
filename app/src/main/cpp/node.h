#pragma once

#include <array>
#include <cstdint>

/** Frames processed per inner block. 96-frame bursts divide by this exactly. */
constexpr int32_t kBlockSize = 32;
constexpr int32_t kMaxPorts = 4;

/**
 * A graph node.
 *
 * Outputs are owned buffers; inputs are borrowed pointers into whatever upstream node
 * produced them, rewired by the graph before each block. Nothing here allocates after
 * construction, and construction never happens on the audio thread.
 *
 * Output buffers are deliberately NOT cleared between blocks. That is what makes a
 * feedback edge cost exactly one block of delay: a node whose source is evaluated after
 * it simply reads the buffer the source left behind last time.
 */
class Node {
public:
    virtual ~Node() = default;

    virtual int32_t inputCount() const = 0;
    virtual int32_t outputCount() const = 0;

    virtual void prepare(int32_t sampleRate) { sampleRate_ = sampleRate; }
    virtual void process(int32_t frames) = 0;

    void setInput(int32_t port, const float *buffer) { inputs_[port] = buffer; }
    const float *output(int32_t port) const { return outputs_[port].data(); }

protected:
    const float *input(int32_t port) const { return inputs_[port]; }
    float *out(int32_t port) { return outputs_[port].data(); }

    int32_t sampleRate_ = 48000;

private:
    std::array<const float *, kMaxPorts> inputs_{};
    std::array<std::array<float, kBlockSize>, kMaxPorts> outputs_{};
};
