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
    void setSampleRate(int32_t rate) { sampleRate_ = rate; }
    bool postAdd(int64_t id, NodeType type);
    bool postRemove(int64_t id);
    bool postConnect(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t dstPort);
    bool postDisconnect(int64_t dstId, int32_t dstPort);
    /** Frees everything the audio thread handed back. Never called from the callback. */
    void collectGarbage();
    /** Frees anything still owned, after the stream has stopped. */
    void reset();

    // ---- audio thread
    void applyCommands();
    void process(int32_t frames);
    const float *outputL() const;
    const float *outputR() const;

private:
    enum class CommandType : int32_t { Add, Remove, Connect, Disconnect };

    struct Command {
        CommandType type = CommandType::Add;
        int64_t id = 0;
        int64_t srcId = 0;
        int32_t srcPort = 0;
        int32_t dstPort = 0;
        NodeType nodeType = NodeType::Unknown;
        Node *node = nullptr;
    };

    struct InputRef {
        int32_t sourceIndex = -1;
        int32_t sourcePort = 0;
    };

    struct Record {
        bool used = false;
        int64_t id = 0;
        NodeType type = NodeType::Unknown;
        Node *node = nullptr;
        std::array<InputRef, kMaxPorts> inputs{};
    };

    int32_t indexOf(int64_t id) const;
    int32_t freeSlot() const;
    void rebuildOrder();
    void retire(int32_t slot);

    std::array<Record, kMaxNodes> nodes_{};
    std::array<int32_t, kMaxNodes> order_{};
    std::array<bool, kMaxNodes> emitted_{};
    int32_t orderCount_ = 0;
    int32_t outIndex_ = -1;
    bool dirty_ = true;

    std::atomic<int32_t> sampleRate_{48000};
    std::array<float, kBlockSize> silence_{};

    SpscQueue<Command, kCommandCapacity> commands_;
    SpscQueue<Node *, kCommandCapacity> garbage_;
};
