#pragma once

#include <array>
#include <cstdint>

#include "notes.h"
#include "scales.h"
#include "transport.h"

/** Frames processed per inner block. 96-frame bursts divide by this exactly. */
constexpr int32_t kBlockSize = 32;

/** Which of a node's slot-indexed lists an entry belongs to. Mirrored by SlotKind.kt side. */
enum class SlotKind : int32_t { Step = 0, Dot = 1, Segment = 2 };

/** One step of a sequence: a degree of the sounding scale, and whether it sounds at all. */
struct StepSlot {
    int32_t degree = 0;
    bool gate = false;
};

/**
 * One dot of a dot sequencer: a note at [step] and [degree], lasting [length] quarter steps
 * and struck at [velocity]. A [length] of 0 vacates the slot.
 */
struct DotSlot {
    int32_t step = 0;
    int32_t degree = 0;
    int32_t length = 0;
    float velocity = 1.0f;
};

/**
 * One segment of an envelope: reach [level] over [time] seconds, bent by [curve], holding
 * there while a note is held when [sustain]. A [time] of 0 vacates the slot, which is how
 * the envelope learns how many segments it has -- they are contiguous, so the first vacated
 * slot is the end.
 *
 * A segment says where it is *going*, never where it starts: it begins wherever the envelope
 * already is. That is what lets a note released during the attack fall from the level it
 * actually reached rather than stepping to a level it never had.
 */
struct SegmentSlot {
    float time = 0.0f;
    float level = 0.0f;
    float curve = 0.0f;
    bool sustain = false;
};

/**
 * One entry of one of those lists, tagged, as it crosses to the graph.
 *
 * A union rather than the union of all their fields spread flat across Command, which is
 * what it was: five members (step, length, degree, gate, curve) that only three command
 * types ever touched, with a segment's *time* riding in `high` -- a field documented as
 * SetModRange's. Trivially copyable, so it travels through the SPSC queue like the POD it is.
 */
struct SlotValue {
    SlotKind kind = SlotKind::Step;
    /** Which entry of the list. */
    int32_t index = 0;
    union {
        StepSlot step;
        DotSlot dot;
        SegmentSlot segment;
    };

    SlotValue() : step() {}
};

/** The three typed ways to build one. Packing by hand at a call site reads as noise. */
inline SlotValue stepSlot(int32_t index, int32_t degree, bool gate) {
    SlotValue v;
    v.kind = SlotKind::Step;
    v.index = index;
    v.step = StepSlot{degree, gate};
    return v;
}
inline SlotValue dotSlot(int32_t index, int32_t step, int32_t degree, int32_t length,
                         float velocity) {
    SlotValue v;
    v.kind = SlotKind::Dot;
    v.index = index;
    v.dot = DotSlot{step, degree, length, velocity};
    return v;
}
inline SlotValue segmentSlot(int32_t index, float time, float level, float curve, bool sustain) {
    SlotValue v;
    v.kind = SlotKind::Segment;
    v.index = index;
    v.segment = SegmentSlot{time, level, curve, sustain};
    return v;
}
/**
 * Eight, since poly subpatches: a PolyIn hands one note output to each instance and a
 * PolySum takes one input back, so the port limit is also the voice limit. It was four,
 * which was as many jacks as a 116dp module face could hold at a finger's height -- and
 * still is, for a module. What has eight is a subpatch, whose box grows with the ports it
 * was given and was never bound by a type's declaration.
 *
 * The cost is the note buffers: a node carries one per port either way, at about a
 * kilobyte each, so this doubles a node from 4KB to 8KB. With kMaxNodes at 256 that is
 * two megabytes, which is nothing on a phone and was worth not building a second, wider
 * kind of node to hold the two that needed it.
 */
constexpr int32_t kMaxPorts = 8;
/**
 * Eight, since FM: two operators want a ratio, an index and its decay besides an
 * envelope's A, D, S and R. Five was the old limit, set by what a panel's single column of
 * rows could hold at a finger's height; past five the panel goes to two columns. Nothing is
 * stored per parameter beyond a routing slot and a published value, so this bounds a
 * command's index and little else.
 */
constexpr int32_t kMaxParams = 8;

/**
 * Something a node needs that is too big, or too slow, to build on the audio thread -- a
 * SoundFont's synth, first. Built on the interface's thread, handed across by pointer like a
 * node, and freed back on that thread when it is replaced; see Graph::postSetResource.
 */
class Resource {
public:
    virtual ~Resource() = default;
};

