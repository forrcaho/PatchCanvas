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

int32_t Graph::stepOf(int64_t id) const {
    if (id == 0) return -1;
    for (const auto &entry : telemetry_) {
        if (entry.id.load(std::memory_order_relaxed) == id) {
            return entry.step.load(std::memory_order_relaxed);
        }
    }
    return -1;
}

bool Graph::postSetStep(int64_t id, int32_t index, float pitch, bool gate) {
    Command cmd;
    cmd.type = CommandType::SetStep;
    cmd.id = id;
    cmd.paramIndex = index;
    cmd.value = pitch;
    cmd.gate = gate;
    return commands_.push(cmd);
}

bool Graph::postSetParam(int64_t id, int32_t paramIndex, float value) {
    Command cmd;
    cmd.type = CommandType::SetParam;
    cmd.id = id;
    cmd.paramIndex = paramIndex;
    cmd.value = value;
    return commands_.push(cmd);
}

bool Graph::postSetTempo(float bpm) {
    Command cmd;
    cmd.type = CommandType::SetTempo;
    cmd.value = bpm;
    return commands_.push(cmd);
}

bool Graph::postResetTransport() {
    Command cmd;
    cmd.type = CommandType::ResetTransport;
    return commands_.push(cmd);
}

double Graph::transportBeat() const {
    return beat_.load(std::memory_order_relaxed);
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
    inIndex_ = -1;
    dirty_ = true;
}

// ---------------------------------------------------------------- audio thread

int32_t Graph::indexOf(int64_t id) const {
    for (int32_t i = 0; i < kMaxNodes; ++i) {
        // A dying node is still rendering for someone's crossfade, but it is no longer
        // addressable -- the same id may legitimately be added again.
        if (nodes_[i].used && !nodes_[i].dying && nodes_[i].id == id) return i;
    }
    return -1;
}

int32_t Graph::freeSlot() const {
    for (int32_t i = 0; i < kMaxNodes; ++i) {
        if (!nodes_[i].used) return i;
    }
    return -1;
}

void Graph::repatch(InputRef &ref, int32_t sourceIndex, int32_t sourcePort) {
    // Whatever the port was heading for becomes what it now fades out of, so the old
    // signal keeps playing all the way down instead of freezing at its last value.
    //
    // Unless a ramp was queued this same drain and has not rendered a sample yet: then
    // its "from" is still the true previous signal, and overwriting it would fade from
    // an intermediate target that was never actually heard.
    const bool queuedButUnrendered =
            ref.rampRemaining > 0 && ref.rampRemaining == ref.rampLength;
    if (!queuedButUnrendered) {
        ref.fromIndex = ref.sourceIndex;
        ref.fromPort = ref.sourcePort;
    }
    ref.sourceIndex = sourceIndex;
    ref.sourcePort = sourcePort;
    ref.rampLength = sourceIndex >= 0 ? rampInSamples_ : rampOutSamples_;
    ref.rampRemaining = ref.rampLength;
}

void Graph::retire(int32_t slot) {
    // Anything pointing at this slot starts fading out of it. The node is not freed
    // yet: it is still the source of those crossfades, and cutting it here would put
    // back exactly the thump the crossfade exists to remove.
    for (auto &record : nodes_) {
        if (!record.used) continue;
        for (auto &ref : record.inputs) {
            if (ref.sourceIndex == slot) repatch(ref, -1, 0);
        }
    }
    // Stop claiming this id, or the interface would go on drawing a playhead for a
    // sequencer that has been deleted. Either store alone would be enough -- no match
    // and no value both read as "nothing to draw" -- and both are here because the cost
    // is two relaxed stores on a path that runs once per deletion.
    telemetry_[slot].id.store(0, std::memory_order_relaxed);
    telemetry_[slot].step.store(-1, std::memory_order_relaxed);

    if (outIndex_ == slot) outIndex_ = -1;
    if (inIndex_ == slot) inIndex_ = -1;

    nodes_[slot].dying = rampOutSamples_ + kBlockSize;
    dirty_ = true;
}

