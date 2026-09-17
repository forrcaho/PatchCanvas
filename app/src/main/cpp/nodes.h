#pragma once

#include <cstdint>

#include "node.h"

#include "adsr.h"
#include "dcblock.h"
#include "limiter.h"
#include "oscillator.h"
#include "svf.h"

/**
 * Node types, mirrored by NodeType.kt. The numbering is part of the JNI contract, so
 * append rather than reorder.
 */
enum class NodeType : int32_t {
    Unknown = 0,
    // 1 was the monophonic Osc, retired when every synth became polyphonic and the
    // polyphonic one took its name. Left unused rather than reassigned, so nothing can
    // mistake an old id for a new module -- as with 7 and 8.
    Filter = 2,
    Env = 3,
    Steps = 4,
    Out = 5,
    In = 6,
    // 7 was Vca, retired with CV: its entire reason was a control-voltage input, and what
    // remained is a gain with a modulatable level, which is what Mix already is.
    // 8 was Clock, retired when the transport replaced it.
    Mix = 9,
    /** The polyphonic synth. Called Voice while a monophonic Osc still existed. */
    Osc = 10,
    Lfo = 11,
    Drone = 12,
};

/**
 * The note lengths a clocked module can step at. Mirrored by INTERVALS in
 * PatchCanvas.kt and indexed by the parameter that chooses one, so append rather than
 * reorder.
 */
constexpr Interval kIntervals[] = {
        {4, 1}, // 1/1
        {2, 1}, // 1/2
        {1, 1}, // 1/4
        {1, 2}, // 1/8
        {1, 4}, // 1/16
        {1, 8}, // 1/32
        {2, 3}, // 1/4 triplet
        {1, 3}, // 1/8 triplet
        {1, 6}, // 1/16 triplet
};
constexpr int32_t kIntervalCount = static_cast<int32_t>(sizeof(kIntervals) / sizeof(kIntervals[0]));
constexpr int32_t kDefaultInterval = 3; // 1/8

/**
 * Pitch is 1V/oct in the Eurorack sense, expressed in octaves: 0 is middle C, 1.0 is an
 * octave up. Using octaves rather than volts keeps the arithmetic to exp2 and avoids
 * pretending there is a voltage anywhere in here.
 */
constexpr float kMiddleC = 261.6256f;

/** Anything not yet implemented: right shape, silent. */
class NullNode : public Node {
public:
    NullNode(int32_t inputs, int32_t outputs) : inputs_(inputs), outputs_(outputs) {}
    int32_t inputCount() const override { return inputs_; }
    int32_t outputCount() const override { return outputs_; }
    void process(int32_t frames) override;

private:
    int32_t inputs_;
    int32_t outputs_;
};

/** State-variable filter, lowpass tap. */
class FilterNode : public Node {
public:
    int32_t inputCount() const override { return 1; }  // in
    int32_t outputCount() const override { return 1; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    daisysp::Svf svf_;
    float cutoffHz_ = 1000.0f;
};

/**
 * An envelope, opened by notes.
 *
 * It took a gate, which was a level: high is on. A pulse is an event and has no duration,
 * so it could never say when to release -- and a note already carries exactly what an ADSR
 * wants, an on, an off and an id to match them by. So this reads notes and the gate is
 * simply whether any note is being held.
 *
 * Legato, not retriggered: a second note arriving over a held one leaves the gate open, so
 * the envelope carries on rather than starting its attack again. Sustain is what an
 * envelope is for, and re-attacking under a held note would make a chord into a stutter.
 */
class EnvNode : public Node {
public:
    int32_t inputCount() const override { return 1; }  // notes
    int32_t outputCount() const override { return 1; }
    uint32_t noteInputs() const override { return 1u << 0; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void notesCut(int32_t port, int32_t source) override;

private:
    /**
     * Notes tracked at once. An envelope is one control voltage however many notes are on
     * it, so this exists only to know when the last one lets go. Past the end an On is not
     * tracked -- the gate is already open and stays open while anything tracked is held,
     * so the worst it costs is a release that comes early on a sixteen-note pile-up.
     */
    static constexpr int32_t kHeld = 16;

    struct Held {
        uint32_t id = 0;
        /** The input slot the event came from, so notesCut can end one source's notes. */
        int32_t source = -1;
    };

    void start(const NoteEvent &event);
    void release(uint32_t id, int32_t source);

    Held held_[kHeld] = {};
    int32_t heldCount_ = 0;
    daisysp::Adsr adsr_;
};

/**
 * A sequence, stepped by the transport at the interval it is set to.
 *
 * It has no clock input. Its step is the transport's count of intervals, modulo the loop
 * length, so two sequencers at different intervals cannot drift apart and resetting the
 * transport puts every one of them back on its first step.
 */
class StepsNode : public Node {
public:
    static constexpr int32_t kSteps = 16;

