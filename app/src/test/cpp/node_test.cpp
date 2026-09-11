// Host-side tests for the individual DSP nodes.
//
// Driven directly rather than through a Graph: these are claims about what each module
// does, and a failure should say which module rather than which patch.

#include <array>
#include <cmath>
#include <string>
#include <vector>
#include <cstdio>

#include "nodes.h"
#include "test_support.h"

using testing::check;

namespace {

constexpr int32_t kRate = 48000;

std::array<float, kBlockSize> constantBuffer(float value) {
    std::array<float, kBlockSize> buffer{};
    buffer.fill(value);
    return buffer;
}

/** Runs a node for `blocks` blocks and returns everything it produced on one output. */
std::vector<float> run(Node &node, int blocks, int32_t port = 0) {
    std::vector<float> all;
    for (int b = 0; b < blocks; ++b) {
        node.process(kBlockSize);
        const float *o = node.output(port);
        all.insert(all.end(), o, o + kBlockSize);
    }
    return all;
}

/**
 * Cycles per window, counted as zero crossings in one direction.
 *
 * Not by looking for the waveform's reset: polyBLEP deliberately smears that edge across
 * several samples, so a threshold low enough to catch every reset counts each one twice
 * and a threshold high enough to count each once misses some entirely. Measured here,
 * the same signal gave 493, 411, 313 and 259 cycles depending only on where the
 * threshold was put. A descending ramp crosses zero exactly once per cycle on its way
 * down -- the reset crosses the other way -- so this is unambiguous.
 */
int countCycles(const std::vector<float> &samples) {
    int cycles = 0;
    for (std::size_t i = 1; i < samples.size(); ++i) {
        if (samples[i - 1] > 0.0f && samples[i] <= 0.0f) ++cycles;
    }
    return cycles;
}

/** Sample indices where a gate goes high, for measuring a period rather than a count. */
std::vector<std::size_t> risingEdgeIndices(const std::vector<float> &samples) {
    std::vector<std::size_t> at;
    for (std::size_t i = 1; i < samples.size(); ++i) {
        if (samples[i - 1] <= 0.5f && samples[i] > 0.5f) at.push_back(i);
    }
    return at;
}

float peak(const std::vector<float> &samples) {
    float worst = 0.0f;
    for (float s : samples) worst = std::max(worst, std::fabs(s));
    return worst;
}

// ---------------------------------------------------------------------------

void oscPlaysTheRequestedPitch() {
    std::printf("osc plays the requested pitch\n");
    const auto zero = constantBuffer(0.0f);
    const auto oneOctave = constantBuffer(1.0f);

    OscNode osc;
    osc.prepare(kRate);
    osc.setInput(0, zero.data());
    osc.setInput(1, zero.data());
    const auto atZero = run(osc, kRate / kBlockSize); // one second

    // Pitch 0 is middle C by definition, and one second of it should contain that many
    // cycles. Counting the waveform's own resets measures the frequency the oscillator
    // actually produced rather than the one it was told.
    const int cycles = countCycles(atZero);
    check(std::abs(cycles - 262) <= 2, "pitch 0 is middle C, got " + std::to_string(cycles));

    OscNode up;
    up.prepare(kRate);
    up.setInput(0, oneOctave.data());
    up.setInput(1, zero.data());
    const int doubled = countCycles(run(up, kRate / kBlockSize));
    check(std::abs(doubled - 523) <= 4, "pitch 1.0 is an octave up, got " + std::to_string(doubled));
}

void oscStaysBandLimited() {
    std::printf("osc stays band limited\n");
    const auto zero = constantBuffer(0.0f);
    // High enough that a naive saw would alias badly.
    const auto high = constantBuffer(4.0f); // ~4.2kHz

    OscNode osc;
    osc.prepare(kRate);
    osc.setInput(0, high.data());
    osc.setInput(1, zero.data());
    const auto samples = run(osc, 64);

    // Both halves matter: silence would satisfy the ceiling on its own.
    check(peak(samples) > 0.5f, "actually produces a signal");
    check(peak(samples) <= 1.05f, "stays inside full scale");
    for (float s : samples) {
        if (!std::isfinite(s)) {
            check(false, "produced a non-finite sample");
            return;
        }
    }
    check(true, "all samples finite");
}

void envFollowsItsGate() {
    std::printf("env follows its gate\n");
    const auto open = constantBuffer(1.0f);
    const auto shut = constantBuffer(0.0f);

    EnvNode env;
    env.prepare(kRate);
    env.setInput(0, open.data());
    const auto held = run(env, 64); // ~43ms, past attack and into decay
    check(peak(held) > 0.5f, "opens while gated");

    env.setInput(0, shut.data());
    // The release is 250ms but the tail is exponential, so "closed" takes considerably
    // longer than the nominal time: measured, it is still at 0.05 after 1024 blocks.
    const auto released = run(env, 2048); // about 1.4s

    check(std::fabs(released.back()) < 0.02f, "closes once the gate goes");
}

void vcaIsShutWithoutControl() {
    std::printf("vca is shut without control\n");
    const auto signal = constantBuffer(1.0f);
    const auto none = constantBuffer(0.0f);
    const auto full = constantBuffer(1.0f);

    VcaNode vca;
    vca.prepare(kRate);
    vca.setInput(0, signal.data());
    vca.setInput(1, none.data());
    check(peak(run(vca, 4)) == 0.0f, "closed with no CV, as hardware is");

    vca.setInput(1, full.data());
    check(std::fabs(peak(run(vca, 4)) - 1.0f) < 0.001f, "open at full CV");
}

void clockRunsAtTheRequestedTempo() {
    std::printf("clock runs at the requested tempo\n");
    ClockNode clock;
    clock.prepare(kRate);
    const auto ticks = run(clock, 4 * kRate / kBlockSize); // four seconds

    // The interval, not the count: the clock starts its first beat on sample zero, which
    // is a tick but not an edge, so counting edges undercounts by one and says nothing
    // about regularity anyway.
    const auto edges = risingEdgeIndices(ticks);
    check(edges.size() >= 3, "it ticks at all");
    if (edges.size() >= 3) {
        bool even = true;
        for (std::size_t i = 2; i < edges.size(); ++i) {
            if (edges[i] - edges[i - 1] != edges[1] - edges[0]) even = false;
        }
        const auto period = edges[1] - edges[0];
        // 120bpm at 48k is 24000 frames a beat. Because it counts frames rather than
        // consulting a timer this is exact, not approximate -- so assert it exactly.
        check(period == 24000, "24000 frames a beat, got " + std::to_string(period));
        check(even, "and every beat the same length");
    }
}

void stepsAdvanceOnEdgesNotLevels() {
    std::printf("steps advance on edges, not levels\n");
    const auto high = constantBuffer(1.0f);
    const auto low = constantBuffer(0.0f);

    StepsNode steps;
    steps.prepare(kRate);

    steps.setInput(0, low.data());
    steps.process(kBlockSize);
    const float first = steps.output(0)[0];

    // Held high for many blocks: a level-triggered sequencer would race through its
    // pattern here, an edge-triggered one moves exactly once.
    steps.setInput(0, high.data());
    for (int i = 0; i < 32; ++i) steps.process(kBlockSize);
    const float afterHold = steps.output(0)[0];
    check(afterHold != first, "a rising edge advances it");

    for (int i = 0; i < 32; ++i) steps.process(kBlockSize);
    check(steps.output(0)[0] == afterHold, "holding the gate does not advance it again");

    steps.setInput(0, low.data());
    steps.process(kBlockSize);
    steps.setInput(0, high.data());
    steps.process(kBlockSize);
    check(steps.output(0)[0] != afterHold, "the next edge advances it again");
}

void mixSumsRatherThanAverages() {
    std::printf("mix sums rather than averages\n");
    const auto quarter = constantBuffer(0.25f);
    const auto silence = constantBuffer(0.0f);

    MixNode mix;
    mix.prepare(kRate);
    mix.setInput(0, quarter.data());
    mix.setInput(1, quarter.data());
    mix.setInput(2, quarter.data());
    mix.setInput(3, quarter.data());
    check(std::fabs(peak(run(mix, 2)) - 1.0f) < 0.001f, "four quarters make one");

    mix.setInput(2, silence.data());
    mix.setInput(3, silence.data());
    // Averaging would drop this to 0.125 and make a patch quieter for having spare
    // inputs, which is not how a mixer behaves.
    check(std::fabs(peak(run(mix, 2)) - 0.5f) < 0.001f, "unused inputs cost nothing");
}

void outProtectsTheListener() {
    std::printf("out protects the listener\n");
    const auto loud = constantBuffer(4.0f);

    OutNode out;
    out.prepare(kRate);
    out.setInput(0, loud.data());
    out.setInput(1, loud.data());
    // DaisySP's DcBlock uses gain = 1 - 10/sampleRate, which is a ~100ms time constant,
    // so this needs far longer than it looks. Measured: still 0.09 after 256 blocks.
    const auto limited = run(out, 1024);

    // A feedback patch reaches full scale instantly, and this is played on headphones.
    check(peak(limited) < 1.5f, "four times full scale is brought back under control");

    // And the DC blocker means a constant input does not sit there as an offset eating
    // the limiter's headroom.
    check(std::fabs(limited.back()) < 0.05f, "a constant settles to nothing");
}

} // namespace

int main() {
    oscPlaysTheRequestedPitch();
    oscStaysBandLimited();
    envFollowsItsGate();
    vcaIsShutWithoutControl();
    clockRunsAtTheRequestedTempo();
    stepsAdvanceOnEdgesNotLevels();
    mixSumsRatherThanAverages();
    outProtectsTheListener();
    return testing::report("nodes");
}