/**
 * A graph node.
 *
 * Outputs are owned buffers; inputs are borrowed pointers into whatever upstream node
 * produced them, rewired by the graph before each block. Nothing here allocates after
 * construction, and construction never happens on the audio thread.
 *
 * Output buffers are deliberately NOT cleared between blocks. That is what makes a
 * feedback edge cost exactly one block of delay: a node whose source is evaluated after
 * it simply reads the buffer the source left behind last time.
 */
class Node {
public:
    virtual ~Node() = default;

    virtual int32_t inputCount() const = 0;
    virtual int32_t outputCount() const = 0;

    /**
     * Which ports carry notes rather than samples, one bit per port index.
     *
     * Note ports share the index space with signal ports deliberately: a cable is a
     * cable to everything that routes one, so the command queue, the topological sort
     * and the interface's own model needed no second notion of a port to learn about.
     * Only what travels down it differs, and these two masks are where that is said.
     */
    virtual uint32_t noteInputs() const { return 0; }
    virtual uint32_t noteOutputs() const { return 0; }

    /**
     * The parameter signal input [port] sweeps, per sample, or -1 for an ordinary input.
     *
     * For such a port the graph hands the node the *parameter*, not the signal: every sample
     * is the knob's value while nothing is patched, and the modulator mapped between the
     * parameter's range once something is -- from the low bracket at 0 to the high one at 1,
     * clamped as modulatedValue clamps. Crossfading between those is the same three cases
     * repatch() gets right for any cable, so patching fades from the knob into the sweep and
     * unpatching fades back to it.
     *
     * Why the graph and not the node: the graph already holds the knob (ParamRef::base), the
     * range (low, high) and the fade, and a node that mapped its own input could not tell a
     * modulator resting at 1.0 from nothing patched. This replaced unityInputs(), whose buffer
     * of ones was the same idea one step short -- it made an unpatched Amp open, but only by
     * making its knob and its port two gains multiplied together, and with the knob exposed as
     * well there were two jacks on one number.
     */
    virtual int32_t drivenParam(int32_t port) const {
        (void) port;
        return -1;
    }

    virtual void prepare(int32_t sampleRate) { sampleRate_ = sampleRate; }

    /**
     * A knob moved. Values arrive in real units -- hertz, seconds, beats per minute --
     * rather than normalized, because the range and the curve belong to the thing being
     * described and the interface should be able to say "440 Hz" rather than "0.63".
     *
     * Called from applyCommands on the audio thread, so an implementation may compute
     * coefficients but must not allocate.
     */
    virtual void setParam(int32_t index, float value) {
        (void) index;
        (void) value;
    }
    /**
     * One entry of a node's slot-indexed list changed: a step, a dot or a segment.
     *
     * Separate from setParam because a list is not a knob: kMaxParams is 8, which is the
     * right size for the controls a panel shows and nowhere near a sequence. Slots are the
     * interface's list positions and the node keeps them in place, so a change to one entry
     * is one command.
     *
     * One entry point rather than three, because the three were the same command wearing
     * different field names all the way down -- an enum value, a post function, an apply
     * case, a JNI shim and a Kotlin extern each, per list. The payload stays *typed*: a node
     * reads `slot.dot.velocity`, not an anonymous float, because nodes.cpp is where the DSP
     * is read and clarity there is worth more than the packing it saves.
     *
     * Audio thread, same rules as setParam.
     */
    virtual void setSlot(const SlotValue &slot) {
        (void) slot;
    }

    /**
     * Where a sequencer has got to, or -1 for everything that is not one.
     *
     * Read on the audio thread only, by the graph, which republishes it through an
     * atomic the interface can see. Nothing outside the audio thread calls this.
     */
    virtual int32_t position() const { return -1; }

    /** How often the transport ticks this node, or none for a node it does not drive. */
    virtual Interval interval() const { return {}; }

    /**
     * A boundary of interval() falls at sample [offset] of the block about to be processed.
     *
     * [count] is which boundary, counted from the transport's beat zero, so a sequencer
     * can take its step from where the transport is rather than counting ticks it has
     * seen -- and stopping, resetting and changing length all land where the position
     * says. Delivered by the graph before process(), on the audio thread.
     *
     * One entry point on purpose: a pulse cable, if one is ever built, calls the same
     * thing with a count of its own.
     */
    virtual void tick(int32_t offset, int64_t count) {
        (void) offset;
        (void) count;
    }