void Graph::reapDying(int32_t frames) {
    bool reaped = false;
    for (int32_t i = 0; i < kMaxNodes; ++i) {
        Record &record = nodes_[i];
        if (!record.used || record.dying <= 0) continue;

        record.dying -= frames;
        if (record.dying > 0) continue;

        // The fades that were reading it have finished, so nothing can reference it any
        // more -- but say so explicitly rather than relying on the arithmetic, because a
        // reused slot would silently reconnect rather than crash.
        for (auto &other : nodes_) {
            for (auto &ref : other.inputs) {
                if (ref.fromIndex == i) ref.fromIndex = -1;
                if (ref.sourceIndex == i) ref.sourceIndex = -1;
            }
        }

        // Handed back rather than deleted: freeing here would be an allocation call on
        // the audio thread. If the return queue is full the node leaks, which is the
        // correct trade against blocking.
        garbage_.push(record.node);
        record = Record{};
        reaped = true;
    }

    // Rebuilt here and not merely marked dirty. process() runs once per inner block but
    // applyCommands only once per callback, so a slot freed at the end of one block
    // would still be sitting in the evaluation order when the next block walked it.
    if (reaped) {
        rebuildOrder();
        dirty_ = false;
    }
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
                if (cmd.nodeType == NodeType::In) inIndex_ = slot;
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
                repatch(nodes_[dst].inputs[cmd.dstPort], src, cmd.srcPort);
                dirty_ = true;
                break;
            }
            case CommandType::SetParam: {
                const int32_t slot = indexOf(cmd.id);
                if (slot < 0) break;
                if (cmd.paramIndex < 0 || cmd.paramIndex >= kMaxParams) break;
                // No topology change, so no re-sort: a knob does not move the graph.
                nodes_[slot].node->setParam(cmd.paramIndex, cmd.value);
                break;
            }
            case CommandType::SetStep: {
                const int32_t slot = indexOf(cmd.id);
                if (slot < 0) break;
                // The node bounds-checks the index itself, because how many steps a
                // sequence has is the node's business and not the graph's.
                nodes_[slot].node->setStep(cmd.paramIndex, cmd.value, cmd.gate);
                break;
            }
            case CommandType::SetTempo:
                transport_.setTempo(cmd.value);
                break;
            case CommandType::ResetTransport:
                transport_.reset();
                break;
            case CommandType::Disconnect: {
                const int32_t dst = indexOf(cmd.id);
                if (dst < 0) break;
                if (cmd.dstPort < 0 || cmd.dstPort >= kMaxPorts) break;
                repatch(nodes_[dst].inputs[cmd.dstPort], -1, 0);
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

void Graph::setLiveInput(const float *mono) {
    if (inIndex_ < 0 || !nodes_[inIndex_].used) return;
    static_cast<InNode *>(nodes_[inIndex_].node)->setSource(mono);
}

void Graph::process(int32_t frames) {
    // Read once for the whole block, so every node divides the same position and no two
    // can disagree about the frame a beat fell on.
    const bool running = transport_.running();
    const double beatsPerFrame = running ? transport_.beatsPerFrame() : 0.0;
    std::array<Tick, kMaxTicks> ticks{};

    for (int32_t i = 0; i < orderCount_; ++i) {
        Record &record = nodes_[order_[i]];
        // Belt and braces against an order that has outlived a slot. Cheap, and the
        // alternative is a null dereference on the audio thread.
        if (!record.used || record.node == nullptr) continue;
        Node *node = record.node;

        node->setTiming(beatsPerFrame, running);
        const Interval interval = node->interval();
        if (!interval.none()) {
            const int32_t count = transport_.ticks(interval, frames, ticks.data(), kMaxTicks);
            for (int32_t t = 0; t < count; ++t) node->tick(ticks[t].offset, ticks[t].count);
        }

        const int32_t ins = node->inputCount();
        for (int32_t p = 0; p < ins; ++p) {
            InputRef &ref = record.inputs[p];
            const bool live = ref.sourceIndex >= 0 && nodes_[ref.sourceIndex].used;
            const float *source = live
                    ? nodes_[ref.sourceIndex].node->output(ref.sourcePort)
                    : silence_.data();

            if (ref.rampRemaining > 0) {
                const bool fromLive = ref.fromIndex >= 0 && nodes_[ref.fromIndex].used;
                const float *previous = fromLive
                        ? nodes_[ref.fromIndex].node->output(ref.fromPort)
                        : silence_.data();

                float *blend = ramp_[p].data();
                for (int32_t i = 0; i < frames; ++i) {
                    const float linear = ref.rampRemaining > 0
                            ? 1.0f - static_cast<float>(ref.rampRemaining) /
                                     static_cast<float>(ref.rampLength)
                            : 1.0f;
                    // Smoothstep rather than linear: a straight ramp is continuous in
                    // value but not in slope, and those two corners are audible as a
                    // soft thump at each end of the fade.
                    const float t = linear * linear * (3.0f - 2.0f * linear);
                    blend[i] = previous[i] * (1.0f - t) + source[i] * t;
                    if (ref.rampRemaining > 0) --ref.rampRemaining;
                }
                node->setInput(p, blend);
            } else {
                node->setInput(p, source);
                ref.fromIndex = -1;
            }
        }
        node->process(frames);

        // Publish where a sequencer has got to. One relaxed store each, for the only
        // thing that travels back up: nothing reads it but a repaint, so a torn read is
        // a frame that draws the previous step and the next frame corrects it.
        const int32_t at = node->position();
        if (at >= 0) {
            telemetry_[order_[i]].id.store(record.id, std::memory_order_relaxed);
            telemetry_[order_[i]].step.store(at, std::memory_order_relaxed);
        }
    }

    // After every node, so all of them saw this block at the same position.
    transport_.advance(frames);
    beat_.store(transport_.beatAt(0), std::memory_order_relaxed);

    reapDying(frames);
}

const float *Graph::outputL() const {
    return outIndex_ >= 0 ? nodes_[outIndex_].node->output(0) : silence_.data();
}

const float *Graph::outputR() const {
    return outIndex_ >= 0 ? nodes_[outIndex_].node->output(1) : silence_.data();
}
