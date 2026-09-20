#pragma once

#include <array>
#include <atomic>
#include <cstdint>

#include "node.h"
#include "nodes.h"
#include "spsc_queue.h"

constexpr int32_t kMaxNodes = 64;
constexpr std::size_t kCommandCapacity = 256;

/**
 * The bridge.
 *
 * Two representations that are never the same object: Kotlin's Patch is UI truth and
 * lives in Compose state; this Graph is audio truth and lives on the callback thread.
 * Everything crosses as POD commands through a lock-free queue.
 *
 * Three rules the audio thread keeps:
 *   - it never allocates, so nodes are constructed on the UI thread and only a pointer
 *     crosses;
 *   - it never frees, so removed nodes travel back over a return queue and are deleted
 *     by whoever calls collectGarbage();
 *   - it never blocks, so the queues are wait-free for one reader and one writer.
 *
 * Cycles are legal. A modular synth without feedback is not one.
 */
class Graph {
public:
    /** Only to fill the unity buffer; see unityInputs() in node.h. */
    Graph() { unity_.fill(1.0f); }

    /**
     * Frees whatever is still owned. Safe only once no callback can be running, which
     * is true by the time a Graph is being destroyed -- but stating it as RAII rather
     * than relying on stop() having been called means a leak cannot depend on a
     * discipline someone forgot.
     */
    ~Graph() { reset(); }

    // ---- UI thread
    void setSampleRate(int32_t rate) {
        sampleRate_ = rate;
        // Both 30ms.
        //
        // The artifact is the envelope itself, not a discontinuity: fading a signal that
        // is already at full amplitude and arbitrary phase puts energy around
        // 1/duration, which at 10ms is a low-frequency thump. 30ms moves that down and
        // quietens it.
        //
        // These were briefly asymmetric, on the theory that fading out ends in silence
        // where an envelope has nothing left to color. That was wrong: 10ms sounded
        // clean on the phone's speaker and was plainly audible on earbuds. The shorter
        // side was not better, only harder to hear.
        //
        // Kept as two values rather than one because Phase 4 gives ports a signal type,
        // and a CV input probably wants to arrive faster than an audio one.
        rampInSamples_ = rate / 33;  // ~30ms
        rampOutSamples_ = rate / 33; // ~30ms

        // Written from here, off the audio thread, which is safe only because this is
        // called while a stream is being opened and no callback can be running.
        transport_.setSampleRate(rate);
    }
    bool postAdd(int64_t id, NodeType type);
    bool postRemove(int64_t id);
    bool postConnect(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t dstPort);
    /**
     * Drops one cable, named by both ends.
     *
     * The source is named because a note input takes several: dropping the port's
     * sources wholesale would silence the two sequencers you meant to keep. A signal
     * input has exactly one source, so for those it is checked and nothing more; an
     * srcId of 0 means every source, whatever the port carries.
     */
    bool postDisconnect(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t dstPort);
    bool postSetParam(int64_t id, int32_t paramIndex, float value);
    /**
     * What parameter [paramIndex] sweeps between when something modulates it, in the
     * parameter's own units. [exponential] makes the sweep geometric, for the parameters
     * whose knob is: a cutoff from 400Hz to 2kHz passes 894Hz halfway, not 1200Hz.
     *
     * Sent when the range is marked and whenever it changes. There is no command to clear
     * one: a parameter nothing is patched to ignores its range, so un-exposing is only
     * ever a disconnect.
     */
    bool postSetModRange(int64_t id, int32_t paramIndex, float low, float high, bool exponential);
    /**
     * Patches output [srcPort] of [srcId] to parameter [paramIndex] of [dstId].
     *
     * A parameter takes one modulator, so this replaces whatever was there, crossfading
     * between the two exactly as a signal input does.
     */
    bool postConnectMod(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t paramIndex);
    /** Unpatches a parameter's modulator, fading back to the knob's own value. */
    bool postDisconnectMod(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t paramIndex);
    /** One step of a sequence, as a degree of the patch's scales. */
    bool postSetStep(int64_t id, int32_t index, int32_t degree, bool gate);
    /** One dot of a dot sequencer, by slot; a length of 0 clears the slot. */
    bool postSetDot(int64_t id, int32_t slot, int32_t step, int32_t degree, int32_t length);
    /**
     * Replaces the patch's scales with [list], which the graph takes ownership of. Built
     * by the caller off the audio thread and swapped in whole; the list it replaces comes
     * back through collectGarbage. Freed here if it cannot be queued.
     */
    bool postSetScales(ScaleList *list);
    /**
     * Hands node [id] a [resource] built off the audio thread, which the graph owns from
     * here. Whatever it replaces -- or the resource itself, if the node is gone or has no use
     * for it -- comes back through collectGarbage, as a replaced scale list does. Freed here
     * if it cannot be queued.
     */
    bool postSetResource(int64_t id, Resource *resource);
    /** Which scale is sounding, published once per block for the interface. */
    int32_t scaleEntry() const;
    /**
     * Which step a sequencer is on, or -1 if it is not running or not a sequencer.
     *
     * The one thing that travels back up. Commands go down a queue because they must all
     * arrive and in order; this is the opposite -- a value whose only reader is a
     * repaint, where the newest is the only one that matters and a missed update is a
     * frame nobody saw. So it is a published atomic rather than a queue, and the audio
     * thread's side of it is one relaxed store per sequencer per block.
     */
    int32_t stepOf(int64_t id) const;
    /**
     * Where a modulated parameter has got to, in its own units, or NaN for a node that is not
     * here. Published once per block as the modulation is applied, for the same reason and in
     * the same way as stepOf: the panel draws the newest value and nothing else wants one.
     *
     * Meaningful only for a parameter something is modulating, which is all the interface
     * asks about. Any other reads whatever its last modulator left there, or zero.
     */
    float paramOf(int64_t id, int32_t index) const;

