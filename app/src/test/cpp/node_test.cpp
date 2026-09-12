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

void filterTracksCutoffAtAudioRate() {
    std::printf("filter tracks cutoff at audio rate\n");

    // A cutoff that alternates every sample. Its value at index 0 is the same in every
    // block, so a filter reading cutoff[0] once per block cannot tell this apart from a
    // constant -- which is exactly the bug this guards.
    std::array<float, kBlockSize> alternating{};
    for (int32_t i = 0; i < kBlockSize; ++i) alternating[i] = (i % 2 == 0) ? 1.0f : -1.0f;
    const auto constant = constantBuffer(1.0f);

    // Something with content to filter.
    std::array<float, kBlockSize> noise{};
    unsigned seed = 22222;
    for (int32_t i = 0; i < kBlockSize; ++i) {
        seed = seed * 1664525u + 1013904223u;
        noise[i] = static_cast<float>(seed >> 8 & 0xFFFF) / 32768.0f - 1.0f;
    }

    FilterNode modulated;
    modulated.prepare(kRate);
    modulated.setInput(0, noise.data());
    modulated.setInput(1, alternating.data());
    const auto varying = run(modulated, 32);

    FilterNode steady;
    steady.prepare(kRate);
    steady.setInput(0, noise.data());
    steady.setInput(1, constant.data());
    const auto fixed = run(steady, 32);

    double difference = 0.0;
    for (std::size_t i = 0; i < varying.size(); ++i) {
        difference += std::fabs(varying[i] - fixed[i]);
    }
    check(difference > 1.0, "audio-rate cutoff modulation actually reaches the filter");
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

/** Clocks the node once: low, then high, holding each long enough to be seen. */
static void tick(StepsNode &steps, const std::array<float, kBlockSize> &low,
                 const std::array<float, kBlockSize> &high) {
    steps.setInput(0, low.data());
    steps.process(kBlockSize);
    steps.setInput(0, high.data());
    steps.process(kBlockSize);
}

void stepsPlayTheirOwnPattern() {
    std::printf("steps play the pattern they are given\n");
    const auto high = constantBuffer(1.0f);
    const auto low = constantBuffer(0.0f);

    StepsNode steps;
    steps.prepare(kRate);

    // One octave up on step 1, which no default pattern contains.
    steps.setStep(1, 1.0f, true);
    tick(steps, low, high);   // -> step 1
    check(std::fabs(steps.output(0)[0] - 1.0f) < 0.0001f,
          "the pitch written to a step is the pitch it plays");

    // Out of range in both directions must be ignored rather than corrupt a neighbour.
    steps.setStep(-1, 9.0f, true);
    steps.setStep(StepsNode::kSteps, 9.0f, true);
    check(std::fabs(steps.output(0)[0] - 1.0f) < 0.0001f,
          "an out-of-range step changes nothing");
}

void aClosedGateIsARestNotASkip() {
    std::printf("a closed gate is a rest, not a skip\n");
    const auto high = constantBuffer(1.0f);
    const auto low = constantBuffer(0.0f);

    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(1, 0.25f, false);  // silent
    steps.setStep(2, 0.75f, true);

    tick(steps, low, high);          // -> step 1, gated off
    check(steps.output(1)[0] == 0.0f, "a closed step emits no gate");
    check(std::fabs(steps.output(0)[0] - 0.25f) < 0.0001f,
          "but its pitch is still on the output");

    tick(steps, low, high);          // -> step 2
    check(steps.output(1)[0] == 1.0f, "the next open step still fires");
    check(std::fabs(steps.output(0)[0] - 0.75f) < 0.0001f,
          "so a rest costs a step rather than being skipped");
}

void theGateFollowsTheClockNotTheStep() {
    std::printf("the gate follows the clock, not the step\n");
    const auto high = constantBuffer(1.0f);
    const auto low = constantBuffer(0.0f);

    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(1, 0.0f, true);

    tick(steps, low, high);
    check(steps.output(1)[0] == 1.0f, "gate up while the clock is up");
    steps.setInput(0, low.data());
    steps.process(kBlockSize);
    check(steps.output(1)[0] == 0.0f,
          "and down when the clock falls -- the step says whether, the clock says how long");
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

void outPassesAudioAtLevel() {
    std::printf("out passes audio at level\n");

    // The limiter test below only ever fed DC, which the DC blocker removes -- so it
    // proved the stage was safe without proving anything came through it. A quiet signal
    // should arrive essentially unchanged.
    OutNode out;
    out.prepare(kRate);
    std::array<float, kBlockSize> buffer{};
    double phase = 0.0;
    float loudest = 0.0f;

    for (int b = 0; b < 400; ++b) {
        for (int32_t i = 0; i < kBlockSize; ++i) {
            buffer[i] = 0.3f * static_cast<float>(std::sin(phase));
            phase += 2.0 * M_PI * 261.0 / kRate;
        }
        out.setInput(0, buffer.data());
        out.setInput(1, buffer.data());
        out.process(kBlockSize);
        if (b > 200) {
            const float *o = out.output(0);
            for (int32_t i = 0; i < kBlockSize; ++i) loudest = std::max(loudest, std::fabs(o[i]));
        }
    }

    const float ratio = loudest / 0.3f;
    check(ratio > 0.85f, "a quiet signal is not attenuated, ratio " + std::to_string(ratio));
    check(ratio < 1.05f, "nor amplified, ratio " + std::to_string(ratio));
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
    filterTracksCutoffAtAudioRate();
    envFollowsItsGate();
    vcaIsShutWithoutControl();
    clockRunsAtTheRequestedTempo();
    stepsAdvanceOnEdgesNotLevels();
    stepsPlayTheirOwnPattern();
    aClosedGateIsARestNotASkip();
    theGateFollowsTheClockNotTheStep();
    mixSumsRatherThanAverages();
    outPassesAudioAtLevel();
    outProtectsTheListener();
    return testing::report("nodes");
}
