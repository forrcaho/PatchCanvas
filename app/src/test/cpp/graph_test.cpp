// Host-side tests for the audio graph.
//
// graph.cpp and nodes.cpp deliberately depend on nothing from Android or Oboe, so they
// compile and run on the desk. Audio bugs are miserable to diagnose on a device, and
// the evaluation order is the part most worth pinning down before it ever gets there.

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
    graph.process(kBlockSize);
    check(energy(graph.outputL(), kBlockSize) == 0.0f, "silent after the cut");
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
    graph.process(kBlockSize);
    check(energy(graph.outputL(), kBlockSize) == 0.0f, "silent once the source is gone");

    // The interesting half. Slots are reused, so a reference left pointing at the old
    // index would not dangle -- it would quietly reconnect to whatever moved in, which
    // is worse than a crash because it looks like it works.
    graph.postAdd(3, NodeType::Osc);
    graph.applyCommands();
    graph.process(kBlockSize);
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