    /** The transport's rate. Rebased on arrival, so the position carries on rather than jumping. */
    bool postSetTempo(float bpm);
    /** Back to the start of bar one. */
    bool postResetTransport();
    /**
     * Where the transport has got to, in beats. Published once per block, like stepOf and
     * for the same reason: a repaint wants only the newest value.
     */
    double transportBeat() const;

    /** Frees everything the audio thread handed back. Never called from the callback. */
    void collectGarbage();
    /** Frees anything still owned, after the stream has stopped. */
    void reset();

    // ---- audio thread
    void applyCommands();
    /**
     * Whether musical time is moving. Owned by the engine rather than sent as a command,
     * because it follows the output switch, which the engine already reads every callback.
     */
    void setTransportRunning(bool running) { transport_.setRunning(running); }
    /** Hands the live microphone block to the In rail, if the patch has one. */
    void setLiveInput(const float *mono);
    void process(int32_t frames);
    const float *outputL() const;
    const float *outputR() const;

private:
    enum class CommandType : int32_t {
        Add, Remove, Connect, Disconnect, SetParam, SetStep, SetTempo, ResetTransport,
        SetScales, SetModRange, ConnectMod, DisconnectMod, SetResource, SetDot,
    };

    struct Command {
        CommandType type = CommandType::Add;
        int64_t id = 0;
        int64_t srcId = 0;
        int32_t srcPort = 0;
        int32_t dstPort = 0;
        NodeType nodeType = NodeType::Unknown;
        Node *node = nullptr;
        int32_t paramIndex = 0;
        /** SetParam's value, and the low end of SetModRange's. */
        float value = 0.0f;
        /** SetModRange only: the high end, and whether the sweep between them is geometric. */
        float high = 0.0f;
        bool exponential = false;
        /** SetStep only: whether the step sounds, and its degree. */
        bool gate = false;
        int32_t degree = 0;
        /** SetScales only: the list to swap in. */
        ScaleList *scales = nullptr;
        /** SetResource only: what the node takes. */
        Resource *resource = nullptr;
        /** SetDot only, beside paramIndex (the slot) and degree: where it starts and how long. */
        int32_t step = 0;
        int32_t length = 0;
    };

