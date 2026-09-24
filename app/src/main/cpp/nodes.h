#pragma once

#include <cstdint>

#include "node.h"
#include "synth.h"

#include "adsr.h"
#include "dcblock.h"
#include "KarplusString.h"
#include "limiter.h"
#include "oscillator.h"
#include "svf.h"

/**
 * Node types, mirrored by NodeType.kt. The numbering is part of the JNI contract, so
 * append rather than reorder.
 */
enum class NodeType : int32_t {
    Unknown = 0,
    // 1 was the monophonic Osc, retired when polyphony moved inside the synths and the
    // polyphonic one took its name. Polyphony has since moved out again, into the poly
    // subpatch, and Osc is monophonic once more -- but an id is retired for good, so this
    // one stays unused and nothing can mistake it for a new module. As with 7 and 8.
    Filter = 2,
    Env = 3,
    Steps = 4,
    Out = 5,
    In = 6,
    // 7 was Vca, retired with CV: its entire reason was a control-voltage input, and what
    // remained is a gain with a modulatable level, which is what Mix already is.
    // 8 was Clock, retired when the transport replaced it.
    Mix = 9,
    /** The oscillator. Called Voice for as long as a second, monophonic Osc existed. */
    Osc = 10,
    Lfo = 11,
    Drone = 12,
    Pluck = 13,
    Fm = 14,
    Sf = 15,
    Seq = 16,
    Chance = 17,
    Chord = 18,
    Arp = 19,
    Euclid = 20,
    /** The VCA, back: id 7 stays retired, because this is not the module that had it. */
    Amp = 21,
    /** A poly subpatch's two edges. Neither is a module: see PolyInNode. */
    PolyIn = 22,
    PolySum = 23,
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
 * The note edge of a poly subpatch: one note input, one note output per instance.
 *
 * A poly subpatch is monophonic on the inside and cloned on the way to the engine, so what
 * reaches the graph is N copies of everything inside it and these two nodes at its edges.
 * Nothing about nesting crosses into C++; a poly subpatch is a *flattening* with a
 * different rule, exactly as a plain subpatch is.
 *
 * This is where the notes are shared out, and the only place anything here chooses between
 * voices -- every synth is monophonic. Its rules are the ones PolySynth used to keep, and
 * deliberately the same ones: a note takes an idle instance, then the oldest instance already released, and
 * only then steals one still holding -- and a stolen instance is sent an Off first, because
 * whatever is inside it is an ordinary Env holding an ordinary note and nothing else would
 * ever end it. An Off finds its instance by id *and* source, since ids belong to the source
 * that chose them.
 *
 * Eight outputs always, whatever the voices knob says, so a port index is valid the moment
 * the graph sees it: Graph's Connect bounds-checks against kMaxPorts, and knobs are sent
 * after cables, so declaring only as many outputs as the knob asks for would drop the
 * cables of instances 5 to 8 on the sync that turned it up. The knob bounds which
 * instances are *chosen*, which is the thing it actually means.
 */
class PolyInNode : public Node {
public:
    /** As many as there are ports. See kMaxPorts. */
    static constexpr int32_t kInstances = kMaxPorts;

    int32_t inputCount() const override { return 1; }  // notes
    int32_t outputCount() const override { return kInstances; }
    uint32_t noteInputs() const override { return 1u << 0; }
    uint32_t noteOutputs() const override { return (1u << static_cast<uint32_t>(kInstances)) - 1u; }
    void process(int32_t frames) override;
    /** Knob 0: how many instances, 1 to kInstances. Mirrors Types.Poly in PatchCanvas.kt. */
    void setParam(int32_t index, float value) override;
    void notesCut(int32_t port, int32_t source) override;
    void heldNotes(int32_t port, NoteBuffer &into) const override;

    /** How many instances are holding a note. For tests. */
    int32_t instancesHeld() const;

private:
    struct Instance {
        /**
         * The On this instance was given, kept whole.
         *
         * Two uses: an Off for a stolen instance has to carry the same id and degree, and
         * heldNotes has to hand the same event to a cable patched later. A Change replaces
         * it, so what is handed over is where the note is now rather than where it began.
         */
        NoteEvent note{};
        bool held = false;
        /** When it was taken, for choosing which to steal. */
        int64_t age = 0;
    };

