#include "nodes.h"

#include "processors.h"
#include "soundfont.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

/** Clamp without pulling in <algorithm>'s iterator machinery at every call site. */
inline float clampf(float value, float low, float high) {
    return value < low ? low : (value > high ? high : value);
}

/** A gate is high above half scale; anything below is off. */
inline bool gateHigh(float value) { return value > 0.5f; }

/**
 * How far tuning controls reach, in cents.
 *
 * Two octaves either way, which is also enough for a full turn of a non-octave scale --
 * Bohlen-Pierce's tritave is 1902 cents, and a transpose that could not reach one period
 * would be a control that ran out before the scale did.
 */
constexpr float kTuneRange = 2400.0f;

/** A figure in degrees, mirroring DEFAULT_PATTERN in PatchCanvas.kt. */
constexpr int32_t kPattern[8] = {0, 3, 7, 10, 12, 10, 7, 3};

} // namespace

void NullNode::process(int32_t frames) {
    for (int32_t port = 0; port < outputs_; ++port) {
        std::memset(out(port), 0, static_cast<size_t>(frames) * sizeof(float));
    }
}

// ---------------------------------------------------------------- Filter

void FilterNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    svf_.Init(static_cast<float>(sampleRate));
    svf_.SetRes(0.3f);
    svf_.SetDrive(0.0f);
}

void FilterNode::process(int32_t frames) {
    float *o = out(0);
    const float *in = input(0);

    // Once per block, which is what is left once the cutoff jack has gone: cutoffHz_ only
    // moves when the knob does or a modulator writes it, and the graph applies a modulator
    // once per block anyway. The per-sample read that was here existed so that audio-rate
    // filter modulation worked through the jack; a module that wants audio rate declares
    // an audio input instead.
    svf_.SetFreq(clampf(cutoffHz_, 20.0f, 18000.0f));
    for (int32_t i = 0; i < frames; ++i) {
        svf_.Process(in[i]);
        o[i] = svf_.Low();
    }
}

void FilterNode::setParam(int32_t index, float value) {
    switch (index) {
        // Hertz outright. This used to be where a cable's zero sat, with the jack moving
        // it in octaves from there as a hardware cutoff input does; with the jack gone, a
        // modulator writes it directly through the range stored on the knob.
        case 0: cutoffHz_ = clampf(value, 20.0f, 18000.0f); break;
        case 1: svf_.SetRes(clampf(value, 0.0f, 0.95f)); break;
        default: break;
    }
}

// ---------------------------------------------------------------- Env

void EnvNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    adsr_.Init(static_cast<float>(sampleRate));
    // Fixed until Phase 5. A short attack and a moderate decay make a plucked shape,
    // which is the one that shows most clearly whether a gate is arriving.
    adsr_.SetAttackTime(0.005f);
    adsr_.SetDecayTime(0.12f);
    adsr_.SetSustainLevel(0.6f);
    adsr_.SetReleaseTime(0.25f);
}

void EnvNode::start(const NoteEvent &event) {
    if (heldCount_ >= kHeld) return;
    held_[heldCount_].id = event.id;
    held_[heldCount_].source = event.source;
    ++heldCount_;
}

void EnvNode::release(uint32_t id, int32_t source) {
    for (int32_t i = 0; i < heldCount_; ++i) {
        if (held_[i].id != id || held_[i].source != source) continue;
        held_[i] = held_[heldCount_ - 1];
        --heldCount_;
        return;
    }
}

void EnvNode::notesCut(int32_t port, int32_t source) {
    (void) port; // one note input, so there is nothing to tell apart
    for (int32_t i = heldCount_ - 1; i >= 0; --i) {
        if (held_[i].source != source) continue;
        held_[i] = held_[heldCount_ - 1];
        --heldCount_;
    }
}

void EnvNode::process(int32_t frames) {
    float *o = out(0);
    const NoteBuffer &notes = notesIn(0);
    int32_t next = 0;

    for (int32_t i = 0; i < frames; ++i) {
        // Events land on their own sample, the way a tick does, and arrive in offset
        // order because the graph merges them that way.
        while (next < notes.count && notes.events[next].offset <= i) {
            const NoteEvent &event = notes.events[next];
            if (event.kind == NoteKind::On) start(event);
            if (event.kind == NoteKind::Off) release(event.id, event.source);
            ++next;
        }
        // Held rather than struck: the gate is open while anything is down, so an
        // overlapping note sustains the envelope instead of restarting it.
        o[i] = adsr_.Process(heldCount_ > 0);
    }
}

void EnvNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: adsr_.SetAttackTime(clampf(value, 0.001f, 5.0f)); break;
        case 1: adsr_.SetDecayTime(clampf(value, 0.001f, 5.0f)); break;
        case 2: adsr_.SetSustainLevel(clampf(value, 0.0f, 1.0f)); break;
        case 3: adsr_.SetReleaseTime(clampf(value, 0.001f, 10.0f)); break;
        default: break;
    }
}

// ---------------------------------------------------------------- Drone

/**
 * Degree per cell, ascending, so a drone that is never told otherwise still lays the scale
 * out in order. The interface sends every cell it has, so this only decides what an
 * untouched node would sound if something asked it to.
 */
DroneNode::DroneNode() {
    for (int32_t i = 0; i < kCells; ++i) degree_[i] = i;
}

void DroneNode::setStep(int32_t index, int32_t degree, bool gate) {
    if (index < 0 || index >= kCells) return;
    degree_[index] = degree;
    on_[index] = gate;
}

void DroneNode::tick(int32_t offset, int64_t count) {
    beat_ = count;
    // Checked on every beat rather than only where an entry ends: whole beats are where a
    // scale can change, and the comparison is per cell and cheap, so there is no second
    // piece of arithmetic about entry lengths to keep in step with ScaleList's.
    retuneDue_ = true;
    retuneBeat_ = count;
    retuneOffset_ = static_cast<uint16_t>(offset);
}

void DroneNode::heldNotes(int32_t port, NoteBuffer &into) const {
    (void) port; // one note output
    for (int32_t i = 0; i < kCells; ++i) {
        if (!on_[i] || sounding_[i] == 0) continue;
        NoteEvent on;
        on.id = sounding_[i];
        on.kind = NoteKind::On;
        on.offset = 0;
        on.degree = degree_[i];
        // The beat every retune so far has been worked out against, so a newcomer starts
        // at the pitch the others are already at rather than the one the note began on.
        on.beat = beat_;
        on.cents = 0.0f;
        on.velocity = 1.0f;
        if (!into.push(on)) return;
    }
}

float DroneNode::octavesAt(int64_t beat, int32_t degree) const {
    return scales_ != nullptr ? scales_->tableAt(beat).octavesOf(degree)
                              : ScaleTable{}.octavesOf(degree);
}

void DroneNode::process(int32_t frames) {
    (void) frames;
    NoteBuffer &notes = notesOut(0);
    // Events do not persist the way sample buffers do -- see NoteBuffer.
    notes.clear();

    for (int32_t i = 0; i < kCells; ++i) {
        // Everything starts at offset 0: a cell is toggled by a finger, between blocks,
        // and there is no boundary within the block it belongs to. Quantizing a drone to
        // anything would be the transport's job and a drone is not the transport's.
        if (on_[i] && sounding_[i] == 0) {
            NoteEvent on;
            on.id = nextNoteId_++;
            on.kind = NoteKind::On;
            on.offset = 0;
            on.degree = degree_[i];
            on.beat = beat_;
            on.cents = 0.0f;
            on.velocity = 1.0f;
            if (notes.push(on)) {
                sounding_[i] = on.id;
                octaves_[i] = octavesAt(beat_, degree_[i]);
            }
        } else if (!on_[i] && sounding_[i] != 0) {
            NoteEvent off;
            off.id = sounding_[i];
            off.kind = NoteKind::Off;
            off.offset = 0;
            if (notes.push(off)) sounding_[i] = 0;
        }
    }

    // A replaced scale list retunes too, at the top of the block and against the beat
    // already known -- unless a tick in this block has asked for its own sample.
    if (scales_ != retunedFor_) {
        if (!retuneDue_) {
            retuneDue_ = true;
            retuneBeat_ = beat_;
            retuneOffset_ = 0;
        }
        retunedFor_ = scales_;
    }
    if (!retuneDue_) return;

    bool said = true;
    for (int32_t i = 0; i < kCells; ++i) {
        if (!on_[i] || sounding_[i] == 0) continue;
        // Exact comparison on purpose: both sides come from the same lookup on the same
        // inputs, so an unchanged degree compares equal and sends nothing.
        const float target = octavesAt(retuneBeat_, degree_[i]);
        if (target == octaves_[i]) continue;

        NoteEvent change;
        change.id = sounding_[i];
        change.kind = NoteKind::Change;
        change.offset = retuneOffset_;
        change.degree = degree_[i];
        change.beat = retuneBeat_;
        change.cents = 0.0f;
        change.velocity = 1.0f;
        if (notes.push(change)) {
            octaves_[i] = target;
        } else {
            said = false;
        }
    }
    // What did not fit goes on the next block, at its top: already late, so no later.
    retuneDue_ = !said;
    retuneOffset_ = 0;
}