    /**
     * Set by the graph before every block: how far one frame moves the transport, whether
     * it is moving at all, and the patch's scales. Zero beats per frame while stopped, so
     * anything timed in beats holds still with it. No scales yet reads as twelve equal
     * steps.
     *
     * [tempo] is the other reading of the same number: how far a frame *would* move the
     * transport at its tempo, running or not. For what keeps time without advancing -- a delay
     * set to an eighth is an eighth long whether or not anything is playing, and reading the
     * zero above would make it no length at all. Zero from a caller that never set a tempo.
     *
     * [beat] is where the transport is at the block's first frame, held while it is stopped.
     * For what has to be *in phase* with it rather than merely at its rate -- a synced LFO,
     * whose cycles start on the beat rather than wherever it happened to be switched on. A
     * tick says the same thing only at the boundaries, and a phase read between them from a
     * count of ticks would drift the way two clocks do.
     */
    void setTiming(double beatsPerFrame, bool running, const ScaleList *scales = nullptr,
                   double tempo = 0.0, double beat = 0.0) {
        beatsPerFrame_ = beatsPerFrame;
        running_ = running;
        scales_ = scales;
        tempo_ = tempo;
        beat_ = beat;
    }

    /**
     * A source was unpatched from note input [port], or deleted out from under it.
     *
     * Repatching a note cable cannot crossfade: there is no signal to fade between. What
     * it must do instead is end what it started, or every voice that source was holding
     * hangs on forever. [source] is the slot the events carried, so only that source's
     * notes end and anything else merged into the same input plays on.
     *
     * Released rather than cut, so a voice ends the way it would have anyway.
     *
     * Audio thread, from applyCommands, same rules as setParam.
     */
    virtual void notesCut(int32_t port, int32_t source) {
        (void) port;
        (void) source;
    }

    /**
     * Takes [incoming] and returns what it replaces, or [incoming] itself if this node has
     * no use for one. Either way the returned pointer goes back to be freed off the audio
     * thread, and nothing the node keeps may be touched there again.
     *
     * Audio thread, same rules as setParam: swap pointers, set fields, allocate nothing.
     */
    virtual Resource *swapResource(Resource *incoming) { return incoming; }

    virtual void process(int32_t frames) = 0;

    void setInput(int32_t port, const float *buffer) { inputs_[port] = buffer; }
    const float *output(int32_t port) const { return outputs_[port].data(); }

    /** Borrowed for the block, like a sample input. Null reads as no events. */
    void setNoteInput(int32_t port, const NoteBuffer *buffer) { noteInputs_[port] = buffer; }
    const NoteBuffer *noteOutput(int32_t port) const { return &noteOutputs_[port]; }

    /**
     * The notes this node is holding on note output [port], written into [into] as Ons.
     *
     * For a destination that has just been patched and missed their starts. A note is two
     * events and a source says each once, so a cable connected while a note is held carries
     * only its end -- found on the phone, where a drone patched to a new oscillator stayed
     * silent until its cells were toggled again, because its notes never end and so never
     * start again either. The graph asks this once, in the first block after the connect.
     *
     * The events carry the beat whose scale the note is sounding in now, so the destination
     * starts it at the pitch every other destination already has. Audio thread; must not
     * allocate. Nothing by default: a node that holds no notes has none to give.
     */
    virtual void heldNotes(int32_t port, NoteBuffer &into) const {
        (void) port;
        (void) into;
    }

protected:
    const float *input(int32_t port) const { return inputs_[port]; }
    float *out(int32_t port) { return outputs_[port].data(); }

    /** The events arriving at a note input this block, already merged and in offset order. */
    const NoteBuffer &notesIn(int32_t port) const {
        static const NoteBuffer empty{};
        return noteInputs_[port] != nullptr ? *noteInputs_[port] : empty;
    }
    /** Clear it at the top of process() -- see NoteBuffer. */
    NoteBuffer &notesOut(int32_t port) { return noteOutputs_[port]; }

    int32_t sampleRate_ = 48000;
    /** See setTiming. */
    double beatsPerFrame_ = 0.0;
    bool running_ = false;
    /** See setTiming: beats per frame at the tempo, whether or not the transport runs. */
    double tempo_ = 0.0;
    /** See setTiming: the transport's beat at this block's first frame. */
    double beat_ = 0.0;
    /** Owned by the graph, and valid for the block it was set for. */
    const ScaleList *scales_ = nullptr;

private:
    std::array<const float *, kMaxPorts> inputs_{};
    std::array<std::array<float, kBlockSize>, kMaxPorts> outputs_{};
    std::array<const NoteBuffer *, kMaxPorts> noteInputs_{};
    std::array<NoteBuffer, kMaxPorts> noteOutputs_{};
};