    int32_t choose() const;
    int32_t holding(uint32_t id, int32_t source) const;

    int32_t voices_ = 4;
    Instance instances_[kInstances];
    int64_t age_ = 0;
    /**
     * Instances whose source was unpatched, one bit each, released at the top of the next
     * block. Not released in notesCut itself: that runs in applyCommands, and a note output
     * is cleared by its producer at the top of process(), so an event written there would
     * be thrown away before anything read it.
     */
    uint32_t cut_ = 0;
};

/**
 * The signal edge of a poly subpatch: one input per instance, summed.
 *
 * Summed rather than averaged, like Mix: a chord is louder than one
 * note, which is true of every instrument, and Out's limiter catches what that costs at the
 * top. An instance that is not sounding contributes silence, so the sum is over what is
 * playing rather than over the knob.
 *
 * Eight inputs always, for the reason PolyIn has eight outputs. An unwired one reads as
 * silence and adds nothing, so there is no knob here at all.
 */
class PolySumNode : public Node {
public:
    int32_t inputCount() const override { return kMaxPorts; }
    int32_t outputCount() const override { return 1; }
    void process(int32_t frames) override;
};

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
    /** Mirrors FILTER_TYPES in PatchCanvas.kt, and the order is the file's. */
    enum Type { kLow = 0, kHigh, kBand, kNotch, kTypeCount };

    int32_t inputCount() const override { return 2; }  // in, notes
    int32_t outputCount() const override { return 1; }
    /**
     * The first node here to take audio and notes at once, which the graph needed nothing
     * new for: it already dispatches per port on this mask.
     */
    uint32_t noteInputs() const override { return 1u << 1; }
    void prepare(int32_t sampleRate) override;
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;

private:
    /** Whichever of the four outputs the type asks for; they are all computed anyway. */
    float chosen(daisysp::Svf &svf) const;

    daisysp::Svf svf_;
    /**
     * The second pole pair, for the steeper slope. Always run, even at 12dB where its
     * output is discarded: a filter whose state has been frozen since the last time the
     * slope changed would resume from a stale sample, which is a step at the one moment
     * nothing is meant to happen. Two double-sampled biquads per filter is cheap.
     */
    daisysp::Svf svf2_;
    float cutoffHz_ = 1000.0f;
    int32_t type_ = kLow;
    bool steep_ = false;
    /** How much of the note's pitch the cutoff follows: 0 none, 1 all of it. */
    float track_ = 0.0f;
    /**
     * The note the cutoff is tracking, in octaves from middle C, and it is *held*.
     *
     * A filter that snapped back to its knob when a note ended would zip the cutoff at
     * every note off, which is audible where the tracking itself is not. Nothing has
     * arrived yet means zero, which is middle C, which is where the knob's own hertz sit.
     */
    float trackTarget_ = 0.0f;
    /**
     * Where the glide to [trackTarget_] has got to.
     *
     * The cutoff cannot simply jump to the new note, which is what it did first and which
     * the capture caught: a four-octave line clicked five times in ten seconds with
     * tracking on and not at all with it off. A modulator moves a cutoff in small steps
     * every block; a note moves it octaves in one, and at any resonance worth having that
     * is a step in the output.
     *
     * Glided in octaves rather than in hertz, because that is the domain the ear hears a
     * cutoff move in -- a fixed rate in hertz would crawl at the bottom and leap at the
     * top. Per block, since that is how often a coefficient is recomputed anyway; per
     * sample would mean a sinf and a powf each one.
     */
    float trackOctaves_ = 0.0f;
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
    void notesCut(int32_t port, int32_t source) override;
    void setSlot(const SlotValue &slot) override;

    /** Mirrored by MAX_SEGMENTS in PatchCanvas.kt. */
    static constexpr int32_t kMaxSegments = 8;

private:
    /**
     * Notes tracked at once. An envelope is one control voltage however many notes are on
     * it, so this exists only to know when the last one lets go. Past the end an On is not
     * tracked -- the gate is already open and stays open while anything tracked is held,
     * so the worst it costs is a release that comes early on a sixteen-note pile-up.
     */
    static constexpr int32_t kHeld = 16;