    /**
     * A port's source, plus enough state to change it without a click.
     *
     * Patching swaps an input from one signal to another between two samples, and that
     * step is broadband -- the same discontinuity that made closing the stream pop,
     * just at the cable instead.
     *
     * This is a true crossfade between two live sources, and it has to be. Fading from
     * a frozen *value* instead means the waveform stops oscillating the instant a cable
     * is pulled and becomes a DC level gliding to zero -- which is not a click but a
     * low-frequency thump, and audible on anything that reproduces bass.
     */
    struct InputRef {
        /** What the port is heading towards. -1 means silence. */
        int32_t sourceIndex = -1;
        int32_t sourcePort = 0;
        /** What it is fading out of. -1 means silence. */
        int32_t fromIndex = -1;
        int32_t fromPort = 0;
        int32_t rampRemaining = 0;
        /** Length of the ramp in flight, so the curve is evaluated against its own span. */
        int32_t rampLength = 1;
    };

    /**
     * One source of a note input.
     *
     * A note input takes several, where a signal input takes one. The reason single
     * source exists at all is to stop signals summing where nobody asked -- merging two
     * event streams hides nothing, since every event stays itself and arrives when it
     * arrived. Bespoke allows it, and a voice fed by two sequencers is the obvious patch.
     *
     * Held in fixed slots that are never compacted: an event carries the slot it came
     * from, so a voice keys what it is sounding by it, and shuffling the list up on a
     * disconnect would hand one source's slot to another with notes still running in it.
     */
    struct NoteSource {
        int32_t index = -1;
        int32_t port = 0;
        /**
         * Connected since the last merge, so the notes the source is already holding
         * have not reached this destination. Mutable because it is spent during the merge,
         * which otherwise only reads.
         */
        mutable bool fresh = false;
    };
    static constexpr int32_t kMaxNoteSources = 4;

    /**
     * A parameter, and whatever is modulating it.
     *
     * The routing is an InputRef, crossfade and all, with one reinterpretation: where an
     * input's -1 means silence, a parameter's means its own knob. So patching a modulator
     * fades from the knob's value to the modulated one, replacing it fades between two
     * live modulators, and unpatching fades back to the knob -- the same three cases
     * repatch() already gets right for signals, in value space instead of sample space.
     *
     * Applied once per block, through the node's own setParam. That makes every parameter
     * of every node modulatable without any node knowing modulation exists, at the cost of
     * a 1500Hz control rate. Audio-rate modulation is not what this is for: a module that
     * wants it declares an audio input, as Osc's fm does.
     */
    struct ParamRef {
        InputRef route;
        /** The knob's own value, which is what an unmodulated parameter is. */
        float base = 0.0f;
        float low = 0.0f;
        float high = 0.0f;
        bool exponential = false;
        /** Whether a range ever arrived. A modulator patched before one does changes nothing. */
        bool ranged = false;
    };

    struct Record {
        bool used = false;
        int64_t id = 0;
        NodeType type = NodeType::Unknown;
        Node *node = nullptr;
        std::array<InputRef, kMaxPorts> inputs{};
        /** Only the ports the node declares as note inputs use these. */
        std::array<std::array<NoteSource, kMaxNoteSources>, kMaxPorts> noteSources{};
        /** Indexed by parameter, not by port: a modulator lands on a knob, not a jack. */
        std::array<ParamRef, kMaxParams> params{};
        /**
         * Samples left before a removed node is actually handed back.
         *
         * A node cannot be freed the moment it is removed, because whatever it fed is
         * still crossfading out of it. It stays in the graph and keeps producing until
         * that fade has run, then goes to the return queue.
         */
        int32_t dying = 0;
    };

