#include "nodes.h"

#include "processors.h"
#include "soundfont.h"

#include <algorithm>
#include <atomic>
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

/** What saturates a filter's resonance; see FilterNode::prepare for the measurements. */
constexpr float kResonanceDrive = 0.02f;

/** How fast a tracked cutoff slides to a new note, in octaves per block. */
constexpr float kTrackGlide = 0.05f;

} // namespace

void NullNode::process(int32_t frames) {
    for (int32_t port = 0; port < outputs_; ++port) {
        std::memset(out(port), 0, static_cast<size_t>(frames) * sizeof(float));
    }
}

// ---------------------------------------------------------------- Filter

void FilterNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    for (daisysp::Svf *svf : {&svf_, &svf2_}) {
        svf->Init(static_cast<float>(sampleRate));
        svf->SetRes(0.3f);
        // Not zero, which is what this was, and the difference is not subtle. The SVF's
        // only limit on its resonance is a cubic term scaled by the drive, so at a drive
        // of zero a sine sitting on the cutoff comes out 39x louder at the old maximum
        // res of 0.95, and 1255x at 1.0 -- measured, not feared. Any drive at all
        // saturates that peak, and the measured cost is nothing: an impulse still rings
        // 12ms at res 0.5, 71ms at 0.9 and past three seconds at 1.0 whatever the drive
        // is, so what the knob *sounds* like is untouched and only the blowup goes.
        //
        // 0.02 rather than more, because a resonant filter is supposed to have a peak:
        // it leaves about 2.6x at the top of the range where 0.5 flattens it to 1.1 and
        // the knob stops doing anything audible at its own peak.
        svf->SetDrive(kResonanceDrive);
    }
}

float FilterNode::chosen(daisysp::Svf &svf) const {
    switch (type_) {
        case kHigh: return svf.High();
        case kBand: return svf.Band();
        case kNotch: return svf.Notch();
        case kLow:
        default: return svf.Low();
    }
}

void FilterNode::process(int32_t frames) {
    float *o = out(0);
    const float *in = input(0);

    // The note the cutoff follows, taken before the samples rather than during them: the
    // last note to start in this block wins, as it does on a monophonic synth, and a Change
    // moves it too so a drone that follows the scale drags the cutoff with it. Resolved by
    // pitchOf, the one place a degree becomes a pitch -- so the filter learns no more about
    // a semitone than a synth does.
    const NoteBuffer &notes = notesIn(1);
    for (int32_t i = 0; i < notes.count; ++i) {
        const NoteEvent &event = notes.events[i];
        if (event.kind == NoteKind::On || event.kind == NoteKind::Change) {
            trackTarget_ = pitchOf(event, scales_);
        }
    }
    // Toward it rather than to it; see trackOctaves_. One pole per block, which at a
    // 32-frame block settles inside about ten milliseconds -- under a note's own 5ms
    // opening twice over, and slow enough that the octaves arrive as a slide rather than
    // as an edge.
    const float remaining = trackTarget_ - trackOctaves_;
    trackOctaves_ += std::min(std::max(remaining, -kTrackGlide), kTrackGlide);

    // Once per block, which is what is left once the cutoff jack has gone: cutoffHz_ only
    // moves when the knob does or a modulator writes it, and the graph applies a modulator
    // once per block anyway. The per-sample read that was here existed so that audio-rate
    // filter modulation worked through the jack; a module that wants audio rate declares
    // an audio input instead.
    //
    // Tracking multiplies whatever the knob and its modulator arrived at, so an Env
    // sweeping the cutoff still sweeps it -- around the note rather than around middle C.
    // A note landing mid-block moves the cutoff at the next one, which is under a
    // millisecond and an order below the 5ms a note takes to open anyway.
    const float hz = clampf(cutoffHz_ * std::exp2(track_ * trackOctaves_), 20.0f, 18000.0f);
    svf_.SetFreq(hz);
    svf2_.SetFreq(hz);
    for (int32_t i = 0; i < frames; ++i) {
        svf_.Process(in[i]);
        const float first = chosen(svf_);
        // The second stage runs on the first's output whether it is used or not; see the
        // note on svf2_. Two of the same filter in series is where the steeper slope comes
        // from, and it is also why the resonant peak is sharper there -- the two peaks
        // multiply, which is what a 24dB filter does.
        svf2_.Process(first);
        o[i] = steep_ ? chosen(svf2_) : first;
    }
}