    /**
     * The shortest a segment may be, so a time of 0 can mean "unused" without a segment
     * ever dividing by it. Well under the 5ms gate ramp, so nothing here is the thing that
     * steps.
     */
    static constexpr float kMinTime = 0.0001f;

    struct Held {
        uint32_t id = 0;
        /** The input slot the event came from, so notesCut can end one source's notes. */
        int32_t source = -1;
    };

    struct Segment {
        /** Seconds. 0 means the slot is unused; see [SegmentSlot]. */
        float time = 0.0f;
        /** Where this segment is going, 0 to 1. Where it starts is wherever the envelope is. */
        float level = 0.0f;
        /** -1 to 1, 0 straight. Positive leaves fast and arrives slow, as a decay does. */
        float curve = 0.0f;
        /** Park here while a note is held. At most one segment has it; the model enforces that. */
        bool sustain = false;
    };

    void start(const NoteEvent &event);
    void release(uint32_t id, int32_t source);
    /** The last note let go: park or release, depending on whether a segment sustains. */
    void letGo();

    /** Recomputed whenever a slot changes: the count is the first unused slot. */
    void rescan();
    /** Begin [index_], from wherever the output currently sits. */
    void enter(int32_t index);
    /** Leave the current segment for the next, ending the envelope past the last. */
    void advance();

    Held held_[kHeld] = {};
    int32_t heldCount_ = 0;

    Segment seg_[kMaxSegments] = {};
    int32_t segCount_ = 0;
    /** Which segment parks, or -1 for an envelope that simply ends. */
    int32_t sustainIndex_ = -1;

    /** The segment being played, and how far through it, 0 to 1. */
    int32_t index_ = 0;
    float phase_ = 0.0f;
    float rate_ = 0.0f;
    /** The output when the current segment was entered, which is what it travels from. */
    float from_ = 0.0f;
    /** The output, and what a new segment starts from. Idle is 0 and stays 0. */
    float value_ = 0.0f;
    bool running_ = false;
    /** Parked at a sustain, waiting for the last note to let go. */
    bool holding_ = false;
    /**
     * 1/(1 - e^-a) for the current segment, where a is its curve scaled. Precomputed
     * because it is the only part of the shape that does not move with the phase.
     */
    float shapeScale_ = 1.0f;
    float shapeA_ = 0.0f;
    /**
     * Per-sample coefficient for following the sustain level while parked on it.
     *
     * Editing the level of the segment an envelope is *currently* holding at has to be
     * heard, or the editor is deaf exactly while a note is held down -- which is when
     * anyone would be dragging it. A glide rather than a jump because this is a level
     * feeding an Amp, and a step in it is the same discontinuity the gate ramp exists to
     * avoid; the rate is the ramp's own 5ms.
     */
    float holdGlide_ = 1.0f;
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
    void setSlot(const SlotValue &slot) override;
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
 * A sequencer of notes with lengths: a grid of steps by degrees, each note ("dot") its own
 * length, and as many to a column as make a chord. Bespoke's DotSequencer is the shape, and
 * the reason notes became events at all (ROADMAP, Phase 6). Called DotSeq for a night, then
 * Seq, when it took over from Steps.
 *
 * Ticked by the transport at its interval. A note starts on the tick of its step and lasts
 * its own length, which is measured in *quarter steps* -- so a note can end partway through
 * a step, and a gap between two notes is made by shortening the first.
 *
 * That is Bespoke's model and was this module's own until Seq took Steps' place in the Add
 * menu: a dot's length was whole steps, nothing could be shorter than one, and a `gate` knob
 * was added to take a share off the last step of every note at once. A length in quarter
 * steps says the same thing per note and says more -- Steps' half step is a length of 2 --
 * so the knob went and duration is the dot's own extent again.
 *
 * The whole steps of a length are counted in ticks, so a stopped transport holds a note
 * exactly as it holds a Steps note; the part step left over is counted in frames from the
 * tick it starts on, at that tick's tempo, as the gate's share was.
 *
 * A jump in the count -- a reset, an interval changed -- ends everything held, since the
 * ticks it was waiting for may now never come.
 *
 * Knobs, mirroring PatchCanvas.kt: length, transpose, interval.
 */
class SeqNode : public Node {
public:
    /** Mirrored by DOT_STEPS in PatchCanvas.kt. */
    static constexpr int32_t kSteps = 32;
    /** Mirrored by MAX_DOTS in PatchCanvas.kt. */
    static constexpr int32_t kMaxDots = 128;
    /**
     * Divisions of a step a note's length is counted in. Mirrored by DOT_SUBSTEPS.
     *
     * Four, which is what a finger can place on a cell a finger can hit: at 32 columns a
     * cell is 20dp and a quarter of it is 5dp, which is already past what a drag can aim
     * at and is only reachable on a short loop. It also makes Steps' half step -- the
     * length the retired `gate` knob defaulted to -- an exact 2.
     */
    static constexpr int32_t kDotSubsteps = 4;
    /** Notes sounding at once. Two chords of eight overlapping, which no voice here can play anyway. */
    static constexpr int32_t kMaxHeld = 16;