    /** Points a port at a new source, ramping rather than cutting. */
    void repatch(InputRef &ref, int32_t sourceIndex, int32_t sourcePort);
    /** Whether port [port] of the node in [slot] carries notes rather than samples. */
    bool isNoteInput(int32_t slot, int32_t port) const;
    /** Adds a source to a note input, if it is not already there and there is room. */
    void addNoteSource(int32_t dst, int32_t port, int32_t src, int32_t srcPort);
    /**
     * Drops sources of a note input, and tells the node so it can end their notes.
     *
     * [src] of -1 drops every source of the port; otherwise only the ones reading that
     * node. There is no crossfade to make here -- nothing is fading, the notes simply
     * have to be ended by whatever is sounding them.
     */
    void dropNoteSources(int32_t dst, int32_t port, int32_t src, int32_t srcPort);
    /**
     * What a parameter would be if [index]'s output [port] were driving it this block.
     *
     * The source's mean over the block, clamped to 0..1 and mapped across the range. The
     * mean rather than any one sample, because a block rate samples whatever is patched --
     * and averaging is at least a crude lowpass where picking a sample is pure aliasing.
     * No source, or no range, is the knob's own value.
     */
    float modulatedValue(const ParamRef &param, int32_t index, int32_t port, int32_t frames) const;
    /**
     * Pushes every modulated or fading parameter of [record] into its node, and publishes
     * where each has got to under [slot].
     */
    void applyModulation(Record &record, int32_t slot, int32_t frames);
    /** Gathers a note input's sources into one buffer, in offset order, tagged by slot. */
    const NoteBuffer &mergeNotes(const Record &record, int32_t port);
    /** Frees nodes whose fade-out has run. Audio thread, end of each block. */
    void reapDying(int32_t frames);

    int32_t indexOf(int64_t id) const;
    int32_t freeSlot() const;
    void rebuildOrder();
    void retire(int32_t slot);

    /** Published by the audio thread, read by the interface. Never the reverse. */
    struct Telemetry {
        std::atomic<int64_t> id{0};
        std::atomic<int32_t> step{-1};
        /** Each modulated parameter's value this block, so its bar can follow the modulator. */
        std::array<std::atomic<float>, kMaxParams> params{};
    };
    std::array<Telemetry, kMaxNodes> telemetry_{};

    /** Enough for any interval longer than a millisecond, which is all of them. */
    static constexpr int32_t kMaxTicks = 4;

    /**
     * Musical time. Deliberately untouched by reset(): the graph is rebuilt every time
     * the app comes back to the front, and that should not lose your place in the bar.
     */
    Transport transport_;
    /** The transport's beat, published for the interface once per block. */
    std::atomic<double> beat_{0.0};

    /** The patch's scales. Null until the interface sends some, which reads as 12-TET. */
    ScaleList *scales_ = nullptr;
    /** Which of them is sounding, published for the interface once per block. */
    std::atomic<int32_t> scaleEntry_{0};

    std::array<Record, kMaxNodes> nodes_{};
    std::array<int32_t, kMaxNodes> order_{};
    std::array<bool, kMaxNodes> emitted_{};
    int32_t orderCount_ = 0;
    int32_t outIndex_ = -1;
    int32_t inIndex_ = -1;
    bool dirty_ = true;

    std::atomic<int32_t> sampleRate_{48000};
    int32_t rampInSamples_ = 1454;  // ~30ms at 48k, recomputed in setSampleRate
    int32_t rampOutSamples_ = 1454; // ~30ms at 48k
    std::array<float, kBlockSize> silence_{};
    /** What an unpatched input reads when its node declares it a unityInputs() port. */
    std::array<float, kBlockSize> unity_{};

    // One per port index, not per node: only one node is processing at a time, so the
    // scratch a ramp renders into can be reused across the whole graph.
    std::array<std::array<float, kBlockSize>, kMaxPorts> ramp_{};
    // Likewise one per port index: a note input's sources are merged into this and the
    // node reads it during its own process(), which is the only time it is valid.
    std::array<NoteBuffer, kMaxPorts> merged_{};
    /** Where a fresh source's held notes are gathered, a member so the merge never grows the stack. */
    NoteBuffer held_{};

    SpscQueue<Command, kCommandCapacity> commands_;
    SpscQueue<Node *, kCommandCapacity> garbage_;
    SpscQueue<ScaleList *, 16> retiredScales_;
    SpscQueue<Resource *, 64> retiredResources_;
};