void FilterNode::setParam(int32_t index, float value) {
    switch (index) {
        // Hertz outright. This used to be where a cable's zero sat, with the jack moving
        // it in octaves from there as a hardware cutoff input does; with the jack gone, a
        // modulator writes it directly through the range stored on the knob.
        case 0: cutoffHz_ = clampf(value, 20.0f, 18000.0f); break;
        case 1: {
            // To 1.0 now, where it stopped at 0.95. The SVF's damping reaches zero there
            // and the filter rings until something stops it -- which is the top of the
            // knob and worth having, and is *not* self-oscillation: with no input at all
            // it stays silent, because nothing here can make the damping negative.
            const float res = clampf(value, 0.0f, 1.0f);
            svf_.SetRes(res);
            svf2_.SetRes(res);
            // SetRes recomputes drive_ from pre_drive_, so neither needs setting again.
            break;
        }
        case 2:
            type_ = static_cast<int32_t>(clampf(value, 0.0f, static_cast<float>(kTypeCount - 1)) + 0.5f);
            break;
        case 3: steep_ = clampf(value, 0.0f, 1.0f) >= 0.5f; break;
        // Percent on the knob, a fraction here. At 100 the cutoff keeps a fixed ratio to
        // the note -- the ratio being whatever the knob's hertz are against middle C, so
        // 785Hz is the third harmonic -- and at 0 the filter is what it always was.
        case 4: track_ = clampf(value, 0.0f, 100.0f) / 100.0f; break;
        default: break;
    }
}

// ---------------------------------------------------------------- Env

void EnvNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    holdGlide_ = 1.0f - expf(-1.0f / (0.005f * static_cast<float>(sampleRate)));
    // The shape that arrives before the interface has said anything, and the one every
    // Env has had: a short attack, a moderate decay, a sustain and a release. It is here
    // so a node that is added and heard in the same block is not silent.
    setSlot(segmentSlot(0, 0.005f, 1.0f, 0.6f, false));
    setSlot(segmentSlot(1, 0.12f, 0.6f, 0.6f, true));
    setSlot(segmentSlot(2, 0.25f, 0.0f, 0.6f, false));
}
void EnvNode::rescan() {
    segCount_ = 0;
    while (segCount_ < kMaxSegments && seg_[segCount_].time > 0.0f) ++segCount_;
    sustainIndex_ = -1;
    for (int32_t i = 0; i < segCount_; ++i) {
        if (seg_[i].sustain) {
            sustainIndex_ = i;
            break;
        }
    }
    if (index_ >= segCount_) {
        // The envelope was shortened out from under itself. Stop where it is rather than
        // reading a slot that is no longer there; the level it holds is the one it reached.
        running_ = false;
        holding_ = false;
    }
}
void EnvNode::setSlot(const SlotValue &slot) {
    if (slot.kind != SlotKind::Segment) return;
    const int32_t i = slot.index;
    if (i < 0 || i >= kMaxSegments) return;
    const SegmentSlot &s = slot.segment;
    seg_[i].time = s.time <= 0.0f ? 0.0f : (s.time < kMinTime ? kMinTime : s.time);
    seg_[i].level = clampf(s.level, 0.0f, 1.0f);
    seg_[i].curve = clampf(s.curve, -1.0f, 1.0f);
    seg_[i].sustain = s.sustain;
    rescan();
}
void EnvNode::enter(int32_t index) {
    index_ = index;
    phase_ = 0.0f;
    from_ = value_;
    const Segment &s = seg_[index];
    rate_ = 1.0f / (s.time * static_cast<float>(sampleRate_));
    // The bend, as a running exponential. a is the curve over a span that reaches a
    // recognisable exponential at the ends without the middle going slack.
    shapeA_ = 6.0f * s.curve;
    // Straight enough that the exponential form would be 0/0, so it is taken as straight.
    shapeScale_ = fabsf(shapeA_) < 1e-3f ? 0.0f : 1.0f / (1.0f - expf(-shapeA_));
}
void EnvNode::advance() {
    if (index_ + 1 >= segCount_) {
        // Past the last segment the envelope simply stops, holding whatever it arrived at.
        // For the usual shape that is zero; for one that ends high it is a level that stays
        // until the next note, which is what an envelope with no release means.
        running_ = false;
        holding_ = false;
        return;
    }
    enter(index_ + 1);
}
void EnvNode::start(const NoteEvent &event) {
    if (heldCount_ < kHeld) {
        held_[heldCount_].id = event.id;
        held_[heldCount_].source = event.source;
        ++heldCount_;
    }
    // Legato: a second note over a held one leaves the envelope where it is. Only the
    // first note of a phrase strikes, and it strikes from wherever the output sits, so a
    // note landing on a tail does not step.
    if (heldCount_ == 1 && segCount_ > 0) {
        running_ = true;
        holding_ = false;
        enter(0);
    }
}
void EnvNode::release(uint32_t id, int32_t source) {
    for (int32_t i = 0; i < heldCount_; ++i) {
        if (held_[i].id != id || held_[i].source != source) continue;
        held_[i] = held_[heldCount_ - 1];
        --heldCount_;
        break;
    }
    if (heldCount_ == 0) letGo();
}
void EnvNode::letGo() {
    // An envelope with no sustain segment is not listening -- it runs to its end whatever
    // happens, which is what makes a release optional.
    if (sustainIndex_ < 0) return;
    if (!running_ && !holding_) return;
    if (index_ > sustainIndex_) return;  // already past it, so already releasing
    holding_ = false;
    if (sustainIndex_ + 1 >= segCount_) {
        // Sustain on the last segment: there is nothing after it to release into, so the
        // envelope stops at the level it is holding rather than hanging open forever.
        running_ = false;
        return;
    }
    // From wherever it actually reached, not from the sustain level it may never have
    // touched: a note let go during the attack falls from there.
    running_ = true;
    enter(sustainIndex_ + 1);
}
void EnvNode::notesCut(int32_t port, int32_t source) {
    (void) port; // one note input, so there is nothing to tell apart
    for (int32_t i = heldCount_ - 1; i >= 0; --i) {
        if (held_[i].source != source) continue;
        held_[i] = held_[heldCount_ - 1];
        --heldCount_;
    }
    if (heldCount_ == 0) letGo();
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
        if (holding_) {
            // Parked, but still following the level: see holdGlide_.
            value_ += (seg_[index_].level - value_) * holdGlide_;
        } else if (running_) {
            phase_ += rate_;
            if (phase_ >= 1.0f) {
                value_ = seg_[index_].level;
                if (seg_[index_].sustain && heldCount_ > 0) {
                    holding_ = true;
                } else {
                    advance();
                }
            } else {
                const float shaped = shapeScale_ == 0.0f
                    ? phase_
                    : (1.0f - expf(-shapeA_ * phase_)) * shapeScale_;
                value_ = from_ + (seg_[index_].level - from_) * shaped;
            }
        }
        o[i] = value_;
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

void DroneNode::setSlot(const SlotValue &slot) {
    if (slot.kind != SlotKind::Step) return;
    const int32_t i = slot.index;
    if (i < 0 || i >= kCells) return;
    degree_[i] = slot.step.degree;
    on_[i] = slot.step.gate;
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
        on.cents = transposeCents_;
        on.velocity = 1.0f;
        if (!into.push(on)) return;
    }
}

float DroneNode::octavesAt(int64_t beat, int32_t degree) const {
    // The transpose is part of where a note is, so a moved knob is a retune like a changed
    // scale: every held note whose pitch it moves is sent a Change and glides there.
    return (scales_ != nullptr ? scales_->tableAt(beat).octavesOf(degree)
                               : ScaleTable{}.octavesOf(degree)) + transposeCents_ / 1200.0f;
}

