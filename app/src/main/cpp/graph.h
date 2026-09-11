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
        // The artefact is the envelope itself, not a discontinuity: fading a signal that
        // is already at full amplitude and arbitrary phase puts energy around
        // 1/duration, which at 10ms is a low-frequency thump. 30ms moves that down and
        // quietens it.
        //
        // These were briefly asymmetric, on the theory that fading out ends in silence
        // where an envelope has nothing left to colour. That was wrong: 10ms sounded
        // clean on the phone's speaker and was plainly audible on earbuds. The shorter
        // side was not better, only harder to hear.
        //
        // Kept as two values rather than one because Phase 4 gives ports a signal type,
        // and a CV input probably wants to arrive faster than an audio one.
        rampInSamples_ = rate / 33;  // ~30ms
        rampOutSamples_ = rate / 33; // ~30ms
    }
    bool postAdd(int64_t id, NodeType type);
    bool postRemove(int64_t id);
    bool postConnect(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t dstPort);
    bool postDisconnect(int64_t dstId, int32_t dstPort);
    bool postSetParam(int64_t id, int32_t paramIndex, float value);
    /** Frees everything the audio thread handed back. Never called from the callback. */
    void collectGarbage();
    /** Frees anything still owned, after the stream has stopped. */
    void reset();

    // ---- audio thread
    void applyCommands();
    /** Hands the live microphone block to the In rail, if the patch has one. */
    void setLiveInput(const float *mono);
    void process(int32_t frames);
    const float *outputL() const;
    const float *outputR() const;

private:
    enum class CommandType : int32_t { Add, Remove, Connect, Disconnect, SetParam };

    struct Command {
        CommandType type = CommandType::Add;
        int64_t id = 0;
        int64_t srcId = 0;
        int32_t srcPort = 0;
        int32_t dstPort = 0;
        NodeType nodeType = NodeType::Unknown;
        Node *node = nullptr;
        int32_t paramIndex = 0;
        float value = 0.0f;
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

    struct Record {
        bool used = false;
        int64_t id = 0;
        NodeType type = NodeType::Unknown;
        Node *node = nullptr;
        std::array<InputRef, kMaxPorts> inputs{};
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
    /** Frees nodes whose fade-out has run. Audio thread, end of each block. */
    void reapDying(int32_t frames);

    int32_t indexOf(int64_t id) const;
    int32_t freeSlot() const;
    void rebuildOrder();
    void retire(int32_t slot);

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

    // One per port index, not per node: only one node is processing at a time, so the
    // scratch a ramp renders into can be reused across the whole graph.
    std::array<std::array<float, kBlockSize>, kMaxPorts> ramp_{};

    SpscQueue<Command, kCommandCapacity> commands_;
    SpscQueue<Node *, kCommandCapacity> garbage_;
};
