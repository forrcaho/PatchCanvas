// Host-side tests for the individual DSP nodes.
//
// Driven directly rather than through a Graph: these are claims about what each module
// does, and a failure should say which module rather than which patch.

#include <algorithm>
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

/** Four beats of 12-TET in C, then four in G. A change of key is a change of entry. */
const ScaleList &cThenG() {
    static ScaleList list = [] {
        ScaleList l;
        l.count = 2;
        for (int32_t t = 0; t < 2; ++t) {
            l.tables[t].size = 12;
            for (int32_t i = 0; i < 12; ++i) l.tables[t].octaves[i] = static_cast<float>(i) / 12.0f;
            l.beats[t] = 4;
        }
        l.tables[1].root = 7.0f / 12.0f;
        l.finish();
        return l;
    }();
    return list;
}

/** The key changes on its beat, and a note held across it keeps the key it started in. */
void aKeyChangeLandsOnItsBeat() {
    std::printf("a key change lands on its beat\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setParam(0, 16.0f);
    steps.setStep(7, 0, true);
    steps.setStep(8, 0, true);

    tickAt(steps, 7, 0, &cThenG());
    check(std::fabs(steps.output(0)[0]) < 0.0001f, "degree 0 before the change is C");
    tickAt(steps, 8, 0, &cThenG());
    check(std::fabs(steps.output(0)[0] - 7.0f / 12.0f) < 0.0001f, "and on the change beat is G");

    StepsNode held;
    held.prepare(kRate);
    held.setParam(0, 16.0f);
    held.setStep(7, 0, true);
    held.setStep(8, 0, false);
    tickAt(held, 7, 0, &cThenG());
    tickAt(held, 8, 0, &cThenG());
    held.setTiming(kBeatsPerFrame, true, &cThenG());
    held.process(kBlockSize);
    check(std::fabs(held.output(0)[kBlockSize - 1]) < 0.0001f,
          "a note held through the change stays in C");
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

// ---------------------------------------------------------------- notes

/** The events one block left behind, for reading what a sequencer said. */
const NoteBuffer &notesOf(const StepsNode &steps) { return *steps.noteOutput(2); }

void theNotesOutputSaysWhatTheGateSays() {
    std::printf("the notes output says what the gate says\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(0, 5, true);

    tickAt(steps, 0);
    check(notesOf(steps).count == 1, "one event on the tick");
    const NoteEvent on = notesOf(steps).events[0];
    check(on.kind == NoteKind::On, "and it is a note starting");
    check(on.offset == 0, "on the tick's own sample");
    check(on.degree == 5, "carrying the step's degree");
    check(on.beat == 0, "and the beat that decides its scale");
    check(on.id != 0, "an id an off can be matched against");

    // The gate is open for frames 0-5999 at the default 1/8 and 120bpm, so nothing more
    // is said until it runs out -- a note is two events, not a stream of them.
    idle(steps, 186); // through frame 5983
    check(notesOf(steps).count == 0, "nothing said while the note is held");

    idle(steps, 1); // frames 5984-6015, where the gate runs out
    check(notesOf(steps).count == 1, "one event as it ends");
    check(notesOf(steps).events[0].kind == NoteKind::Off, "and it is the note ending");
    check(notesOf(steps).events[0].id == on.id, "the same note that started");

    // A beat that is not zero, because zero is also what carrying no beat at all would
    // look like. At the default 1/8 the eighth tick is beat four, worked out in integers
    // from the count -- which is the whole reason the note carries it rather than the
    // voice asking the transport where it is.
    tickAt(steps, 8); // a length of 8 brings this back to step 0, which sounds

    check(notesOf(steps).count == 1, "the ninth eighth starts a note");
    check(notesOf(steps).events[0].beat == 4, "on beat four, counted rather than measured");
}

void aRestStartsNothing() {
    std::printf("a rest starts nothing\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setParam(0, 2.0f); // two steps, so the rest comes round quickly
    steps.setStep(0, 0, true);
    steps.setStep(1, 0, false);

    tickAt(steps, 0);
    check(notesOf(steps).count == 1, "the sounding step starts a note");
    idle(steps, 187); // past the gate, which ends it
    tickAt(steps, 1);
    check(notesOf(steps).count == 0, "and the rest says nothing at all");
}

void aTransposeRidesOnTheNote() {
    std::printf("a transpose rides on the note\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(0, 0, true);
    steps.setParam(1, 700.0f);

    tickAt(steps, 0);
    check(notesOf(steps).count == 1, "the note is there");
    // As cents against the degree rather than folded into it: the degree is a step in a
    // scale, and 700 cents is not a number of steps in any tuning but one.
    check(std::fabs(notesOf(steps).events[0].cents - 700.0f) < 0.01f, "and carries the cents");
    check(notesOf(steps).events[0].degree == 0, "leaving the degree alone");
}

/** Builds a note on, ready to hand to a voice. */
NoteEvent noteOn(uint32_t id, int32_t degree, int32_t source = 0, int64_t beat = 0) {
    NoteEvent event;
    event.id = id;
    event.kind = NoteKind::On;
    event.degree = degree;
    event.source = static_cast<uint8_t>(source);
    event.beat = beat;
    return event;
}

NoteEvent noteOff(uint32_t id, int32_t source = 0) {
    NoteEvent event;
    event.id = id;
    event.kind = NoteKind::Off;
    event.source = static_cast<uint8_t>(source);
    return event;
}

/** Runs the voice for some blocks with nothing new arriving. */
std::vector<float> voiceIdle(VoiceNode &voice, int blocks, const ScaleList *scales = nullptr) {
    static const NoteBuffer empty{};
    voice.setNoteInput(0, &empty);
    voice.setTiming(0.0, false, scales);
    return run(voice, blocks);
}

/**
 * What is left after [blocks], rather than everything that happened during them.
 *
 * A release starts at full amplitude and ends at nothing, so the peak of a window that
 * contains the whole of one says only that the note was once loud. The question is always
 * what is still sounding at the end.
 *
 * Sixteen blocks is 512 frames, which is nearly three cycles of middle C. A shorter tail
 * measures the peak of whatever part of the waveform it happened to land on -- the same
 * held note read 0.65 and 0.14 four blocks apart.
 */
std::vector<float> voiceAfter(VoiceNode &voice, int blocks, const ScaleList *scales = nullptr) {
    voiceIdle(voice, blocks, scales);
    return voiceIdle(voice, 16, scales);
}

void aVoiceSoundsAChordAndLetsItGo() {
    std::printf("a voice sounds a chord and lets it go\n");
    VoiceNode voice;
    voice.prepare(kRate);

    NoteBuffer chord;
    chord.push(noteOn(1, 0));
    chord.push(noteOn(2, 4));
    chord.push(noteOn(3, 7));
    voice.setNoteInput(0, &chord);
    voice.setTiming(0.0, false, nullptr);
    const auto sounding = run(voice, 1);
    check(peak(sounding) > 0.0f, "three notes down one cable sound");

    // Held, because nothing has said otherwise. A sustain that decayed on its own would
    // be a sequencer's note length leaking into the voice.
    check(peak(voiceAfter(voice, 200)) > 0.1f, "and hold until they are told to stop");

    NoteBuffer release;
    release.push(noteOff(1));
    release.push(noteOff(2));
    release.push(noteOff(3));
    voice.setNoteInput(0, &release);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    // 2000 blocks is 1.3 seconds. A 0.25s release is a time constant rather than a
    // duration -- DaisySP's decays towards -0.01 and stops when it crosses zero, which
    // takes about four of them -- so "past the release" is a second, not a quarter of one.
    check(peak(voiceAfter(voice, 2000)) < 0.001f, "then the chord ends");
}

void anIdBelongsToTheSourceThatChoseIt() {
    std::printf("an id belongs to the source that chose it\n");
    VoiceNode voice;
    voice.prepare(kRate);

    // Two sources, both counting from one, which is what every source does: it has no
    // idea it is one of several.
    NoteBuffer both;
    both.push(noteOn(1, 0, 0));
    both.push(noteOn(1, 7, 1));
    voice.setNoteInput(0, &both);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    check(peak(voiceAfter(voice, 100)) > 0.1f, "both sound");

    NoteBuffer one;
    one.push(noteOff(1, 0));
    voice.setNoteInput(0, &one);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    check(peak(voiceAfter(voice, 600)) > 0.1f, "and one source's off leaves the other's note alone");

    NoteBuffer other;
    other.push(noteOff(1, 1));
    voice.setNoteInput(0, &other);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    check(peak(voiceAfter(voice, 2000)) < 0.001f, "while its own off ends it");
}

void unpatchingASourceEndsItsNotes() {
    std::printf("unpatching a source ends its notes\n");
    VoiceNode voice;
    voice.prepare(kRate);

    NoteBuffer both;
    both.push(noteOn(1, 0, 0));
    both.push(noteOn(2, 7, 1));
    voice.setNoteInput(0, &both);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);

    // There is no crossfade to make on a note cable, so this is the whole mechanism: the
    // voice ends what that source started, because nothing else knows it is sounding.
    voice.notesCut(0, 1);
    check(peak(voiceAfter(voice, 600)) > 0.1f, "the source still patched plays on");
    voice.notesCut(0, 0);
    check(peak(voiceAfter(voice, 2000)) < 0.001f, "and the one that left is silent");
}

void aVoiceResolvesANoteAgainstItsOwnBeat() {
    std::printf("a voice resolves a note against its own beat\n");
    const ScaleList &scales = chromaticThenMajor();

    // The same degree, twice, either side of the switch: degree 2 is two semitones up in
    // 12-TET and four in major. The beat travels on the event because only the node that
    // ticked knows it in integers -- the voice never works it out for itself.
    const float expected[2] = {2.0f / 12.0f, 4.0f / 12.0f};
    const int64_t beats[2] = {0, 4};
    for (int i = 0; i < 2; ++i) {
        VoiceNode voice;
        voice.prepare(kRate);
        voice.setParam(0, 3.0f); // a sine, which crosses zero once a cycle and no more

        NoteBuffer note;
        note.push(noteOn(1, 2, 0, beats[i]));
        voice.setNoteInput(0, &note);
        voice.setTiming(0.0, false, &scales);
        run(voice, 1);

        const auto sounding = voiceIdle(voice, kRate / kBlockSize, &scales); // one second
        const int cycles = countCycles(sounding);
        const int wanted = static_cast<int>(kMiddleC * std::exp2(expected[i]) + 0.5f);
        check(std::abs(cycles - wanted) <= 3,
              "degree 2 on beat " + std::to_string(beats[i]) + " is " +
                      std::to_string(wanted) + "Hz, got " + std::to_string(cycles));
    }
}

void aNinthNoteStealsAVoice() {
    std::printf("a ninth note steals a voice\n");
    VoiceNode voice;
    voice.prepare(kRate);

    NoteBuffer all;
    for (uint32_t i = 0; i < VoiceNode::kVoices + 1; ++i) {
        all.push(noteOn(i + 1, static_cast<int32_t>(i)));
    }
    voice.setNoteInput(0, &all);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    check(peak(voiceAfter(voice, 100)) > 0.1f, "nine notes into eight voices still sounds");

    // The ninth took the first one's voice, so ending the first ends nothing: what is
    // sounding under that voice is the ninth note now.
    NoteBuffer off;
    off.push(noteOff(1));
    voice.setNoteInput(0, &off);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    check(peak(voiceAfter(voice, 600)) > 0.1f,
          "and the note that stole it is not ended by the old one's off");
}

void anLfoStaysInsideItsRangeAtItsRate() {
    std::printf("an lfo stays inside its range, at its rate\n");
    for (int wave = 0; wave < 4; ++wave) {
        LfoNode lfo;
        lfo.prepare(kRate);
        lfo.setParam(0, 10.0f);
        lfo.setParam(1, static_cast<float>(wave));
        const auto second = run(lfo, kRate / kBlockSize);

        float lowest = 1.0f;
        float highest = 0.0f;
        int rises = 0;
        for (std::size_t i = 0; i < second.size(); ++i) {
            lowest = std::min(lowest, second[i]);
            highest = std::max(highest, second[i]);
            if (i > 0 && second[i - 1] < 0.5f && second[i] >= 0.5f) ++rises;
        }
        const std::string name = "wave " + std::to_string(wave);
        // Unipolar is the contract: a destination maps 0..1 across its own range, so a
        // modulator dipping below zero would push a knob past the end it was given.
        check(lowest >= 0.0f && highest <= 1.0f, name + " never leaves 0..1");
        check(lowest < 0.05f && highest > 0.95f, name + " reaches both ends of it");
        check(rises >= 9 && rises <= 11, name + " crosses the middle ten times a second at 10Hz");
    }
    LfoNode fresh;
    fresh.prepare(kRate);
    fresh.setParam(1, 3.0f);
    const auto first = run(fresh, 1);
    check(first[0] < 0.01f, "a sine starts from the bottom of the range, not its middle");
}

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
    aKeyChangeLandsOnItsBeat();
    mixSumsRatherThanAverages();
    outPassesAudioAtLevel();
    outProtectsTheListener();
    theNotesOutputSaysWhatTheGateSays();
    aRestStartsNothing();
    aTransposeRidesOnTheNote();
    aVoiceSoundsAChordAndLetsItGo();
    anIdBelongsToTheSourceThatChoseIt();
    unpatchingASourceEndsItsNotes();
    aVoiceResolvesANoteAgainstItsOwnBeat();
    aNinthNoteStealsAVoice();
    anLfoStaysInsideItsRangeAtItsRate();
    return testing::report("nodes");
}