void DroneNode::setParam(int32_t index, float value) {
    if (index != 0) return;
    transposeCents_ = clampf(value, -kTuneRange, kTuneRange);
    if (!retuneDue_) {
        retuneDue_ = true;
        retuneBeat_ = beat_;
        retuneOffset_ = 0;
    }
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
            on.cents = transposeCents_;
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
        change.cents = transposeCents_;
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

void StepsNode::setSlot(const SlotValue &slot) {
    if (slot.kind != SlotKind::Step) return;
    const int32_t i = slot.index;
    if (i < 0 || i >= kSteps) return;
    degree_[i] = slot.step.degree;
    gate_[i] = slot.step.gate;
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

// ---------------------------------------------------------------- Seq

void SeqNode::setSlot(const SlotValue &slot) {
    if (slot.kind != SlotKind::Dot) return;
    const int32_t i = slot.index;
    if (i < 0 || i >= kMaxDots) return;
    const DotSlot &d = slot.dot;
    dotStep_[i] = std::max(0, std::min(d.step, kSteps - 1));
    dotDegree_[i] = d.degree;
    dotLength_[i] = std::max(0, std::min(d.length, kSteps * kDotSubsteps));
    dotVelocity_[i] = clampf(d.velocity, 0.0f, 1.0f);
}

void SeqNode::tick(int32_t offset, int64_t count) {
    if (pendingCount_ < kMaxPending) {
        pending_[pendingCount_].offset = offset;
        pending_[pendingCount_].count = count;
        ++pendingCount_;
    }
}

void SeqNode::release(NoteBuffer &notes, int32_t h, uint16_t offset) {
    NoteEvent off;
    off.id = held_[h].id;
    off.kind = NoteKind::Off;
    off.offset = offset;
    notes.push(off);
    held_[h] = held_[--heldCount_];
}

void SeqNode::endAll(NoteBuffer &notes, uint16_t offset) {
    while (heldCount_ > 0) release(notes, heldCount_ - 1, offset);
}

void SeqNode::onTick(NoteBuffer &notes, uint16_t offset, int64_t count) {
    const Interval interval = kIntervals[intervalIndex_];

    // The ticks a held note is waiting for are consecutive; anything else and it may
    // wait forever, so it ends here instead.
    if (lastCount_ >= 0 && count != lastCount_ + 1) endAll(notes, offset);
    lastCount_ = count;

    // Ends before starts, so a note followed at once by another at the same degree is two
    // notes rather than one whose Off lands after the second's On. Only the notes that end
    // on a tick: one with a part step left is counted out in frames below.
    for (int32_t h = 0; h < heldCount_;) {
        if (held_[h].gateLeft < 0 && held_[h].tail == 0 && held_[h].endCount <= count) {
            release(notes, h, offset);
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
        on.velocity = dotVelocity_[d];
        if (!notes.push(on)) break;
        // Whole steps in ticks, the part step in frames. A length under one step has no
        // whole steps at all, so its part starts on this very tick -- which is why the
        // pass below runs after the starts rather than beside the ends above.
        held_[heldCount_++] = Held{
                on.id, on.degree, beat,
                count + dotLength_[d] / kDotSubsteps,
                dotLength_[d] % kDotSubsteps,
                -1,
        };
    }

    // A note whose whole steps have run out starts counting its part step, in frames worked
    // out at this tick's tempo. With the transport stopped there is nothing to count, so it
    // ends on the tick after instead, as every note did before a length had a part.
    for (int32_t h = 0; h < heldCount_; ++h) {
        Held &held = held_[h];
        if (held.gateLeft >= 0 || held.tail == 0 || held.endCount > count) continue;
        const int64_t frames = beatsPerFrame_ > 0.0
                ? static_cast<int64_t>(held.tail * interval.num /
                                       (kDotSubsteps * interval.den * beatsPerFrame_))
                : 0;
        if (frames > 0) held.gateLeft = frames; else held.tail = 0;
    }
}

void SeqNode::process(int32_t frames) {
    NoteBuffer &notes = notesOut(0);
    notes.clear();
    int32_t next = 0;
    for (int32_t i = 0; i < frames; ++i) {
        while (next < pendingCount_ && pending_[next].offset <= i) {
            onTick(notes, static_cast<uint16_t>(i), pending_[next].count);
            ++next;
        }
        if (!running_) continue;
        for (int32_t h = 0; h < heldCount_;) {
            // A sample early rather than late, as Steps' gate: late would need an offset
            // past the end of the block on the frame the gate runs out on.
            if (held_[h].gateLeft > 0 && --held_[h].gateLeft == 0) {
                release(notes, h, static_cast<uint16_t>(i));
            } else {
                ++h;
            }
        }
    }
    pendingCount_ = 0;
}

void SeqNode::heldNotes(int32_t port, NoteBuffer &into) const {
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

void SeqNode::setParam(int32_t index, float value) {
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
    gate.init(sampleRate);
}

void OscVoice::strike(float note, float strength, bool stolen) {
    setFreq(note);
    // Through the ramp rather than into the oscillator's own amplitude. SetAmp here was a
    // step the moment a note landed on a voice still sounding, which is what two abutting
    // notes at different velocities are -- see GateRamp.
    velocity = strength;
    // Nothing else to do for a stolen voice: the ramp is already open and stays open, which
    // is what "taken while still sounding" should sound like. See GateRamp.
    (void) stolen;
}

float OscVoice::render(bool open, bool &finished) {
    const float amplitude = gate.process(open, velocity, finished);
    if (finished) return 0.0f;
    return osc.Process() * amplitude;
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
            // Applied to the note sounding as well as the next: a waveform is what the
            // module is, not something the next note starts using.
                    voice().osc.SetWaveform(kWaves[wave]);
            break;
        }
        case 1:
            // Cents, never semitones: a semitone is a fact about 12-TET and means nothing in
            // the tunings this knob has to work in. The same two octaves either way as every
            // other cents knob here (TUNE_RANGE).
            voice().setTune(clampf(value, -2400.0f, 2400.0f));
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
    MonoSynth::prepare(sampleRate);
    applyAll();
}

void PluckNode::applyAll() {
    // R as a time constant, like the envelopes': the fade reaches a third in R seconds.
    const float step = std::exp(-1.0f / (release_ * static_cast<float>(sampleRate_)));
    PluckVoice &voice = this->voice();
    voice.apply(decay_, bright_, stiff_);
    voice.releaseStep = step;
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
    gate.init(rate);
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
    if (!stolen) {
        // A fresh voice starts both sines at zero, so every note's attack is the same
        // shape rather than depending on where a free voice's phases were left. A stolen
        // one leaves them running, because restarting a phase mid-cycle is a step.
        carrier = 0.0f;
        modulator = 0.0f;
    }
}

float FmVoice::render(bool open, bool &finished) {
    // Velocity rides the ramp here too, for the same reason it does on an Osc: applied
    // straight to the output it stepped whenever a note took a sounding voice.
    const float amplitude = gate.process(open, velocity, finished);
    if (finished) return 0.0f;
    constexpr float kTwoPi = 6.28318530718f;
    // The amplitude is no longer in here. It was Chowning's brass -- brighter as louder --
    // but with the envelope gone the amplitude is a 5ms ramp and nothing else, so leaving
    // it in would only have taken the first five milliseconds off every note's brightness.
    // Patch an Env to the index for the coupling, and choose its shape.
    const float depth = index * velocity * brightness;
    const float sample = std::sin(kTwoPi * carrier + depth * std::sin(kTwoPi * modulator));
    brightness *= fallStep;

    carrier += carrierStep;
    carrier -= std::floor(carrier);
    modulator += modulatorStep;
    modulator -= std::floor(modulator);
    return sample * amplitude;
}

void FmNode::prepare(int32_t sampleRate) {
    MonoSynth::prepare(sampleRate);
    applyAll();
}

void FmNode::applyAll() {
    const float fallStep = std::exp(-1.0f / (fall_ * static_cast<float>(sampleRate_)));
    FmVoice &voice = this->voice();
    voice.ratio = ratio_;
    voice.index = index_;
    voice.fallStep = fallStep;
    // A new ratio moves a sounding note's modulator at once; its carrier is untouched.
    voice.setFreq(voice.hz);
}

void FmNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: ratio_ = clampf(value, 0.25f, 16.0f); break;
        case 1: index_ = clampf(value, 0.0f, 10.0f); break;
        case 2: fall_ = clampf(value, 0.01f, 20.0f); break;
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

// ---------------------------------------------------------------- poly subpatch edges

void PolyInNode::setParam(int32_t index, float value) {
    if (index == 0) {
        voices_ = static_cast<int32_t>(clampf(value, 1.0f, static_cast<float>(kInstances)) + 0.5f);
    }
}

int32_t PolyInNode::choose() const {
    // The rule a synth used to keep for its own voices, and for its reasons: stealing one
    // restarts whatever is inside it mid-note, so it is the last resort rather than the
    // first, and by the time every instance is held the next note was going to cost
    // something regardless.
    for (int32_t k = 0; k < voices_; ++k) {
        if (!instances_[k].held && instances_[k].note.id == 0) return k;
    }
    int32_t chosen = -1;
    for (int32_t k = 0; k < voices_; ++k) {
        if (instances_[k].held) continue;
        if (chosen < 0 || instances_[k].age < instances_[chosen].age) chosen = k;
    }
    if (chosen >= 0) return chosen;
    for (int32_t k = 0; k < voices_; ++k) {
        if (chosen < 0 || instances_[k].age < instances_[chosen].age) chosen = k;
    }
    return chosen < 0 ? 0 : chosen;
}

int32_t PolyInNode::holding(uint32_t id, int32_t source) const {
    for (int32_t k = 0; k < kInstances; ++k) {
        // Both, because ids belong to the source that chose them: two sequencers patched
        // to this subpatch are each counting from one.
        if (instances_[k].held && instances_[k].note.id == id &&
            instances_[k].note.source == static_cast<uint8_t>(source)) {
            return k;
        }
    }
    return -1;
}

int32_t PolyInNode::instancesHeld() const {
    int32_t n = 0;
    for (const auto &instance : instances_) n += instance.held ? 1 : 0;
    return n;
}

void PolyInNode::notesCut(int32_t port, int32_t source) {
    (void) port; // one note input, so there is nothing to tell apart
    for (int32_t k = 0; k < kInstances; ++k) {
        if (instances_[k].held && instances_[k].note.source == static_cast<uint8_t>(source)) {
            cut_ |= 1u << static_cast<uint32_t>(k);
        }
    }
}

void PolyInNode::heldNotes(int32_t port, NoteBuffer &into) const {
    if (port < 0 || port >= kInstances || !instances_[port].held) return;
    NoteEvent on = instances_[port].note;
    // An On even where a Change moved it last: what the new cable missed is the start.
    on.kind = NoteKind::On;
    on.offset = 0;
    into.push(on);
}

void PolyInNode::process(int32_t frames) {
    (void) frames;
    for (int32_t k = 0; k < kInstances; ++k) notesOut(k).clear();

    for (int32_t k = 0; k < kInstances; ++k) {
        if ((cut_ & (1u << static_cast<uint32_t>(k))) == 0) continue;
        NoteEvent off = instances_[k].note;
        off.kind = NoteKind::Off;
        off.offset = 0;
        notesOut(k).push(off);
        instances_[k].held = false;
    }
    cut_ = 0;

    const NoteBuffer &in = notesIn(0);
    for (int32_t e = 0; e < in.count; ++e) {
        const NoteEvent &event = in.events[e];
        if (event.kind == NoteKind::On) {
            const int32_t k = choose();
            if (instances_[k].held) {
                // Told to let go on the same sample. Inside the instance is an ordinary
                // Env holding an ordinary note, and nothing else would ever end it.
                NoteEvent off = instances_[k].note;
                off.kind = NoteKind::Off;
                off.offset = event.offset;
                notesOut(k).push(off);
            }
            instances_[k].note = event;
            instances_[k].held = true;
            instances_[k].age = age_++;
            notesOut(k).push(event);
            continue;
        }
        const int32_t k = holding(event.id, event.source);
        if (k < 0) continue;
        notesOut(k).push(event);
        if (event.kind == NoteKind::Off) instances_[k].held = false;
        // A Change moves the note, so what heldNotes hands over moves with it.
        if (event.kind == NoteKind::Change) instances_[k].note = event;
    }
}

void PolySumNode::process(int32_t frames) {
    float *o = out(0);
    for (int32_t i = 0; i < frames; ++i) {
        float sum = 0.0f;
        for (int32_t p = 0; p < kMaxPorts; ++p) sum += input(p)[i];
        o[i] = sum;
    }
}

// ---------------------------------------------------------------- Amp

void AmpNode::process(int32_t frames) {
    const float *in = input(0);
    const float *gain = input(1);  // the gain knob, per sample: see drivenParam
    float *o = out(0);
    for (int32_t i = 0; i < frames; ++i) o[i] = in[i] * gain[i];
}

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

// ---------------------------------------------------------------- Noise

namespace {
/**
 * Each noise node's seed: a counter stepped by the golden ratio's bits, so no two nodes made
 * in one run start from the same state or from states a few steps apart. Nodes are made on
 * the interface's thread, but the tests make them on others, so it is atomic anyway.
 */
std::atomic<uint32_t> noiseSeed{0x9E3779B9u};
} // namespace

NoiseNode::NoiseNode() {
    state_ = noiseSeed.fetch_add(0x9E3779B9u, std::memory_order_relaxed);
    if (state_ == 0) state_ = 1; // the one state a xorshift never leaves
}

float NoiseNode::white() {
    // Marsaglia's xorshift32.
    state_ ^= state_ << 13;
    state_ ^= state_ >> 17;
    state_ ^= state_ << 5;
    // The top 24 bits, as a float in [-1, 1): exact, since a float's mantissa is 24 bits.
    return static_cast<float>(state_ >> 8) * (2.0f / 16777216.0f) - 1.0f;
}

void NoiseNode::process(int32_t frames) {
    float *o = out(0);
    for (int32_t i = 0; i < frames; ++i) {
        const float w = white();
        // Kellet's coefficients are for 44.1kHz; at 48 the corners move up a tenth of an
        // octave, which is well inside the 0.05dB the filter is quoted to.
        pink_[0] = 0.99886f * pink_[0] + w * 0.0555179f;
        pink_[1] = 0.99332f * pink_[1] + w * 0.0750759f;
        pink_[2] = 0.96900f * pink_[2] + w * 0.1538520f;
        pink_[3] = 0.86650f * pink_[3] + w * 0.3104856f;
        pink_[4] = 0.55000f * pink_[4] + w * 0.5329522f;
        pink_[5] = -0.7616f * pink_[5] - w * 0.0168980f;
        const float pink = pink_[0] + pink_[1] + pink_[2] + pink_[3] + pink_[4] + pink_[5] +
                           pink_[6] + w * 0.5362f;
        pink_[6] = w * 0.115926f;
        brown_ = (brown_ + 0.02f * w) / 1.02f;

        // Scaled to about the same loudness, around 0.2 RMS, so switching the color changes
        // the color and not the level. Measured, not derived: see node_test. Not higher,
        // because pink and brown are Gaussian where white is uniform -- at 0.3 RMS they
        // peaked past full scale, where white at the same level never gets above 0.52.
        switch (type_) {
            case 1: o[i] = pink * 0.115f; break;
            case 2: o[i] = brown_ * 3.5f; break;
            default: o[i] = w * 0.35f; break;
        }
    }
}

void NoiseNode::setParam(int32_t index, float value) {
    if (index == 0) type_ = static_cast<int32_t>(clampf(value, 0.0f, 2.0f) + 0.5f);
}

// ---------------------------------------------------------------- Delay

float DelayNode::targetSamples() const {
    float samples;
    if (interval_ >= kFree) {
        samples = timeMs_ * 0.001f * static_cast<float>(sampleRate_);
    } else {
        // 120bpm until a graph says otherwise, which only a test that never sets one does.
        const double perFrame = tempo_ > 0.0 ? tempo_ : 2.0 / static_cast<double>(sampleRate_);
        const Interval &step = kIntervals[interval_];
        samples = static_cast<float>(static_cast<double>(step.num) / step.den / perFrame);
    }
    // Two short of the line, since a fractional read looks one sample past where it points.
    return clampf(samples, 1.0f, static_cast<float>(kMaxSamples - 2));
}

void DelayNode::process(int32_t frames) {
    const float *in = input(0);
    float *o = out(0);
    const double target = targetSamples();
    if (delay_ < 0.0) delay_ = target; // the first block starts where it is going
    // Smoothly into place over about 40ms when the move is small, and never faster than half
    // a sample per sample when it is large. The limit is the pitch: a read point moving at
    // speed v plays what is in the line at 1 - v times its pitch, and the exponential on its own
    // moved a 300ms change at 7.5 samples a sample -- a dive six times deeper than tape's,
    // measured as steps six times the sine's own. At half a sample, the line bends by at most
    // an octave down or a fifth up while a time changes, and a small change -- or a modulator
    // wobbling it -- never reaches the limit at all. The Filter's cutoff found the same thing:
    // a smooth moves most in the first moment, which for a large jump is the wrong moment.
    const double approach = 1.0 / (0.04 * static_cast<double>(sampleRate_));
    constexpr double kMaxGlide = 0.5;
    for (int32_t i = 0; i < frames; ++i) {
        delay_ += std::clamp((target - delay_) * approach, -kMaxGlide, kMaxGlide);
        // Linear, between the two samples either side. Hermite was tried when a capture showed
        // a haze through a glide, and measured no different: the haze was the Reverb after it,
        // smearing a tone that was sweeping an octave, which is what a reverb is for.
        const float echo = line_.Read(static_cast<float>(delay_));
        float back = in[i] + feedback_ * echo;
        // A decaying tail spends a long time in denormal numbers, which some processors take
        // hundreds of cycles over. Flushed well below anything audible.
        if (std::fabs(back) < 1e-20f) back = 0.0f;
        line_.Write(back);
        o[i] = in[i] + mix_ * (echo - in[i]);
    }
}

void DelayNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: interval_ = static_cast<int32_t>(clampf(value, 0.0f, static_cast<float>(kFree)) + 0.5f); break;
        case 1: timeMs_ = clampf(value, 1.0f, 4000.0f); break;
        // Below 1, so every echo is quieter than the one before and the tail always ends.
        case 2: feedback_ = clampf(value, 0.0f, 0.95f); break;
        case 3: mix_ = clampf(value, 0.0f, 1.0f); break;
        default: break;
    }
}

// ---------------------------------------------------------------- Reverb

void ReverbNode::prepare(int32_t sampleRate) {
    Node::prepare(sampleRate);
    room_.prepare(static_cast<float>(sampleRate));
    plate_.prepare(static_cast<float>(sampleRate));
    room_.set(size_, damp_);
    plate_.set(size_, damp_);
}

void ReverbNode::process(int32_t frames) {
    const float *in = input(0);
    float *left = out(0);
    float *right = out(1);
    const float target = static_cast<float>(type_);
    const float step = 1.0f / (0.05f * static_cast<float>(sampleRate_));
    for (int32_t i = 0; i < frames; ++i) {
        blend_ += clampf(target - blend_, -step, step);
        // Smoothstep over the linear blend, as every crossfade here is: a straight ramp is
        // continuous in level but not in slope, and both corners are heard.
        const float t = blend_ * blend_ * (3.0f - 2.0f * blend_);
        float roomL, roomR, plateL, plateR;
        room_.process(in[i], roomL, roomR);
        plate_.process(in[i], plateL, plateR);
        const float wetL = kRoomGain * roomL * (1.0f - t) + kPlateGain * plateL * t;
        const float wetR = kRoomGain * roomR * (1.0f - t) + kPlateGain * plateR * t;
        left[i] = in[i] + mix_ * (wetL - in[i]);
        right[i] = in[i] + mix_ * (wetR - in[i]);
    }
}

void ReverbNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: type_ = static_cast<int32_t>(clampf(value, 0.0f, 1.0f) + 0.5f); break;
        case 1: size_ = clampf(value, 0.0f, 1.0f); break;
        case 2: damp_ = clampf(value, 0.0f, 1.0f); break;
        case 3: mix_ = clampf(value, 0.0f, 1.0f); break;
        default: return;
    }
    room_.set(size_, damp_);
    plate_.set(size_, damp_);
}

// ---------------------------------------------------------------- factory

Node *makeNode(NodeType type) {
    switch (type) {
        case NodeType::Filter: return new FilterNode();
        case NodeType::Env: return new EnvNode();
        case NodeType::Steps: return new StepsNode();
        case NodeType::Mix: return new MixNode();
        case NodeType::Amp: return new AmpNode();
        case NodeType::Noise: return new NoiseNode();
        case NodeType::Delay: return new DelayNode();
        case NodeType::Reverb: return new ReverbNode();
        case NodeType::PolyIn: return new PolyInNode();
        case NodeType::PolySum: return new PolySumNode();
        case NodeType::Osc: return new OscNode();
        case NodeType::Pluck: return new PluckNode();
        case NodeType::Fm: return new FmNode();
        case NodeType::Sf: return new SfNode();
        case NodeType::Seq: return new SeqNode();
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
