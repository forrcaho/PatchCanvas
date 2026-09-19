#include "processors.h"

#include <algorithm>
#include <cmath>

namespace {

float clampf(float value, float low, float high) {
    return value < low ? low : (value > high ? high : value);
}

int32_t whole(float value, int32_t low, int32_t high) {
    return static_cast<int32_t>(std::lround(clampf(value, static_cast<float>(low), static_cast<float>(high))));
}

/** How much of its step a generated note sounds for: half, as a Steps note does. */
constexpr double kGateFraction = 0.5;

/** A note's frames of gate at [interval], or 0 with the transport stopped. */
int64_t gateFrames(Interval interval, double beatsPerFrame) {
    return beatsPerFrame > 0.0
            ? static_cast<int64_t>(kGateFraction * interval.num / (interval.den * beatsPerFrame))
            : 0;
}

NoteEvent offFor(uint32_t id, uint16_t offset) {
    NoteEvent off;
    off.id = id;
    off.kind = NoteKind::Off;
    off.offset = offset;
    return off;
}

} // namespace

// ---------------------------------------------------------------- shared

NoteLink *NoteLinks::find(uint32_t id, int32_t source) {
    for (auto &link : links_) {
        if (link.used && link.inId == id && link.source == source) return &link;
    }
    return nullptr;
}

NoteLink *NoteLinks::claim(uint32_t id, int32_t source) {
    for (auto &link : links_) {
        if (!link.used) {
            link = NoteLink{};
            link.used = true;
            link.inId = id;
            link.source = source;
            return &link;
        }
    }
    return nullptr;
}

void NoteLinks::cut(int32_t source, NoteBuffer &into, uint16_t offset) {
    for (auto &link : links_) {
        if (!link.used || link.source != source) continue;
        for (int32_t k = 0; k < link.count; ++k) into.push(offFor(link.outId[k], offset));
        link.used = false;
    }
}

void NoteLinks::held(NoteBuffer &into) const {
    for (const auto &link : links_) {
        if (!link.used) continue;
        for (int32_t k = 0; k < link.count; ++k) {
            NoteEvent on;
            on.id = link.outId[k];
            on.kind = NoteKind::On;
            on.degree = link.degree + link.outDegree[k];
            on.beat = link.beat;
            on.cents = link.cents;
            on.velocity = link.velocity;
            if (!into.push(on)) return;
        }
    }
}

void PendingOffs::flush(NoteBuffer &into) {
    for (int32_t i = 0; i < offs.count; ++i) into.push(offs.events[i]);
    offs.clear();
}

// ---------------------------------------------------------------- Chance

void ChanceNode::setParam(int32_t index, float value) {
    if (index == 0) chance_ = clampf(value, 0.0f, 1.0f);
}

void ChanceNode::notesCut(int32_t port, int32_t source) {
    (void) port;
    links_.cut(source, pending_.offs, 0);
}

void ChanceNode::heldNotes(int32_t port, NoteBuffer &into) const {
    (void) port;
    links_.held(into);
}

void ChanceNode::process(int32_t frames) {
    (void) frames;
    NoteBuffer &notes = notesOut(0);
    notes.clear();
    pending_.flush(notes);
    const NoteBuffer &in = notesIn(0);
    for (int32_t i = 0; i < in.count; ++i) {
        NoteEvent event = in.events[i];
        if (event.kind == NoteKind::On) {
            // Decided here, once. The roll is taken even at 0 and 1 so the sequence of
            // choices does not shift when the knob passes through either end.
            const bool passes = dice_.roll() < chance_;
            if (!passes) continue;
            NoteLink *link = links_.claim(event.id, event.source);
            if (link == nullptr) continue;
            link->count = 1;
            link->degree = event.degree;
            link->beat = event.beat;
            link->cents = event.cents;
            link->velocity = event.velocity;
            link->outId[0] = links_.nextId();
            event.id = link->outId[0];
            notes.push(event);
        } else {
            // An Off or a Change for a note that was dropped finds no link, and says nothing.
            NoteLink *link = links_.find(event.id, event.source);
            if (link == nullptr) continue;
            if (event.kind == NoteKind::Change) {
                link->degree = event.degree;
                link->beat = event.beat;
                link->cents = event.cents;
            } else {
                link->used = false;
            }
            event.id = link->outId[0];
            notes.push(event);
        }
    }
}

// ---------------------------------------------------------------- Chord

void ChordNode::setParam(int32_t index, float value) {
    if (index >= 0 && index < 3) intervals_[index] = whole(value, -24, 24);
}

void ChordNode::notesCut(int32_t port, int32_t source) {
    (void) port;
    links_.cut(source, pending_.offs, 0);
}

void ChordNode::heldNotes(int32_t port, NoteBuffer &into) const {
    (void) port;
    links_.held(into);
}

