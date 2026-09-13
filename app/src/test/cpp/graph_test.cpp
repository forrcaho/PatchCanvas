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

/**
 * Out now runs a DC blocker, whose one-pole tail decays over tens of milliseconds, so
 * "silent" can never mean exactly zero again. It means the signal has gone.
 */
bool nearSilent(const float *buffer, int32_t frames) {
    return energy(buffer, frames) < 0.03f;
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
    render(graph, 64);
    const float baseline = maxStep(render(graph, 64));

    // Both orderings, because the UI may coalesce a replacement into a bare connect or
    // may still send the redundant disconnect first.
    graph.postDisconnect(3, 0);
    graph.postConnect(2, 0, 3, 0);
    graph.applyCommands();
    const auto swapped = render(graph, 64);

    check(maxStep(swapped) <= baseline * 1.25f, "swapping sources adds no step");
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
    check(nearSilent(graph.outputL(), kBlockSize), "silent once the ramp has run");
}

void patchingDoesNotStep() {
    std::printf("patching does not step\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    render(graph, 64); // settle

    // Measured against the signal's own worst step rather than an absolute threshold.
    // A band-limited saw steps hard once per cycle by design, so any fixed number would
    // either be met by a genuine click or fail on a waveform that simply has edges. The
    // claim worth testing is that patching adds nothing the signal did not already do.
    const auto steady = render(graph, 64);
    const float baseline = maxStep(steady);
    check(baseline > 0.0f, "the source actually moves");

    graph.postDisconnect(2, 0);
    graph.applyCommands();
    const auto onDisconnect = render(graph, 64);

    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    const auto onConnect = render(graph, 64);

    // A fade can only scale the signal down, so a transition should never out-step the
    // steady state. The margin is for the limiter's gain moving underneath it.
    check(maxStep(onDisconnect) <= baseline * 1.25f, "disconnecting adds no step");
    check(maxStep(onConnect) <= baseline * 1.25f, "connecting adds no step");
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
    check(nearSilent(graph.outputL(), kBlockSize), "silent once the source is gone");

    // The interesting half. Slots are reused, so a reference left pointing at the old
    // index would not dangle -- it would quietly reconnect to whatever moved in, which
    // is worse than a crash because it looks like it works.
    graph.postAdd(3, NodeType::Osc);
    graph.applyCommands();
    render(graph, 64);
    check(nearSilent(graph.outputL(), kBlockSize),
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

/**
 * The one value that travels back up. Everything else crosses as a command into a queue;
 * this is published for a repaint, so it has to be current, and it has to stop being
 * claimed the moment the node it describes is gone.
 */
void theGraphReportsWhereASequencerHasGot() {
    std::printf("the graph reports where a sequencer has got to\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Steps);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(2, 0, 1, 0);
    graph.postConnect(1, 0, 3, 0);
    graph.postSetTempo(300.0f); // fast, so a few steps pass quickly
    graph.applyCommands();

    check(graph.stepOf(99) == -1, "an id nothing owns reports nothing");
    check(graph.stepOf(1) == -1, "an oscillator is not a sequencer");

    graph.process(kBlockSize);
    check(graph.stepOf(2) == -1, "a stopped transport starts nothing");

    graph.setTransportRunning(true);
    graph.process(kBlockSize);
    check(graph.stepOf(2) == 0, "the first tick is the first step");

    // 300bpm at 48k is 9600 frames a beat, so a default 1/8 step is 4800; run well past one.
    for (int i = 0; i < 600; ++i) graph.process(kBlockSize);
    check(graph.stepOf(2) > 0, "and the step advances with the transport");

    graph.postRemove(2);
    graph.applyCommands();
    check(graph.stepOf(2) == -1, "a removed sequencer stops being reported");
}

/**
 * Switching the output off is a pause, not a stop. Everything a sequencer does comes
 * from the position, so if the position holds, so does the music.
 */
void stoppingHoldsThePositionAndResetReturnsToTheStart() {
    std::printf("stopping holds the position, and reset returns to the start\n");
    Graph graph;
    graph.setSampleRate(48000);
    graph.postAdd(2, NodeType::Steps);
    graph.postSetTempo(300.0f);
    graph.applyCommands();

    graph.setTransportRunning(true);
    for (int i = 0; i < 1000; ++i) graph.process(kBlockSize);
    const int32_t step = graph.stepOf(2);
    const double beat = graph.transportBeat();
    check(beat > 0.0, "the transport moves while running");

    graph.setTransportRunning(false);
    for (int i = 0; i < 1000; ++i) graph.process(kBlockSize);
    check(graph.transportBeat() == beat, "and holds exactly still while stopped");
    check(graph.stepOf(2) == step, "and so does the sequencer");

    graph.postResetTransport();
    graph.applyCommands();
    graph.setTransportRunning(true);
    graph.process(kBlockSize);
    check(graph.stepOf(2) == 0, "reset puts the sequencer back on its first step");
    check(graph.transportBeat() < 0.01, "and the transport back at the top");
}

/** Backgrounding the app rebuilds the graph, and should not lose your place in the bar. */
void theTransportSurvivesAGraphReset() {
    std::printf("the transport survives a graph reset\n");
    Graph graph;
    graph.setSampleRate(48000);
    graph.setTransportRunning(true);
    for (int i = 0; i < 1000; ++i) graph.process(kBlockSize);
    const double before = graph.transportBeat();

    graph.reset();
    graph.process(kBlockSize);
    check(graph.transportBeat() > before, "it carries on from where it was, not from zero");
}

// ---------------------------------------------------------------- the transport itself

/** Every tick of each interval across a run, as absolute frames and counts. */
struct Heard {
    std::vector<int64_t> frames;
    std::vector<int64_t> counts;
};

void listen(const Transport &transport, Interval interval, int64_t blockStart, Heard &into) {
    std::array<Tick, 4> ticks{};
    const int32_t n = transport.ticks(interval, kBlockSize, ticks.data(), 4);
    for (int32_t i = 0; i < n; ++i) {
        into.frames.push_back(blockStart + ticks[i].offset);
        into.counts.push_back(ticks[i].count);
    }
}

std::size_t between(const std::vector<int64_t> &frames, int64_t from, int64_t to) {
    return static_cast<std::size_t>(
            std::lower_bound(frames.begin(), frames.end(), to) -
            std::lower_bound(frames.begin(), frames.end(), from));
}

/**
 * The reason the transport exists. 127bpm is 22677.17 frames a beat, which no whole
 * number of frames can hold, so anything counting its own period drifts -- by a sixth of
 * a second after twenty minutes. Divisions of one position cannot.
 */
void divisionsOfOneTransportNeverDrift() {
    std::printf("divisions of one transport never drift\n");
    Transport transport;
    transport.setSampleRate(48000);
    transport.setTempo(127.0);
    transport.setRunning(true);

    Heard beats, sixteenths, triplets;
    const int64_t blocks = 10LL * 60 * 48000 / kBlockSize; // ten minutes
    for (int64_t b = 0; b < blocks; ++b) {
        const int64_t start = b * kBlockSize;
        listen(transport, Interval{1, 1}, start, beats);
        listen(transport, Interval{1, 4}, start, sixteenths);
        listen(transport, Interval{1, 3}, start, triplets);
        transport.advance(kBlockSize);
    }

    const double framesPerBeat = 48000.0 * 60.0 / 127.0;
    check(beats.frames.size() > 1200, "ten minutes at 127bpm is some 1270 beats");

    // Within one frame of exactly on time, every beat for ten minutes. Up to and including
    // one, because every 127th beat falls exactly on a frame, where frame * beatsPerFrame
    // can round a hair below the whole beat and tick a frame late -- 21 microseconds,
    // and every division of that beat is late by the same frame, since rounding cannot
    // reorder them. A counter that truncated its period would be 200 frames out by now.
    double earliest = 0.0;
    double latest = 0.0;
    for (std::size_t n = 0; n < beats.frames.size(); ++n) {
        const double off = static_cast<double>(beats.frames[n]) - static_cast<double>(n) * framesPerBeat;
        earliest = std::min(earliest, off);
        latest = std::max(latest, off);
    }
    check(earliest >= 0.0 && latest <= 1.0,
          "beat n lands within a frame of n beats, ten minutes in -- offsets ran " +
          std::to_string(earliest) + " to " + std::to_string(latest));

    bool together = true;
    bool divided = true;
    for (std::size_t n = 0; n + 1 < beats.frames.size(); ++n) {
        const int64_t at = beats.frames[n];
        if (!std::binary_search(sixteenths.frames.begin(), sixteenths.frames.end(), at) ||
            !std::binary_search(triplets.frames.begin(), triplets.frames.end(), at)) {
            together = false;
        }
        if (between(sixteenths.frames, at, beats.frames[n + 1]) != 4 ||
            between(triplets.frames, at, beats.frames[n + 1]) != 3) {
            divided = false;
        }
    }
    check(together, "every beat is a sixteenth and a triplet on the very same frame");
    check(divided, "with exactly four sixteenths and three triplets in every beat");
}

void resumingNeitherRepeatsNorSkipsATick() {
    std::printf("resuming neither repeats nor skips a tick\n");
    Transport transport;
    transport.setSampleRate(48000);
    transport.setTempo(120.0);
    transport.setRunning(true);

    Heard heard;
    int64_t frame = 0;
    auto play = [&](int blocks) {
        for (int b = 0; b < blocks; ++b) {
            listen(transport, Interval{1, 4}, frame, heard);
            transport.advance(kBlockSize);
            frame += kBlockSize;
        }
    };

    play(1500);
    transport.setRunning(false);
    const double held = transport.beatAt(0);
    const std::size_t before = heard.counts.size();
    play(1500);
    check(heard.counts.size() == before, "nothing ticks while stopped");
    check(transport.beatAt(0) == held, "and the position does not move");
    transport.setRunning(true);
    play(1500);

    bool consecutive = heard.counts.size() > 2 && heard.counts.front() == 0;
    for (std::size_t i = 1; i < heard.counts.size(); ++i) {
        if (heard.counts[i] != heard.counts[i - 1] + 1) consecutive = false;
    }
    check(consecutive, "every count from zero, once each, across the pause");
}

void aTempoChangeCarriesOnFromTheCurrentBeat() {
    std::printf("a tempo change carries on from the current beat\n");
    Transport transport;
    transport.setSampleRate(48000);
    transport.setRunning(true);
    for (int b = 0; b < 1000; ++b) transport.advance(kBlockSize);

    const double before = transport.beatAt(0);
    transport.setTempo(90.0);
    check(std::fabs(transport.beatAt(0) - before) < 1e-9, "the position does not jump");
    check(std::fabs(transport.beatsPerFrame() - 90.0 / 60.0 / 48000.0) < 1e-15,
          "but moves at the new rate from here");

    transport.setTempo(9999.0);
    check(transport.tempo() == Transport::kMaxTempo, "an absurd tempo is clamped");
}

// ---------------------------------------------------------------- scales

ScaleList *listOfLengths(std::initializer_list<int32_t> beats) {
    auto *list = new ScaleList();
    for (int32_t length : beats) {
        ScaleTable &table = list->tables[list->count];
        table.size = 12;
        for (int32_t i = 0; i < 12; ++i) table.octaves[i] = static_cast<float>(i) / 12.0f;
        list->beats[list->count++] = length;
    }
    list->finish();
    return list;
}

/** Mirrors ScaleTest's own wrapping cases, because this is now the table that sounds. */
void aScaleTableWrapsByPeriod() {
    std::printf("a scale table wraps by period\n");
    ScaleTable pentatonic;
    pentatonic.size = 5;
    for (int32_t i = 0; i < 5; ++i) pentatonic.octaves[i] = static_cast<float>(i) * 0.2f;

    check(std::fabs(pentatonic.octavesOf(6) - 1.2f) < 1e-5f, "degree 6 of five is degree 1 an octave up");
    check(std::fabs(pentatonic.octavesOf(-1) - (-0.2f)) < 1e-5f, "degree -1 is the top degree an octave down");

    ScaleTable tritave = pentatonic;
    tritave.period = 1.5849625f;
    check(std::fabs(tritave.octavesOf(5) - 1.5849625f) < 1e-5f, "a full turn travels the period, not an octave");

    const ScaleTable unsent;
    check(std::fabs(unsent.octavesOf(7) - 7.0f / 12.0f) < 1e-6f, "a table nobody sent is twelve equal steps");
}

void aScaleListSwitchesOnWholeBeatsAndLoops() {
    std::printf("a scale list switches on whole beats and loops\n");
    const ScaleList *list = listOfLengths({4, 2, 3});
    check(list->totalBeats == 9, "the loop is the sum of its entries");
    const int32_t expected[] = {0, 0, 0, 0, 1, 1, 2, 2, 2, 0};
    bool right = true;
    for (int32_t beat = 0; beat < 10; ++beat) {
        if (list->entryAt(beat) != expected[beat]) right = false;
    }
    check(right, "each entry holds for its own beats, and the list starts again after the last");
    check(list->entryAt(-1) == 2, "a negative beat wraps rather than indexing off the front");
    delete list;

    const ScaleList *single = listOfLengths({4});
    check(single->entryAt(1000) == 0, "one entry is simply a fixed scale");
    delete single;
}

/**
 * A replaced list goes back to the interface to be freed, never deleted on the audio
 * thread. ASan's leak check at exit is what makes this a test: a list swapped out and
 * never collected fails the whole binary.
 */
void aReplacedScaleListIsHandedBackAndFreed() {
    std::printf("a replaced scale list is handed back and freed\n");
    Graph graph;
    graph.setSampleRate(48000);
    graph.postSetScales(listOfLengths({4, 4}));
    graph.applyCommands();
    graph.postSetScales(listOfLengths({2, 2}));
    graph.postSetScales(listOfLengths({1, 1}));
    graph.applyCommands();
    graph.collectGarbage();

    graph.setTransportRunning(true);
    for (int i = 0; i < 1600; ++i) graph.process(kBlockSize); // 51200 frames, over two beats at 120
    check(graph.scaleEntry() == 0, "two beats in, a list of one-beat entries is back on its first");
    for (int i = 0; i < 751; ++i) graph.process(kBlockSize); // past beat three
    check(graph.scaleEntry() == 1, "and on its second a beat later");
}

void resetStartsOnTheFirstFrameOfBarOne() {
    std::printf("reset starts on the first frame of bar one\n");
    Transport transport;
    transport.setSampleRate(48000);
    transport.setRunning(true);
    for (int b = 0; b < 777; ++b) transport.advance(kBlockSize);

    transport.reset();
    std::array<Tick, 4> ticks{};
    const int32_t n = transport.ticks(Interval{4, 1}, kBlockSize, ticks.data(), 4);
    check(n == 1 && ticks[0].offset == 0 && ticks[0].count == 0,
          "the very first frame after a reset is the downbeat");
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
    theGraphReportsWhereASequencerHasGot();
    stoppingHoldsThePositionAndResetReturnsToTheStart();
    theTransportSurvivesAGraphReset();
    divisionsOfOneTransportNeverDrift();
    resumingNeitherRepeatsNorSkipsATick();
    aTempoChangeCarriesOnFromTheCurrentBeat();
    resetStartsOnTheFirstFrameOfBarOne();
    aScaleTableWrapsByPeriod();
    aScaleListSwitchesOnWholeBeatsAndLoops();
    aReplacedScaleListIsHandedBackAndFreed();

    std::printf("\n%d checks, %d failed\n", checks, failures);
    std::fflush(stdout);
    return failures == 0 ? 0 : 1;
}
