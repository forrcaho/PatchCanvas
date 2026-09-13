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

/** 120bpm at 48k: 24000 frames a beat, so the default 1/8 step is 12000. */
constexpr double kBeatsPerFrame = 120.0 / 60.0 / kRate;

/** Delivers one tick the way the graph does -- timing, then the tick, then the block. */
void tickAt(StepsNode &steps, int64_t count, int32_t offset = 0,
            const ScaleList *scales = nullptr) {
    steps.setTiming(kBeatsPerFrame, true, scales);
    steps.tick(offset, count);
    steps.process(kBlockSize);
}

/** Renders blocks with no tick in them, running or stopped. */
void idle(StepsNode &steps, int blocks, bool running = true) {
    for (int i = 0; i < blocks; ++i) {
        steps.setTiming(running ? kBeatsPerFrame : 0.0, running);
        steps.process(kBlockSize);
    }
}

void stepsTakeTheirStepFromTheCount() {
    std::printf("steps take their step from the count\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setParam(0, 4.0f);

    // -1 is what keeps a sequencer the transport has never reached from drawing a playhead.
    check(steps.position() == -1, "no step before the first tick");

    tickAt(steps, 0);
    check(steps.position() == 0, "count 0 is step 0");
    tickAt(steps, 6);
    check(steps.position() == 2, "the count wraps at the loop length, got " +
                                         std::to_string(steps.position()));
    // Where the transport is, not one more than last time: after a reset or a skip the
    // step is whatever the position names.
    tickAt(steps, 3);
    check(steps.position() == 3, "a count out of sequence lands where it says");
}

void stepsPlayTheirOwnPattern() {
    std::printf("steps play the pattern they are given\n");
    StepsNode steps;
    steps.prepare(kRate);

    // One octave up on step 1, which no default pattern contains.
    steps.setStep(1, 12, true);
    tickAt(steps, 1);
    check(std::fabs(steps.output(0)[0] - 1.0f) < 0.0001f,
          "the pitch written to a step is the pitch it plays");

    // Out of range in both directions must be ignored rather than corrupt a neighbour.
    steps.setStep(-1, 108, true);
    steps.setStep(StepsNode::kSteps, 108, true);
    check(std::fabs(steps.output(0)[0] - 1.0f) < 0.0001f,
          "an out-of-range step changes nothing");
}

void aClosedGateIsARestNotASkip() {
    std::printf("a closed gate is a rest, not a skip\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(0, 6, true);
    steps.setStep(1, 3, false);  // a rest, remembering a pitch of its own
    steps.setStep(2, 9, true);

    tickAt(steps, 0);
    tickAt(steps, 1);                // the rest
    check(steps.output(1)[0] == 0.0f, "a closed step emits no gate");
    // The remembered degree exists so switching the step back on restores what was
    // there. It is not a note, nobody can see it, and emitting it makes the pitch jump
    // for no visible reason -- so the output holds whatever last actually sounded.
    check(std::fabs(steps.output(0)[0] - 0.5f) < 0.0001f,
          "and the pitch holds the last note rather than the rest's own");

    tickAt(steps, 2);
    check(steps.output(1)[0] == 1.0f, "the next open step still fires");
    check(std::fabs(steps.output(0)[0] - 0.75f) < 0.0001f,
          "so a rest costs a step rather than being skipped");
}

/**
 * The other half of holding: a rest keeps the degree it remembers, so switching it back
 * on restores the note that was there rather than an empty step the user has to refill.
 */
void aRestKeepsTheNoteItRemembers() {
    std::printf("a rest keeps the note it remembers\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setParam(0, 2.0f);          // a two-step loop, so step 1 comes round quickly
    steps.setStep(0, 6, true);
    steps.setStep(1, 3, false);

    tickAt(steps, 0);
    tickAt(steps, 1);                 // the rest
    check(std::fabs(steps.output(0)[0] - 0.5f) < 0.0001f, "held while it is a rest");

    steps.setStep(1, 3, true);        // switch it back on
    tickAt(steps, 2);                 // step 0
    tickAt(steps, 3);                 // step 1, now sounding
    check(steps.output(1)[0] == 1.0f, "it fires once it is open again");
    check(std::fabs(steps.output(0)[0] - 0.25f) < 0.0001f,
          "with the pitch it was holding on to all along");
}

void aNoteLastsHalfItsStep() {
    std::printf("a note lasts half its step\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(0, 0, true);

    // The default 1/8 at 120bpm is 12000 frames, so the gate is open for frames 0-5999.
    tickAt(steps, 0);
    check(steps.output(1)[0] == 1.0f, "open as the step starts");
    idle(steps, 185);                 // through frame 5951
    check(steps.output(1)[kBlockSize - 1] == 1.0f, "still open just short of half the step");
    idle(steps, 2);                   // through frame 6015
    check(steps.output(1)[kBlockSize - 1] == 0.0f, "closed by half the step");
}

/**
 * Stopping the transport stops time, not just the ticks. A gate that ran out while
 * stopped would cut a note short at the moment the output was switched off, and resume
 * on a different note than the one that was playing.
 */
void aStoppedTransportHoldsTheNote() {
    std::printf("a stopped transport holds the note\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(0, 0, true);

    tickAt(steps, 0);
    idle(steps, 400, false);          // 12800 frames, twice the gate, stopped
    check(steps.output(1)[kBlockSize - 1] == 1.0f, "the gate does not run out while stopped");
    idle(steps, 200, true);
    check(steps.output(1)[kBlockSize - 1] == 0.0f, "and does once time moves again");
}

void aTickLandsOnItsOwnSample() {
    std::printf("a tick lands on its own sample\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(0, 0, true);
    steps.setStep(1, 12, true);

    tickAt(steps, 0);
    idle(steps, 200);                 // well past the first note's gate

    // Inside the block rather than at its start: the transport knows the frame, and a
    // sequencer that rounded to the block would be up to 32 frames late on every note.
    tickAt(steps, 1, 10);
    const float *gate = steps.output(1);
    const float *pitch = steps.output(0);
    check(gate[9] == 0.0f && gate[10] == 1.0f, "the gate opens on the tick's sample");
    check(std::fabs(pitch[9]) < 0.0001f && std::fabs(pitch[10] - 1.0f) < 0.0001f,
          "and the pitch moves on the same sample");
}

/** Four beats of 12-TET, then four of diatonic major. Built as the JNI bridge builds one. */
const ScaleList &chromaticThenMajor() {
    static ScaleList list = [] {
        ScaleList l;
        l.count = 2;
        l.tables[0].size = 12;
        for (int32_t i = 0; i < 12; ++i) l.tables[0].octaves[i] = static_cast<float>(i) / 12.0f;
        constexpr int32_t major[7] = {0, 2, 4, 5, 7, 9, 11};
        l.tables[1].size = 7;
        for (int32_t i = 0; i < 7; ++i) l.tables[1].octaves[i] = static_cast<float>(major[i]) / 12.0f;
        l.beats[0] = 4;
        l.beats[1] = 4;
        l.finish();
        return l;
    }();
    return list;
}

/**
 * A note takes the scale of the beat it starts on. At the default 1/8, count 7 is beat
 * 3.5 -- the last eighth in 12-TET -- and count 8 is beat 4, the first in major, where
 * degree 2 is a whole tone rather than a semitone.
 */
void aNoteTakesTheScaleOfTheBeatItStartsOn() {
    std::printf("a note takes the scale of the beat it starts on\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setParam(0, 16.0f);
    steps.setStep(7, 2, true);
    steps.setStep(8, 2, true);

    tickAt(steps, 7, 0, &chromaticThenMajor());
    check(std::fabs(steps.output(0)[0] - 2.0f / 12.0f) < 0.0001f, "the eighth before the switch is 12-TET");
    tickAt(steps, 8, 0, &chromaticThenMajor());
    check(std::fabs(steps.output(0)[0] - 4.0f / 12.0f) < 0.0001f, "the eighth on the switch beat is major");
}

/**
 * A held note keeps the scale it started in. Step 8 is a rest in major; the pitch holds
 * step 7's note, and must hold it as it was played -- in 12-TET -- not re-read in major.
 */
void aNoteHeldThroughASwitchKeepsItsPitch() {
    std::printf("a note held through a switch keeps its pitch\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setParam(0, 16.0f);
    steps.setStep(7, 2, true);
    steps.setStep(8, 5, false);

    tickAt(steps, 7, 0, &chromaticThenMajor());
    tickAt(steps, 8, 0, &chromaticThenMajor());
    check(std::fabs(steps.output(0)[0] - 2.0f / 12.0f) < 0.0001f,
          "the held pitch is still the 12-TET one after the switch");
    // And a block later, when the pitch is worked out afresh from what was stored at the
    // tick -- a rest that overwrote the stored beat would only be heard from here on.
    steps.setTiming(kBeatsPerFrame, true, &chromaticThenMajor());
    steps.process(kBlockSize);
    check(std::fabs(steps.output(0)[kBlockSize - 1] - 2.0f / 12.0f) < 0.0001f,
          "and still the 12-TET one a block after that");
}

/**
 * The case rounding would get wrong: an eighth-note triplet on the switch beat. Count 12
 * of 1/3 is exactly beat 4, which floating arithmetic could leave a hair short of 4 --
 * so it is worked out in integers, and must be major.
 */
void aTripletOnTheSwitchBeatTakesTheNewScale() {
    std::printf("a triplet on the switch beat takes the new scale\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setParam(0, 16.0f);
    steps.setParam(2, 7.0f); // 1/8 triplet
    steps.setStep(11, 2, true);
    steps.setStep(12, 2, true);

    tickAt(steps, 11, 0, &chromaticThenMajor());
    check(std::fabs(steps.output(0)[0] - 2.0f / 12.0f) < 0.0001f, "the triplet before beat 4 is 12-TET");
    tickAt(steps, 12, 0, &chromaticThenMajor());
    check(std::fabs(steps.output(0)[0] - 4.0f / 12.0f) < 0.0001f, "the triplet on beat 4 is major");
}

void theIntervalIsChosenByParameter() {
    std::printf("the interval is chosen by parameter\n");
    StepsNode steps;
    steps.prepare(kRate);

    const Interval initial = steps.interval();
    check(initial.num == kIntervals[kDefaultInterval].num &&
          initial.den == kIntervals[kDefaultInterval].den, "starts on the default interval");

    steps.setParam(2, 4.0f);
    check(steps.interval().num == 1 && steps.interval().den == 4, "index 4 is a sixteenth");

    steps.setParam(2, 99.0f);
    const Interval last = kIntervals[kIntervalCount - 1];
    check(steps.interval().num == last.num && steps.interval().den == last.den,
          "an out-of-range choice clamps rather than reading past the table");
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
    stepsTakeTheirStepFromTheCount();
    stepsPlayTheirOwnPattern();
    aClosedGateIsARestNotASkip();
    aRestKeepsTheNoteItRemembers();
    aNoteLastsHalfItsStep();
    aStoppedTransportHoldsTheNote();
    aTickLandsOnItsOwnSample();
    theIntervalIsChosenByParameter();
    aNoteTakesTheScaleOfTheBeatItStartsOn();
    aNoteHeldThroughASwitchKeepsItsPitch();
    aTripletOnTheSwitchBeatTakesTheNewScale();
    mixSumsRatherThanAverages();
    outPassesAudioAtLevel();
    outProtectsTheListener();
    return testing::report("nodes");
}