    int32_t inputCount() const override { return 0; }
    int32_t outputCount() const override { return 1; } // notes
    uint32_t noteOutputs() const override { return 1u << 0; }
    void process(int32_t frames) override;
    void setParam(int32_t index, float value) override;
    void setSlot(const SlotValue &slot) override;
    int32_t position() const override { return step_; }
    Interval interval() const override { return kIntervals[intervalIndex_]; }
    void tick(int32_t offset, int64_t count) override;
    void heldNotes(int32_t port, NoteBuffer &into) const override;

    /** Notes sounding now, for tests. */
    int32_t notesHeld() const { return heldCount_; }

private:
    static constexpr int32_t kMaxPending = 4;

    struct Held {
        uint32_t id;
        int32_t degree;
        int64_t beat;
        /** The tick its whole steps run out on: where it ends, or where its part step starts. */
        int64_t endCount;
        /** Quarter steps past [endCount], 0 for a note that ends on the tick. */
        int32_t tail;
        /** Frames of that part step still to sound, or -1 until [endCount] has come. */
        int64_t gateLeft;
    };

    void onTick(NoteBuffer &notes, uint16_t offset, int64_t count);
    void endAll(NoteBuffer &notes, uint16_t offset);
    void release(NoteBuffer &notes, int32_t h, uint16_t offset);

    Tick pending_[kMaxPending] = {};
    int32_t pendingCount_ = 0;

    int32_t step_ = -1;
    int64_t lastCount_ = -1;
    int32_t length_ = 16;
    int32_t intervalIndex_ = kDefaultInterval;
    float transposeCents_ = 0.0f;

    int32_t dotStep_[kMaxDots] = {};
    int32_t dotDegree_[kMaxDots] = {};
    /** In quarter steps; 0 for an empty slot. */
    int32_t dotLength_[kMaxDots] = {};
    /** 0 to 1, how hard the note is struck. */
    float dotVelocity_[kMaxDots] = {};

    Held held_[kMaxHeld] = {};
    int32_t heldCount_ = 0;
    uint32_t nextNoteId_ = 1;
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
    void setSlot(const SlotValue &slot) override;
    /** Its one knob: a transpose in cents, as Steps has. Held notes glide to it. */
    void setParam(int32_t index, float value) override;
    Interval interval() const override { return {1, 1}; }
    void tick(int32_t offset, int64_t count) override;
    void heldNotes(int32_t port, NoteBuffer &into) const override;

private:
    float transposeCents_ = 0.0f;
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
 * An Osc's one voice: a band-limited waveform, on and off.
 *
 * It had an ADSR. The envelope went with the redesign, because a built-in one is the same
 * envelope for every voice of the module and cannot be patched to anything else -- an Env
 * inside a poly subpatch is per note and can be sent wherever you like. What is left is
 * the click, which the gate ramp takes off; see GateRamp.
 */
struct OscVoice {
    daisysp::Oscillator osc;
    GateRamp gate;
    /** How hard this note was struck. Applied by [gate], which glides to it; see GateRamp. */
    float velocity = 1.0f;
    /** The note's own pitch, before [tune]: kept so a turned knob retunes the note sounding. */
    float hz = 261.63f;
    /** The tune knob as a ratio, 2^(cents/1200). */
    float tune = 1.0f;

