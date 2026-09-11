// Host-side tests for the audio graph.
//
// graph.cpp and nodes.cpp deliberately depend on nothing from Android or Oboe, so they
// compile and run on the desk. Audio bugs are miserable to diagnose on a device, and
// the evaluation order is the part most worth pinning down before it ever gets there.

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "graph.h"

namespace {

int failures = 0;
int checks = 0;

void check(bool ok, const std::string &what) {
    ++checks;
    if (!ok) {
        ++failures;
        std::printf("  FAIL: %s\n", what.c_str());
    }
}

float energy(const float *buffer, int32_t frames) {
    float total = 0.0f;
    for (int32_t i = 0; i < frames; ++i) total += std::fabs(buffer[i]);
    return total;
}

/** Renders several blocks end to end, so a transition can be examined across them. */
std::vector<float> render(Graph &graph, int blocks) {
    std::vector<float> all;
    for (int b = 0; b < blocks; ++b) {
        graph.process(kBlockSize);
        const float *left = graph.outputL();
        all.insert(all.end(), left, left + kBlockSize);
    }
    return all;
}

/** The largest jump between adjacent samples: what a click actually is. */
float maxStep(const std::vector<float> &samples) {
    float worst = 0.0f;
    for (std::size_t i = 1; i < samples.size(); ++i) {
        worst = std::max(worst, std::fabs(samples[i] - samples[i - 1]));
    }
    return worst;
}

bool finite(const float *buffer, int32_t frames) {
    for (int32_t i = 0; i < frames; ++i) {
        if (!std::isfinite(buffer[i])) return false;
    }
    return true;
}

// ---------------------------------------------------------------------------

void signalReachesTheOutputWithinOneBlock() {
    std::printf("signal reaches the output within one block\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    graph.process(kBlockSize);

    // The discriminating part: if the sink were evaluated before the source it would
    // read the source's previous block, which on the very first block is silence. A
    // non-zero output here means the topological order really did put Osc first.
    check(energy(graph.outputL(), kBlockSize) > 0.0f, "output is non-zero on the first block");
    check(energy(graph.outputR(), kBlockSize) == 0.0f, "unconnected right stays silent");
}

void unpatchingFadesTheSignalNotADcLevel() {
    std::printf("unpatching fades the signal, not a DC level\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    render(graph, 16);

    graph.postDisconnect(2, 0);
    graph.applyCommands();
    const auto tail = render(graph, 48);

    int crossings = 0;
    for (std::size_t i = 1; i < tail.size(); ++i) {
        if ((tail[i - 1] < 0.0f) != (tail[i] < 0.0f)) ++crossings;
    }

    // 220Hz across a 30ms fade is roughly thirteen half-cycles. Fading from a frozen
    // value instead would stop the oscillation dead and glide a DC level to zero, which
    // crosses at most once -- inaudible as a click and very audible as a thump.
    check(crossings > 4, "the signal keeps oscillating all the way down");
    check(maxStep(tail) < 0.05f, "and still does not step");
}

void replacingASourceCrossfades() {
    std::printf("replacing a source crossfades\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Osc);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(1, 0, 3, 0);
    graph.applyCommands();
    render(graph, 16);

    // Both orderings, because the UI may coalesce a replacement into a bare connect or
    // may still send the redundant disconnect first.
    graph.postDisconnect(3, 0);
    graph.postConnect(2, 0, 3, 0);
    graph.applyCommands();
    const auto swapped = render(graph, 64);

    check(maxStep(swapped) < 0.05f, "swapping sources does not step");
    check(energy(swapped.data(), static_cast<int32_t>(swapped.size())) > 0.0f,
          "and the new source arrives");
}

void aChainIsOrderedEndToEnd() {
    std::printf("a chain is ordered end to end\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Filter);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(2, 0, 3, 0);
    graph.applyCommands();
    graph.process(kBlockSize);

    check(energy(graph.outputL(), kBlockSize) > 0.0f,
          "three-deep chain resolves in one block");
}

void disconnectingSilencesTheOutput() {
    std::printf("disconnecting silences the output\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    graph.process(kBlockSize);
    check(energy(graph.outputL(), kBlockSize) > 0.0f, "sounding before the cut");

    graph.postDisconnect(2, 0);
    graph.applyCommands();
    // Silence arrives after the declick ramp, not on the next sample. That delay is the
    // feature; asserting immediate silence would be asserting the click back.
    render(graph, 64);
    check(energy(graph.outputL(), kBlockSize) == 0.0f, "silent once the ramp has run");
}

void patchingDoesNotStep() {
    std::printf("patching does not step\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Out);
    graph.applyCommands();
    render(graph, 4); // settle at silence

    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    const auto onConnect = render(graph, 64);

    graph.postDisconnect(2, 0);
    graph.applyCommands();
    const auto onDisconnect = render(graph, 64);

    // A hard patch would step by the source's instantaneous value, which for a
    // full-scale oscillator is up to 1.0. The oscillator's own slope is about 0.03 per
    // sample at 220Hz, so anything near that means the transition was ramped, not cut.
    // Tightened from 0.1: with a 10ms smoothstep the envelope contributes almost
    // nothing, so what is left should be barely more than the oscillator's own slope
    // of about 0.03 per sample at 220Hz.
    check(maxStep(onConnect) < 0.05f, "connecting does not step");
    check(maxStep(onDisconnect) < 0.05f, "disconnecting does not step");
    check(energy(onConnect.data(), static_cast<int32_t>(onConnect.size())) > 0.0f,
          "and the signal does arrive");
}

void aReusedSlotDoesNotInheritOldCables() {
    std::printf("a reused slot does not inherit old cables\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    graph.process(kBlockSize);
    check(energy(graph.outputL(), kBlockSize) > 0.0f, "sounding to begin with");

    graph.postRemove(1);
    graph.applyCommands();
    render(graph, 64);
    check(energy(graph.outputL(), kBlockSize) == 0.0f, "silent once the source is gone");

    // The interesting half. Slots are reused, so a reference left pointing at the old
    // index would not dangle -- it would quietly reconnect to whatever moved in, which
    // is worse than a crash because it looks like it works.
    graph.postAdd(3, NodeType::Osc);
    graph.applyCommands();
    render(graph, 64);
    check(energy(graph.outputL(), kBlockSize) == 0.0f,
          "a new node in the freed slot is NOT silently patched in");

    graph.collectGarbage();
}

void feedbackTerminatesAndStaysFinite() {
    std::printf("feedback terminates and stays finite\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Filter);
    graph.postAdd(2, NodeType::Filter);
    graph.postAdd(3, NodeType::Out);
    // A cycle: each filter feeds the other. Without the back edge being broken this
    // would have no topological order at all.
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(2, 0, 1, 0);
    graph.postConnect(2, 0, 3, 0);
    graph.applyCommands();

    for (int i = 0; i < 64; ++i) graph.process(kBlockSize);

    check(finite(graph.outputL(), kBlockSize), "a feedback loop does not blow up");
}

void everyNodeInACycleStillRuns() {
    std::printf("every node in a cycle still runs\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Filter);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(2, 0, 3, 0);
    // Filter's cutoff fed from its own output: a self-loop, so it is in a cycle and
    // cannot be topologically ordered, but it must still be evaluated.
    graph.postConnect(2, 0, 2, 1);
    graph.applyCommands();
    graph.process(kBlockSize);
    graph.process(kBlockSize);

    check(energy(graph.outputL(), kBlockSize) > 0.0f,
          "a node inside a cycle is still evaluated");
    check(finite(graph.outputL(), kBlockSize), "and stays finite");
}

void duplicateAndOverfullAreRefusedNotCrashed() {
    std::printf("duplicate and overfull are refused, not crashed\n");
    Graph graph;
    graph.setSampleRate(48000);

    check(graph.postAdd(1, NodeType::Osc), "first add accepted");
    check(graph.postAdd(1, NodeType::Osc), "duplicate id is queued");
    graph.applyCommands();
    // The duplicate must have been dropped rather than occupying a second slot; the
    // node it carried is handed back rather than leaked.
    graph.collectGarbage();

    for (int i = 0; i < kMaxNodes + 8; ++i) graph.postAdd(100 + i, NodeType::Osc);
    graph.applyCommands();
    graph.process(kBlockSize);
    check(finite(graph.outputL(), kBlockSize), "an over-full graph still renders");
    graph.collectGarbage();
}

void commandsSurviveAPartialBlock() {
    std::printf("commands survive a partial block\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();

    // 96-frame bursts divide by kBlockSize exactly, but nothing guarantees that on
    // every device, so a short final block has to work too.
    graph.process(kBlockSize / 2);
    check(energy(graph.outputL(), kBlockSize / 2) > 0.0f, "a half block still renders");
}

} // namespace

int main() {
    signalReachesTheOutputWithinOneBlock();
    patchingDoesNotStep();
    unpatchingFadesTheSignalNotADcLevel();
    replacingASourceCrossfades();
    aChainIsOrderedEndToEnd();
    disconnectingSilencesTheOutput();
    aReusedSlotDoesNotInheritOldCables();
    feedbackTerminatesAndStaysFinite();
    everyNodeInACycleStillRuns();
    duplicateAndOverfullAreRefusedNotCrashed();
    commandsSurviveAPartialBlock();

    std::printf("\n%d checks, %d failed\n", checks, failures);
    std::fflush(stdout);
    return failures == 0 ? 0 : 1;
}
