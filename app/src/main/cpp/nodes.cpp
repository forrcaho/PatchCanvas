#include "nodes.h"

#include <cmath>
#include <cstring>

namespace {
constexpr double kTwoPi = 6.283185307179586;
} // namespace

void NullNode::process(int32_t frames) {
    for (int32_t port = 0; port < outputs_; ++port) {
        std::memset(out(port), 0, static_cast<size_t>(frames) * sizeof(float));
    }
}

void OscNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    phase_ = 0.0;
}

void OscNode::process(int32_t frames) {
    float *o = out(0);
    const float *pitch = input(0);
    const float *fm = input(1);

    for (int32_t i = 0; i < frames; ++i) {
        // 1V/oct in the Eurorack sense, with 0 meaning the base frequency. Phase 4
        // gives this a real parameter rather than a constant.
        const double hz = baseHz_ * std::exp2(static_cast<double>(pitch[i])) +
                          static_cast<double>(fm[i]) * 100.0;
        phase_ += hz * kTwoPi / sampleRate_;
        if (phase_ >= kTwoPi) phase_ -= kTwoPi;
        o[i] = static_cast<float>(std::sin(phase_));
    }
}

void FilterNode::process(int32_t frames) {
    float *o = out(0);
    const float *in = input(0);
    const float *cutoff = input(1);

    for (int32_t i = 0; i < frames; ++i) {
        // Coefficient from a normalised cutoff control; clamped so the filter cannot be
        // driven unstable by a cable carrying something unexpected.
        float coeff = 0.15f + cutoff[i] * 0.5f;
        if (coeff < 0.001f) coeff = 0.001f;
        if (coeff > 0.999f) coeff = 0.999f;
        state_ += (in[i] - state_) * coeff;
        o[i] = state_;
    }
}

void OutNode::process(int32_t frames) {
    std::memcpy(out(0), input(0), static_cast<size_t>(frames) * sizeof(float));
    std::memcpy(out(1), input(1), static_cast<size_t>(frames) * sizeof(float));
}

Node *makeNode(NodeType type) {
    switch (type) {
        case NodeType::Osc: return new OscNode();
        case NodeType::Filter: return new FilterNode();
        case NodeType::Out: return new OutNode();
        case NodeType::Env: return new NullNode(1, 1);
        case NodeType::Steps: return new NullNode(1, 2);
        case NodeType::In: return new NullNode(0, 2);
        default: return new NullNode(1, 1);
    }
}