    void init(float sampleRate);
    void strike(float hz, float strength, bool stolen);
    void setFreq(float note) {
        hz = note;
        osc.SetFreq(hz * tune);
    }
    /**
     * Cents, applied on top of whatever the note is -- including mid-glide and while held, so
     * a modulator on it is a vibrato. The phase runs on through the change, so a pitch that
     * moves once a block is a frequency that steps, never a waveform that does.
     */
    void setTune(float cents) {
        tune = std::exp2(cents / 1200.0f);
        osc.SetFreq(hz * tune);
    }
    float render(bool open, bool &finished);
};

/**
 * Notes in, sound out: one note's worth of oscillator.
 *
 * Monophonic, which the roadmap spent two phases arriving back at. "A three-voice patch is
 * twelve nodes" was Phase 7's plan and Phase 6 replaced it, putting the voices inside this
 * module; a chord then cost one module, and cost per-note patching everything. A poly
 * subpatch is the twelve nodes again, built for you -- so this is one voice and a chord is
 * the box around it.
 */
class OscNode : public MonoSynth<OscVoice> {
public:
    // One note at a time, and two knobs: the waveform, and a tune in cents. Polyphony is a
    // Poly subpatch around it -- see MonoSynth.
    void setParam(int32_t index, float value) override;
};

/**
 * A Pluck's one voice: a burst of noise into a Karplus-Strong string.
 *
 * The string is DaisySP's, which is Emilie Gillet's from Rings. The excitation is written
 * here, after DaisySP's StringVoice (Plaits' string voice, also hers): a burst one period
 * long, low-passed at a cutoff that rises with the note, with brightness, and with how hard
 * the note was struck. StringVoice itself is not vendored because its sustain mode draws
 * on Dust, which calls rand() -- a mutex, on Android -- for a mode nothing here uses.
 */
struct PluckVoice {
    daisysp::String string;
    daisysp::Svf excitation;

    void init(float sampleRate);
    void strike(float hz, float velocity, bool stolen);
    void setFreq(float hz);
    float render(bool gate, bool &finished);

    /** The node's knobs, kept so each strike can add its own accent to them. */
    void apply(float decay, float bright, float stiff);
    /** The knobs with this note's accent added, into the string. */
    void update();

    float sampleRate = 48000.0f;
    /** The note's frequency as a fraction of the sample rate, which is what sizes the burst. */
    float f0 = 0.0f;
    float accent = 1.0f;
    float decayKnob = 0.8f;
    float brightKnob = 0.5f;
    float stiffKnob = 0.3f;
    /** The brightness actually used: the knob, and more of it the harder the strike. */
    float bright = 0.5f;
    /** Samples of noise still to go into the string. */
    int32_t noiseLeft = 0;
    uint32_t rng = 1;
    /** The release's fade, multiplied down each sample once the note is let go. */
    float fade = 1.0f;
    float releaseStep = 1.0f;
    /** A peak follower, for knowing a string has rung out while still held. */
    float level = 0.0f;
    float levelFall = 0.9995f;
};

/**
 * A plucked string: notes in, sound out.
 *
 * Built as a module rather than left to be patched from parts, which is the roadmap's test
 * for a fixed module: a string is a delay line whose length *is* its pitch, so the line has
 * to be retuned by the note -- and nothing patchable sets a delay's length from a note. It
 * is also exactly the kind of thing that is fiddly to get right from parts.
 *
 * A note off lets the string ring for R before it is silent, like a finger coming down on
 * it; a string that rings out while still held frees its voice anyway, so the module is not
 * deaf until something lets go. Order of knobs mirrors PatchCanvas.kt: decay, bright,
 * stiff, R.
 */
class PluckNode : public MonoSynth<PluckVoice> {
public:
    void prepare(int32_t sampleRate) override;
    void setParam(int32_t index, float value) override;

private:
    void applyAll();

