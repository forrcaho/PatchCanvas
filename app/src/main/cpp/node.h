#pragma once

#include <array>
#include <cstdint>

#include "notes.h"
#include "scales.h"
#include "transport.h"

/** Frames processed per inner block. 96-frame bursts divide by this exactly. */
constexpr int32_t kBlockSize = 32;
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
     * Signal inputs whose *unpatched* value is 1.0 rather than 0.0, one bit per port index.
     *
     * Silence is the right idle for an input that is summed or filtered, and the wrong one
     * for an input that multiplies. An Amp with nothing on its modulation input is a VCA
     * with no control voltage, which in hardware is a module that does nothing audible and
     * on a phone is a module you assume is broken -- there is no panel meter to tell you
     * which. So the port says what its own silence means, and the graph hands it a buffer
     * of ones instead. Patching then crossfades from unity to the modulator and unpatching
     * fades back, which is the same three cases repatch() already gets right.
     */
    virtual uint32_t unityInputs() const { return 0; }

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
     * One step of a sequence changed.
     *
     * Separate from setParam because a pattern is not a knob: kMaxParams is 8, which is
     * the right size for the controls a panel shows and nowhere near a sequence. The note
     * arrives as a degree, and is resolved against whichever scale is sounding on the
     * beat it starts -- which only the audio thread can know to the sample.
     *
     * Audio thread, same rules as setParam.
     */
    virtual void setStep(int32_t index, int32_t degree, bool gate) {
        (void) index;
        (void) degree;
        (void) gate;
    }

    /**
     * Dot [slot] of a dot sequencer: a note at [step], [degree], lasting [length] steps, or
     * no dot when [length] is 0. Slots are the interface's list positions; the node keeps
     * them in place so a change to one dot is one command.
     *
     * Audio thread, same rules as setParam.
     */
    virtual void setDot(int32_t slot, int32_t step, int32_t degree, int32_t length) {
        (void) slot;
        (void) step;
        (void) degree;
        (void) length;
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
     */
    void setTiming(double beatsPerFrame, bool running, const ScaleList *scales = nullptr) {
        beatsPerFrame_ = beatsPerFrame;
        running_ = running;
        scales_ = scales;
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
    /** Owned by the graph, and valid for the block it was set for. */
    const ScaleList *scales_ = nullptr;

private:
    std::array<const float *, kMaxPorts> inputs_{};
    std::array<std::array<float, kBlockSize>, kMaxPorts> outputs_{};
    std::array<const NoteBuffer *, kMaxPorts> noteInputs_{};
    std::array<NoteBuffer, kMaxPorts> noteOutputs_{};
};
