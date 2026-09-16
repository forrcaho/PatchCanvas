#include "nodes.h"

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
    (void) offset;
    beat_ = count;
}

void DroneNode::process(int32_t frames) {
    (void) frames;
    NoteBuffer &notes = notesOut(0);
    // Events do not persist the way sample buffers do -- see NoteBuffer.
    notes.clear();

    for (int32_t i = 0; i < kCells; ++i) {
        // Everything starts at offset 0: a cell is toggled by a finger, between blocks,
        // and there is no boundary within the block it belongs to. Quantising a drone to
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
            if (notes.push(on)) sounding_[i] = on.id;
        } else if (!on_[i] && sounding_[i] != 0) {
            NoteEvent off;
            off.id = sounding_[i];
            off.kind = NoteKind::Off;
            off.offset = 0;
            if (notes.push(off)) sounding_[i] = 0;
        }
    }
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

// ---------------------------------------------------------------- Voice

void OscNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    for (auto &voice : voices_) {
        voice.osc.Init(static_cast<float>(sampleRate));
        voice.osc.SetWaveform(daisysp::Oscillator::WAVE_POLYBLEP_SAW);
        voice.osc.SetAmp(1.0f);
        voice.env.Init(static_cast<float>(sampleRate));
        voice.env.SetAttackTime(attack_);
        voice.env.SetDecayTime(decay_);
        voice.env.SetSustainLevel(sustain_);
        voice.env.SetReleaseTime(release_);
    }
}

void OscNode::start(const NoteEvent &event) {
    // A note takes an idle voice, then the oldest one already released, and only then
    // steals one that is still held. Stealing a held voice restarts an oscillator
    // mid-cycle, which is a click -- so it is the last resort rather than the rule, and
    // by the time eight notes are held a ninth was going to cost something regardless.
    Voice *chosen = nullptr;
    for (auto &voice : voices_) {
        if (!voice.active) { chosen = &voice; break; }
    }
    if (chosen == nullptr) {
        for (auto &voice : voices_) {
            if (voice.gate) continue;
            if (chosen == nullptr || voice.age < chosen->age) chosen = &voice;
        }
    }
    if (chosen == nullptr) {
        for (auto &voice : voices_) {
            if (chosen == nullptr || voice.age < chosen->age) chosen = &voice;
        }
    }
    // Taking a voice that is still held restarts its envelope from where it is, because
    // the gate never fell and the envelope has no edge to see. Softly: from the level it
    // reached rather than from zero, which would be a step in the middle of a note.
    const bool stolen = chosen->active && chosen->gate;

    // Resolved once, here, and never again: a held note keeps the pitch it started on.
    // Retuning a sounding voice was considered and rejected -- a major third dropping to
    // a minor third mid-note is a step with no ramp, which is the transient every
    // crossfade in this engine exists to prevent.
    //
    // Against the scale of the beat the note started on, which travelled with it. The
    // engine still never learns what a semitone is: this is a table lookup and an exp2.
    const float octaves = scales_ != nullptr
            ? scales_->tableAt(event.beat).octavesOf(event.degree)
            : ScaleTable{}.octavesOf(event.degree);
    chosen->osc.SetFreq(kMiddleC * std::exp2(octaves + event.cents / 1200.0f));
    chosen->osc.SetAmp(clampf(event.velocity, 0.0f, 1.0f));
    chosen->id = event.id;
    chosen->source = event.source;
    chosen->gate = true;
    chosen->active = true;
    chosen->age = age_++;
    if (stolen) chosen->env.Retrigger(false);
}

void OscNode::release(uint32_t id, int32_t source) {
    for (auto &voice : voices_) {
        // Both, because ids belong to the source that chose them: two sequencers patched
        // to the same input are each counting from one.
        if (voice.gate && voice.id == id && voice.source == source) voice.gate = false;
    }
}

void OscNode::notesCut(int32_t port, int32_t source) {
    (void) port; // one note input, so there is nothing to tell apart
    for (auto &voice : voices_) {
        if (voice.source == source) voice.gate = false;
    }
}

void OscNode::process(int32_t frames) {
    float *o = out(0);
    const NoteBuffer &notes = notesIn(0);
    int32_t next = 0;

    for (int32_t i = 0; i < frames; ++i) {
        // Events land on their own sample, the way a tick does. Already in offset order,
        // merged that way by the graph.
        while (next < notes.count && notes.events[next].offset <= i) {
            const NoteEvent &event = notes.events[next];
            if (event.kind == NoteKind::On) start(event);
            if (event.kind == NoteKind::Off) release(event.id, event.source);
            // Change is reserved and does nothing yet.
            ++next;
        }

        // Summed, not averaged, like Mix: a chord is louder than one note, which is true
        // of every instrument, and Out's limiter catches what that costs at the top.
        float sum = 0.0f;
        for (auto &voice : voices_) {
            // A free voice is stepped by nothing: an oscillator that is not accumulating
            // phase is one that starts its next note from zero.
            if (!voice.active) continue;

            const float amplitude = voice.env.Process(voice.gate);
            if (!voice.gate && !voice.env.IsRunning()) {
                // Finished its release, so it is free rather than merely quiet. Said here
                // rather than by looking at the amplitude, which passes through zero on
                // its way up as well.
                voice.active = false;
                voice.source = -1;
                voice.id = 0;
                continue;
            }
            sum += voice.osc.Process() * amplitude;
        }
        o[i] = sum;
    }
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
            for (auto &voice : voices_) voice.osc.SetWaveform(kWaves[wave]);
            break;
        }
        case 1:
            attack_ = clampf(value, 0.001f, 5.0f);
            for (auto &voice : voices_) voice.env.SetAttackTime(attack_);
            break;
        case 2:
            decay_ = clampf(value, 0.001f, 5.0f);
            for (auto &voice : voices_) voice.env.SetDecayTime(decay_);
            break;
        case 3:
            sustain_ = clampf(value, 0.0f, 1.0f);
            for (auto &voice : voices_) voice.env.SetSustainLevel(sustain_);
            break;
        case 4:
            release_ = clampf(value, 0.001f, 10.0f);
            for (auto &voice : voices_) voice.env.SetReleaseTime(release_);
            break;
        default: break;
    }
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
        case NodeType::Lfo: return new LfoNode();
        case NodeType::Drone: return new DroneNode();
        case NodeType::Out: return new OutNode();
        case NodeType::In: return new InNode();
        default: return new NullNode(1, 1);
    }
}
