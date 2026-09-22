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
#include <initializer_list>
#include <functional>
#include <iterator>
#include <memory>

#include "nodes.h"
#include "processors.h"
#include "soundfont.h"
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

/** One note, as the graph would deliver it: an On or an Off at the top of the block. */
NoteBuffer noteAt(NoteKind kind, uint32_t id, int32_t source = 0, int32_t degree = 0) {
    NoteBuffer buffer;
    NoteEvent event;
    event.id = id;
    event.kind = kind;
    event.offset = 0;
    event.source = source;
    event.degree = degree;
    event.velocity = 1.0f;
    buffer.push(event);
    return buffer;
}

const NoteBuffer kNoNotes{};

/**
 * An oscillator sounding one degree.
 *
 * Nothing here drones any more: the monophonic oscillator that did was retired when every
 * synth became polyphonic, so a tone is a note that is being held. It needed three knobs
 * flattening to hold steady when Osc had an envelope; a held note now simply holds, which
 * is what taking the envelope out was for.
 */
void holdDegree(OscNode &osc, int32_t degree, const NoteBuffer &note) {
    osc.prepare(kRate);
    osc.setNoteInput(0, &note);
    osc.process(kBlockSize);
    osc.setNoteInput(0, &kNoNotes);
    (void) degree;
}

/** What a sequencer said in the block just rendered. */
const NoteBuffer &stepNotes(const StepsNode &steps) { return *steps.noteOutput(0); }

/** The note it started in that block, or a zeroed event if it started none. */
NoteEvent startedNote(const StepsNode &steps) {
    const NoteBuffer &notes = stepNotes(steps);
    for (int32_t i = 0; i < notes.count; ++i) {
        if (notes.events[i].kind == NoteKind::On) return notes.events[i];
    }
    return NoteEvent{};
}

/**
 * What a note will sound as, in octaves from middle C, resolved exactly as OscNode
 * resolves it.
 *
 * The sequencer used to do this itself and put the answer on a pitch output. It now sends
 * the degree and the beat that chooses the scale, and the resolving happens in the
 * oscillator -- so a test that used to read a pitch reads the note and resolves it here,
 * against the same table and through the same call.
 */
float soundsAs(const NoteEvent &note, const ScaleList *scales) {
    const float base = scales != nullptr ? scales->tableAt(note.beat).octavesOf(note.degree)
                                         : ScaleTable{}.octavesOf(note.degree);
    return base + note.cents / 1200.0f;
}

float peak(const std::vector<float> &samples) {
    float worst = 0.0f;
    for (float s : samples) worst = std::max(worst, std::fabs(s));
    return worst;
}

// ---------------------------------------------------------------------------

void oscPlaysTheRequestedPitch() {
    std::printf("osc plays the requested pitch\n");

    // With no scale list the engine reads twelve equal steps, so degree 0 is middle C and
    // degree 12 is an octave above it. Driven by a note now rather than a pitch buffer,
    // which is the only way an oscillator is asked for a pitch at all.
    OscNode osc;
    const auto middleC = noteAt(NoteKind::On, 1, 0, 0);
    holdDegree(osc, 0, middleC);
    const auto atZero = run(osc, kRate / kBlockSize); // one second

    const int cycles = countCycles(atZero);
    check(std::abs(cycles - 262) <= 2, "degree 0 is middle C, got " + std::to_string(cycles));

    OscNode up;
    const auto octaveUp = noteAt(NoteKind::On, 1, 0, 12);
    holdDegree(up, 12, octaveUp);
    const int doubled = countCycles(run(up, kRate / kBlockSize));
    check(std::abs(doubled - 523) <= 4, "degree 12 is an octave up, got " + std::to_string(doubled));
}