    StepsNode();

    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 1; } // notes
    /**
     * Notes, and nothing else. This is the end of the "same sequence, said twice" that
     * pitch and gate were: the gate went with the envelope that read it, and pitch with
     * the monophonic Osc that was the only thing taking one. A note carries the degree,
     * the beat that decides its scale and the transpose in a single event, and is the
     * only one of the three that can hold a chord.
     */
    uint32_t noteOutputs() const override { return 1u << 0; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void setStep(int32_t index, int32_t degree, bool gate) override;
    int32_t position() const override { return step_; }
    Interval interval() const override { return kIntervals[intervalIndex_]; }
    void tick(int32_t offset, int64_t count) override;

private:
    /** More than one interval boundary per block would need an interval under a millisecond. */
    static constexpr int32_t kMaxPending = 4;

    Tick pending_[kMaxPending] = {};
    int32_t pendingCount_ = 0;

    /** -1 until the first tick, which is what keeps a stopped sequencer from drawing a playhead. */
    int32_t step_ = -1;
    /** The last step that actually sounded, which is the one a note event describes. */
    int32_t voiced_ = 0;
    /**
     * The whole beat that note started on. Kept, not recomputed, so a note held through a
     * scale change keeps the scale it started in.
     */
    int64_t voicedBeat_ = 0;
    int32_t length_ = 8;
    int32_t intervalIndex_ = kDefaultInterval;
    /** Frames of gate left on the note that last started. Counts only while the transport runs. */
    int64_t gateRemaining_ = 0;
    /**
     * The note now sounding on the notes output, or 0 for none.
     *
     * Ids are this node's own and start again at 1 whenever it is rebuilt, which is all
     * they have to be: an Off is matched against the source that sent the On, and the
     * graph tags every event with which source that was.
     */
    uint32_t soundingId_ = 0;
    uint32_t nextNoteId_ = 1;
    /** Cents, so a tuning with no semitone in it is still expressible. */
    float transposeCents_ = 0.0f;
    /** Degrees of the patch's scale; which scale is decided when each note starts. */
    int32_t degree_[kSteps] = {};
    bool gate_[kSteps] = {};
};

/**
 * Notes that stay on until they are turned off: a grid of held pitches.
 *
 * Every other note source here is clocked, which left the engine with nothing that simply
 * sounds. A drone is the plainest thing a note cable can carry, and it is also what the
 * graph tests use for a tone now that no oscillator drones by itself.
 *
 * A cell is a degree, and the interface lays the degrees out as a grid -- the scale's
 * degrees up the rows, octaves across the columns -- because ScaleTable::octavesOf already
 * treats a degree as an unbounded integer that runs into the next period past the end of
 * the table. The grid is a two-dimensional view of that one axis and costs the engine
 * nothing: a cell is a degree and nothing here knows about rows.
 *
 * It is ticked at a quarter note, which it uses for nothing but knowing the beat. A note
 * has to name the beat it starts on so the right scale resolves it, and a drone must be
 * able to sound with the transport stopped -- so the ticks tell it where the music is
 * without its sounding depending on them.
 *
 * A drone note follows the scale; a sequencer's note does not. Every other held note here
 * keeps the scale it started in, a rule made for notes that end within a step. A drone's
 * never end, so under that rule a degree toggled in 12-TET went on sounding its 12-TET
 * pitch through every later scale, while the grid showed the new one -- found on the phone,
 * 2026-09-16. So at each beat, and whenever the scale list itself is replaced, a drone
 * sends a Change for every held note whose pitch has moved, and the oscillator glides
 * there rather than stepping.
 */
class DroneNode : public Node {
public:
    /** Mirrored by DRONE_CELLS in PatchCanvas.kt, and capped by the degrees a scale can hold. */
    static constexpr int32_t kCells = kMaxDegrees;