void ChordNode::process(int32_t frames) {
    (void) frames;
    NoteBuffer &notes = notesOut(0);
    notes.clear();
    pending_.flush(notes);
    const NoteBuffer &in = notesIn(0);
    for (int32_t i = 0; i < in.count; ++i) {
        const NoteEvent &event = in.events[i];
        if (event.kind == NoteKind::On) {
            NoteLink *link = links_.claim(event.id, event.source);
            if (link == nullptr) continue;
            link->degree = event.degree;
            link->beat = event.beat;
            link->cents = event.cents;
            link->velocity = event.velocity;
            // The note itself, then each interval that is not 0, as notes of their own.
            link->outDegree[link->count++] = 0;
            for (int32_t interval : intervals_) {
                if (interval != 0) link->outDegree[link->count++] = interval;
            }
            for (int32_t k = 0; k < link->count; ++k) {
                NoteEvent on = event;
                on.id = link->outId[k] = links_.nextId();
                on.degree = event.degree + link->outDegree[k];
                notes.push(on);
            }
            continue;
        }
        NoteLink *link = links_.find(event.id, event.source);
        if (link == nullptr) continue;
        if (event.kind == NoteKind::Change) {
            link->degree = event.degree;
            link->beat = event.beat;
            link->cents = event.cents;
        } else {
            link->used = false;
        }
        // The chord it started with, moved or ended whole.
        for (int32_t k = 0; k < link->count; ++k) {
            NoteEvent out = event;
            out.id = link->outId[k];
            out.degree = event.degree + link->outDegree[k];
            notes.push(out);
        }
    }
}

// ---------------------------------------------------------------- Arp

void ArpNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: mode_ = whole(value, 0, 3); break;
        case 1: octaves_ = whole(value, 1, 4); break;
        case 2: intervalIndex_ = whole(value, 0, kIntervalCount - 1); break;
        default: break;
    }
}

void ArpNode::tick(int32_t offset, int64_t count) {
    if (pendingCount_ < kMaxPending) pending_[pendingCount_++] = Tick{offset, count};
}

void ArpNode::hold(const NoteEvent &event) {
    if (heldCount_ >= kMaxHeld) return;
    // Kept in order of pitch, which is what up and down mean.
    int32_t at = heldCount_;
    while (at > 0 && (held_[at - 1].degree > event.degree ||
                      (held_[at - 1].degree == event.degree && held_[at - 1].cents > event.cents))) {
        held_[at] = held_[at - 1];
        --at;
    }
    held_[at] = In{event.id, event.source, event.degree, event.cents, event.velocity};
    ++heldCount_;
}

void ArpNode::letGo(uint32_t id, int32_t source) {
    for (int32_t i = 0; i < heldCount_; ++i) {
        if (held_[i].id != id || held_[i].source != source) continue;
        for (int32_t j = i; j + 1 < heldCount_; ++j) held_[j] = held_[j + 1];
        --heldCount_;
        return;
    }
}

void ArpNode::notesCut(int32_t port, int32_t source) {
    (void) port;
    // Only what it was holding. The note it is playing is its own, and ends on its gate.
    for (int32_t i = heldCount_ - 1; i >= 0; --i) {
        if (held_[i].source == source) letGo(held_[i].id, source);
    }
}

void ArpNode::heldNotes(int32_t port, NoteBuffer &into) const {
    (void) port;
    if (soundingId_ == 0) return;
    NoteEvent on;
    on.id = soundingId_;
    on.kind = NoteKind::On;
    on.degree = soundingDegree_;
    on.beat = soundingBeat_;
    on.cents = soundingCents_;
    into.push(on);
}

void ArpNode::end(NoteBuffer &notes, uint16_t offset) {
    if (soundingId_ == 0) return;
    notes.push(offFor(soundingId_, offset));
    soundingId_ = 0;
}

void ArpNode::step(NoteBuffer &notes, uint16_t offset, int64_t count) {
    end(notes, offset);
    if (heldCount_ == 0) {
        position_ = -1;
        falling_ = false;
        return;
    }
    const int32_t total = heldCount_ * octaves_;
    // The held notes may have changed under it; where it was is kept, inside what there is.
    if (position_ >= total) position_ %= total;
    switch (mode_) {
        case 0: position_ = (position_ + 1) % total; break;
        case 1: position_ = position_ <= 0 ? total - 1 : position_ - 1; break;
        case 2:
            // Up and down without repeating either end: 0 1 2 1 0 1 2 ...
            if (total == 1 || position_ < 0) {
                position_ = 0;
                falling_ = false;
            } else if (!falling_) {
                if (position_ + 1 >= total) { falling_ = true; position_ = total - 2; } else { ++position_; }
            } else {
                if (position_ - 1 < 0) { falling_ = false; position_ = 1; } else { --position_; }
            }
            break;
        default: position_ = std::min(total - 1, static_cast<int32_t>(dice_.roll() * total)); break;
    }

    const In &note = held_[position_ % heldCount_];
    const int32_t octave = position_ / heldCount_;
    const Interval interval = kIntervals[intervalIndex_];
    const int64_t beat = floorDiv(count * interval.num, interval.den);
    // An octave is the scale's period, in degrees: as many as the scale sounding has.
    int32_t size = scales_ != nullptr ? scales_->tableAt(beat).size : 12;
    if (size <= 0) size = 12;

    NoteEvent on;
    on.id = nextId_++;
    on.kind = NoteKind::On;
    on.offset = offset;
    on.degree = note.degree + octave * size;
    on.beat = beat;
    on.cents = note.cents;
    on.velocity = note.velocity;
    if (!notes.push(on)) return;
    soundingId_ = on.id;
    soundingDegree_ = on.degree;
    soundingCents_ = on.cents;
    soundingBeat_ = beat;
    gateRemaining_ = gateFrames(interval, beatsPerFrame_);
}