    float decay_ = 0.8f;
    float bright_ = 0.5f;
    float stiff_ = 0.3f;
    float release_ = 1.0f;
};

/**
 * An FM's one voice: a sine whose phase is pushed around by another sine.
 *
 * Two operators, as decided when it was designed: a modulator at [ratio] times the note,
 * and the carrier at the note, the modulator's depth being the index -- in radians of
 * phase, the classic measure. The index follows the amplitude envelope, which is
 * Chowning's brass (brighter as it gets louder), and falls on its own with time constant
 * [fall], which is the bell and the electric piano: brightness dying faster than loudness.
 * Velocity scales both loudness and index, so a harder note is a brighter one.
 */
struct FmVoice {
    GateRamp gate;

    void init(float sampleRate);
    void strike(float hz, float velocity, bool stolen);
    void setFreq(float hz);
    float render(bool open, bool &finished);

    float sampleRate = 48000.0f;
    float hz = 0.0f;
    float ratio = 1.0f;
    float index = 2.0f;
    /** The index's own decay, as a per-sample multiplier on [brightness]. */
    float fallStep = 1.0f;
    float velocity = 1.0f;
    /** Where the index's fall has got to: 1 at the strike, towards 0 after. */
    float brightness = 1.0f;
    /** Phases in cycles, 0..1, and how far each moves a sample. */
    float carrier = 0.0f;
    float modulator = 0.0f;
    float carrierStep = 0.0f;
    float modulatorStep = 0.0f;
};

/**
 * Two-operator FM: notes in, sound out. Order of knobs mirrors PatchCanvas.kt: ratio,
 * index, fall.
 *
 * The envelope went with Osc's, and with it Chowning's coupling of brightness to loudness
 * -- the index followed the amplitude envelope, so a note got brighter as it got louder.
 * That is now a cable: expose the index and patch an Env to it, which is both the thing
 * that could not be done before and a strictly larger set of sounds, since the envelope on
 * the index no longer has to be the one on the amplitude. [fall] stays, because the index
 * dying faster than the note is per strike and never was a knob's worth of envelope.
 *
 * A module rather than two oscillators patched together, and not only because modulation
 * runs once a block: the modulator has to follow the note's own pitch at audio rate, and an
 * Osc's frequency is set by the notes it is sent rather than by anything patchable. Two of
 * them would be two independent notes, not a carrier and its modulator.
 */
class FmNode : public MonoSynth<FmVoice> {
public:
    void prepare(int32_t sampleRate) override;
    void setParam(int32_t index, float value) override;

private:
    void applyAll();

    float ratio_ = 1.0f;
    float index_ = 2.0f;
    float fall_ = 1.0f;
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

/**
 * A gain something else turns: audio in, modulation in, audio out.
 *
 * The VCA, returning. It was retired with CV on the argument that a Mix channel is
 * `in * level` and a level with a modulation jack is the same module -- which was true,
 * and stopped being the point. Inside a poly subpatch the thing you reach for after Env
 * is the thing Env opens, and reaching for it should be one cable rather than opening a
 * Mix's panel, exposing a knob, setting its brackets and then patching. Node id 7 stays
 * retired all the same: that module took a control voltage, and there is no such thing
 * here any more.
 *
 * Per sample, not per block -- [mod] drives the gain knob (Node::drivenParam), so what
 * arrives on it is the gain itself, every sample: the knob with nothing patched, and the
 * modulator swept between the gain's brackets with something there. An attack of a
 * millisecond is an attack of a millisecond, where a parameter applied once per block, at
 * 1500Hz, would make a plucked envelope a staircase.
 */
class AmpNode : public Node {
public:
    int32_t inputCount() const override { return 2; }  // in, mod
    int32_t outputCount() const override { return 1; }
    int32_t drivenParam(int32_t port) const override { return port == 1 ? 0 : -1; }
    void process(int32_t frames) override;
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