void oscStaysBandLimited() {
    std::printf("osc stays band limited\n");

    // Four octaves up, ~4.2kHz, where a naive saw would alias badly.
    OscNode osc;
    const auto high = noteAt(NoteKind::On, 1, 0, 48);
    holdDegree(osc, 48, high);
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

/**
 * The cutoff jack has gone with CV, so the knob is the only thing that moves the cutoff --
 * that, or a modulator writing the same parameter once per block. The test that was here
 * proved the opposite property, that an audio-rate cutoff buffer reached the filter
 * per sample; there is no such buffer now, and a module wanting audio rate declares an
 * audio input instead.
 */
void filterCutoffFollowsItsKnob() {
    std::printf("filter cutoff follows its knob\n");

    // Noise, so there is something at every frequency for a lowpass to take away.
    std::array<float, kBlockSize> noise{};
    unsigned seed = 22222;
    for (int32_t i = 0; i < kBlockSize; ++i) {
        seed = seed * 1664525u + 1013904223u;
        noise[i] = static_cast<float>(seed >> 8 & 0xFFFF) / 32768.0f - 1.0f;
    }

    FilterNode open;
    open.prepare(kRate);
    open.setInput(0, noise.data());
    open.setParam(0, 18000.0f);
    const auto wide = run(open, 32);

    FilterNode shut;
    shut.prepare(kRate);
    shut.setInput(0, noise.data());
    shut.setParam(0, 100.0f);
    const auto narrow = run(shut, 32);

    check(peak(wide) > 0.1f, "the filter passes something when it is open");
    check(peak(narrow) < peak(wide) * 0.5f, "and a low cutoff takes the top off the noise");
}

/** A sine at [hz] through a filter set up by [setUp], as the largest sample it produces. */
float filterGainAt(float hz, const std::function<void(FilterNode &)> &setUp) {
    FilterNode filter;
    filter.prepare(kRate);
    setUp(filter);
    std::array<float, kBlockSize> tone{};
    float worst = 0.0f;
    int32_t frame = 0;
    // A second of it: long enough for the filter's own ring to settle, so what is left is
    // the steady-state gain rather than the transient of the tone starting.
    for (int block = 0; block < kRate / kBlockSize; ++block) {
        for (int32_t i = 0; i < kBlockSize; ++i, ++frame) {
            tone[i] = std::sin(6.2831853f * hz * static_cast<float>(frame) / kRate);
        }
        filter.setInput(0, tone.data());
        filter.process(kBlockSize);
        if (block > 8) {
            for (int32_t i = 0; i < kBlockSize; ++i) worst = std::max(worst, std::fabs(filter.output(0)[i]));
        }
    }
    return worst;
}

/** Each kind keeps what it is named for and takes away the rest. */
void aFilterHasFourKinds() {
    std::printf("a filter keeps what its kind is named for\n");
    constexpr float kCutoff = 1000.0f;
    const auto kind = [](int type) {
        return [type](FilterNode &f) {
            f.setParam(0, kCutoff);
            f.setParam(1, 0.2f); // out of the resonant peak's way
            f.setParam(2, static_cast<float>(type));
        };
    };
    const float low = 100.0f;
    const float high = 8000.0f;

    const float lowPassLow = filterGainAt(low, kind(FilterNode::kLow));
    const float lowPassHigh = filterGainAt(high, kind(FilterNode::kLow));
    check(lowPassLow > 0.5f && lowPassHigh < lowPassLow * 0.2f, "low passes the low");

    const float highPassLow = filterGainAt(low, kind(FilterNode::kHigh));
    const float highPassHigh = filterGainAt(high, kind(FilterNode::kHigh));
    check(highPassHigh > 0.5f && highPassLow < highPassHigh * 0.2f, "high passes the high");

    const float bandAt = filterGainAt(kCutoff, kind(FilterNode::kBand));
    check(bandAt > filterGainAt(low, kind(FilterNode::kBand)) * 2.0f &&
                  bandAt > filterGainAt(high, kind(FilterNode::kBand)) * 2.0f,
          "band keeps the middle and drops both ends");

    // The notch is the one that is easiest to get subtly wrong -- an SVF's notch output is
    // the one that needed its own correction upstream -- so it is checked at the cutoff
    // against both sides rather than against a threshold.
    const float notchAt = filterGainAt(kCutoff, kind(FilterNode::kNotch));
    check(notchAt < filterGainAt(low, kind(FilterNode::kNotch)) * 0.5f &&
                  notchAt < filterGainAt(high, kind(FilterNode::kNotch)) * 0.5f,
          "and notch drops the middle and keeps both ends");
}

void theSteeperSlopeIsSteeper() {
    std::printf("the steeper slope rolls off faster, and only past the cutoff\n");
    const auto at = [](bool steep) {
        return [steep](FilterNode &f) {
            f.setParam(0, 1000.0f);
            f.setParam(1, 0.2f);
            f.setParam(2, static_cast<float>(FilterNode::kLow));
            f.setParam(3, steep ? 1.0f : 0.0f);
        };
    };
    // Two octaves up, where 12dB and 24dB are far enough apart to be unambiguous.
    const float gentle = filterGainAt(4000.0f, at(false));
    const float steep = filterGainAt(4000.0f, at(true));
    check(steep < gentle * 0.5f, "two octaves up, the steeper one is much quieter");

    // And well below it both are open, so the slope is not simply a volume knob.
    const float gentleLow = filterGainAt(100.0f, at(false));
    const float steepLow = filterGainAt(100.0f, at(true));
    check(steepLow > gentleLow * 0.8f, "well below the cutoff both pass the signal");
}

/**
 * The resonance is bounded, which it was not.
 *
 * The SVF's only limit on its resonance is a cubic term scaled by the drive, and the drive
 * was zero: a sine sitting exactly on the cutoff came out 39x at the old maximum res of
 * 0.95 and 1255x at 1.0. Nothing caught it because nothing had ever pointed a tone at the
 * cutoff and looked at the number. Out's limiter would have held the output, which is the
 * point -- it would have held it as a brick wall over whatever else was playing.
 */
void resonanceIsBoundedAtEveryKindAndSlope() {
    std::printf("resonance is bounded, at every kind and slope\n");
    for (int type = 0; type < FilterNode::kTypeCount; ++type) {
        for (int steep = 0; steep <= 1; ++steep) {
            const float gain = filterGainAt(1000.0f, [type, steep](FilterNode &f) {
                f.setParam(0, 1000.0f);
                f.setParam(1, 1.0f); // as resonant as the knob goes
                f.setParam(2, static_cast<float>(type));
                f.setParam(3, static_cast<float>(steep));
            });
            check(std::isfinite(gain), "finite at type " + std::to_string(type));
            // Room for a real resonant peak -- a filter without one is not resonant -- and
            // nowhere near the 39x that shipped.
            check(gain < 8.0f, "and bounded at type " + std::to_string(type) +
                                       " slope " + std::to_string(steep) +
                                       ", was " + std::to_string(gain));
        }
    }
}

/** What the top of the res knob is for, and what it is not. */
void fullResonanceRingsButDoesNotOscillate() {
    std::printf("at full resonance a filter rings, and silence stays silent\n");
    const auto ringMs = [](float res) {
        FilterNode filter;
        filter.prepare(kRate);
        filter.setParam(0, 400.0f);
        filter.setParam(1, res);
        std::array<float, kBlockSize> in{};
        in[0] = 1.0f; // one impulse, then nothing
        float first = 0.0f;
        int32_t last = 0;
        for (int block = 0; block < 3 * kRate / kBlockSize; ++block) {
            filter.setInput(0, in.data());
            filter.process(kBlockSize);
            for (int32_t i = 0; i < kBlockSize; ++i) {
                const float out = std::fabs(filter.output(0)[i]);
                if (block == 0) first = std::max(first, out);
                if (out > first * 0.01f) last = block * kBlockSize + i;
            }
            in[0] = 0.0f;
        }
        return 1000.0f * static_cast<float>(last) / kRate;
    };
    check(ringMs(0.5f) < 100.0f, "a middling resonance rings briefly");
    check(ringMs(0.9f) > ringMs(0.5f), "more of it rings longer");
    check(ringMs(1.0f) > 1000.0f, "and at the top it rings on and on");

    // It is not an oscillator: nothing here can make the damping negative, so with no
    // input at all there is nothing to ring.
    FilterNode silent;
    silent.prepare(kRate);
    silent.setParam(0, 400.0f);
    silent.setParam(1, 1.0f);
    std::array<float, kBlockSize> nothing{};
    silent.setInput(0, nothing.data());
    const auto rendered = run(silent, 200);
    check(peak(rendered) == 0.0f, "and silence in is silence out");
}

/**
 * The cutoff follows the note, which nothing in the app could do before this.
 *
 * Measured as a ratio rather than as hertz: what tracking promises is that the filter sits
 * the same distance above every note, so the test is that an octave up moves the cutoff an
 * octave up -- which is the property, where a number in hertz would only be this note.
 */
void theCutoffFollowsTheNote() {
    std::printf("a filter's cutoff follows the note it is given\n");
    // Where the corner actually is, found by asking which of two tones survives: a tone an
    // octave under the corner passes, one an octave over it does not.
    const auto passes = [](float tone, int32_t degree, float track) {
        FilterNode filter;
        filter.prepare(kRate);
        filter.setParam(0, 1000.0f);
        filter.setParam(1, 0.2f);
        filter.setParam(4, track);
        // Built here rather than with the noteOn helper, which the file declares further
        // down among the voice tests.
        NoteBuffer notes;
        NoteEvent on;
        on.id = 1;
        on.kind = NoteKind::On;
        on.degree = degree;
        notes.push(on);
        filter.setNoteInput(1, &notes);
        filter.setTiming(0.0, false, nullptr);
        std::array<float, kBlockSize> in{};
        float worst = 0.0f;
        int32_t frame = 0;
        for (int block = 0; block < kRate / kBlockSize; ++block) {
            for (int32_t i = 0; i < kBlockSize; ++i, ++frame) {
                in[i] = std::sin(6.2831853f * tone * static_cast<float>(frame) / kRate);
            }
            filter.setInput(0, in.data());
            filter.process(kBlockSize);
            // Well past the slide: a tracked cutoff moves at a fixed rate in octaves per
            // block, so four octaves takes eighty of them, and measuring from block eight
            // would read the filter on its way rather than where it arrived.
            if (block > 200) {
                for (int32_t i = 0; i < kBlockSize; ++i) {
                    worst = std::max(worst, std::fabs(filter.output(0)[i]));
                }
            }
            // Only the first block carries the note; the pitch is held from then on.
            static const NoteBuffer kNone{};
            filter.setNoteInput(1, &kNone);
        }
        return worst;
    };

    // Untracked, the corner stays at the knob whatever note arrives.
    check(passes(4000.0f, 0, 0.0f) < 0.2f, "without tracking a tone above the cutoff is cut");
    check(passes(4000.0f, 24, 0.0f) < 0.2f, "and it stays cut two octaves up the keyboard");

    // Tracked, two octaves up the keyboard takes the corner two octaves up with it, so the
    // same 4kHz tone is now under it.
    check(passes(4000.0f, 24, 100.0f) > 0.5f, "with tracking the same tone is let through");
    // And the ratio holds the other way: a tone that passed at middle C is cut two octaves
    // down, because the corner went down with the note.
    check(passes(700.0f, 0, 100.0f) > 0.5f, "a tone under the corner passes at middle C");
    check(passes(700.0f, -24, 100.0f) < 0.2f, "and is cut two octaves down");

    // Half tracking is half the distance, in octaves: two octaves of note move the corner
    // one. 4kHz is two octaves over a 1kHz corner, so at half tracking it is still out.
    check(passes(4000.0f, 24, 50.0f) < 0.4f, "half tracking moves the corner half as far");
}

/**
 * A tracked cutoff slides to the new note rather than jumping to it.
 *
 * It jumped, first. A four-octave line through a resonant tracked filter clicked five times
 * in ten seconds on the phone and not at all with the tracking off -- found by capture
 * rather than by ear, which is the only reason it is not in the roadmap as a bug. A
 * modulator moves a cutoff a little every block; a note moves it octaves in one.
 */
void aTrackedCutoffSlidesRatherThanJumping() {
    std::printf("a tracked cutoff slides to the note rather than jumping\n");
    FilterNode filter;
    filter.prepare(kRate);
    filter.setParam(0, 800.0f);
    filter.setParam(1, 0.6f);  // resonant, where a coefficient jump is loudest
    filter.setParam(4, 100.0f);

    std::array<float, kBlockSize> in{};
    int32_t frame = 0;
    std::vector<float> all;
    const auto play = [&](int blocks, const NoteBuffer *notes) {
        static const NoteBuffer kNone{};
        for (int b = 0; b < blocks; ++b) {
            for (int32_t i = 0; i < kBlockSize; ++i, ++frame) {
                in[i] = std::sin(6.2831853f * 300.0f * static_cast<float>(frame) / kRate);
            }
            filter.setInput(0, in.data());
            filter.setNoteInput(1, b == 0 && notes != nullptr ? notes : &kNone);
            filter.setTiming(0.0, false, nullptr);
            filter.process(kBlockSize);
            const float *o = filter.output(0);
            all.insert(all.end(), o, o + kBlockSize);
        }
    };

    NoteBuffer low;
    NoteEvent down;
    down.id = 1;
    down.kind = NoteKind::On;
    down.degree = -24;
    low.push(down);
    play(400, &low);
    const std::size_t settled = all.size();

    NoteBuffer high;
    NoteEvent up;
    up.id = 2;
    up.kind = NoteKind::On;
    up.degree = 24;
    high.push(up);
    play(400, &high);

    // Measured against the level around it, not outright. A resonant cutoff sweeping past
    // the tone makes the tone louder, and a louder sine has bigger steps between samples
    // for no bad reason at all -- the first cut of this test failed on exactly that. What
    // a click is, is a step out of proportion to the waveform carrying it.
    const auto worstRatio = [&all](std::size_t from, std::size_t to) {
        float worst = 0.0f;
        constexpr std::size_t kWindow = 256;
        for (std::size_t at = from; at + kWindow < to; at += kWindow / 2) {
            float step = 0.0f;
            float level = 0.0f;
            for (std::size_t i = at + 1; i < at + kWindow; ++i) {
                step = std::max(step, std::fabs(all[i] - all[i - 1]));
                level = std::max(level, std::fabs(all[i]));
            }
            if (level > 1e-4f) worst = std::max(worst, step / level);
        }
        return worst;
    };
    const float steady = worstRatio(settled - 8000, settled - 100);
    const float crossing = worstRatio(settled, settled + 8000);
    check(steady > 0.0f, "the tone is getting through");
    // Two, which sits between what this measures with the rate limit (1.17x, and what is
    // left of that is the resonance retuning rather than any edge) and what it measured
    // when the cutoff jumped to the note (8.9x). An exponential glide read 2.15x, which is
    // why it is a rate limit: smoothing moves a quarter of the distance in the first block,
    // and a quarter of four octaves is a whole octave of coefficient in one step.
    check(crossing < steady * 2.0f,
          "and four octaves of tracking costs no step out of proportion to it, was " +
                  std::to_string(crossing / steady) + "x");
}

void envFollowsTheNotesItIsHolding() {
    std::printf("env follows the notes it is holding\n");
    EnvNode env;
    env.prepare(kRate);

    const auto on = noteAt(NoteKind::On, 1);
    env.setNoteInput(0, &on);
    run(env, 1);
    // The buffer is valid for one block only, as the graph's merge is, so it is taken
    // back before the envelope is left to run.
    env.setNoteInput(0, &kNoNotes);
    check(peak(run(env, 64)) > 0.5f, "opens while a note is held"); // ~43ms, into decay

    const auto off = noteAt(NoteKind::Off, 1);
    env.setNoteInput(0, &off);
    run(env, 1);
    env.setNoteInput(0, &kNoNotes);
    // The release is 250ms but the tail is exponential, so "closed" takes considerably
    // longer than the nominal time: measured, it is still at 0.05 after 1024 blocks.
    check(std::fabs(run(env, 2048).back()) < 0.02f, "closes once the last one lets go");
}

void envSustainsUnderAChordAndWaitsForTheLastNote() {
    std::printf("env sustains under a chord and waits for the last note\n");
    EnvNode env;
    env.prepare(kRate);

    const auto first = noteAt(NoteKind::On, 1);
    env.setNoteInput(0, &first);
    run(env, 1);
    env.setNoteInput(0, &kNoNotes);
    const auto settled = run(env, 64);

    // A second note over a held one must not restart the attack: an envelope that
    // re-struck under a chord would turn one into a stutter. Legato, so the level simply
    // carries on from where it was rather than diving to zero and climbing again.
    const auto second = noteAt(NoteKind::On, 2);
    env.setNoteInput(0, &second);
    run(env, 1);
    env.setNoteInput(0, &kNoNotes);
    const auto during = run(env, 4);
    check(during.front() > settled.back() * 0.5f, "a second note does not restart the attack");

    // Letting go of one of two leaves the gate open, because something is still down.
    const auto liftFirst = noteAt(NoteKind::Off, 1);
    env.setNoteInput(0, &liftFirst);
    run(env, 1);
    env.setNoteInput(0, &kNoNotes);
    check(run(env, 2048).back() > 0.3f, "and one of two letting go is not the end of it");

    const auto liftSecond = noteAt(NoteKind::Off, 2);
    env.setNoteInput(0, &liftSecond);
    run(env, 1);
    env.setNoteInput(0, &kNoNotes);
    check(std::fabs(run(env, 2048).back()) < 0.02f, "the last one closes it");
}

void envEndsTheNotesOfASourceThatWasUnpatched() {
    std::printf("env ends the notes of a source that was unpatched\n");
    EnvNode env;
    env.prepare(kRate);

    // Two sources holding a note each. Unpatching one must release only its own, or the
    // envelope stays open on a note whose sender is gone -- the hanging note notesCut
    // exists to prevent.
    const auto fromA = noteAt(NoteKind::On, 1, 0);
    env.setNoteInput(0, &fromA);
    run(env, 1);
    const auto fromB = noteAt(NoteKind::On, 1, 1); // same id, different source
    env.setNoteInput(0, &fromB);
    run(env, 1);
    env.setNoteInput(0, &kNoNotes);
    run(env, 32);

    // Measured after longer than the 250ms release rather than over the next 21ms: a
    // gate that wrongly closed is still near its sustain level a moment later, so a short
    // window cannot tell an envelope that is holding from one that has just let go.
    env.notesCut(0, 0);
    check(run(env, 2048).back() > 0.3f, "the other source's note still holds it open");

    env.notesCut(0, 1);
    check(std::fabs(run(env, 2048).back()) < 0.02f, "and cutting the last closes it");
}

/**
 * An Off is matched against the source that sent it, not against its id alone.
 *
 * Ids are each source's own and start again at 1 whenever a node is rebuilt, so two
 * sequencers patched to one envelope will both be holding a note called 1 almost at once.
 * Matching on the id alone releases whichever was found first, and the envelope then lets
 * go of a note nobody lifted.
 *
 * The sequence below is the one that tells the two apart. A gate is only "anything held",
 * so the wrong note being released is invisible until something asks specifically about
 * the note that should still be down -- which is what cutting source 1 at the end does.
 */
void envMatchesAnOffAgainstItsOwnSource() {
    std::printf("env matches an off against its own source\n");
    EnvNode env;
    env.prepare(kRate);

    const auto fromA = noteAt(NoteKind::On, 1, 0);
    env.setNoteInput(0, &fromA);
    run(env, 1);
    const auto fromB = noteAt(NoteKind::On, 1, 1); // the same id, a different source
    env.setNoteInput(0, &fromB);
    run(env, 1);

    // An Off carrying that id from a source holding nothing must release nothing at all.
    const auto strayOff = noteAt(NoteKind::Off, 1, 2);
    env.setNoteInput(0, &strayOff);
    run(env, 1);
    env.setNoteInput(0, &kNoNotes);
    run(env, 32);

    // Source 1 lets go. Source 0's note was never lifted, so the envelope stays open --
    // and it only can if the stray Off above released nothing and this one released
    // source 1's note rather than source 0's.
    env.notesCut(0, 1);
    check(run(env, 2048).back() > 0.3f, "the note nobody lifted is still holding it open");
}

/**
 * What the VCA's test used to say, against the module that replaced it.
 *
 * A Mix channel is `in * level`, which is a VCA with the level on a knob instead of a
 * jack -- so Mix shut at zero and open at one is the same claim, and is why retiring the
 * VCA cost the catalog nothing.
 */
void aMixChannelIsAGainThatCanBeShut() {
    std::printf("a mix channel is a gain that can be shut\n");
    const auto signal = constantBuffer(1.0f);
    // Named, not a temporary: setInput keeps the pointer, and a buffer built inline dies
    // at the end of the statement that made it.
    const auto quiet = constantBuffer(0.0f);

    MixNode mix;
    mix.prepare(kRate);
    mix.setInput(0, signal.data());
    mix.setInput(1, quiet.data());
    mix.setInput(2, quiet.data());
    mix.setInput(3, quiet.data());

    mix.setParam(0, 0.0f);
    check(peak(run(mix, 4)) == 0.0f, "shut at a level of nothing");

    mix.setParam(0, 1.0f);
    check(std::fabs(peak(run(mix, 4)) - 1.0f) < 0.001f, "and open at full");
}

/**
 * The Amp is a multiplier with a knob, and an open one when nothing is patched to it.
 *
 * The second half is the part worth pinning: silence is the right idle for an input that is
 * summed and the wrong one for an input that multiplies, so this port declares itself a
 * unity input and the graph hands it ones. An Amp dropped into a patch with nothing on its
 * mod jack has to pass its audio, or it reads as a module that does not work.
 */
void anAmpMultipliesAndIsOpenWithNothingPatched() {
    std::printf("an amp multiplies, and is open with nothing patched\n");
    const auto signal = constantBuffer(1.0f);
    const auto half = constantBuffer(0.5f);
    const auto shut = constantBuffer(0.0f);

    AmpNode amp;
    amp.prepare(kRate);
    amp.setInput(0, signal.data());

    amp.setInput(1, half.data());
    check(std::fabs(peak(run(amp, 4)) - 0.5f) < 0.001f, "the modulator is a gain");

    amp.setInput(1, shut.data());
    check(peak(run(amp, 4)) == 0.0f, "and shuts it");

    amp.setInput(1, half.data());
    amp.setParam(0, 2.0f);
    check(std::fabs(peak(run(amp, 4)) - 1.0f) < 0.001f, "the knob multiplies on top of it");

    // What Graph hands an unpatched unity input; see Node::unityInputs.
    check(amp.unityInputs() == (1u << 1), "the mod port asks for ones rather than silence");
    const auto ones = constantBuffer(1.0f);
    amp.setParam(0, 1.0f);
    amp.setInput(1, ones.data());
    check(std::fabs(peak(run(amp, 4)) - 1.0f) < 0.001f, "so nothing patched is wide open");
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
    check(std::fabs(soundsAs(startedNote(steps), nullptr) - 1.0f) < 0.0001f,
          "the degree written to a step is the pitch it plays");

    // Out of range in both directions must be ignored rather than corrupt a neighbor.
    // Checked by coming round to step 1 again, since a sequencer only says anything at a
    // tick now -- there is no held output to re-read between them.
    steps.setStep(-1, 108, true);
    steps.setStep(StepsNode::kSteps, 108, true);
    tickAt(steps, 9); // step 1 again, a lap later
    check(std::fabs(soundsAs(startedNote(steps), nullptr) - 1.0f) < 0.0001f,
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
    check(stepNotes(steps).count == 1 && stepNotes(steps).events[0].kind == NoteKind::Off,
          "a closed step starts nothing, and ends what was sounding");
    tickAt(steps, 2);
    check(stepNotes(steps).count == 1 && stepNotes(steps).events[0].kind == NoteKind::On,
          "the next open step still fires");
    check(std::fabs(soundsAs(startedNote(steps), nullptr) - 0.75f) < 0.0001f,
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
    check(stepNotes(steps).count == 1 && stepNotes(steps).events[0].kind == NoteKind::Off,
          "the rest starts nothing");

    steps.setStep(1, 3, true);        // switch it back on
    tickAt(steps, 2);                 // step 0
    tickAt(steps, 3);                 // step 1, now sounding
    check(stepNotes(steps).count >= 1 &&
                  stepNotes(steps).events[stepNotes(steps).count - 1].kind == NoteKind::On,
          "it fires once it is open again");
    check(std::fabs(soundsAs(startedNote(steps), nullptr) - 0.25f) < 0.0001f,
          "with the degree it was holding on to all along");
}

void aNoteLastsHalfItsStep() {
    std::printf("a note lasts half its step\n");
    StepsNode steps;
    steps.prepare(kRate);
    steps.setStep(0, 0, true);

    // The default 1/8 at 120bpm is 12000 frames, so the note runs for frames 0-5999.
    // Measured now by when its Off arrives rather than by a gate falling, which is the
    // same claim: a note is two events, and the second one is its length.
    tickAt(steps, 0);
    check(stepNotes(steps).count == 1 && stepNotes(steps).events[0].kind == NoteKind::On,
          "it starts as the step does");
    idle(steps, 185);                 // through frame 5951
    check(stepNotes(steps).count == 0, "and is still held just short of half the step");
    idle(steps, 2);                   // through frame 6015
    check(stepNotes(steps).count == 1 && stepNotes(steps).events[0].kind == NoteKind::Off,
          "then ends by half the step");
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
    idle(steps, 400, false);          // 12800 frames, twice the note, stopped
    check(stepNotes(steps).count == 0, "the note does not run out while stopped");
    bool ended = false;
    for (int i = 0; i < 200 && !ended; ++i) {
        idle(steps, 1, true);
        ended = stepNotes(steps).count == 1 &&
                stepNotes(steps).events[0].kind == NoteKind::Off;
    }
    check(ended, "and does once time moves again");
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
    check(stepNotes(steps).count == 1 && stepNotes(steps).events[0].offset == 10,
          "the note starts on the tick's own sample");
    check(std::fabs(soundsAs(startedNote(steps), nullptr) - 1.0f) < 0.0001f,
          "carrying that step's own degree");
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
    check(std::fabs(soundsAs(startedNote(steps), &chromaticThenMajor()) - 2.0f / 12.0f) < 0.0001f,
          "the eighth before the switch is 12-TET");
    tickAt(steps, 8, 0, &chromaticThenMajor());
    check(std::fabs(soundsAs(startedNote(steps), &chromaticThenMajor()) - 4.0f / 12.0f) < 0.0001f,
          "the eighth on the switch beat is major");
}

/*
 * "A note held through a switch keeps its pitch" was here, and went with the pitch output
 * it was about. It guarded a sample-and-hold: the held pitch had to stay the one worked
 * out at the tick rather than be re-read against whatever scale had since arrived. Nothing
 * holds a pitch now -- a note is two events and carries the beat that chooses its scale,
 * so there is no second reading to get wrong. What survives of it is
 * aRestKeepsTheNoteItRemembers, which is the other half: a rest keeps its degree so that
 * switching it back on restores the note that was there.
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
    check(std::fabs(soundsAs(startedNote(steps), &chromaticThenMajor()) - 2.0f / 12.0f) < 0.0001f,
          "the triplet before beat 4 is 12-TET");
    tickAt(steps, 12, 0, &chromaticThenMajor());
    check(std::fabs(soundsAs(startedNote(steps), &chromaticThenMajor()) - 4.0f / 12.0f) < 0.0001f,
          "the triplet on beat 4 is major");
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
    check(std::fabs(soundsAs(startedNote(steps), &cThenG())) < 0.0001f,
          "degree 0 before the change is C");
    tickAt(steps, 8, 0, &cThenG());
    check(std::fabs(soundsAs(startedNote(steps), &cThenG()) - 7.0f / 12.0f) < 0.0001f,
          "and on the change beat is G");
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
const NoteBuffer &notesOf(const StepsNode &steps) { return *steps.noteOutput(0); }

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

// ---------------------------------------------------------------- drone

namespace {

const NoteBuffer &notesOf(const DroneNode &drone) { return *drone.noteOutput(0); }

// Mirrored by DRONE_CELLS in PatchCanvas.kt, which cannot see this. Kotlin sizes a
// drone's cell list from its own copy and the engine drops anything past the end, so the
// two drifting apart is the kind of mismatch that fails silently in production.
static_assert(DroneNode::kCells == 64, "DRONE_CELLS in PatchCanvas.kt mirrors this");

/** One block, with the transport running or not, and no tick in it. */
void run(DroneNode &drone, bool running = false, int blocks = 1) {
    for (int i = 0; i < blocks; ++i) {
        drone.setTiming(running ? kBeatsPerFrame : 0.0, running);
        drone.process(kBlockSize);
    }
}

} // namespace

void aDroneHoldsItsNoteWithTheTransportStopped() {
    std::printf("a drone holds its note with the transport stopped\n");
    DroneNode drone;
    drone.prepare(kRate);

    run(drone);
    check(notesOf(drone).count == 0, "an untouched grid says nothing");

    drone.setStep(3, 7, true);
    run(drone);
    check(notesOf(drone).count == 1, "a toggled cell starts one note");
    const NoteEvent on = notesOf(drone).events[0];
    check(on.kind == NoteKind::On, "and it is a note starting");
    check(on.degree == 7, "carrying the cell's degree");
    check(on.offset == 0, "at the top of the block, having no boundary of its own");
    check(on.id != 0, "with an id an off can be matched against");

    // The property the whole module exists for: nothing here is clocked, so the note is
    // still sounding after a hundred blocks in which the transport never moved. This is
    // also what makes it the graph suite's tone source.
    run(drone, false, 100);
    check(notesOf(drone).count == 0, "and says nothing more while it is held");

    drone.setStep(3, 7, false);
    run(drone);
    check(notesOf(drone).count == 1, "untoggling ends it");
    check(notesOf(drone).events[0].kind == NoteKind::Off, "with an off");
    check(notesOf(drone).events[0].id == on.id, "for the note that started");

    run(drone, false, 4);
    check(notesOf(drone).count == 0, "and nothing after that");
}

void aDroneSoundsSeveralCellsAtOnce() {
    std::printf("a drone sounds several cells at once\n");
    DroneNode drone;
    drone.prepare(kRate);

    drone.setStep(0, 0, true);
    drone.setStep(1, 4, true);
    drone.setStep(2, 7, true);
    run(drone);
    check(notesOf(drone).count == 3, "three cells, three notes");

    uint32_t ids[3] = {};
    int32_t degrees[3] = {};
    for (int32_t i = 0; i < 3; ++i) {
        ids[i] = notesOf(drone).events[i].id;
        degrees[i] = notesOf(drone).events[i].degree;
    }
    check(degrees[0] == 0 && degrees[1] == 4 && degrees[2] == 7, "each carrying its own degree");
    check(ids[0] != ids[1] && ids[1] != ids[2] && ids[0] != ids[2], "and its own id");

    // One cell off leaves the others alone, which is what a chord has to do and what a
    // single held gate could never say.
    drone.setStep(1, 4, false);
    run(drone);
    check(notesOf(drone).count == 1, "one off");
    check(notesOf(drone).events[0].id == ids[1], "for the cell that was untoggled");
}

void aDroneNoteTakesTheBeatOfTheLastTick() {
    std::printf("a drone note takes the beat of the last tick\n");
    DroneNode drone;
    drone.prepare(kRate);

    // Ticked at a quarter, so the count is the beat. A note has to name the beat it
    // starts on or the wrong scale resolves it -- and a drone learns the beat from the
    // transport without its sounding depending on one.
    drone.setTiming(kBeatsPerFrame, true);
    drone.tick(0, 6);
    drone.process(kBlockSize);

    drone.setStep(0, 2, true);
    run(drone, true);
    check(notesOf(drone).count == 1, "the cell starts");
    check(notesOf(drone).events[0].beat == 6, "on the beat the transport last ticked");
}

void aDroneSaysNothingTwiceForTheSameCell() {
    std::printf("a drone says nothing twice for the same cell\n");
    DroneNode drone;
    drone.prepare(kRate);

    drone.setStep(5, 5, true);
    run(drone);
    check(notesOf(drone).count == 1, "the first toggle starts it");

    // Setting a cell that is already on must not re-trigger it. The interface resends a
    // cell whenever anything about it changes, and a note restarted every time a finger
    // moved elsewhere would be a stutter nothing on screen explained.
    drone.setStep(5, 5, true);
    run(drone);
    check(notesOf(drone).count == 0, "and setting it again starts nothing");

    drone.setStep(5, 5, false);
    run(drone);
    drone.setStep(5, 5, false);
    run(drone);
    check(notesOf(drone).count == 0, "as untoggling a silent cell ends nothing");
}

void aDroneIgnoresACellOutsideItsGrid() {
    std::printf("a drone ignores a cell outside its grid\n");
    DroneNode drone;
    drone.prepare(kRate);
    drone.setStep(-1, 0, true);
    drone.setStep(DroneNode::kCells, 0, true);
    drone.setStep(DroneNode::kCells + 99, 0, true);
    run(drone);
    check(notesOf(drone).count == 0, "nothing sounds and nothing is written past the end");
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
std::vector<float> voiceIdle(Node &voice, int blocks, const ScaleList *scales = nullptr) {
    static const NoteBuffer empty{};
    voice.setNoteInput(0, &empty);
    voice.setTiming(0.0, false, scales);
    return run(voice, blocks);
}

/** The largest jump between adjacent samples: what a click actually is. */
float maxStep(const std::vector<float> &samples) {
    float worst = 0.0f;
    for (std::size_t i = 1; i < samples.size(); ++i) {
        worst = std::max(worst, std::fabs(samples[i] - samples[i - 1]));
    }
    return worst;
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
std::vector<float> voiceAfter(Node &voice, int blocks, const ScaleList *scales = nullptr) {
    voiceIdle(voice, blocks, scales);
    return voiceIdle(voice, 16, scales);
}

/**
 * A synth sounds one note at a time, and the last one wins.
 *
 * It sounded a chord once, with eight voices of its own. Those went to the poly subpatch --
 * a voice is a patch stamped out per note now, so leaving eight in here as well would be
 * two allocators with the inner one never choosing anything. What has to survive is
 * everything a single note needs: it holds until it is told to stop, a second note takes
 * the voice, and an Off for a note that was already taken does not cut the one that took
 * it.
 */
void aSynthSoundsOneNoteAtATime() {
    std::printf("a synth sounds one note at a time, and the last one wins\n");
    OscNode voice;
    voice.prepare(kRate);

    NoteBuffer chord;
    chord.push(noteOn(1, 0));
    chord.push(noteOn(2, 4));
    chord.push(noteOn(3, 7));
    voice.setNoteInput(0, &chord);
    voice.setTiming(0.0, false, nullptr);
    check(peak(run(voice, 1)) >= 0.0f, "three notes down one cable are taken");
    check(voice.inUse(), "and one of them is sounding");

    // Held, because nothing has said otherwise. A sustain that decayed on its own would
    // be a sequencer's note length leaking into the synth.
    check(peak(voiceAfter(voice, 200)) > 0.1f, "which holds until it is told to stop");

    // The third note took the voice, so ending the first two ends nothing.
    NoteBuffer stale;
    stale.push(noteOff(1));
    stale.push(noteOff(2));
    voice.setNoteInput(0, &stale);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    check(peak(voiceAfter(voice, 200)) > 0.1f, "an off for a note that was taken cuts nothing");

    NoteBuffer release;
    release.push(noteOff(3));
    voice.setNoteInput(0, &release);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    check(peak(voiceAfter(voice, 200)) < 0.001f, "and its own off ends it");
    check(!voice.inUse(), "leaving the voice free");
}

/** Hands [events] to a PolyIn and renders one block. */
void polySend(PolyInNode &poly, const NoteBuffer &events) {
    poly.setNoteInput(0, &events);
    poly.setTiming(0.0, false, nullptr);
    poly.process(kBlockSize);
    poly.setNoteInput(0, &kNoNotes);
}

/** Which instances said something this block, and what kind. */
int32_t polyCount(const PolyInNode &poly, int32_t instance) {
    return poly.noteOutput(instance)->count;
}

/**
 * A chord goes out one note per instance, and an Off follows its own note home.
 *
 * This is the whole of what a poly subpatch adds: inside it there is one of everything, so
 * the boundary has to decide which copy each note belongs to. The rules are PolySynth's --
 * idle first, then the oldest released, then steal -- because they are the same rules and
 * were each paid for by a bug.
 */
void aPolySubpatchGivesEachNoteItsOwnInstance() {
    std::printf("a poly subpatch gives each note its own instance\n");
    PolyInNode poly;
    poly.prepare(kRate);
    poly.setParam(0, 4.0f);

    NoteBuffer chord;
    chord.push(noteOn(1, 0));
    chord.push(noteOn(2, 4));
    chord.push(noteOn(3, 7));
    polySend(poly, chord);
    check(poly.instancesHeld() == 3, "three notes take three instances, " +
                                             std::to_string(poly.instancesHeld()));
    for (int32_t k = 0; k < 3; ++k) {
        check(polyCount(poly, k) == 1, "instance " + std::to_string(k) + " got one note");
    }
    check(polyCount(poly, 3) == 0, "and the fourth got none");

    NoteBuffer off;
    off.push(noteOff(2));
    polySend(poly, off);
    check(polyCount(poly, 1) == 1 && poly.noteOutput(1)->events[0].kind == NoteKind::Off,
          "the off goes to the instance that has that note");
    check(polyCount(poly, 0) == 0 && polyCount(poly, 2) == 0, "and to no other");
    check(poly.instancesHeld() == 2, "which lets its instance go");
}

/** The voices knob bounds which instances are chosen, not how many ports exist. */
void aPolySubpatchUsesOnlyTheInstancesItsKnobAllows() {
    std::printf("a poly subpatch uses only the instances its knob allows\n");
    PolyInNode poly;
    poly.prepare(kRate);
    check(poly.outputCount() == PolyInNode::kInstances,
          "every instance has a port whatever the knob says");

    poly.setParam(0, 2.0f);
    NoteBuffer three;
    three.push(noteOn(1, 0));
    three.push(noteOn(2, 4));
    three.push(noteOn(3, 7));
    polySend(poly, three);
    check(poly.instancesHeld() == 2, "at two voices, three notes hold two instances");
    check(polyCount(poly, 2) == 0, "and the third instance is never given one");
}

/**
 * Stealing tells the instance to let go first.
 *
 * Inside an instance is an ordinary Env holding an ordinary note. Nothing else would ever
 * end it, so an On landing on a held instance without an Off in front of it leaves that
 * Env open forever and the instance never comes back.
 */
void stealingAnInstanceEndsTheNoteItWasHolding() {
    std::printf("stealing an instance ends the note it was holding\n");
    PolyInNode poly;
    poly.prepare(kRate);
    poly.setParam(0, 1.0f);

    NoteBuffer first;
    first.push(noteOn(1, 0));
    polySend(poly, first);

    NoteBuffer second;
    second.push(noteOn(2, 7));
    polySend(poly, second);
    check(polyCount(poly, 0) == 2, "the one instance is told two things");
    check(poly.noteOutput(0)->events[0].kind == NoteKind::Off, "an off for the note it had");
    check(poly.noteOutput(0)->events[0].id == 1, "named as that note");
    check(poly.noteOutput(0)->events[1].kind == NoteKind::On, "then the on that took it");
}

/** Two sources both counting from one, which is what every source does. */
void aPolySubpatchMatchesAnOffAgainstItsOwnSource() {
    std::printf("a poly subpatch matches an off against its own source\n");
    PolyInNode poly;
    poly.prepare(kRate);
    poly.setParam(0, 4.0f);

    NoteBuffer both;
    both.push(noteOn(1, 0, 0));
    both.push(noteOn(1, 7, 1));
    polySend(poly, both);
    check(poly.instancesHeld() == 2, "the same id from two sources is two notes");

    NoteBuffer one;
    one.push(noteOff(1, 1));
    polySend(poly, one);
    check(poly.instancesHeld() == 1, "and one source's off ends one of them");
    check(polyCount(poly, 1) == 1 && polyCount(poly, 0) == 0, "the second one");
}

/**
 * Unpatching a source ends what it was holding, and a cable patched while notes are held
 * is given them.
 *
 * Both are rules the rest of the engine already keeps; the boundary has to keep them too,
 * because from inside the subpatch it is the source.
 */
void aPolySubpatchEndsAndDeliversHeldNotes() {
    std::printf("a poly subpatch ends and delivers held notes\n");
    PolyInNode poly;
    poly.prepare(kRate);
    poly.setParam(0, 4.0f);

    NoteBuffer on;
    on.push(noteOn(1, 5, 0));
    polySend(poly, on);

    NoteBuffer into;
    poly.heldNotes(0, into);
    check(into.count == 1 && into.events[0].kind == NoteKind::On && into.events[0].degree == 5,
          "an instance hands over the note it is holding");
    NoteBuffer other;
    poly.heldNotes(1, other);
    check(other.count == 0, "and an instance holding nothing hands over nothing");

    poly.notesCut(0, 0);
    polySend(poly, kNoNotes);
    check(polyCount(poly, 0) == 1 && poly.noteOutput(0)->events[0].kind == NoteKind::Off,
          "unpatching the source releases what it held");
    check(poly.instancesHeld() == 0, "leaving nothing held");
}

/** The other edge: every instance's audio back into one cable. */
void aPolySubpatchSumsItsInstances() {
    std::printf("a poly subpatch sums its instances\n");
    const auto quarter = constantBuffer(0.25f);
    const auto quiet = constantBuffer(0.0f);
    PolySumNode sum;
    sum.prepare(kRate);
    for (int32_t p = 0; p < kMaxPorts; ++p) sum.setInput(p, quiet.data());

    sum.setInput(0, quarter.data());
    check(std::fabs(peak(run(sum, 2)) - 0.25f) < 0.001f, "one instance sounding is itself");
    sum.setInput(1, quarter.data());
    sum.setInput(2, quarter.data());
    check(std::fabs(peak(run(sum, 2)) - 0.75f) < 0.001f,
          "three of them sum rather than average");
}

/**
 * The 5ms ramp that is all an Osc has left of an envelope.
 *
 * Not an envelope: what it owes is that a note neither starts nor stops with a step in it,
 * and both edges can have one. A saw is at -1 at phase zero, so a fresh note without a ramp
 * begins with a full-amplitude jump out of silence; and a note let go is at whatever phase
 * it had reached, so ending it without a ramp drops that sample to nothing.
 */
void aNoteStartsAndStopsWithoutAStep() {
    std::printf("a note starts and stops without a step\n");
    auto biggestJump = [](const std::vector<float> &x) {
        float worst = 0.0f;
        for (std::size_t i = 1; i < x.size(); ++i) worst = std::max(worst, std::fabs(x[i] - x[i - 1]));
        return worst;
    };

    {
        OscNode square;
        square.prepare(kRate);
        square.setParam(0, 1.0f); // a square, which is at full amplitude from its first sample
        NoteBuffer on;
        on.push(noteOn(1, 0));
        square.setNoteInput(0, &on);
        square.setTiming(0.0, false, nullptr);
        // One block is 32 frames of a 240-frame ramp, so the note is still on its way up.
        const float opening = peak(run(square, 1));
        const float full = peak(voiceIdle(square, 20));
        // DaisySP's polyblep square is scaled to 0.707, not 1, which is why this is a
        // ratio and why "full" is measured rather than assumed.
        check(full > 0.7f, "a square reaches full level, " + std::to_string(full));
        check(opening < 0.2f * full,
              "and its first block is still opening, " + std::to_string(opening));
    }

    OscNode osc;
    osc.prepare(kRate);
    osc.setParam(0, 3.0f); // a sine, whose own slope is what the release is measured against

    NoteBuffer on;
    on.push(noteOn(1, 0));
    osc.setNoteInput(0, &on);
    osc.setTiming(0.0, false, nullptr);
    run(osc, 1);
    voiceIdle(osc, 40);
    const float steady = biggestJump(voiceIdle(osc, 8));

    // Across the join: the step a release makes is between the last sample the note was
    // sounding and the first after the off, so a vector starting at the off cannot see it.
    auto closing = voiceIdle(osc, 2);
    NoteBuffer off;
    off.push(noteOff(1));
    osc.setNoteInput(0, &off);
    osc.setTiming(0.0, false, nullptr);
    const auto released = run(osc, 1);
    closing.insert(closing.end(), released.begin(), released.end());
    const auto tail = voiceIdle(osc, 12); // 13 blocks is 416 frames, past the 240-frame ramp
    closing.insert(closing.end(), tail.begin(), tail.end());
    check(biggestJump(closing) < 3.0f * steady,
          "letting go is no sharper than the waveform, " + std::to_string(biggestJump(closing)) +
                  " against " + std::to_string(steady));
    check(peak(voiceIdle(osc, 4)) < 0.001f, "and it is over inside 13 blocks, not a release");
}

void anIdBelongsToTheSourceThatChoseIt() {
    std::printf("an id belongs to the source that chose it\n");
    OscNode voice;
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

/**
 * Unpatching a source ends the note it was holding, and only that source's.
 *
 * There is no crossfade to make on a note cable, so this is the whole mechanism: the synth
 * ends what that source started, because nothing else knows it is sounding. Monophonic, so
 * the shape is "whoever has the voice" rather than "which of eight" -- what has to hold is
 * that the *other* source unpatching takes nothing with it.
 */
void unpatchingASourceEndsItsNotes() {
    std::printf("unpatching a source ends its notes, and only its own\n");
    OscNode voice;
    voice.prepare(kRate);

    NoteBuffer both;
    both.push(noteOn(1, 0, 0));
    both.push(noteOn(2, 7, 1)); // takes the voice, being the later of the two
    voice.setNoteInput(0, &both);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);

    voice.notesCut(0, 0);
    check(peak(voiceAfter(voice, 200)) > 0.1f, "the source that does not hold it takes nothing");
    voice.notesCut(0, 1);
    check(peak(voiceAfter(voice, 200)) < 0.001f, "and the one that does ends it");
    check(!voice.inUse(), "leaving the voice free");
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
        OscNode voice;
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

/**
 * A note over a held one takes the voice, and takes it without a step in the sound.
 *
 * This was "a ninth note steals a voice" when there were eight. With one it is what every
 * legato phrase does, so what it costs matters more: the voice is told it was stolen, and
 * an Osc keeps its gate ramp open rather than dropping to silence and back.
 */
void aSecondNoteTakesTheVoice() {
    std::printf("a second note takes the voice, without a step\n");
    OscNode voice;
    voice.prepare(kRate);
    voice.setParam(0, 3.0f); // a sine, whose own slope is what a step is measured against

    NoteBuffer first;
    first.push(noteOn(1, 0));
    voice.setNoteInput(0, &first);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    voiceIdle(voice, 40);
    auto joined = voiceIdle(voice, 2);
    const float steady = maxStep(joined);

    NoteBuffer second;
    second.push(noteOn(2, 7));
    voice.setNoteInput(0, &second);
    voice.setTiming(0.0, false, nullptr);
    const auto taken = run(voice, 1);
    joined.insert(joined.end(), taken.begin(), taken.end());
    check(peak(voiceAfter(voice, 40)) > 0.1f, "the second note sounds");
    check(maxStep(joined) < 4.0f * steady,
          "and takes the voice without a step, " + std::to_string(maxStep(joined)) +
                  " against " + std::to_string(steady));

    // The first note's voice is gone, so its own off ends nothing.
    NoteBuffer off;
    off.push(noteOff(1));
    voice.setNoteInput(0, &off);
    voice.setTiming(0.0, false, nullptr);
    run(voice, 1);
    check(peak(voiceAfter(voice, 100)) > 0.1f, "and the note it took is not ended by the old one's off");
}

/**
 * The pitch of a steady tone, in hertz, by autocorrelation.
 *
 * Not by counting zero crossings, as the oscillator tests do: a plucked string is rich in
 * harmonics and crosses zero several times a cycle, and read 2753 cycles for a 523Hz note.
 * The lag at which the signal best matches itself is the period whatever its shape;
 * refined between samples by a parabola through the peak.
 */
float pitchOf(const std::vector<float> &samples) {
    const int32_t window = 4096;
    const int32_t shortest = kRate / 2000;
    const int32_t longest = kRate / 40;
    auto correlation = [&](int32_t lag) {
        double sum = 0.0;
        for (int32_t i = 0; i < window; ++i) sum += samples[i] * samples[i + lag];
        return sum;
    };
    // The first lag that comes near the best one, not the best outright: a lag of two
    // periods matches almost as well as one, and a shade better on a decaying tone would
    // report the note an octave down.
    std::vector<double> values(longest + 2);
    double best = 0.0;
    for (int32_t lag = shortest; lag <= longest + 1; ++lag) {
        values[lag] = correlation(lag);
        best = std::max(best, values[lag]);
    }
    int32_t chosen = shortest;
    for (int32_t lag = shortest + 1; lag <= longest; ++lag) {
        if (values[lag] > 0.9 * best && values[lag] >= values[lag - 1] && values[lag] >= values[lag + 1]) {
            chosen = lag;
            break;
        }
    }
    const double a = values[chosen - 1], b = values[chosen], c = values[chosen + 1];
    const double shift = (a - c) / (2.0 * (a - 2.0 * b + c));
    return static_cast<float>(kRate / (chosen + shift));
}

/** One note into [synth], delivered in the block this renders. */
void play(Node &synth, const NoteEvent &event) {
    NoteBuffer notes;
    notes.push(event);
    synth.setNoteInput(0, &notes);
    synth.setTiming(0.0, false, nullptr);
    run(synth, 1);
}

void aPluckSoundsItsNoteAtItsPitch() {
    std::printf("a pluck sounds its note, at its pitch\n");
    PluckNode pluck;
    pluck.prepare(kRate);
    pluck.setParam(0, 0.97f); // decay: past 0.95 the string rings on
    pluck.setParam(2, 0.25f); // stiff: the plain string, neither buzzing nor stiffened

    play(pluck, noteOn(1, 12)); // an octave above middle C
    const auto second = voiceIdle(pluck, kRate / kBlockSize);
    const float level = peak(second);
    check(level > 0.1f, "a plucked note is heard, peak " + std::to_string(level));
    check(level < 2.0f, "and not far louder than an Osc's, peak " + std::to_string(level));

    const float wanted = 2.0f * kMiddleC;
    const float heard = pitchOf(second);
    // Within 5 cents. A string's pitch is its delay's length, and DaisySP compensates for
    // the phase the damping filter adds; this is the check that the voice hands it hertz.
    check(std::fabs(1200.0f * std::log2(heard / wanted)) < 5.0f,
          "at " + std::to_string(wanted) + "Hz, heard " + std::to_string(heard));
}

void aPluckRingsOutWhileHeld() {
    std::printf("a pluck rings out while held, and its voice comes back\n");
    PluckNode pluck;
    pluck.prepare(kRate);
    pluck.setParam(0, 0.1f); // a short decay

    play(pluck, noteOn(1, 0));
    check(peak(voiceIdle(pluck, 10)) > 0.05f, "struck");
    // Four seconds on, still held: a string is not an envelope with a sustain, and nothing
    // is holding it up. The note was never released, so its voice freeing itself is what
    // this is about -- without that the module is deaf until something lets go.
    check(peak(voiceAfter(pluck, 4 * kRate / kBlockSize)) < 0.001f, "and falls silent held");
    check(!pluck.inUse(), "and gives its voice back, though never released");

    // And takes it again for the next note, which is what a freed voice is for: a string
    // that rang out while held must not leave the module deaf.
    play(pluck, noteOn(2, 0));
    check(peak(voiceIdle(pluck, 10)) > 0.05f, "and is struck again after");
    check(pluck.inUse(), "taking the voice back");
}

void aPluckIsLetGoOverItsRelease() {
    std::printf("a pluck is let go over its release\n");
    const float releases[2] = {0.02f, 2.0f};
    float left[2] = {};
    for (int i = 0; i < 2; ++i) {
        PluckNode pluck;
        pluck.prepare(kRate);
        pluck.setParam(0, 0.97f); // would ring forever
        pluck.setParam(3, releases[i]);
        play(pluck, noteOn(1, 0));
        voiceIdle(pluck, 50);
        play(pluck, noteOff(1));
        // A third of a second after the off.
        left[i] = peak(voiceAfter(pluck, kRate / 3 / kBlockSize));
    }
    check(left[0] < 0.001f, "a short release is a muted string, " + std::to_string(left[0]));
    check(left[1] > 0.05f, "a long one lets it ring, " + std::to_string(left[1]));
}

/**
 * How much of [hz] is in [samples], by Goertzel over a Hann window: the size of one bin of
 * a DFT, without computing the rest. Relative, not calibrated -- the FM tests compare one
 * partial against another in the same signal.
 */
float magnitudeAt(const std::vector<float> &samples, float hz) {
    const double w = 2.0 * M_PI * hz / kRate;
    const double coefficient = 2.0 * std::cos(w);
    double s1 = 0.0, s2 = 0.0;
    const std::size_t n = samples.size();
    for (std::size_t i = 0; i < n; ++i) {
        const double hann = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / (n - 1));
        const double s0 = samples[i] * hann + coefficient * s1 - s2;
        s2 = s1;
        s1 = s0;
    }
    return static_cast<float>(std::sqrt(s1 * s1 + s2 * s2 - coefficient * s1 * s2) / n);
}

/** An FM holding middle C. Its three knobs are all it has now; see FmNode. */
void holdFm(FmNode &fm, float ratio, float index, float fall) {
    fm.prepare(kRate);
    fm.setParam(0, ratio);
    fm.setParam(1, index);
    fm.setParam(2, fall);
    play(fm, noteOn(1, 0));
}

void fmWithNoIndexIsASine() {
    std::printf("fm with no index is a sine at the note\n");
    FmNode fm;
    holdFm(fm, 1.0f, 0.0f, 20.0f);
    const auto tone = voiceIdle(fm, 400);
    const float heard = pitchOf(tone);
    check(std::fabs(1200.0f * std::log2(heard / kMiddleC)) < 5.0f,
          "at middle C, heard " + std::to_string(heard));
    const float fundamental = magnitudeAt(tone, kMiddleC);
    check(magnitudeAt(tone, 2.0f * kMiddleC) < 0.01f * fundamental, "and nothing at twice it");
    check(peak(tone) > 0.9f && peak(tone) < 1.1f, "at full level, " + std::to_string(peak(tone)));
}

void fmIndexAndRatioPlaceTheSidebands() {
    std::printf("fm's index makes sidebands, and its ratio places them\n");
    // Ratio 1: sidebands at every multiple of the note, so the second harmonic appears.
    FmNode one;
    holdFm(one, 1.0f, 3.0f, 20.0f);
    const auto harmonic = voiceIdle(one, 400);
    check(magnitudeAt(harmonic, 2.0f * kMiddleC) > 0.2f * magnitudeAt(harmonic, kMiddleC),
          "at ratio 1 an index puts energy at twice the note");

    // Ratio 2: sidebands at f +- 2kf, which is odd multiples only -- 3f strong, 2f absent.
    // A ratio that were ignored, or applied to the carrier, would fail one or the other.
    FmNode two;
    holdFm(two, 2.0f, 2.0f, 20.0f);
    const auto odd = voiceIdle(two, 400);
    const float base = magnitudeAt(odd, kMiddleC);
    check(magnitudeAt(odd, 3.0f * kMiddleC) > 0.2f * base, "at ratio 2, three times the note");
    check(magnitudeAt(odd, 2.0f * kMiddleC) < 0.01f * base, "and nothing at twice it");
}

void fmBrightnessFallsFasterThanLoudness() {
    std::printf("fm's brightness falls faster than its loudness\n");
    FmNode fm;
    holdFm(fm, 1.0f, 4.0f, 0.1f); // a tenth of a second
    const auto early = voiceIdle(fm, 20);
    voiceIdle(fm, kRate / kBlockSize); // a second later
    const auto late = voiceIdle(fm, 20);
    const float earlyRatio = magnitudeAt(early, 2.0f * kMiddleC) / magnitudeAt(early, kMiddleC);
    const float lateRatio = magnitudeAt(late, 2.0f * kMiddleC) / magnitudeAt(late, kMiddleC);
    check(earlyRatio > 0.3f, "bright when struck, " + std::to_string(earlyRatio));
    check(lateRatio < 0.02f, "mellow a second on, " + std::to_string(lateRatio));
    check(peak(late) > 0.9f, "and still as loud: the fall is on the index, not the level");
}

/**
 * A SoundFont made here: one preset, one instrument, one looping sine.
 *
 * The app shipped GeneralUser GS until 2026-09-19 and these tests loaded it out of the
 * assets; it is the user's to install now, so the tests carry their own bank instead. A
 * made one is better for them anyway: 250Hz exactly (192 samples at 48kHz, so the loop is
 * seamless) at key 60, where a real bank's presets are each tuned however their sampler
 * felt -- GeneralUser's piano is 12 cents sharp at C5 and its organs sound an octave down.
 * What these tests measure is the node's tuning, so the bank's own must be exact.
 *
 * Little-endian, as SF2 is and as every machine this builds on is.
 */
std::vector<char> sineBank() {
    std::vector<char> out;
    auto u8 = [&](int v) { out.push_back(static_cast<char>(v & 0xFF)); };
    auto u16 = [&](int v) { u8(v); u8(v >> 8); };
    auto u32 = [&](uint32_t v) { u8(static_cast<int>(v)); u8(static_cast<int>(v >> 8)); u8(static_cast<int>(v >> 16)); u8(static_cast<int>(v >> 24)); };
    auto tag = [&](const char *t) { for (int i = 0; i < 4; ++i) u8(t[i]); };
    auto name20 = [&](const char *n) {
        int i = 0;
        for (; i < 20 && n[i] != 0; ++i) u8(n[i]);
        for (; i < 20; ++i) u8(0);
    };
    // A chunk whose size is filled in once its body is written.
    auto chunk = [&](const char *t, const std::function<void()> &body) {
        tag(t);
        const std::size_t at = out.size();
        u32(0);
        body();
        const uint32_t size = static_cast<uint32_t>(out.size() - at - 4);
        for (int i = 0; i < 4; ++i) out[at + i] = static_cast<char>((size >> (8 * i)) & 0xFF);
    };

    // 192 samples is 250Hz at 48kHz; eight cycles, looped whole.
    constexpr int32_t kCycle = 192;
    constexpr int32_t kCycles = 8;
    constexpr int32_t kSamples = kCycle * kCycles;

    tag("RIFF");
    const std::size_t riffSize = out.size();
    u32(0);
    tag("sfbk");

    chunk("LIST", [&] {
        tag("INFO");
        chunk("ifil", [&] { u16(2); u16(1); });
        chunk("isng", [&] { name20("EMU8000"); });
        chunk("INAM", [&] { name20("Sine"); });
    });
    chunk("LIST", [&] {
        tag("sdta");
        chunk("smpl", [&] {
            for (int32_t i = 0; i < kSamples; ++i) {
                const double phase = 2.0 * M_PI * (i % kCycle) / kCycle;
                u16(static_cast<int>(std::lround(std::sin(phase) * 16000.0)) & 0xFFFF);
            }
            // The 46 zero samples SF2 requires after each sample.
            for (int32_t i = 0; i < 46; ++i) u16(0);
        });
    });
    chunk("LIST", [&] {
        tag("pdta");
        chunk("phdr", [&] {
            name20("Sine"); u16(0); u16(0); u16(0); u32(0); u32(0); u32(0);   // preset 0, bank 0
            name20("EOP"); u16(0); u16(0); u16(1); u32(0); u32(0); u32(0);    // terminal
        });
        chunk("pbag", [&] { u16(0); u16(0); u16(1); u16(0); });
        chunk("pmod", [&] { for (int i = 0; i < 10; ++i) u8(0); });
        chunk("pgen", [&] {
            u16(41); u16(0);   // instrument 0
            u16(0); u16(0);    // terminal
        });
        chunk("inst", [&] {
            name20("SineI"); u16(0);
            name20("EOI"); u16(1);
        });
        chunk("ibag", [&] { u16(0); u16(0); u16(3); u16(0); });
        chunk("imod", [&] { for (int i = 0; i < 10; ++i) u8(0); });
        chunk("igen", [&] {
            u16(43); u8(0); u8(127);   // key range, every key
            u16(54); u16(1);           // sample modes: loop continuously
            u16(53); u16(0);           // sample 0
            u16(0); u16(0);            // terminal
        });
        chunk("shdr", [&] {
            name20("SineS");
            u32(0); u32(kSamples); u32(0); u32(kSamples);   // start, end, loop start, loop end
            u32(48000);
            u8(60); u8(0);            // key 60 plays it at its own rate; no correction
            u16(0); u16(1);           // no link, mono
            name20("EOS");
            u32(0); u32(0); u32(0); u32(0); u32(0); u8(0); u8(0); u16(0); u16(0);
        });
    });

    const uint32_t size = static_cast<uint32_t>(out.size() - riffSize - 4);
    for (int i = 0; i < 4; ++i) out[riffSize + i] = static_cast<char>((size >> (8 * i)) & 0xFF);
    return out;
}

/** What a note at key 60 sounds as in the made bank: 192 samples a cycle at 48kHz. */
constexpr float kBankHz = 250.0f;

/** The made bank, parsed once. */
const SoundFont *testBank() {
    static std::unique_ptr<SoundFont> bank = [] {
        const std::vector<char> bytes = sineBank();
        return std::unique_ptr<SoundFont>(SoundFont::load(bytes.data(), static_cast<int32_t>(bytes.size())));
    }();
    return bank.get();
}

/** An SF node playing [program] of bank 0 from the made bank. */
void readySf(SfNode &sf, int32_t program) {
    sf.prepare(kRate);
    sf.setParam(0, static_cast<float>(program));
    Resource *none = sf.swapResource(new SoundFontSynth(*testBank()));
    check(none == nullptr, "a fresh node had no synth to give back");
}

void theMadeBankLoadsWithItsPreset() {
    std::printf("the made bank loads, with its preset\n");
    const SoundFont *bank = testBank();
    check(bank != nullptr, "it parses");
    if (bank == nullptr) return;
    check(bank->presetCount() == 1, "one preset, " + std::to_string(bank->presetCount()));
    check(bank->presetBank(0) == 0 && bank->presetProgram(0) == 0,
          "bank 0 program 0, which is what a new SF plays");
    check(std::string(bank->presetName(0)) == "Sine", "named");
    const char junk[] = "RIFF....not a soundfont";
    check(SoundFont::load(junk, sizeof(junk)) == nullptr, "and anything else is refused");
}

void anSfPlaysItsNoteInTune() {
    std::printf("an sf plays its note in tune, a quarter tone included\n");
    if (testBank() == nullptr) return;
    const float cents[2] = {0.0f, 50.0f};
    for (float c : cents) {
        SfNode sf;
        readySf(sf, 0);
        NoteEvent note = noteOn(1, 12); // an octave above middle C
        note.cents = c;
        play(sf, note);
        voiceIdle(sf, 100); // past the attack
        const auto tone = voiceIdle(sf, 300);
        const float wanted = 2.0f * kBankHz * std::exp2(c / 1200.0f);
        const float heard = pitchOf(tone);
        // A quarter tone is between two keys, so this is the channel's tuning at work: the
        // path every non-12 scale takes.
        check(std::fabs(1200.0f * std::log2(heard / wanted)) < 5.0f,
              "at " + std::to_string(wanted) + "Hz, heard " + std::to_string(heard));
        check(peak(tone) > 0.1f && peak(tone) < 1.5f, "at a level beside an Osc's, " + std::to_string(peak(tone)));
    }
}

void anSfGlidesAndLetsGo() {
    std::printf("an sf glides a held note, and lets it go\n");
    if (testBank() == nullptr) return;
    SfNode sf;
    readySf(sf, 0);
    play(sf, noteOn(1, 12));
    voiceIdle(sf, 100);
    NoteEvent move = noteOn(1, 16);
    move.kind = NoteKind::Change;
    play(sf, move);
    voiceIdle(sf, 100); // well past the 30ms glide
    const float wanted = 2.0f * kBankHz * std::exp2(4.0f / 12.0f);
    const float heard = pitchOf(voiceIdle(sf, 300));
    check(std::fabs(1200.0f * std::log2(heard / wanted)) < 5.0f,
          "a Change moves it to " + std::to_string(wanted) + "Hz, heard " + std::to_string(heard));
    check(sf.notesHeld() == 1, "and it is still the one note");

    play(sf, noteOff(1));
    check(sf.notesHeld() == 0, "an off lets it go");
    check(peak(voiceAfter(sf, 3 * kRate / kBlockSize)) < 0.001f, "and it falls silent");
}

void anSfWithoutItsFontIsSilent() {
    std::printf("an sf without its font is silent, and takes one later\n");
    SfNode sf;
    sf.prepare(kRate);
    sf.setParam(0, static_cast<float>(0));
    play(sf, noteOn(1, 12));
    check(peak(voiceIdle(sf, 20)) == 0.0f, "silent while the font loads");
    if (testBank() == nullptr) return;

    // The note it was sent before its font is struck when the font arrives: a drone's
    // chord is sent once, and a node that dropped it would stay silent until retoggled.
    delete sf.swapResource(new SoundFontSynth(*testBank()));
    voiceIdle(sf, 100);
    const float heard = pitchOf(voiceIdle(sf, 300));
    check(std::fabs(1200.0f * std::log2(heard / (2.0f * kBankHz))) < 5.0f,
          "a note held before the font sounds once it lands, heard " + std::to_string(heard));
    play(sf, noteOff(1));
    voiceIdle(sf, 3 * kRate / kBlockSize);
    // Swapping in a synth hands back the one it replaces, for the graph to free off the
    // audio thread. Under ASan a dropped one is a leak, and a double free a crash.
    delete sf.swapResource(new SoundFontSynth(*testBank()));
    Resource *old = sf.swapResource(new SoundFontSynth(*testBank()));
    check(old != nullptr, "a second synth gives the first back");
    delete old;
    sf.setParam(0, static_cast<float>(0));
    play(sf, noteOn(2, 0));
    check(peak(voiceIdle(sf, 200)) > 0.05f, "and sounds once it has one");
}

/** Whole steps, in the quarter steps a dot's length is counted in. See SeqNode. */
constexpr int32_t Q(int32_t steps) { return steps * SeqNode::kDotSubsteps; }

/** Ticks [dots] once at [count] and returns what it said. */
NoteBuffer tickDots(SeqNode &dots, int64_t count, int32_t offset = 0) {
    dots.setTiming(0.0, true, nullptr);
    dots.tick(offset, count);
    dots.process(kBlockSize);
    return *dots.noteOutput(0);
}

int countKind(const NoteBuffer &notes, NoteKind kind) {
    int n = 0;
    for (int32_t i = 0; i < notes.count; ++i) n += notes.events[i].kind == kind ? 1 : 0;
    return n;
}

void aDotLastsItsLength() {
    std::printf("a dot lasts its length, in steps\n");
    SeqNode dots;
    dots.setDot(0, 0, 7, Q(3), 1.0f);
    const NoteBuffer first = tickDots(dots, 0);
    check(countKind(first, NoteKind::On) == 1 && first.events[0].degree == 7, "starts on its step");
    const uint32_t id = first.events[0].id;
    check(countKind(tickDots(dots, 1), NoteKind::Off) == 0, "held through the second step");
    check(countKind(tickDots(dots, 2), NoteKind::Off) == 0, "and the third");
    const NoteBuffer end = tickDots(dots, 3);
    check(countKind(end, NoteKind::Off) == 1 && end.events[0].id == id, "and ends as the fourth begins");
}

void aDotIsStruckAtItsVelocity() {
    std::printf("a dot is struck at its own velocity\n");
    const auto same = [](float a, float b) { return std::fabs(a - b) < 1e-5f; };
    SeqNode dots;
    dots.setDot(0, 0, 0, Q(1), 0.4f);
    dots.setDot(1, 0, 7, Q(1), 1.0f);
    const NoteBuffer said = tickDots(dots, 0);
    check(said.count == 2, "two notes");
    check(same(said.events[0].velocity, 0.4f), "the quiet one says so");
    check(same(said.events[1].velocity, 1.0f), "and the other is full");

    // Out of range from a hand-edited file or some future interface: clamped rather than
    // trusted, since a velocity above one is an oscillator amplitude above one.
    SeqNode wild;
    wild.setDot(0, 0, 0, Q(1), 4.0f);
    wild.setDot(1, 0, 7, Q(1), -1.0f);
    const NoteBuffer clamped = tickDots(wild, 0);
    check(same(clamped.events[0].velocity, 1.0f), "above one is one");
    check(same(clamped.events[1].velocity, 0.0f), "below zero is zero");
}

/**
 * The click velocity brought with it, and the reason the level lives in GateRamp.
 *
 * Two notes that abut -- the second's On in the same block as the first's Off, which is what
 * a sequencer sends when a dot is a whole step long -- leave the gate ramp open on purpose,
 * because closing and reopening it is the step the ramp exists to avoid. Velocity as an
 * oscillator amplitude, set outright at the strike, then put that step back: the waveform
 * jumped by the whole difference between the two velocities. Found on the phone as clicking
 * between notes that went away when every note was at full, which is the tell -- equal
 * velocities have no difference to step.
 */
void abuttingNotesAtDifferentVelocitiesDoNotStep() {
    std::printf("a second note at another velocity does not step the waveform\n");
    const auto worstStep = [](float first, float second) {
        OscNode osc;
        osc.prepare(kRate);
        osc.setParam(0, 3.0f); // a sine, where a step in the level has nowhere to hide

        NoteBuffer on;
        NoteEvent start = noteOn(1, 0);
        start.velocity = first;
        on.push(start);
        osc.setNoteInput(0, &on);
        osc.setTiming(0.0, false, nullptr);
        run(osc, 1);
        std::vector<float> all = voiceIdle(osc, 20);

        // Ends before starts, in one buffer, exactly as SeqNode orders them.
        NoteBuffer turn;
        turn.push(noteOff(1));
        NoteEvent next = noteOn(2, 0);
        next.velocity = second;
        turn.push(next);
        osc.setNoteInput(0, &turn);
        osc.setTiming(0.0, false, nullptr);
        const std::vector<float> across = run(osc, 1);
        all.insert(all.end(), across.begin(), across.end());
        const std::vector<float> after = voiceIdle(osc, 20);
        all.insert(all.end(), after.begin(), after.end());
        return maxStep(all);
    };

    // The waveform's own largest step at this pitch, which is the floor for any of this.
    const float steady = worstStep(1.0f, 1.0f);
    check(steady < 0.05f, "two notes at one velocity are smooth");
    check(worstStep(1.0f, 0.4f) < steady * 1.5f, "and so is a quieter note after a loud one");
    check(worstStep(0.2f, 1.0f) < steady * 1.5f, "and a loud one after a quiet one");
}

/**
 * What velocity is worth having: the same note, quieter.
 *
 * Measured out of an Osc rather than asserted from the event, because velocity reaches the
 * sound through OscVoice::strike and nowhere else -- if that ever stops setting the
 * amplitude, this fails while every event in the graph still carries the right number.
 */
void velocityIsHeard() {
    std::printf("a quieter note is a quieter sound\n");
    const auto peakAt = [](float velocity) {
        OscNode osc;
        osc.prepare(kRate);
        NoteBuffer notes;
        NoteEvent on = noteOn(1, 0);
        on.velocity = velocity;
        notes.push(on);
        osc.setNoteInput(0, &notes);
        osc.setTiming(0.0, false, nullptr);
        run(osc, 1);
        // Past the 5ms gate ramp, and long enough to catch a peak of the waveform rather
        // than wherever one block happened to land -- see voiceAfter.
        return peak(voiceIdle(osc, 16));
    };
    const float full = peakAt(1.0f);
    const float half = peakAt(0.5f);
    check(full > 0.5f, "a full note sounds");
    check(half < full * 0.6f && half > full * 0.4f, "and half the velocity is about half of it");
}

void aColumnOfDotsIsAChord() {
    std::printf("a column of dots is a chord, each note its own length\n");
    SeqNode dots;
    dots.setDot(0, 0, 0, Q(1), 1.0f);
    dots.setDot(1, 0, 4, Q(2), 1.0f);
    dots.setDot(2, 0, 7, Q(4), 1.0f);
    check(countKind(tickDots(dots, 0), NoteKind::On) == 3, "three notes start together");
    check(countKind(tickDots(dots, 1), NoteKind::Off) == 1, "the shortest ends first");
    check(countKind(tickDots(dots, 2), NoteKind::Off) == 1, "then the next");
    check(dots.notesHeld() == 1, "leaving the longest");
    NoteBuffer held;
    dots.heldNotes(0, held);
    check(held.count == 1 && held.events[0].degree == 7, "which is what a new cable is told is held");
}

void aDotEndsBeforeTheNextStarts() {
    std::printf("a dot ends before the next one at its degree starts\n");
    SeqNode dots;
    dots.setDot(0, 0, 5, Q(2), 1.0f);
    dots.setDot(1, 2, 5, Q(1), 1.0f);
    tickDots(dots, 0);
    tickDots(dots, 1);
    const NoteBuffer turn = tickDots(dots, 2);
    check(turn.count == 2, "an off and an on");
    check(turn.events[0].kind == NoteKind::Off && turn.events[1].kind == NoteKind::On,
          "the off first, so the two are two notes");
}

void dotsLoopAtTheLength() {
    std::printf("dots loop at the sequence's length\n");
    SeqNode dots;
    dots.setParam(0, 4.0f);
    dots.setDot(0, 1, 2, Q(1), 1.0f);
    int ons = 0;
    for (int64_t count = 0; count < 12; ++count) {
        const NoteBuffer said = tickDots(dots, count);
        if (countKind(said, NoteKind::On) > 0) {
            check(count % 4 == 1, "only on step 1 of each turn, not " + std::to_string(count));
            ++ons;
        }
    }
    check(ons == 3, "three turns, three notes");
    dots.setDot(0, 0, 0, 0, 1.0f);
    int after = 0;
    for (int64_t count = 12; count < 20; ++count) after += countKind(tickDots(dots, count), NoteKind::On);
    check(after == 0, "and a cleared slot plays nothing");
}

void aJumpInTimeEndsWhatWasHeld() {
    std::printf("a jump in time ends what was held\n");
    SeqNode dots;
    dots.setDot(0, 0, 0, Q(8), 1.0f);
    tickDots(dots, 0);
    check(dots.notesHeld() == 1, "held");
    // The transport reset: the tick eight steps on, which would have ended it, may never
    // come -- so the note ends where the count jumped.
    const NoteBuffer reset = tickDots(dots, 0);
    check(countKind(reset, NoteKind::Off) == 1, "ended at the jump");
    check(countKind(reset, NoteKind::On) == 1, "and struck again, since step 0 has a dot");
}

/** [events] into processor [node] as one block, and what came out. */
NoteBuffer through(Node &node, std::initializer_list<NoteEvent> events) {
    NoteBuffer in;
    for (const auto &e : events) in.push(e);
    node.setNoteInput(0, &in);
    node.setTiming(0.0, true, nullptr);
    node.process(kBlockSize);
    node.setNoteInput(0, &kNoNotes);
    return *node.noteOutput(0);
}

/** One tick at [count] into a clocked node, with [events] arriving on the same sample. */
NoteBuffer tickWith(Node &node, int64_t count, std::initializer_list<NoteEvent> events = {}) {
    NoteBuffer in;
    for (const auto &e : events) in.push(e);
    node.setNoteInput(0, &in);
    node.setTiming(0.0, true, nullptr);
    node.tick(0, count);
    node.process(kBlockSize);
    node.setNoteInput(0, &kNoNotes);
    return *node.noteOutput(0);
}

/** The degree of the first On in [notes], or -999 if there is none. */
int firstOnDegree(const NoteBuffer &notes) {
    for (int32_t i = 0; i < notes.count; ++i) {
        if (notes.events[i].kind == NoteKind::On) return notes.events[i].degree;
    }
    return -999;
}

/**
 * A length that is not a whole number of steps ends partway through one.
 *
 * This is what the `gate` knob used to do to every note at once, and why it went: a length
 * in quarter steps says it per note. Steps' half step is a length of 2.
 */
void aDotEndsPartwayThroughAStep() {
    std::printf("a dot whose length is not whole steps ends inside one\n");
    // 120bpm is two beats a second; an eighth-note step is half a beat, 12000 frames.
    const double beatsPerFrame = 2.0 / kRate;
    const int32_t stepFrames = 12000;
    auto offsIn = [&](SeqNode &seq, int32_t frames) {
        int offs = 0;
        for (int32_t done = 0; done < frames; done += kBlockSize) {
            seq.setTiming(beatsPerFrame, true, nullptr);
            seq.process(kBlockSize);
            offs += countKind(*seq.noteOutput(0), NoteKind::Off);
        }
        return offs;
    };
    auto tickAt = [&](SeqNode &seq, int64_t count) {
        seq.setTiming(beatsPerFrame, true, nullptr);
        seq.tick(0, count);
        seq.process(kBlockSize);
        return *seq.noteOutput(0);
    };

    SeqNode seq;
    seq.setDot(0, 0, 0, Q(1) + 2, 1.0f); // a step and a half
    check(countKind(tickAt(seq, 0), NoteKind::On) == 1, "starts");
    check(offsIn(seq, stepFrames - kBlockSize) == 0, "sounds all through its first step");
    tickAt(seq, 1);
    check(offsIn(seq, stepFrames / 2 - 2 * kBlockSize) == 0, "and the first half of the next");
    check(offsIn(seq, 4 * kBlockSize) == 1, "and ends halfway through it");
    check(countKind(tickAt(seq, 2), NoteKind::Off) == 0, "not again on the next tick");

    // Shorter than a step: it has no whole steps at all, so its part starts on the tick it
    // does -- the case that has to be counted out after the starts rather than before them.
    SeqNode half;
    half.setDot(0, 0, 0, 2, 1.0f);
    check(countKind(tickAt(half, 0), NoteKind::On) == 1, "a half-step note starts");
    check(offsIn(half, stepFrames / 2 - 2 * kBlockSize) == 0, "and holds half a step");
    check(offsIn(half, 4 * kBlockSize) == 1, "then ends, without waiting for a tick");

    SeqNode legato;
    legato.setDot(0, 0, 0, Q(1), 1.0f);
    tickAt(legato, 0);
    check(offsIn(legato, stepFrames - kBlockSize) == 0, "a whole-step note sounds its whole step");
    check(countKind(tickAt(legato, 1), NoteKind::Off) == 1, "and ends on the tick after");
}

void aDroneTransposeMovesWhatItHolds() {
    std::printf("a drone's transpose moves what it holds\n");
    DroneNode drone;
    drone.setStep(0, 0, true);
    drone.setTiming(0.0, false, nullptr);
    drone.process(kBlockSize);
    check(drone.noteOutput(0)->count == 1, "holding one note");

    drone.setParam(0, -1200.0f);
    drone.setTiming(0.0, false, nullptr);
    drone.process(kBlockSize);
    const NoteBuffer &moved = *drone.noteOutput(0);
    check(moved.count == 1 && moved.events[0].kind == NoteKind::Change && moved.events[0].cents == -1200.0f,
          "a turned knob sends the held note down an octave, as a glide");

    drone.setStep(1, 7, true);
    drone.setTiming(0.0, false, nullptr);
    drone.process(kBlockSize);
    check(firstOnDegree(*drone.noteOutput(0)) == 7 && drone.noteOutput(0)->events[0].cents == -1200.0f,
          "and a new note starts there too");
    NoteBuffer held;
    drone.heldNotes(0, held);
    check(held.count == 2 && held.events[0].cents == -1200.0f, "which a late cable is told");
}

void chanceDecidesEachNoteOnce() {
    std::printf("chance decides each note once, and its off follows it\n");
    ChanceNode all;
    all.setParam(0, 1.0f);
    check(countKind(through(all, {noteOn(1, 0)}), NoteKind::On) == 1, "at 1 every note passes");
    check(countKind(through(all, {noteOff(1)}), NoteKind::Off) == 1, "and its off");

    ChanceNode none;
    none.setParam(0, 0.0f);
    check(countKind(through(none, {noteOn(1, 0)}), NoteKind::On) == 0, "at 0 none does");
    check(through(none, {noteOff(1)}).count == 0, "and a dropped note's off says nothing");

    ChanceNode half;
    half.setParam(0, 0.5f);
    int passed = 0;
    for (uint32_t id = 1; id <= 1000; ++id) {
        passed += countKind(through(half, {noteOn(id, 0)}), NoteKind::On);
        through(half, {noteOff(id)});
    }
    check(passed > 420 && passed < 580, "at 0.5, about half: " + std::to_string(passed));

    // Unpatching the source ends what it had passed, downstream too.
    ChanceNode cut;
    cut.setParam(0, 1.0f);
    through(cut, {noteOn(1, 0, 3), noteOn(2, 4, 5)});
    cut.notesCut(0, 3);
    const NoteBuffer after = through(cut, {});
    check(countKind(after, NoteKind::Off) == 1, "a cut source's note is ended in the next block");
}

void chordMakesEveryNoteAChord() {
    std::printf("chord makes every note a chord, and ends it whole\n");
    ChordNode chord; // 4 and 7 by default: a major triad in twelve equal steps
    const NoteBuffer on = through(chord, {noteOn(1, 2)});
    check(countKind(on, NoteKind::On) == 3, "three notes for one");
    check(on.events[0].degree == 2 && on.events[1].degree == 6 && on.events[2].degree == 9,
          "the note and its intervals, in degrees");
    check(on.events[0].id != on.events[1].id && on.events[1].id != on.events[2].id, "each its own note");

    NoteEvent move = noteOn(1, 3);
    move.kind = NoteKind::Change;
    const NoteBuffer moved = through(chord, {move});
    check(countKind(moved, NoteKind::Change) == 3 && moved.events[2].degree == 10, "a change moves the chord");

    const NoteBuffer off = through(chord, {noteOff(1)});
    check(countKind(off, NoteKind::Off) == 3, "and an off ends all of it");
    for (int32_t i = 0; i < 3; ++i) {
        check(off.events[i].id == on.events[i].id, "each off matched to its on");
    }

    ChordNode single;
    single.setParam(0, 0.0f);
    single.setParam(1, 0.0f);
    check(countKind(through(single, {noteOn(1, 0)}), NoteKind::On) == 1, "intervals of 0 add nothing");
}

void anArpPlaysWhatIsHeld() {
    std::printf("an arp plays what is held, in each of its modes\n");
    auto run = [](int mode, int octaves, int ticks) {
        ArpNode arp;
        arp.setParam(0, static_cast<float>(mode));
        arp.setParam(1, static_cast<float>(octaves));
        std::vector<int> heard;
        heard.push_back(firstOnDegree(tickWith(arp, 0, {noteOn(1, 7), noteOn(2, 0), noteOn(3, 4)})));
        for (int t = 1; t < ticks; ++t) heard.push_back(firstOnDegree(tickWith(arp, t)));
        return heard;
    };
    check(run(0, 1, 6) == std::vector<int>{0, 4, 7, 0, 4, 7}, "up, in order of pitch whatever the order held");
    check(run(1, 1, 4) == std::vector<int>{7, 4, 0, 7}, "down");
    check(run(2, 1, 7) == std::vector<int>{0, 4, 7, 4, 0, 4, 7}, "up and down, not repeating the ends");
    check(run(0, 2, 7) == std::vector<int>{0, 4, 7, 12, 16, 19, 0}, "and on up an octave");

    ArpNode arp;
    tickWith(arp, 0, {noteOn(1, 0)});
    const NoteBuffer next = tickWith(arp, 1, {noteOff(1)});
    check(countKind(next, NoteKind::Off) == 1 && countKind(next, NoteKind::On) == 0,
          "let go of everything and it ends its note and plays no more");
}

void euclidSpreadsItsPulses() {
    std::printf("euclid spreads its pulses as evenly as they go\n");
    std::string tresillo;
    for (int i = 0; i < 8; ++i) tresillo += EuclidNode::hit(i, 8, 3, 0) ? 'x' : '.';
    check(tresillo == "x..x..x.", "3 over 8 is the tresillo, got " + tresillo);
    std::string five;
    for (int i = 0; i < 8; ++i) five += EuclidNode::hit(i, 8, 5, 0) ? 'x' : '.';
    check(std::count(five.begin(), five.end(), 'x') == 5, "5 over 8 has five, " + five);
    std::string turned;
    for (int i = 0; i < 8; ++i) turned += EuclidNode::hit(i, 8, 3, 1) ? 'x' : '.';
    check(turned == "..x..x.x", "rotate turns it, " + turned);

    EuclidNode euclid;
    euclid.setParam(3, 5.0f);
    std::string played;
    for (int64_t t = 0; t < 8; ++t) {
        const NoteBuffer said = tickWith(euclid, t);
        played += countKind(said, NoteKind::On) > 0 ? 'x' : '.';
        if (t == 0) check(firstOnDegree(said) == 5, "at its degree");
    }
    check(played == tresillo, "and plays what it spreads, " + played);
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

// ---------------------------------------------------------------- a drone follows the scale

namespace {

/** The first event of [kind] a drone left in the block just rendered, or null. */
const NoteEvent *firstOf(const DroneNode &drone, NoteKind kind) {
    const NoteBuffer &notes = *drone.noteOutput(0);
    for (int32_t i = 0; i < notes.count; ++i) {
        if (notes.events[i].kind == kind) return &notes.events[i];
    }
    return nullptr;
}

/** One block with a tick at [offset], against [scales]. */
void tickDrone(DroneNode &drone, int64_t count, int32_t offset, const ScaleList &scales) {
    drone.setTiming(kBeatsPerFrame, true, &scales);
    drone.tick(offset, count);
    drone.process(kBlockSize);
}

} // namespace

/**
 * Found on the phone: a drone holding degree 10 through a list of 12-TET and Harmonic minor
 * never changed pitch, because a note resolved its scale once, at note-on, and a drone's
 * notes never end. Degree 10 is B-flat in 12-TET and the F an octave and a fourth up in
 * major, so a Change has to be sent at the beat the scale turns.
 */
void aDroneRetunesItsHeldNotesWhenTheScaleChanges() {
    std::printf("a drone retunes its held notes when the scale changes\n");
    DroneNode drone;
    drone.prepare(kRate);
    drone.setStep(10, 10, true);

    tickDrone(drone, 3, 0, chromaticThenMajor()); // beat 3: the last of 12-TET
    const NoteEvent *on = firstOf(drone, NoteKind::On);
    check(on != nullptr, "the note starts");
    const uint32_t id = on != nullptr ? on->id : 0;
    check(firstOf(drone, NoteKind::Change) == nullptr, "and nothing moves it in the scale it started in");

    tickDrone(drone, 4, 7, chromaticThenMajor()); // beat 4, on sample 7: major
    const NoteEvent *change = firstOf(drone, NoteKind::Change);
    check(change != nullptr, "the held note is told the scale turned");
    if (change != nullptr) {
        check(change->id == id, "naming the note that is sounding");
        check(change->degree == 10, "with its degree");
        check(change->beat == 4, "and the beat that chooses its new scale");
        check(change->offset == 7, "on the tick's own sample");
    }

    tickDrone(drone, 5, 0, chromaticThenMajor()); // beat 5: still major
    check(firstOf(drone, NoteKind::Change) == nullptr, "and nothing more while the scale holds");
}

void aDroneSaysNothingForADegreeTheChangeLeavesWhereItWas() {
    std::printf("a drone says nothing for a degree the change leaves where it was\n");
    DroneNode drone;
    drone.prepare(kRate);
    drone.setStep(0, 0, true); // the tonic is the tonic in both scales

    tickDrone(drone, 3, 0, chromaticThenMajor());
    tickDrone(drone, 4, 0, chromaticThenMajor());
    check(firstOf(drone, NoteKind::Change) == nullptr, "a pitch that did not move is not sent");
}

/**
 * The list can be replaced with the transport stopped, when no tick is coming: editing the
 * scale must still retune what is holding, at the top of the next block.
 */
void aReplacedScaleListRetunesADroneWithTheTransportStopped() {
    std::printf("a replaced scale list retunes a drone with the transport stopped\n");
    DroneNode drone;
    drone.prepare(kRate);
    drone.setStep(2, 2, true);
    tickDrone(drone, 4, 0, chromaticThenMajor()); // beat 4, major: degree 2 is E

    drone.setTiming(0.0, false, &cThenG()); // same beat, now G: degree 2 is A
    drone.process(kBlockSize);
    const NoteEvent *change = firstOf(drone, NoteKind::Change);
    check(change != nullptr, "the held note is retuned without a tick");
    if (change != nullptr) {
        check(change->offset == 0, "at the top of the block");
        check(change->beat == 4, "against the beat it already knew");
    }
}

/**
 * The other half: an oscillator told to move a held note glides there. Degree 10 in 12-TET
 * is 466Hz and in major 698Hz. Over the 30ms glide it has to be neither -- a step would read
 * as the new pitch from the first cycle, which is the transient that got retuning rejected
 * the first time.
 */
void aHeldNoteGlidesToItsNewPitch() {
    std::printf("a held note glides to its new pitch\n");
    OscNode osc;
    osc.setTiming(kBeatsPerFrame, true, &chromaticThenMajor());
    const auto on = noteAt(NoteKind::On, 1, 0, 10);
    holdDegree(osc, 10, on);
    const int before = countCycles(run(osc, kRate / kBlockSize));
    check(std::abs(before - 466) <= 3, "degree 10 in 12-TET, got " + std::to_string(before));

    NoteBuffer move = noteAt(NoteKind::Change, 1, 0, 10);
    move.events[0].beat = 4; // major
    osc.setNoteInput(0, &move);
    std::vector<float> glide = run(osc, 1);
    osc.setNoteInput(0, &kNoNotes);
    const auto rest = run(osc, 44); // 45 blocks in all: exactly the 1440-frame glide
    glide.insert(glide.end(), rest.begin(), rest.end());
    const int during = countCycles(glide);
    // 14 cycles if it never moved, 21 if it stepped at once; a glide is between.
    check(during >= 15 && during <= 19, "partway between the two during the glide, got " + std::to_string(during));

    const int after = countCycles(run(osc, kRate / kBlockSize));
    check(std::abs(after - 698) <= 4, "and degree 10 in major once it arrives, got " + std::to_string(after));
}

void aChangeForANoteNobodyHoldsMovesNothing() {
    std::printf("a change for a note nobody holds moves nothing\n");
    OscNode osc;
    osc.setTiming(kBeatsPerFrame, true, &chromaticThenMajor());
    const auto on = noteAt(NoteKind::On, 1, 0, 10);
    holdDegree(osc, 10, on);

    NoteBuffer stray = noteAt(NoteKind::Change, 99, 0, 10); // an id no voice has
    stray.events[0].beat = 4;
    osc.setNoteInput(0, &stray);
    osc.process(kBlockSize);
    osc.setNoteInput(0, &kNoNotes);
    const int cycles = countCycles(run(osc, kRate / kBlockSize));
    check(std::abs(cycles - 466) <= 3, "the note that is held stays put, got " + std::to_string(cycles));
}

void aDroneReportsTheNotesItIsHolding() {
    std::printf("a drone reports the notes it is holding\n");
    DroneNode drone;
    drone.prepare(kRate);
    drone.setStep(4, 4, true);
    drone.setStep(9, 9, true);
    tickDrone(drone, 3, 0, chromaticThenMajor());
    uint32_t ids[2] = {};
    const NoteBuffer &started = *drone.noteOutput(0);
    for (int32_t i = 0; i < started.count && i < 2; ++i) ids[i] = started.events[i].id;

    drone.setStep(4, 4, false);
    tickDrone(drone, 4, 0, chromaticThenMajor()); // degree 4 ends; the scale turns to major

    NoteBuffer held;
    drone.heldNotes(0, held);
    check(held.count == 1, "only the cell still on");
    if (held.count == 1) {
        check(held.events[0].kind == NoteKind::On, "as a start");
        check(held.events[0].id == ids[1], "under the id it has been sounding by");
        check(held.events[0].degree == 9, "with its degree");
        check(held.events[0].beat == 4, "and the beat whose scale it is sounding in now");
    }

    DroneNode silent;
    silent.prepare(kRate);
    NoteBuffer none;
    silent.heldNotes(0, none);
    check(none.count == 0, "an untouched drone holds nothing");
}

int main() {
    oscPlaysTheRequestedPitch();
    oscStaysBandLimited();
    filterCutoffFollowsItsKnob();
    aFilterHasFourKinds();
    theSteeperSlopeIsSteeper();
    resonanceIsBoundedAtEveryKindAndSlope();
    fullResonanceRingsButDoesNotOscillate();
    theCutoffFollowsTheNote();
    aTrackedCutoffSlidesRatherThanJumping();
    envFollowsTheNotesItIsHolding();
    envSustainsUnderAChordAndWaitsForTheLastNote();
    envEndsTheNotesOfASourceThatWasUnpatched();
    envMatchesAnOffAgainstItsOwnSource();
    aMixChannelIsAGainThatCanBeShut();
    anAmpMultipliesAndIsOpenWithNothingPatched();
    aPolySubpatchGivesEachNoteItsOwnInstance();
    aPolySubpatchUsesOnlyTheInstancesItsKnobAllows();
    stealingAnInstanceEndsTheNoteItWasHolding();
    aPolySubpatchMatchesAnOffAgainstItsOwnSource();
    aPolySubpatchEndsAndDeliversHeldNotes();
    aPolySubpatchSumsItsInstances();
    stepsTakeTheirStepFromTheCount();
    stepsPlayTheirOwnPattern();
    aClosedGateIsARestNotASkip();
    aRestKeepsTheNoteItRemembers();
    aNoteLastsHalfItsStep();
    aStoppedTransportHoldsTheNote();
    aTickLandsOnItsOwnSample();
    theIntervalIsChosenByParameter();
    aNoteTakesTheScaleOfTheBeatItStartsOn();
    aTripletOnTheSwitchBeatTakesTheNewScale();
    aKeyChangeLandsOnItsBeat();
    mixSumsRatherThanAverages();
    outPassesAudioAtLevel();
    outProtectsTheListener();
    theNotesOutputSaysWhatTheGateSays();
    aRestStartsNothing();
    aTransposeRidesOnTheNote();
    aSynthSoundsOneNoteAtATime();
    aNoteStartsAndStopsWithoutAStep();
    anIdBelongsToTheSourceThatChoseIt();
    unpatchingASourceEndsItsNotes();
    aVoiceResolvesANoteAgainstItsOwnBeat();
    aSecondNoteTakesTheVoice();
    aPluckSoundsItsNoteAtItsPitch();
    aPluckRingsOutWhileHeld();
    aPluckIsLetGoOverItsRelease();
    fmWithNoIndexIsASine();
    fmIndexAndRatioPlaceTheSidebands();
    fmBrightnessFallsFasterThanLoudness();
    theMadeBankLoadsWithItsPreset();
    anSfPlaysItsNoteInTune();
    anSfGlidesAndLetsGo();
    anSfWithoutItsFontIsSilent();
    aDotLastsItsLength();
    aDotIsStruckAtItsVelocity();
    abuttingNotesAtDifferentVelocitiesDoNotStep();
    velocityIsHeard();
    aColumnOfDotsIsAChord();
    aDotEndsBeforeTheNextStarts();
    dotsLoopAtTheLength();
    aJumpInTimeEndsWhatWasHeld();
    aDotEndsPartwayThroughAStep();
    aDroneTransposeMovesWhatItHolds();
    chanceDecidesEachNoteOnce();
    chordMakesEveryNoteAChord();
    anArpPlaysWhatIsHeld();
    euclidSpreadsItsPulses();
    anLfoStaysInsideItsRangeAtItsRate();
    aDroneHoldsItsNoteWithTheTransportStopped();
    aDroneSoundsSeveralCellsAtOnce();
    aDroneNoteTakesTheBeatOfTheLastTick();
    aDroneSaysNothingTwiceForTheSameCell();
    aDroneIgnoresACellOutsideItsGrid();
    aDroneRetunesItsHeldNotesWhenTheScaleChanges();
    aDroneSaysNothingForADegreeTheChangeLeavesWhereItWas();
    aReplacedScaleListRetunesADroneWithTheTransportStopped();
    aHeldNoteGlidesToItsNewPitch();
    aChangeForANoteNobodyHoldsMovesNothing();
    aDroneReportsTheNotesItIsHolding();
    return testing::report("nodes");
}
