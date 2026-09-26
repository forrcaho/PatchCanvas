#pragma once

#include <cstdint>

#include "nodes.h"

/*
 * Note processors: notes in, notes out. Bespoke's strength is a shelf of small modules like
 * these between a sequencer and a synth, and here they are cheap, because a note is an event
 * with an id -- a processor answers to its sources' ids and speaks with ids of its own.
 *
 * Every one of them has to keep the rules a source keeps. Each On it sends is matched by an
 * Off; a source unpatched from its input ends the notes that source started, downstream as
 * well (notesCut queues the Offs and the next block sends them); and a note it is holding is
 * reported to a cable patched in late (heldNotes).
 */

/**
 * What an incoming note became: the source that sent it and the id it came with, and the
 * notes it went out as. Chance sends one or none; Chord sends up to four.
 */
struct NoteLink {
    static constexpr int32_t kMaxOut = 4;

    bool used = false;
    uint32_t inId = 0;
    int32_t source = -1;
    int32_t count = 0;
    /** The incoming note's degree; each note sent is this plus its offset. */
    int32_t degree = 0;
    uint32_t outId[kMaxOut] = {};
    int32_t outDegree[kMaxOut] = {};
    float cents = 0.0f;
    int64_t beat = 0;
    float velocity = 1.0f;
};

/** The notes a processor has passed on and not yet ended. */
class NoteLinks {
public:
    static constexpr int32_t kLinks = 32;

    NoteLink *find(uint32_t id, int32_t source);
    /** A free link, or null when every one is holding a note. */
    NoteLink *claim(uint32_t id, int32_t source);
    /** Offs for every note [source] sent, into [into] at [offset], and forgets them. */
    void cut(int32_t source, NoteBuffer &into, uint16_t offset);
    /** Every held note as an On, for a cable patched in late. */
    void held(NoteBuffer &into) const;

    uint32_t nextId() { return nextId_++; }

private:
    NoteLink links_[kLinks];
    uint32_t nextId_ = 1;
};

/** The Offs a notesCut owes, sent at the top of the next block. */
struct PendingOffs {
    NoteBuffer offs;

    /** Moves them into [into], ahead of anything else this block. */
    void flush(NoteBuffer &into);
};

/** A generator of numbers that never locks: rand() takes a mutex on Android. */
struct Dice {
    uint32_t state = 0x9E3779B9u;

    /** 0 to 1, never 1. */
    float roll() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return static_cast<float>(state >> 8) * (1.0f / 16777216.0f);
    }
};

/**
 * Passes each note with a probability, and drops the rest. Variation without rewriting the
 * sequence: a Steps pattern through a Chance at 0.7 is the same line with gaps that differ
 * every time round. A note is decided when it starts; its Off and any Change follow it.
 */
class ChanceNode : public Node {
public:
    int32_t inputCount() const override { return 1; }
    int32_t outputCount() const override { return 1; }
    uint32_t noteInputs() const override { return 1u << 0; }
    uint32_t noteOutputs() const override { return 1u << 0; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void notesCut(int32_t port, int32_t source) override;
    void heldNotes(int32_t port, NoteBuffer &into) const override;

private:
    float chance_ = 0.5f;
    Dice dice_;
    NoteLinks links_;
    PendingOffs pending_;
};

/**
 * Each note becomes a chord: the note, and up to three more at intervals counted in degrees
 * of the scale sounding -- so the same knobs make a major triad in a major scale and a
 * minor one on its sixth degree, and mean something in any tuning. An interval of 0 adds
 * nothing. A held note keeps the chord it started with.
 */
class ChordNode : public Node {
public:
    int32_t inputCount() const override { return 1; }
    int32_t outputCount() const override { return 1; }
    uint32_t noteInputs() const override { return 1u << 0; }
    uint32_t noteOutputs() const override { return 1u << 0; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void notesCut(int32_t port, int32_t source) override;
    void heldNotes(int32_t port, NoteBuffer &into) const override;

private:
    int32_t intervals_[3] = {4, 7, 0};
    NoteLinks links_;
    PendingOffs pending_;
};

/**
 * An arpeggiator: the notes held at its input, played one at a time on the transport's
 * ticks, up, down, up and down or at random, across one to four octaves. Clocked like Steps
 * and with Steps' note length, half a step. Hold a chord on a Drone and it plays it.
 *
 * Knobs, mirroring PatchCanvas.kt: mode, octaves, interval.
 */
class ArpNode : public Node {
public:
    static constexpr int32_t kMaxHeld = 16;