void ArpNode::process(int32_t frames) {
    NoteBuffer &notes = notesOut(0);
    notes.clear();
    const NoteBuffer &in = notesIn(0);
    int32_t nextEvent = 0;
    int32_t nextTick = 0;
    for (int32_t i = 0; i < frames; ++i) {
        // What is held first, then the tick: a chord arriving on the sample a tick falls
        // on is played from that tick, not the one after.
        while (nextEvent < in.count && in.events[nextEvent].offset <= i) {
            const NoteEvent &event = in.events[nextEvent++];
            if (event.kind == NoteKind::On) hold(event);
            if (event.kind == NoteKind::Off) letGo(event.id, event.source);
            if (event.kind == NoteKind::Change) {
                letGo(event.id, event.source);
                hold(event);
            }
        }
        while (nextTick < pendingCount_ && pending_[nextTick].offset <= i) {
            step(notes, static_cast<uint16_t>(i), pending_[nextTick].count);
            ++nextTick;
        }
        if (running_ && gateRemaining_ > 0 && --gateRemaining_ == 0) end(notes, static_cast<uint16_t>(i));
    }
    pendingCount_ = 0;
}

// ---------------------------------------------------------------- Euclid

bool EuclidNode::hit(int32_t index, int32_t steps, int32_t pulses, int32_t rotate) {
    if (steps <= 0 || pulses <= 0) return false;
    if (pulses >= steps) return true;
    // Bresenham's line, which spreads [pulses] over [steps] as evenly as whole steps allow
    // and gives the same patterns as Bjorklund's algorithm up to rotation -- 3 over 8 is
    // x..x..x., the tresillo.
    const int32_t at = ((index + rotate) % steps + steps) % steps;
    return (at * pulses) % steps < pulses;
}

void EuclidNode::setParam(int32_t index, float value) {
    switch (index) {
        case 0: steps_ = whole(value, 1, kMaxSteps); break;
        case 1: pulses_ = whole(value, 0, kMaxSteps); break;
        case 2: rotate_ = whole(value, 0, kMaxSteps - 1); break;
        case 3: degree_ = whole(value, -24, 24); break;
        case 4: intervalIndex_ = whole(value, 0, kIntervalCount - 1); break;
        default: break;
    }
}

void EuclidNode::tick(int32_t offset, int64_t count) {
    if (pendingCount_ < kMaxPending) pending_[pendingCount_++] = Tick{offset, count};
}

void EuclidNode::heldNotes(int32_t port, NoteBuffer &into) const {
    (void) port;
    if (soundingId_ == 0) return;
    NoteEvent on;
    on.id = soundingId_;
    on.kind = NoteKind::On;
    on.degree = degree_;
    on.beat = soundingBeat_;
    into.push(on);
}

void EuclidNode::process(int32_t frames) {
    NoteBuffer &notes = notesOut(0);
    notes.clear();
    int32_t next = 0;
    for (int32_t i = 0; i < frames; ++i) {
        while (next < pendingCount_ && pending_[next].offset <= i) {
            const int64_t count = pending_[next++].count;
            const auto offset = static_cast<uint16_t>(i);
            if (soundingId_ != 0) {
                notes.push(offFor(soundingId_, offset));
                soundingId_ = 0;
            }
            step_ = static_cast<int32_t>(((count % steps_) + steps_) % steps_);
            if (!hit(step_, steps_, pulses_, rotate_)) continue;
            const Interval interval = kIntervals[intervalIndex_];
            NoteEvent on;
            on.id = nextId_++;
            on.kind = NoteKind::On;
            on.offset = offset;
            on.degree = degree_;
            on.beat = floorDiv(count * interval.num, interval.den);
            if (!notes.push(on)) continue;
            soundingId_ = on.id;
            soundingBeat_ = on.beat;
            gateRemaining_ = gateFrames(interval, beatsPerFrame_);
        }
        if (running_ && gateRemaining_ > 0 && --gateRemaining_ == 0 && soundingId_ != 0) {
            notes.push(offFor(soundingId_, static_cast<uint16_t>(i)));
            soundingId_ = 0;
        }
    }
    pendingCount_ = 0;
}
