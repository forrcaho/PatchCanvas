#include "graph.h"

// ---------------------------------------------------------------- UI thread

bool Graph::postAdd(int64_t id, NodeType type) {
    // Allocated here, deliberately. The audio thread is handed a pointer and never a
    // constructor.
    Node *node = makeNode(type);
    node->prepare(sampleRate_.load(std::memory_order_relaxed));

    Command cmd;
    cmd.type = CommandType::Add;
    cmd.id = id;
    cmd.nodeType = type;
    cmd.node = node;
    if (!commands_.push(cmd)) {
        delete node; // never made it across, so it is still ours to free
        return false;
    }
    return true;
}

bool Graph::postRemove(int64_t id) {
    Command cmd;
    cmd.type = CommandType::Remove;
    cmd.id = id;
    return commands_.push(cmd);
}

bool Graph::postConnect(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t dstPort) {
    Command cmd;
    cmd.type = CommandType::Connect;
    cmd.srcId = srcId;
    cmd.srcPort = srcPort;
    cmd.id = dstId;
    cmd.dstPort = dstPort;
    return commands_.push(cmd);
}

bool Graph::postDisconnect(int64_t dstId, int32_t dstPort) {
    Command cmd;
    cmd.type = CommandType::Disconnect;
    cmd.id = dstId;
    cmd.dstPort = dstPort;
    return commands_.push(cmd);
}

void Graph::collectGarbage() {
    Node *dead = nullptr;
    while (garbage_.pop(dead)) {
        delete dead;
    }
}

void Graph::reset() {
    // Only safe once the stream is stopped and no callback can be running.
    Command cmd;
    while (commands_.pop(cmd)) {
        if (cmd.type == CommandType::Add) delete cmd.node;
    }
    for (auto &record : nodes_) {
        delete record.node;
        record = Record{};
    }
    collectGarbage();
    orderCount_ = 0;
    outIndex_ = -1;
    dirty_ = true;
}

// ---------------------------------------------------------------- audio thread

int32_t Graph::indexOf(int64_t id) const {
    for (int32_t i = 0; i < kMaxNodes; ++i) {
        if (nodes_[i].used && nodes_[i].id == id) return i;
    }
    return -1;
}

int32_t Graph::freeSlot() const {
    for (int32_t i = 0; i < kMaxNodes; ++i) {
        if (!nodes_[i].used) return i;
    }
    return -1;
}

void Graph::retire(int32_t slot) {
    // Anything pointing at this slot has to forget it first. Slots are reused, so a
    // stale reference would not dangle -- it would silently reconnect to whatever
    // moved in, which is worse because it looks like it works.
    for (auto &record : nodes_) {
        if (!record.used) continue;
        for (auto &ref : record.inputs) {
            if (ref.sourceIndex == slot) ref = InputRef{};
        }
    }
    if (outIndex_ == slot) outIndex_ = -1;

    // Handed back rather than deleted: freeing here would be an allocation call on the
    // audio thread. If the return queue is full the node leaks, which is the correct
    // trade against blocking.
    garbage_.push(nodes_[slot].node);
    nodes_[slot] = Record{};
    dirty_ = true;
}

void Graph::applyCommands() {
    Command cmd;
    while (commands_.pop(cmd)) {
        switch (cmd.type) {
            case CommandType::Add: {
                if (indexOf(cmd.id) >= 0) { garbage_.push(cmd.node); break; }
                const int32_t slot = freeSlot();
                if (slot < 0) { garbage_.push(cmd.node); break; }
                nodes_[slot].used = true;
                nodes_[slot].id = cmd.id;
                nodes_[slot].type = cmd.nodeType;
                nodes_[slot].node = cmd.node;
                nodes_[slot].inputs.fill(InputRef{});
                if (cmd.nodeType == NodeType::Out) outIndex_ = slot;
                dirty_ = true;
                break;
            }
            case CommandType::Remove: {
                const int32_t slot = indexOf(cmd.id);
                if (slot >= 0) retire(slot);
                break;
            }
            case CommandType::Connect: {
                const int32_t dst = indexOf(cmd.id);
                const int32_t src = indexOf(cmd.srcId);
                if (dst < 0 || src < 0) break;
                if (cmd.dstPort < 0 || cmd.dstPort >= kMaxPorts) break;
                nodes_[dst].inputs[cmd.dstPort] = InputRef{src, cmd.srcPort};
                dirty_ = true;
                break;
            }
            case CommandType::Disconnect: {
                const int32_t dst = indexOf(cmd.id);
                if (dst < 0) break;
                if (cmd.dstPort < 0 || cmd.dstPort >= kMaxPorts) break;
                nodes_[dst].inputs[cmd.dstPort] = InputRef{};
                dirty_ = true;
                break;
            }
        }
    }

    if (dirty_) {
        rebuildOrder();
        dirty_ = false;
    }
}

void Graph::rebuildOrder() {
    emitted_.fill(false);
    orderCount_ = 0;

    // Kahn's, expressed as repeated sweeps rather than with reverse edges. At most 64
    // nodes and only on a topology change, so the quadratic worst case is cheaper than
    // maintaining the adjacency it would replace.
    bool progress = true;
    while (progress) {
        progress = false;
        for (int32_t i = 0; i < kMaxNodes; ++i) {
            if (!nodes_[i].used || emitted_[i]) continue;

            bool ready = true;
            for (int32_t p = 0; p < nodes_[i].node->inputCount(); ++p) {
                const int32_t src = nodes_[i].inputs[p].sourceIndex;
                if (src >= 0 && !emitted_[src]) { ready = false; break; }
            }
            if (!ready) continue;

            emitted_[i] = true;
            order_[orderCount_++] = i;
            progress = true;
        }
    }

    // Whatever is left is in a cycle. Appending it costs exactly one block of delay on
    // the back edge, because output buffers are never cleared between blocks -- a node
    // evaluated before its source simply reads what that source left last time. That is
    // the standard way to make feedback finite, and it is why the buffers persist.
    for (int32_t i = 0; i < kMaxNodes; ++i) {
        if (nodes_[i].used && !emitted_[i]) {
            emitted_[i] = true;
            order_[orderCount_++] = i;
        }
    }
}

void Graph::process(int32_t frames) {
    for (int32_t i = 0; i < orderCount_; ++i) {
        Record &record = nodes_[order_[i]];
        Node *node = record.node;

        const int32_t ins = node->inputCount();
        for (int32_t p = 0; p < ins; ++p) {
            const InputRef &ref = record.inputs[p];
            const bool live = ref.sourceIndex >= 0 && nodes_[ref.sourceIndex].used;
            node->setInput(p, live ? nodes_[ref.sourceIndex].node->output(ref.sourcePort)
                                   : silence_.data());
        }
        node->process(frames);
    }
}

const float *Graph::outputL() const {
    return outIndex_ >= 0 ? nodes_[outIndex_].node->output(0) : silence_.data();
}

const float *Graph::outputR() const {
    return outIndex_ >= 0 ? nodes_[outIndex_].node->output(1) : silence_.data();
}