// ---------------------------------------------------------------- Steps

/**
 * The pentatonic figure the module used to have hardcoded, now only a starting point.
 *
 * Kept as the default so a patch saved before the grid existed still sounds the same
 * when it is loaded, and so a freshly added Steps makes music rather than one repeated
 * note. Everything past the eighth step repeats it, which is what a length of 8 hides
 * until the length is raised.
 */
StepsNode::StepsNode() {
    for (int32_t i = 0; i < kSteps; ++i) {
        degree_[i] = kPattern[i % 8];
        gate_[i] = true;
    }
}

void StepsNode::setStep(int32_t index, int32_t degree, bool gate) {
    if (index < 0 || index >= kSteps) return;
    degree_[index] = degree;
    gate_[index] = gate;
}

void StepsNode::tick(int32_t offset, int64_t count) {
    if (pendingCount_ < kMaxPending) {
        pending_[pendingCount_].offset = offset;
        pending_[pendingCount_].count = count;
        ++pendingCount_;
    }
}

/**
 * How much of its step a note sounds for.
 *
 * Fixed for now. The note length used to be the width of the clock's gate, and a knob to
 * replace it would be a third row on a panel that fits two; the dot sequencer brings a
 * length on every note, which is where the control belongs. Half keeps notes distinct
 * and leaves the envelope a release before the next one.
 */
constexpr double kGateFraction = 0.5;

void StepsNode::process(int32_t frames) {
    NoteBuffer &notes = notesOut(0);
    // Events do not persist the way sample buffers do -- see NoteBuffer.
    notes.clear();
    int32_t next = 0;

    for (int32_t i = 0; i < frames; ++i) {
        while (next < pendingCount_ && pending_[next].offset <= i) {
            const int64_t length = length_ > 0 ? length_ : 1;
            const int64_t count = pending_[next].count;
            const Interval step = interval();
            step_ = static_cast<int32_t>(((count % length) + length) % length);
            // A rest is the absence of a note: it keeps the degree it remembers, so that
            // switching it back on restores what was there, and emits nothing.
            // Whatever is still sounding ends before anything else starts. At half a step
            // a note has always run out by the next tick, but a tempo or an interval
            // changed mid-note can leave one running, and a note with no Off hangs.
            if (soundingId_ != 0) {
                NoteEvent off;
                off.id = soundingId_;
                off.kind = NoteKind::Off;
                off.offset = static_cast<uint16_t>(i);
                notes.push(off);
                soundingId_ = 0;
            }

            if (gate_[step_]) {
                voiced_ = step_;
                // The beat is the boundary's own, worked out from its count in integers,
                // not the transport's floating position: the note belongs to the boundary
                // it ticked for, so which scale it gets is never decided by rounding.
                voicedBeat_ = floorDiv(count * step.num, step.den);

                // A degree, the beat that decides its scale, and the transpose as cents
                // against it. Said once, as an event, where pitch and gate used to say
                // the same thing continuously between them.
                NoteEvent on;
                on.id = nextNoteId_++;
                on.kind = NoteKind::On;
                on.offset = static_cast<uint16_t>(i);
                on.degree = degree_[voiced_];
                on.beat = voicedBeat_;
                on.cents = transposeCents_;
                on.velocity = 1.0f;
                if (notes.push(on)) soundingId_ = on.id;
            }

            // In frames, worked out at the tick from the tempo it started at. Stopping
            // the transport freezes it rather than letting it run out, so a note held
            // when time stops is still the same note when it starts again.
            gateRemaining_ = beatsPerFrame_ > 0.0
                    ? static_cast<int64_t>(kGateFraction * step.num / (step.den * beatsPerFrame_))
                    : 0;
            ++next;
        }

        if (running_ && gateRemaining_ > 0) {
            --gateRemaining_;
            // The gate falls on the next sample; the Off goes out on this one. A sample
            // early rather than a sample late, because late would need an offset past the
            // end of the block on the frame the gate happens to run out on.
            if (gateRemaining_ == 0 && soundingId_ != 0) {
                NoteEvent off;
                off.id = soundingId_;
                off.kind = NoteKind::Off;
                off.offset = static_cast<uint16_t>(i);
                notes.push(off);
                soundingId_ = 0;
            }
        }
    }
    pendingCount_ = 0;
}

void StepsNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: {
            length_ = static_cast<int32_t>(clampf(value, 1.0f, static_cast<float>(kSteps)) + 0.5f);
            // Shortening the loop past the note being held would leave the pitch on a
            // step the sequence no longer reaches. The step itself is left alone: the next
            // tick derives it from the count, so it lands in range without being told.
            if (voiced_ >= length_) voiced_ = 0;
            break;
        }
        case 1: transposeCents_ = clampf(value, -kTuneRange, kTuneRange); break;
        case 2:
            intervalIndex_ = static_cast<int32_t>(
                    clampf(value, 0.0f, static_cast<float>(kIntervalCount - 1)) + 0.5f);
            break;
        default: break;
    }
}

// ---------------------------------------------------------------- DotSeq

void DotSeqNode::setDot(int32_t slot, int32_t step, int32_t degree, int32_t length) {
    if (slot < 0 || slot >= kMaxDots) return;
    dotStep_[slot] = std::max(0, std::min(step, kSteps - 1));
    dotDegree_[slot] = degree;
    dotLength_[slot] = std::max(0, std::min(length, kSteps));
}

void DotSeqNode::tick(int32_t offset, int64_t count) {
    if (pendingCount_ < kMaxPending) {
        pending_[pendingCount_].offset = offset;
        pending_[pendingCount_].count = count;
        ++pendingCount_;
    }
}

void DotSeqNode::endAll(NoteBuffer &notes, uint16_t offset) {
    for (int32_t h = 0; h < heldCount_; ++h) {
        NoteEvent off;
        off.id = held_[h].id;
        off.kind = NoteKind::Off;
        off.offset = offset;
        notes.push(off);
    }
    heldCount_ = 0;
}

void DotSeqNode::process(int32_t frames) {
    NoteBuffer &notes = notesOut(0);
    notes.clear();
    (void) frames;

    for (int32_t p = 0; p < pendingCount_; ++p) {
        const int64_t count = pending_[p].count;
        const auto offset = static_cast<uint16_t>(pending_[p].offset);
        const Interval interval = kIntervals[intervalIndex_];

        // The ticks a held note is waiting for are consecutive; anything else and it may
        // wait forever, so it ends here instead.
        if (lastCount_ >= 0 && count != lastCount_ + 1) endAll(notes, offset);
        lastCount_ = count;

        // Ends before starts, so a dot followed at once by another at the same degree is
        // two notes rather than one whose Off lands after the second's On.
        for (int32_t h = 0; h < heldCount_;) {
            if (held_[h].endCount <= count) {
                NoteEvent off;
                off.id = held_[h].id;
                off.kind = NoteKind::Off;
                off.offset = offset;
                notes.push(off);
                held_[h] = held_[--heldCount_];
            } else {
                ++h;
            }
        }

        const int64_t length = length_ > 0 ? length_ : 1;
        step_ = static_cast<int32_t>(((count % length) + length) % length);
        // The beat the boundary falls on, in integers, which decides the scale -- as Steps.
        const int64_t beat = floorDiv(count * interval.num, interval.den);
        for (int32_t d = 0; d < kMaxDots; ++d) {
            if (dotLength_[d] <= 0 || dotStep_[d] != step_) continue;
            if (heldCount_ >= kMaxHeld) break;
            NoteEvent on;
            on.id = nextNoteId_++;
            on.kind = NoteKind::On;
            on.offset = offset;
            on.degree = dotDegree_[d];
            on.beat = beat;
            on.cents = transposeCents_;
            on.velocity = 1.0f;
            if (!notes.push(on)) break;
            held_[heldCount_++] = Held{on.id, on.degree, beat, count + dotLength_[d]};
        }
    }
    pendingCount_ = 0;
}