    DroneNode();

    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 1; }
    uint32_t noteOutputs() const override { return 1u << 0; }
    void process(int32_t frames) override;
    void setStep(int32_t index, int32_t degree, bool gate) override;
    Interval interval() const override { return {1, 1}; }
    void tick(int32_t offset, int64_t count) override;
    void heldNotes(int32_t port, NoteBuffer &into) const override;

private:
    int32_t degree_[kCells] = {};
    bool on_[kCells] = {};
    /**
     * The id each cell is sounding under, or 0 for one that is silent.
     *
     * This is what makes the node idempotent: process() emits only where on_ and this
     * disagree, so a held note is sent once and nothing re-triggers it. A push that does
     * not fit -- kMaxNoteEvents is 32 and the grid is larger -- simply leaves them
     * disagreeing, and the rest go out on the next block.
     */
    uint32_t sounding_[kCells] = {};
    /**
     * The pitch each sounding cell was last told to play, in octaves from middle C. Kept so
     * a retune is sent only where a pitch actually moved: most scale changes leave some
     * degrees where they were, and a glide from a pitch to itself is a command for nothing.
     */
    float octaves_[kCells] = {};
    uint32_t nextNoteId_ = 1;
    /** The last quarter-note boundary seen, which is the beat a note starting now belongs to. */
    int64_t beat_ = 0;

    /** A degree's pitch in the scale sounding on [beat], resolved as OscNode resolves it. */
    float octavesAt(int64_t beat, int32_t degree) const;

    /**
     * A retune waiting for process() to say it. Set by a tick, since events can only be
     * written into the block that is about to render, and by the scale list being replaced
     * -- which can happen with the transport stopped, when no tick will come.
     */
    bool retuneDue_ = false;
    int64_t retuneBeat_ = 0;
    uint16_t retuneOffset_ = 0;
    /** Compared by address only, never followed: it says the list was swapped, nothing more. */
    const ScaleList *retunedFor_ = nullptr;
};

/**
 * Notes in, sound out: a small polyphonic synth with its voices built in.
 *
 * This is Bespoke's shape rather than Eurorack's. A Eurorack cable carries one signal, so
 * polyphony there means building a voice and copying it -- which is where the roadmap's
 * "a three-voice patch is twelve nodes" came from. Here a chord arrives down one cable
 * and whatever sounds it allocates the voices, so a chord costs one module.
 *
 * The voices are made when the node is, because the audio thread cannot make anything.
 * Patched voices, stamped out N times, are the other way to do this and are Phase 7's;
 * the two can coexist, and notes are the first step either way.
 */
class OscNode : public Node {
public:
    /**
     * Eight. A sixteen-note column would be a chord nobody plays, and every voice costs
     * an oscillator and an envelope whether it is sounding or not.
     */
    static constexpr int32_t kVoices = 8;

