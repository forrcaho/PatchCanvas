#include "graph.h"

#include <cmath>
#include <limits>

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

bool Graph::postDisconnect(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t dstPort) {
    Command cmd;
    cmd.type = CommandType::Disconnect;
    cmd.srcId = srcId;
    cmd.srcPort = srcPort;
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

float Graph::paramOf(int64_t id, int32_t index) const {
    if (id != 0 && index >= 0 && index < kMaxParams) {
        for (const auto &entry : telemetry_) {
            if (entry.id.load(std::memory_order_relaxed) == id) {
                return entry.params[index].load(std::memory_order_relaxed);
            }
        }
    }
    return std::numeric_limits<float>::quiet_NaN();
}

bool Graph::postSetStep(int64_t id, int32_t index, int32_t degree, bool gate) {
    Command cmd;
    cmd.type = CommandType::SetStep;
    cmd.id = id;
    cmd.paramIndex = index;
    cmd.degree = degree;
    cmd.gate = gate;
    return commands_.push(cmd);
}

bool Graph::postSetScales(ScaleList *list) {
    Command cmd;
    cmd.type = CommandType::SetScales;
    cmd.scales = list;
    if (!commands_.push(cmd)) {
        delete list; // never made it across, so it is still ours to free
        return false;
    }
    return true;
}

bool Graph::postSetResource(int64_t id, Resource *resource) {
    Command cmd;
    cmd.type = CommandType::SetResource;
    cmd.id = id;
    cmd.resource = resource;
    if (!commands_.push(cmd)) {
        delete resource;
        return false;
    }
    return true;
}

int32_t Graph::scaleEntry() const {
    return scaleEntry_.load(std::memory_order_relaxed);
}

bool Graph::postSetParam(int64_t id, int32_t paramIndex, float value) {
    Command cmd;
    cmd.type = CommandType::SetParam;
    cmd.id = id;
    cmd.paramIndex = paramIndex;
    cmd.value = value;
    return commands_.push(cmd);
}

bool Graph::postSetModRange(int64_t id, int32_t paramIndex, float low, float high,
                            bool exponential) {
    Command cmd;
    cmd.type = CommandType::SetModRange;
    cmd.id = id;
    cmd.paramIndex = paramIndex;
    cmd.value = low;
    cmd.high = high;
    cmd.exponential = exponential;
    return commands_.push(cmd);
}

bool Graph::postConnectMod(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t paramIndex) {
    Command cmd;
    cmd.type = CommandType::ConnectMod;
    cmd.srcId = srcId;
    cmd.srcPort = srcPort;
    cmd.id = dstId;
    cmd.paramIndex = paramIndex;
    return commands_.push(cmd);
}

bool Graph::postDisconnectMod(int64_t srcId, int32_t srcPort, int64_t dstId, int32_t paramIndex) {
    Command cmd;
    cmd.type = CommandType::DisconnectMod;
    cmd.srcId = srcId;
    cmd.srcPort = srcPort;
    cmd.id = dstId;
    cmd.paramIndex = paramIndex;
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
    ScaleList *replaced = nullptr;
    while (retiredScales_.pop(replaced)) {
        delete replaced;
    }
    Resource *retired = nullptr;
    while (retiredResources_.pop(retired)) {
        delete retired;
    }
}

void Graph::reset() {
    // Only safe once the stream is stopped and no callback can be running.
    Command cmd;
    while (commands_.pop(cmd)) {
        if (cmd.type == CommandType::Add) delete cmd.node;
        if (cmd.type == CommandType::SetScales) delete cmd.scales;
        if (cmd.type == CommandType::SetResource) delete cmd.resource;
    }
    // Resent by the interface on the next start, like everything else the graph held.
    delete scales_;
    scales_ = nullptr;
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

bool Graph::isNoteInput(int32_t slot, int32_t port) const {
    if (port < 0 || port >= kMaxPorts) return false;
    const Record &record = nodes_[slot];
    if (!record.used || record.node == nullptr) return false;
    return (record.node->noteInputs() & (1u << static_cast<uint32_t>(port))) != 0;
}

void Graph::addNoteSource(int32_t dst, int32_t port, int32_t src, int32_t srcPort) {
    auto &sources = nodes_[dst].noteSources[port];
    // Already patched. Sending it twice is not an error -- the interface sends the whole
    // difference it sees -- and adding it twice would play every note down it twice.
    for (const auto &source : sources) {
        if (source.index == src && source.port == srcPort) return;
    }
    for (auto &source : sources) {
        if (source.index < 0) {
            source.index = src;
            source.port = srcPort;
            source.fresh = true;
            return;
        }
    }
    // Full. Refused rather than evicting one of the four already playing.
}

void Graph::dropNoteSources(int32_t dst, int32_t port, int32_t src, int32_t srcPort) {
    Record &record = nodes_[dst];
    auto &sources = record.noteSources[port];
    for (int32_t s = 0; s < kMaxNoteSources; ++s) {
        if (sources[s].index < 0) continue;
        if (src >= 0 && sources[s].index != src) continue;
        // -1 is any port of that node, which is what a node being deleted means.
        if (src >= 0 && srcPort >= 0 && sources[s].port != srcPort) continue;
        sources[s] = NoteSource{};
        // The slot is free before the node hears about it, so a voice released here
        // cannot be handed the same slot again mid-release.
        if (record.node != nullptr) record.node->notesCut(port, s);
    }
}

const NoteBuffer &Graph::mergeNotes(const Record &record, int32_t port) {
    NoteBuffer &into = merged_[port];
    into.clear();

    for (int32_t s = 0; s < kMaxNoteSources; ++s) {
        const NoteSource &source = record.noteSources[port][s];
        if (source.index < 0 || !nodes_[source.index].used) continue;
        const NoteBuffer *from = nodes_[source.index].node->noteOutput(source.port);

        // A cable connected while its source holds notes: start them here first, at the
        // top of the block, or this destination never hears them begin. A note the source
        // is starting in this very block is already in its buffer and is skipped, so it
        // does not start twice.
        if (source.fresh) {
            source.fresh = false;
            held_.clear();
            nodes_[source.index].node->heldNotes(source.port, held_);
            for (int32_t h = 0; h < held_.count; ++h) {
                bool startingNow = false;
                for (int32_t e = 0; e < from->count; ++e) {
                    if (from->events[e].kind == NoteKind::On && from->events[e].id == held_.events[h].id) {
                        startingNow = true;
                        break;
                    }
                }
                if (startingNow) continue;
                NoteEvent event = held_.events[h];
                event.source = static_cast<uint8_t>(s);
                if (!into.push(event)) break;
            }
        }

        for (int32_t e = 0; e < from->count; ++e) {
            NoteEvent event = from->events[e];
            // Stamped here rather than by the source, which has no idea it is one of
            // several and picks its ids as though it were alone.
            event.source = static_cast<uint8_t>(s);
            if (!into.push(event)) break;
        }
    }

    // Insertion sort, and stable, so events at the same sample stay in source order.
    // At most kMaxNoteEvents of them and nearly always already sorted, which is the case
    // insertion sort is linear in; anything cleverer would be slower here and harder to
    // read.
    for (int32_t i = 1; i < into.count; ++i) {
        const NoteEvent event = into.events[i];
        int32_t j = i - 1;
        while (j >= 0 && into.events[j].offset > event.offset) {
            into.events[j + 1] = into.events[j];
            --j;
        }
        into.events[j + 1] = event;
    }
    return into;
}

void Graph::retire(int32_t slot) {
    // Anything pointing at this slot starts fading out of it. The node is not freed
    // yet: it is still the source of those crossfades, and cutting it here would put
    // back exactly the thump the crossfade exists to remove.
    for (int32_t i = 0; i < kMaxNodes; ++i) {
        if (!nodes_[i].used) continue;
        for (int32_t p = 0; p < kMaxPorts; ++p) {
            if (nodes_[i].inputs[p].sourceIndex == slot) repatch(nodes_[i].inputs[p], -1, 0);
            // Notes cannot fade, so a deleted source has to end what it started here and
            // now: the node is about to stop existing, and a voice waiting for its Off
            // would hold forever.
            dropNoteSources(i, p, slot, -1);
        }
        // A deleted modulator fades back to the knob it was turning, from the node that is
        // still lingering to render exactly that fade.
        for (auto &param : nodes_[i].params) {
            if (param.route.sourceIndex == slot) repatch(param.route, -1, 0);
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
            for (auto &port : other.noteSources) {
                for (auto &source : port) {
                    if (source.index == i) source = NoteSource{};
                }
            }
            for (auto &param : other.params) {
                if (param.route.fromIndex == i) param.route.fromIndex = -1;
                if (param.route.sourceIndex == i) param.route.sourceIndex = -1;
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
                nodes_[slot].params.fill(ParamRef{});
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
                if (cmd.srcPort < 0 || cmd.srcPort >= kMaxPorts) break;

                // Notes are typed, and this is the one place in the engine where a patch
                // is refused for what it carries. Signals stay advisory: audio into a CV
                // input is a technique, and blocking it would make this less modular than
                // the thing it is modeled on. An event is not a voltage, though -- a
                // voice reading a note buffer nobody fills would simply never sound, and
                // the silence would look like a bug in everything except the cable.
                const bool noteSrc = (nodes_[src].node->noteOutputs() &
                                      (1u << static_cast<uint32_t>(cmd.srcPort))) != 0;
                if (noteSrc != isNoteInput(dst, cmd.dstPort)) break;

                if (noteSrc) {
                    addNoteSource(dst, cmd.dstPort, src, cmd.srcPort);
                } else {
                    repatch(nodes_[dst].inputs[cmd.dstPort], src, cmd.srcPort);
                }
                dirty_ = true;
                break;
            }
            case CommandType::SetParam: {
                const int32_t slot = indexOf(cmd.id);
                if (slot < 0) break;
                if (cmd.paramIndex < 0 || cmd.paramIndex >= kMaxParams) break;
                // No topology change, so no re-sort: a knob does not move the graph.
                //
                // Remembered always, applied only when nothing is modulating it. A modulated
                // parameter is set from its modulator before every block anyway, so this is
                // the value it fades back to when unpatched -- and pushing it into the node
                // now would only recompute coefficients the next block overwrites.
                ParamRef &param = nodes_[slot].params[cmd.paramIndex];
                param.base = cmd.value;
                if (param.route.sourceIndex < 0 && param.route.rampRemaining <= 0) {
                    nodes_[slot].node->setParam(cmd.paramIndex, cmd.value);
                }
                break;
            }
            case CommandType::SetModRange: {
                const int32_t slot = indexOf(cmd.id);
                if (slot < 0) break;
                if (cmd.paramIndex < 0 || cmd.paramIndex >= kMaxParams) break;
                ParamRef &param = nodes_[slot].params[cmd.paramIndex];
                param.low = cmd.value;
                param.high = cmd.high;
                param.exponential = cmd.exponential;
                param.ranged = true;
                break;
            }
            case CommandType::ConnectMod: {
                const int32_t dst = indexOf(cmd.id);
                const int32_t src = indexOf(cmd.srcId);
                if (dst < 0 || src < 0) break;
                if (cmd.paramIndex < 0 || cmd.paramIndex >= kMaxParams) break;
                if (cmd.srcPort < 0 || cmd.srcPort >= nodes_[src].node->outputCount()) break;
                // A note output has a sample buffer nobody writes, so modulating from one
                // would pin the parameter at the bottom of its range with no visible cause.
                if ((nodes_[src].node->noteOutputs() &
                     (1u << static_cast<uint32_t>(cmd.srcPort))) != 0) {
                    break;
                }
                repatch(nodes_[dst].params[cmd.paramIndex].route, src, cmd.srcPort);
                dirty_ = true;
                break;
            }
            case CommandType::DisconnectMod: {
                const int32_t dst = indexOf(cmd.id);
                if (dst < 0) break;
                if (cmd.paramIndex < 0 || cmd.paramIndex >= kMaxParams) break;
                // One modulator per parameter, so the source is named for symmetry with a
                // cable and checked by nothing: the interface says this knob is now free.
                repatch(nodes_[dst].params[cmd.paramIndex].route, -1, 0);
                dirty_ = true;
                break;
            }
            case CommandType::SetStep: {
                const int32_t slot = indexOf(cmd.id);
                if (slot < 0) break;
                // The node bounds-checks the index itself, because how many steps a
                // sequence has is the node's business and not the graph's.
                nodes_[slot].node->setStep(cmd.paramIndex, cmd.degree, cmd.gate);
                break;
            }
            case CommandType::SetScales:
                // Swapped whole, and the old list handed back to be freed off this thread.
                // If the return queue is full it leaks, which is the same trade the nodes
                // make against blocking.
                if (scales_ != nullptr) retiredScales_.push(scales_);
                scales_ = cmd.scales;
                break;
            case CommandType::SetResource: {
                // The node takes it and gives back what it had; a node that is gone gives
                // back what it was offered. Leaks if the return queue is full, the trade
                // SetScales makes rather than freeing here.
                const int32_t slot = indexOf(cmd.id);
                Resource *back = slot >= 0 ? nodes_[slot].node->swapResource(cmd.resource)
                                           : cmd.resource;
                if (back != nullptr) retiredResources_.push(back);
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
                if (isNoteInput(dst, cmd.dstPort)) {
                    // Which source, because the port may have several. An id that is no
                    // longer here took its own sources with it when it was removed.
                    const int32_t src = cmd.srcId != 0 ? indexOf(cmd.srcId) : -1;
                    if (cmd.srcId != 0 && src < 0) break;
                    dropNoteSources(dst, cmd.dstPort, src, cmd.srcPort);
                } else {
                    // One source, so naming it adds nothing: the interface is the
                    // authority on what is patched, and it says this port is now empty.
                    repatch(nodes_[dst].inputs[cmd.dstPort], -1, 0);
                }
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
            for (int32_t p = 0; p < nodes_[i].node->inputCount() && ready; ++p) {
                const int32_t src = nodes_[i].inputs[p].sourceIndex;
                if (src >= 0 && !emitted_[src]) { ready = false; break; }
                // Note cables order the graph exactly as signal cables do. They must: a
                // voice evaluated before its sequencer would hear every note a block late,
                // which is 0.67ms of lateness nobody asked for and, worse, is invisible.
                for (const auto &source : nodes_[i].noteSources[p]) {
                    if (source.index >= 0 && !emitted_[source.index]) { ready = false; break; }
                }
            }
            // A modulator before the knob it turns, for the same reason: evaluated after, the
            // node would read the previous block's sweep, and a block late is still late.
            for (int32_t p = 0; p < kMaxParams && ready; ++p) {
                const int32_t src = nodes_[i].params[p].route.sourceIndex;
                if (src >= 0 && !emitted_[src]) ready = false;
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

float Graph::modulatedValue(const ParamRef &param, int32_t index, int32_t port,
                            int32_t frames) const {
    if (index < 0 || !param.ranged || !nodes_[index].used || frames <= 0) return param.base;

    const float *buffer = nodes_[index].node->output(port);
    float sum = 0.0f;
    for (int32_t i = 0; i < frames; ++i) sum += buffer[i];
    const float mean = sum / static_cast<float>(frames);
    // NaN compares false both ways, so it falls through to the bottom of the range rather
    // than reaching a node's setParam.
    const float amount = mean > 1.0f ? 1.0f : (mean > 0.0f ? mean : 0.0f);

    // Geometric only where it can be: a range touching zero or crossing it has no ratio,
    // and a stepped or bipolar parameter is linear anyway.
    if (param.exponential && param.low > 0.0f && param.high > 0.0f) {
        return param.low * std::pow(param.high / param.low, amount);
    }
    return param.low + amount * (param.high - param.low);
}

void Graph::applyModulation(Record &record, int32_t slot, int32_t frames) {
    bool published = false;
    for (int32_t p = 0; p < kMaxParams; ++p) {
        ParamRef &param = record.params[p];
        InputRef &ref = param.route;
        if (ref.sourceIndex < 0 && ref.rampRemaining <= 0) continue;

        const float target = modulatedValue(param, ref.sourceIndex, ref.sourcePort, frames);
        float value = target;
        if (ref.rampRemaining > 0) {
            ref.rampRemaining = ref.rampRemaining > frames ? ref.rampRemaining - frames : 0;
            const float linear = 1.0f - static_cast<float>(ref.rampRemaining) /
                                        static_cast<float>(ref.rampLength);
            // Smoothstep, as for signals. At a block rate the corners are steps in a
            // parameter rather than in a waveform, but a gain parameter turns one into the
            // other.
            const float t = linear * linear * (3.0f - 2.0f * linear);
            const float previous = modulatedValue(param, ref.fromIndex, ref.fromPort, frames);
            value = previous * (1.0f - t) + target * t;
        }
        // The last block of a fade back to the knob lands on the knob exactly, and the
        // next block skips this parameter altogether.
        if (ref.rampRemaining == 0) ref.fromIndex = -1;
        record.node->setParam(p, value);
        // One relaxed store each, like a sequencer's step: a torn read is a bar drawn a block
        // stale, and the next frame corrects it.
        telemetry_[slot].params[p].store(value, std::memory_order_relaxed);
        published = true;
    }
    if (published) telemetry_[slot].id.store(record.id, std::memory_order_relaxed);
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

        node->setTiming(beatsPerFrame, running, scales_);
        const Interval interval = node->interval();
        if (!interval.none()) {
            const int32_t count = transport_.ticks(interval, frames, ticks.data(), kMaxTicks);
            for (int32_t t = 0; t < count; ++t) node->tick(ticks[t].offset, ticks[t].count);
        }

        applyModulation(record, order_[i], frames);

        const int32_t ins = node->inputCount();
        const uint32_t noteMask = node->noteInputs();
        for (int32_t p = 0; p < ins; ++p) {
            if ((noteMask & (1u << static_cast<uint32_t>(p))) != 0) {
                // No ramp, and nothing to ramp between: the events are gathered from
                // whatever is patched and handed over as they are. Valid only for this
                // node's process(), like the crossfade scratch above it.
                node->setNoteInput(p, &mergeNotes(record, p));
                continue;
            }
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
    if (scales_ != nullptr) {
        const auto wholeBeat = static_cast<int64_t>(std::floor(transport_.beatAt(0)));
        scaleEntry_.store(scales_->entryAt(wholeBeat), std::memory_order_relaxed);
    }

    reapDying(frames);
}

const float *Graph::outputL() const {
    return outIndex_ >= 0 ? nodes_[outIndex_].node->output(0) : silence_.data();
}

const float *Graph::outputR() const {
    return outIndex_ >= 0 ? nodes_[outIndex_].node->output(1) : silence_.data();
}