void DotSeqNode::heldNotes(int32_t port, NoteBuffer &into) const {
    (void) port;
    for (int32_t h = 0; h < heldCount_; ++h) {
        NoteEvent on;
        on.id = held_[h].id;
        on.kind = NoteKind::On;
        on.degree = held_[h].degree;
        on.beat = held_[h].beat;
        on.cents = transposeCents_;
        on.velocity = 1.0f;
        into.push(on);
    }
}

void DotSeqNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: length_ = static_cast<int32_t>(clampf(value, 1.0f, static_cast<float>(kSteps)) + 0.5f); break;
        case 1: transposeCents_ = clampf(value, -kTuneRange, kTuneRange); break;
        case 2:
            intervalIndex_ = static_cast<int32_t>(
                    clampf(value, 0.0f, static_cast<float>(kIntervalCount - 1)) + 0.5f);
            break;
        default: break;
    }
}

// ---------------------------------------------------------------- Voice

void OscVoice::init(float sampleRate) {
    osc.Init(sampleRate);
    osc.SetWaveform(daisysp::Oscillator::WAVE_POLYBLEP_SAW);
    osc.SetAmp(1.0f);
    env.Init(sampleRate);
}

void OscVoice::strike(float hz, float velocity, bool stolen) {
    osc.SetFreq(hz);
    osc.SetAmp(velocity);
    // Taking a voice that is still held restarts its envelope from where it is, because
    // the gate never fell and the envelope has no edge to see. Softly: from the level it
    // reached rather than from zero, which would be a step in the middle of a note.
    if (stolen) env.Retrigger(false);
}

float OscVoice::render(bool gate, bool &finished) {
    const float amplitude = env.Process(gate);
    // Finished its release, so it is free rather than merely quiet. Said here rather than
    // by looking at the amplitude, which passes through zero on its way up as well.
    if (!gate && !env.IsRunning()) {
        finished = true;
        return 0.0f;
    }
    return osc.Process() * amplitude;
}

void OscNode::prepare(int32_t sampleRate) {
    PolySynth::prepare(sampleRate);
    forEachVoice([this](OscVoice &voice) {
        voice.env.SetAttackTime(attack_);
        voice.env.SetDecayTime(decay_);
        voice.env.SetSustainLevel(sustain_);
        voice.env.SetReleaseTime(release_);
    });
}

void OscNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: {
            // Order mirrors kWaves in OscNode::setParam.
            const auto wave = static_cast<uint8_t>(clampf(value, 0.0f, 3.0f) + 0.5f);
            static const uint8_t kWaves[4] = {
                    daisysp::Oscillator::WAVE_POLYBLEP_SAW,
                    daisysp::Oscillator::WAVE_POLYBLEP_SQUARE,
                    daisysp::Oscillator::WAVE_POLYBLEP_TRI,
                    daisysp::Oscillator::WAVE_SIN,
            };
            // Every voice, including any sounding: one module is one instrument, and half
            // a chord changing shape underneath you is not what the control says.
            forEachVoice([&](OscVoice &voice) { voice.osc.SetWaveform(kWaves[wave]); });
            break;
        }
        case 1:
            attack_ = clampf(value, 0.001f, 5.0f);
            forEachVoice([this](OscVoice &voice) { voice.env.SetAttackTime(attack_); });
            break;
        case 2:
            decay_ = clampf(value, 0.001f, 5.0f);
            forEachVoice([this](OscVoice &voice) { voice.env.SetDecayTime(decay_); });
            break;
        case 3:
            sustain_ = clampf(value, 0.0f, 1.0f);
            forEachVoice([this](OscVoice &voice) { voice.env.SetSustainLevel(sustain_); });
            break;
        case 4:
            release_ = clampf(value, 0.001f, 10.0f);
            forEachVoice([this](OscVoice &voice) { voice.env.SetReleaseTime(release_); });
            break;
        default: break;
    }
}