    int32_t inputCount() const override { return 1; }
    int32_t outputCount() const override { return 1; }
    uint32_t noteInputs() const override { return 1u << 0; }
    uint32_t noteOutputs() const override { return 1u << 0; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void notesCut(int32_t port, int32_t source) override;
    void heldNotes(int32_t port, NoteBuffer &into) const override;
    Interval interval() const override { return interval_; }
    void tick(int32_t offset, int64_t count) override;
    int32_t position() const override { return position_; }

    /** Notes held at the input, for tests. */
    int32_t notesHeldIn() const { return heldCount_; }

private:
    struct In {
        uint32_t id;
        int32_t source;
        int32_t degree;
        float cents;
        float velocity;
    };
    static constexpr int32_t kMaxPending = 4;

    void hold(const NoteEvent &event);
    void letGo(uint32_t id, int32_t source);
    void step(NoteBuffer &notes, uint16_t offset, int64_t count);
    void end(NoteBuffer &notes, uint16_t offset);

    In held_[kMaxHeld] = {};
    int32_t heldCount_ = 0;
    Tick pending_[kMaxPending] = {};
    int32_t pendingCount_ = 0;

    int32_t mode_ = 0;
    int32_t octaves_ = 1;
    Interval interval_ = intervalOf(kDefaultInterval);
    /** Where in the pattern it is: -1 before the first note. */
    int32_t position_ = -1;
    /** Up-and-down's direction. */
    bool falling_ = false;
    Dice dice_;

    uint32_t soundingId_ = 0;
    int32_t soundingDegree_ = 0;
    float soundingCents_ = 0.0f;
    int64_t soundingBeat_ = 0;
    int64_t gateRemaining_ = 0;
    uint32_t nextId_ = 1;
};

/**
 * A Euclidean rhythm: [pulses] notes spread as evenly as they go over [steps], turned by
 * [rotate], each at one degree. The tresillo is 3 over 8; most of the world's bell patterns
 * are one of these. Clocked like Steps, with Steps' half-step notes.
 *
 * Knobs, mirroring PatchCanvas.kt: steps, pulses, rotate, degree, interval.
 */
class EuclidNode : public Node {
public:
    /** Mirrored by EUCLID_STEPS in PatchCanvas.kt. */
    static constexpr int32_t kMaxSteps = 32;

    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 1; }
    uint32_t noteOutputs() const override { return 1u << 0; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void heldNotes(int32_t port, NoteBuffer &into) const override;
    Interval interval() const override { return interval_; }
    void tick(int32_t offset, int64_t count) override;
    int32_t position() const override { return step_; }

    /** Whether step [index] of the pattern sounds. Public for tests and the panel's mirror of it. */
    static bool hit(int32_t index, int32_t steps, int32_t pulses, int32_t rotate);

private:
    static constexpr int32_t kMaxPending = 4;

    Tick pending_[kMaxPending] = {};
    int32_t pendingCount_ = 0;
    int32_t steps_ = 8;
    int32_t pulses_ = 3;
    int32_t rotate_ = 0;
    int32_t degree_ = 0;
    Interval interval_ = intervalOf(kDefaultInterval);
    int32_t step_ = -1;

    uint32_t soundingId_ = 0;
    int64_t soundingBeat_ = 0;
    int64_t gateRemaining_ = 0;
    uint32_t nextId_ = 1;
};