    int32_t inputCount() const override { return 1; }  // notes
    int32_t outputCount() const override { return 1; }
    uint32_t noteInputs() const override { return 1u << 0; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void notesCut(int32_t port, int32_t source) override;

private:
    struct Voice {
        daisysp::Oscillator osc;
        daisysp::Adsr env;
        /** Who it belongs to: the id its On carried, and the input slot that sent it. */
        uint32_t id = 0;
        int32_t source = -1;
        bool gate = false;
        /**
         * Taken, whether or not it is making a sound yet.
         *
         * Separate from the envelope's own idea of running, because that only becomes true
         * once a sample has been processed -- and every note of a chord starts on the same
         * sample, before any of them has. Asking the envelope instead handed the whole
         * chord to voice zero, one note overwriting the next, which sounded exactly like a
         * monophonic sequencer and was found by a test asserting three notes sound.
         */
        bool active = false;
        /** When it started, for choosing which to steal. */
        int64_t age = 0;
        /** Where its pitch is now, in octaves from middle C, cents included. */
        float octaves = 0.0f;
        /** A glide in progress: from, to, and frames still to go. */
        float glideFrom = 0.0f;
        float glideTo = 0.0f;
        int32_t glideLeft = 0;
    };

    void start(const NoteEvent &event);
    void release(uint32_t id, int32_t source);
    /** A held note told to move: it glides there rather than stepping. */
    void change(const NoteEvent &event);
    /** A note's pitch in octaves from middle C, against the scale of the beat it carries. */
    float pitchOf(const NoteEvent &event) const;

    /** How long a glide takes: 30ms, like every crossfade in the engine. */
    int32_t glideFrames_ = 1440;

    Voice voices_[kVoices];
    int64_t age_ = 0;
    float attack_ = 0.005f;
    float decay_ = 0.12f;
    float sustain_ = 0.6f;
    float release_ = 0.25f;
};

/**
 * A slow wave, for turning knobs.
 *
 * Unipolar, 0 to 1, because that is what a modulation cable means: the destination stores
 * the low and high it sweeps between, in its own units, so the modulator only ever says
 * how far along. A bipolar LFO would put half of every sweep below the low end.
 *
 * Free-running in hertz for now. Locking it to the transport is the obvious next control
 * and the reason the waveform order matches the oscillator's, so the panel draws the same
 * four pictures.
 */
class LfoNode : public Node {
public:
    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 1; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    double phase_ = 0.0;
    float rateHz_ = 1.0f;
    /** Order mirrors kWaves in OscNode::setParam: saw, square, triangle, sine. */
    int32_t wave_ = 3;
};

/** Sums its inputs. Necessary because an input takes exactly one source. */
class MixNode : public Node {
public:
    int32_t inputCount() const override { return 4; }
    int32_t outputCount() const override { return 1; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    float level_[4] = {1.0f, 1.0f, 1.0f, 1.0f};
};

/**
 * The sink. DC blocked and limited, in that order.
 *
 * The limiter is not polish: a feedback patch reaches full scale instantly, and this is
 * an instrument played on headphones.
 */
class OutNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // L, R
    int32_t outputCount() const override { return 2; } // mirrored, for the engine to read
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    float level_ = 1.0f;
    daisysp::DcBlock dcLeft_;
    daisysp::DcBlock dcRight_;
    daisysp::Limiter limitLeft_;
    daisysp::Limiter limitRight_;
};

/**
 * The live microphone, pinned left.
 *
 * Its source is handed to it by the engine each block rather than read here: the input
 * stream is drained once per callback, and a node has no business knowing about streams.
 * With no source it is silent, which is what the rail does when the mic is switched off.
 */
class InNode : public Node {
public:
    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 2; } // L, R
    void process(int32_t frames) override;

    /** Audio thread, before process(). Null means silence. */
    void setSource(const float *mono) { source_ = mono; }
    void setParam(int32_t index, float value) override;

private:
    const float *source_ = nullptr;
    /**
     * Unprocessed input is raw by request, so a phone mic at talking distance arrives
     * far below what an oscillator produces. This was a constant; it is a knob because
     * the right amount depends on the room.
     */
    float gain_ = 8.0f;
};

/** Allocates a node for a type. Never called on the audio thread. */
Node *makeNode(NodeType type);