// ---------------------------------------------------------------- Pluck

void PluckVoice::init(float rate) {
    sampleRate = rate;
    string.Init(rate);
    excitation.Init(rate);
    // A follower that falls by half in about 70ms: slower than a cycle of anything
    // audible, so a waveform passing through zero never looks like silence.
    levelFall = std::exp(-1.0f / (0.1f * rate));
}

void PluckVoice::setFreq(float hz) {
    string.SetFreq(hz);
    f0 = clampf(hz / sampleRate, 0.0f, 0.25f);
}

void PluckVoice::apply(float decay, float brightness, float stiff) {
    decayKnob = decay;
    brightKnob = brightness;
    stiffKnob = stiff;
    update();
}

void PluckVoice::update() {
    // Striking harder is brighter and rings longer, as StringVoice has it: a quarter of
    // the way from the knob to the top at full velocity.
    bright = brightKnob + 0.25f * accent * (1.0f - brightKnob);
    string.SetBrightness(bright);
    string.SetDamping(decayKnob + 0.25f * accent * (1.0f - decayKnob));
    // Below a quarter the bridge curves and the string buzzes like a sitar's; above it,
    // it stiffens towards a bell. Between, a plain string. StringVoice's mapping.
    const float nonLinearity = stiffKnob < 0.24f ? (stiffKnob - 0.24f) * 4.166f
            : (stiffKnob > 0.26f ? (stiffKnob - 0.26f) * 1.35135f : 0.0f);
    string.SetNonLinearity(nonLinearity);
}

void PluckVoice::strike(float hz, float velocity, bool stolen) {
    (void) stolen; // a string struck again while ringing is what a string does
    setFreq(hz);
    accent = velocity;
    update();
    const float cutoff = std::fmin(
            4.0f * f0 * std::exp2((bright * (2.0f - bright) - 0.5f) * 6.0f), 0.499f);
    excitation.SetFreq(cutoff * sampleRate);
    excitation.SetRes(0.5f);
    noiseLeft = f0 > 0.0f ? static_cast<int32_t>(1.0f / f0) : 0;
    fade = 1.0f;
    level = 1.0f;
}

float PluckVoice::render(bool gate, bool &finished) {
    float noise = 0.0f;
    if (noiseLeft > 0) {
        rng = rng * 1664525u + 1013904223u;
        noise = static_cast<float>(rng >> 8) * (2.0f / 16777216.0f) - 1.0f;
        // Scaled by how hard it was struck, which StringVoice leaves to the brightness.
        noise *= accent;
        --noiseLeft;
    }
    excitation.Process(noise);
    float sample = string.Process(excitation.Low());

    if (!gate) fade *= releaseStep;
    sample *= fade;

    level = std::fmax(std::fabs(sample), level * levelFall);
    // Rung out, or released and faded: either way there is nothing left to hear.
    if (noiseLeft == 0 && (level < 1.0e-4f || fade < 1.0e-4f)) {
        finished = true;
        return 0.0f;
    }
    return sample;
}

void PluckNode::prepare(int32_t sampleRate) {
    PolySynth::prepare(sampleRate);
    applyAll();
}

void PluckNode::applyAll() {
    // R as a time constant, like the envelopes': the fade reaches a third in R seconds.
    const float step = std::exp(-1.0f / (release_ * static_cast<float>(sampleRate_)));
    forEachVoice([&](PluckVoice &voice) {
        voice.apply(decay_, bright_, stiff_);
        voice.releaseStep = step;
    });
}

void PluckNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: decay_ = clampf(value, 0.0f, 1.0f); break;
        case 1: bright_ = clampf(value, 0.0f, 1.0f); break;
        case 2: stiff_ = clampf(value, 0.0f, 1.0f); break;
        case 3: release_ = clampf(value, 0.01f, 10.0f); break;
        default: return;
    }
    applyAll();
}

// ---------------------------------------------------------------- FM

void FmVoice::init(float rate) {
    sampleRate = rate;
    env.Init(rate);
}

void FmVoice::setFreq(float frequency) {
    hz = frequency;
    carrierStep = hz / sampleRate;
    modulatorStep = hz * ratio / sampleRate;
}

void FmVoice::strike(float frequency, float strength, bool stolen) {
    velocity = strength;
    brightness = 1.0f;
    setFreq(frequency);
    if (stolen) {
        // As an Osc's: from where the envelope is, since the gate never fell. The phases
        // run on, because restarting them mid-cycle is a step.
        env.Retrigger(false);
    } else {
        // A fresh voice starts both sines at zero, so every note's attack is the same
        // shape rather than depending on where a free voice's phases were left.
        carrier = 0.0f;
        modulator = 0.0f;
    }
}

float FmVoice::render(bool gate, bool &finished) {
    const float amplitude = env.Process(gate);
    if (!gate && !env.IsRunning()) {
        finished = true;
        return 0.0f;
    }
    constexpr float kTwoPi = 6.28318530718f;
    const float depth = index * velocity * amplitude * brightness;
    const float sample = std::sin(kTwoPi * carrier + depth * std::sin(kTwoPi * modulator));
    brightness *= fallStep;

    carrier += carrierStep;
    carrier -= std::floor(carrier);
    modulator += modulatorStep;
    modulator -= std::floor(modulator);
    return sample * amplitude * velocity;
}

void FmNode::prepare(int32_t sampleRate) {
    PolySynth::prepare(sampleRate);
    applyAll();
}

void FmNode::applyAll() {
    const float fallStep = std::exp(-1.0f / (fall_ * static_cast<float>(sampleRate_)));
    forEachVoice([&](FmVoice &voice) {
        voice.ratio = ratio_;
        voice.index = index_;
        voice.fallStep = fallStep;
        // A new ratio moves a sounding note's modulator at once; its carrier is untouched.
        voice.setFreq(voice.hz);
        voice.env.SetAttackTime(attack_);
        voice.env.SetDecayTime(decay_);
        voice.env.SetSustainLevel(sustain_);
        voice.env.SetReleaseTime(release_);
    });
}

void FmNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: ratio_ = clampf(value, 0.25f, 16.0f); break;
        case 1: index_ = clampf(value, 0.0f, 10.0f); break;
        case 2: fall_ = clampf(value, 0.01f, 20.0f); break;
        case 3: attack_ = clampf(value, 0.001f, 5.0f); break;
        case 4: decay_ = clampf(value, 0.001f, 5.0f); break;
        case 5: sustain_ = clampf(value, 0.0f, 1.0f); break;
        case 6: release_ = clampf(value, 0.001f, 10.0f); break;
        default: return;
    }
    applyAll();
}

// ---------------------------------------------------------------- LFO

void LfoNode::process(int32_t frames) {
    float *o = out(0);
    const double step = static_cast<double>(rateHz_) / static_cast<double>(sampleRate_);
    for (int32_t i = 0; i < frames; ++i) {
        const auto phase = static_cast<float>(phase_);
        switch (wave_) {
            case 0: o[i] = phase; break;                                  // saw, rising
            case 1: o[i] = phase < 0.5f ? 1.0f : 0.0f; break;             // square
            case 2: o[i] = 1.0f - std::fabs(2.0f * phase - 1.0f); break;  // triangle, from 0
            // Cosine rather than sine, so it starts from the bottom of the range like the
            // other three instead of from the middle of it.
            default: o[i] = 0.5f - 0.5f * std::cos(2.0f * static_cast<float>(M_PI) * phase); break;
        }
        // Naive, not band-limited. Nothing reads this at audio rate -- a parameter samples
        // it once a block -- and a square that is a clean 1 or 0 is the useful kind.
        phase_ += step;
        if (phase_ >= 1.0) phase_ -= std::floor(phase_);
    }
}

void LfoNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: rateHz_ = clampf(value, 0.01f, 40.0f); break;
        case 1: wave_ = static_cast<int32_t>(clampf(value, 0.0f, 3.0f) + 0.5f); break;
        default: break;
    }
}

// ---------------------------------------------------------------- Mix

void MixNode::process(int32_t frames) {
    float *o = out(0);
    const float *a = input(0);
    const float *b = input(1);
    const float *c = input(2);
    const float *d = input(3);
    for (int32_t i = 0; i < frames; ++i) {
        // Summed, not averaged: an unused input contributes silence, and averaging would
        // make a patch quieter simply for having spare inputs. The limiter catches the
        // rest.
        o[i] = a[i] * level_[0] + b[i] * level_[1] + c[i] * level_[2] + d[i] * level_[3];
    }
}

void MixNode::setParam(int32_t index, float value) {
    if (index >= 0 && index < 4) level_[index] = clampf(value, 0.0f, 2.0f);
}

// ---------------------------------------------------------------- Out

void OutNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    dcLeft_.Init(static_cast<float>(sampleRate));
    dcRight_.Init(static_cast<float>(sampleRate));
    limitLeft_.Init();
    limitRight_.Init();
}

void OutNode::process(int32_t frames) {
    float *left = out(0);
    float *right = out(1);
    const float *inLeft = input(0);
    const float *inRight = input(1);

    // DC first, then limit. A blocked offset would otherwise eat the limiter's headroom
    // while being inaudible itself.
    for (int32_t i = 0; i < frames; ++i) {
        left[i] = dcLeft_.Process(inLeft[i] * level_);
        right[i] = dcRight_.Process(inRight[i] * level_);
    }
    // DaisySP's Limiter multiplies everything by a fixed 0.7 whether it is loud or not,
    // which is seven decibels given away before any limiting has happened -- a fader,
    // not a limiter. Compensating that in pre_gain makes the stage transparent below
    // threshold and leaves it to act only where it is meant to. It also brings the knee
    // in at about 0.7 rather than 1.0, so the saturation stays gentle.
    constexpr float kMakeUp = 1.0f / 0.7f;
    limitLeft_.ProcessBlock(left, static_cast<size_t>(frames), kMakeUp);
    limitRight_.ProcessBlock(right, static_cast<size_t>(frames), kMakeUp);
}

void OutNode::setParam(int32_t index, float value) {
    if (index == 0) level_ = clampf(value, 0.0f, 2.0f);
}

// ---------------------------------------------------------------- In

void InNode::process(int32_t frames) {
    float *left = out(0);
    float *right = out(1);

    if (source_ == nullptr) {
        std::memset(left, 0, static_cast<size_t>(frames) * sizeof(float));
        std::memset(right, 0, static_cast<size_t>(frames) * sizeof(float));
        return;
    }

    // The device microphone is mono, so both rails carry the same signal. Spreading it
    // would be inventing a stereo image that is not there.
    for (int32_t i = 0; i < frames; ++i) {
        const float sample = source_[i] * gain_;
        left[i] = sample;
        right[i] = sample;
    }
}

void InNode::setParam(int32_t index, float value) {
    if (index == 0) gain_ = clampf(value, 0.0f, 64.0f);
}

// ---------------------------------------------------------------- factory

Node *makeNode(NodeType type) {
    switch (type) {
        case NodeType::Filter: return new FilterNode();
        case NodeType::Env: return new EnvNode();
        case NodeType::Steps: return new StepsNode();
        case NodeType::Mix: return new MixNode();
        case NodeType::Osc: return new OscNode();
        case NodeType::Pluck: return new PluckNode();
        case NodeType::Fm: return new FmNode();
        case NodeType::Sf: return new SfNode();
        case NodeType::DotSeq: return new DotSeqNode();
        case NodeType::Chance: return new ChanceNode();
        case NodeType::Chord: return new ChordNode();
        case NodeType::Arp: return new ArpNode();
        case NodeType::Euclid: return new EuclidNode();
        case NodeType::Lfo: return new LfoNode();
        case NodeType::Drone: return new DroneNode();
        case NodeType::Out: return new OutNode();
        case NodeType::In: return new InNode();
        default: return new NullNode(1, 1);
    }
}
