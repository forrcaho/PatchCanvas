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

/** Node ids the poly rig below builds itself from; see polyRig. */
constexpr int64_t kEdge = 90;
constexpr int64_t kSumNode = 91;
constexpr int64_t kOutNode = 92;
constexpr int64_t kFirstOsc = 100;

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

/**
 * A steady tone at [id]: an oscillator holding one note, fed by a drone.
 *
 * Nothing drones by itself any more. The monophonic oscillator these tests used as a
 * source was retired when every synth became polyphonic, and a tone is now a note that is
 * being held -- which is a good part of why Drone exists. The envelope is flattened so the
 * tone is steady within a block rather than still decaying while it is measured.
 *
 * The oscillator is added first so that it, rather than its drone, takes the lowest free
 * slot: one test is about what happens to a freed slot, and wants the audible node in it.
 * The drone takes id + 1000, so a test still names its source by the id it chose.
 */
void addTone(Graph &graph, int64_t id) {
    graph.postAdd(id, NodeType::Osc);
    graph.postAdd(id + 1000, NodeType::Drone);
    graph.postSetStep(id + 1000, 0, 0, true);
    graph.postSetParam(id, 1, 0.0005f); // attack
    graph.postSetParam(id, 2, 0.0005f); // decay
    graph.postSetParam(id, 3, 1.0f);    // sustain, so the note holds at full level
    graph.postConnect(id + 1000, 0, id, 0);
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

    addTone(graph, 1);
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

    addTone(graph, 1);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    render(graph, 16);

    graph.postDisconnect(1, 0, 2, 0);
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

    addTone(graph, 1);
    addTone(graph, 2);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(1, 0, 3, 0);
    graph.applyCommands();
    render(graph, 64);
    const float baseline = maxStep(render(graph, 64));

    // Both orderings, because the UI may coalesce a replacement into a bare connect or
    // may still send the redundant disconnect first.
    graph.postDisconnect(1, 0, 3, 0);
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

    addTone(graph, 1);
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

    addTone(graph, 1);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    graph.process(kBlockSize);
    check(energy(graph.outputL(), kBlockSize) > 0.0f, "sounding before the cut");

    graph.postDisconnect(1, 0, 2, 0);
    graph.applyCommands();
    // Silence arrives after the declick ramp, not on the next sample. That delay is the
    // feature; asserting immediate silence would be asserting the click back.
    // 400 blocks, not 64. The 30ms fade is long over by then; what takes the time is Out's
    // DC blocker, whose one-pole tail is charged by cutting the waveform wherever it was.
    // Measured, that tail is a clean exponential -- 0.069 at 100 blocks, 0.038 at 200,
    // 0.020 at 300, 0.010 at 400 -- so it crosses the 0.03 threshold around 250 and the
    // assertion is put well clear of that rather than just past it.
    render(graph, 400);
    check(nearSilent(graph.outputL(), kBlockSize), "silent once the ramp has run");
}

void patchingDoesNotStep() {
    std::printf("patching does not step\n");
    Graph graph;
    graph.setSampleRate(48000);

    addTone(graph, 1);
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

    graph.postDisconnect(1, 0, 2, 0);
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

    addTone(graph, 1);
    graph.postAdd(2, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    graph.process(kBlockSize);
    check(energy(graph.outputL(), kBlockSize) > 0.0f, "sounding to begin with");

    graph.postRemove(1);
    graph.applyCommands();
    render(graph, 400); // past the DC blocker's tail, as above
    check(nearSilent(graph.outputL(), kBlockSize), "silent once the source is gone");

    // The interesting half. Slots are reused, so a reference left pointing at the old
    // index would not dangle -- it would quietly reconnect to whatever moved in, which
    // is worse than a crash because it looks like it works.
    // A tone rather than a bare oscillator: a polyphonic one with nothing patched to it
    // is silent anyway, so this check would pass whether the slot had been reconnected or
    // not. It has to be able to make a sound for its silence to mean anything.
    addTone(graph, 3);
    graph.applyCommands();
    render(graph, 400);
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

    addTone(graph, 1);
    graph.postAdd(2, NodeType::Mix);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(2, 0, 3, 0);
    // A mix channel fed from its own output: a self-loop, so it is in a cycle and cannot
    // be topologically ordered, but it must still be evaluated. Filter's cutoff jack used
    // to be this loop and went with CV. Half gain on the back edge, so what the cycle
    // costs is one block of delay rather than a signal that grows without bound.
    graph.postSetParam(2, 1, 0.5f);
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

    addTone(graph, 1);
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

    ScaleTable inG = pentatonic;
    inG.root = 7.0f / 12.0f;
    check(std::fabs(inG.octavesOf(0) - 7.0f / 12.0f) < 1e-5f, "a root moves degree 0 off middle C");
    check(std::fabs(inG.octavesOf(6) - (7.0f / 12.0f + 1.2f)) < 1e-5f, "and every other degree with it");

    const ScaleList *far = listOfLengths({4});
    ScaleList clamped = *far;
    clamped.tables[0].root = 9.0f;
    clamped.finish();
    check(clamped.tables[0].root == kMaxRoot, "an absurd root is clamped to two octaves");
    delete far;

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

/**
 * The whole path, because each half passing on its own proves nothing about the join: the
 * drone has to be ticked by the transport, its Change has to survive the merge into the
 * oscillator's input, and the oscillator has to be reading the same scale list. Any one of
 * those missing would leave the drone as silent about a scale change as it was on the
 * phone, with every node test green.
 *
 * Sine, flat envelope, 120bpm: a beat is 24000 frames and the list turns from 12-TET to
 * major at beat 4. Degree 10 is 466Hz before and 698Hz after.
 */
void aDroneFollowsTheScaleThroughTheGraph() {
    std::printf("a drone follows the scale through the graph\n");
    Graph graph;
    graph.setSampleRate(48000);
    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Out);
    graph.postAdd(3, NodeType::Drone);
    graph.postSetParam(1, 0, 3.0f);    // sine, so a zero crossing is a cycle
    graph.postSetParam(1, 1, 0.0005f); // and a flat envelope
    graph.postSetParam(1, 2, 0.0005f);
    graph.postSetParam(1, 3, 1.0f);
    graph.postSetStep(3, 10, 10, true);
    graph.postConnect(3, 0, 1, 0);
    graph.postConnect(1, 0, 2, 0);

    auto *list = new ScaleList();
    list->count = 2;
    list->tables[0].size = 12;
    for (int32_t i = 0; i < 12; ++i) list->tables[0].octaves[i] = static_cast<float>(i) / 12.0f;
    constexpr int32_t major[7] = {0, 2, 4, 5, 7, 9, 11};
    list->tables[1].size = 7;
    for (int32_t i = 0; i < 7; ++i) list->tables[1].octaves[i] = static_cast<float>(major[i]) / 12.0f;
    list->beats[0] = 4;
    list->beats[1] = 4;
    list->finish();
    graph.postSetScales(list);
    graph.postSetTempo(120.0f);
    graph.applyCommands();
    graph.setTransportRunning(true);

    auto cycles = [](const std::vector<float> &samples) {
        int count = 0;
        for (std::size_t i = 1; i < samples.size(); ++i) {
            if (samples[i - 1] > 0.0f && samples[i] <= 0.0f) ++count;
        }
        return count;
    };

    render(graph, 750);                      // to beat 1
    const int before = cycles(render(graph, 750)); // beats 1 to 2, half a second
    check(std::abs(before - 233) <= 3, "degree 10 in 12-TET, got " + std::to_string(before));

    render(graph, 1625);                     // past the turn at beat 4, and the glide
    const int after = cycles(render(graph, 750));
    check(std::abs(after - 349) <= 3, "degree 10 in major after the turn, got " + std::to_string(after));
    graph.collectGarbage();
}

/**
 * Found on the phone: a drone already holding a chord, patched to an oscillator added
 * after the cells were toggled, stayed silent. A source says a note's start once, and a
 * drone's notes never end -- so a new destination never heard one begin. The graph now
 * asks the source for what it is holding on the first block after the connect.
 */
void aNewDestinationHearsWhatADroneIsAlreadyHolding() {
    std::printf("a new destination hears what a drone is already holding\n");
    Graph graph;
    graph.setSampleRate(48000);
    graph.postAdd(3, NodeType::Drone);
    graph.postAdd(2, NodeType::Out);
    graph.postSetStep(3, 0, 0, true);
    graph.applyCommands();
    render(graph, 32); // holding degree 0 with nothing listening

    addTone(graph, 1); // an oscillator of its own, fed by its own drone at 1001...
    graph.postRemove(1001); // ...which goes, so the only notes it can hear are drone 3's
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(3, 0, 1, 0);
    graph.applyCommands();
    render(graph, 16);
    check(!nearSilent(graph.outputL(), kBlockSize), "a destination patched to a held drone sounds its note");
    graph.collectGarbage();
}

/**
 * The other half of the same fix: a note that starts in the very block its cable is
 * connected is in the source's buffer already, and must not be handed over a second time
 * as a held note. Two starts of one note take two voices, and at a quiet output level that
 * is simply twice as loud as the same note arriving the ordinary way.
 */
void aNoteStartingAsItsCableConnectsStartsOnce() {
    std::printf("a note starting as its cable connects starts once\n");
    auto peakOf = [](const std::vector<float> &samples) {
        float peak = 0.0f;
        for (float v : samples) peak = std::max(peak, std::fabs(v));
        return peak;
    };
    auto build = [](Graph &graph) {
        graph.setSampleRate(48000);
        graph.postAdd(1, NodeType::Osc);
        graph.postAdd(2, NodeType::Out);
        graph.postAdd(3, NodeType::Drone);
        graph.postSetParam(1, 0, 3.0f); // sine
        graph.postSetParam(1, 1, 0.0005f);
        graph.postSetParam(1, 2, 0.0005f);
        graph.postSetParam(1, 3, 1.0f);
        graph.postSetParam(2, 0, 0.2f); // quiet, so Out's limiter stays out of the comparison
        graph.postConnect(1, 0, 2, 0);
    };

    Graph ordinary;
    build(ordinary);
    ordinary.postConnect(3, 0, 1, 0);
    ordinary.applyCommands();
    render(ordinary, 4);
    ordinary.postSetStep(3, 0, 0, true); // the cable was there first
    ordinary.applyCommands();
    render(ordinary, 64);
    const float once = peakOf(render(ordinary, 64));

    Graph together;
    build(together);
    together.postSetStep(3, 0, 0, true); // the note and its cable in the same block
    together.postConnect(3, 0, 1, 0);
    together.applyCommands();
    render(together, 64);
    const float same = peakOf(render(together, 64));

    check(once > 0.05f, "the note sounds the ordinary way");
    check(std::fabs(same - once) < 0.1f * once, "and no louder when it starts as its cable connects");
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

// ---------------------------------------------------------------- notes

/** A sequencer and a voice, patched by their note ports, with the transport running. */
void aNoteCableSoundsAndOrdersTheGraph() {
    std::printf("a note cable sounds, and orders the graph\n");
    Graph graph;
    graph.setSampleRate(48000);

    // The voice is added first, and so takes a lower slot than the sequencer feeding it.
    // That is the whole point: with nothing ordering them, the sweep that builds the
    // evaluation order emits them in slot order and the voice would run first. Adding
    // them the other way round would pass whether note cables order the graph or not.
    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Steps);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(2, 0, 1, 0); // notes out -> notes in
    graph.postConnect(1, 0, 3, 0);
    graph.postSetTempo(300.0f);
    graph.applyCommands();
    graph.setTransportRunning(true);

    // A voice evaluated before its sequencer reads the previous block's events, which on
    // the very first block are none at all. Sound here means the note cable ordered them.
    graph.process(kBlockSize);
    check(energy(graph.outputL(), kBlockSize) > 0.0f, "the first tick is heard in its own block");

    const auto sustained = render(graph, 64);
    check(energy(sustained.data(), static_cast<int32_t>(sustained.size())) > 0.0f,
          "and it keeps sounding");
}

void notesAndSignalsDoNotPatchToEachOther() {
    std::printf("notes and signals do not patch to each other\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Steps);
    graph.postAdd(2, NodeType::Osc);
    graph.postAdd(3, NodeType::Out);
    graph.postAdd(4, NodeType::Osc);
    // Every wrong way round: the sequencer's pitch CV into the voice's note input, an
    // oscillator into the same, and the note output into the sink's audio input.
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(4, 0, 2, 0);
    graph.postConnect(1, 0, 3, 0);
    graph.postConnect(2, 0, 3, 1);
    graph.postSetTempo(300.0f);
    graph.applyCommands();
    graph.setTransportRunning(true);

    // What is asserted is the outcome, not the mechanism: refused or merely ignored, a
    // signal in a note input makes no sound, because nothing reads a float buffer as
    // events or the reverse. The engine refuses it anyway, so the boundary says no
    // explicitly rather than by accident -- and the refusal a finger meets is in
    // Patch.connect, where it can leave the port armed and say so.
    const auto rendered = render(graph, 64);
    check(nearSilent(graph.outputL(), kBlockSize), "a signal in a note input sounds nothing");
    check(energy(rendered.data(), static_cast<int32_t>(rendered.size())) < 0.03f * 64,
          "and a note cable carries nothing into an audio input");
    // The right way round still works on the same graph, so the refusal is about the
    // kinds and not about the patch having been poisoned.
    graph.postConnect(1, 0, 2, 0);
    graph.applyCommands();
    // Past the next tick. A 1/8 at 300bpm is 4800 frames, and nothing sounds until one:
    // patching mid-note joins at the next note rather than the one already playing, since
    // the voice never heard that one start.
    render(graph, 200);
    check(energy(graph.outputR(), kBlockSize) > 0.0f, "while the note port itself patches");
}

void aRemovedSourceEndsTheNotesItStarted() {
    std::printf("a removed source ends the notes it started\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Steps);
    graph.postAdd(2, NodeType::Osc);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(2, 0, 3, 0);
    // Whole notes at 60bpm: four seconds a step, so the note under test is still held
    // rather than having ended on its own while the test was looking away.
    graph.postSetParam(1, 2, 0.0f);
    graph.postSetTempo(60.0f);
    graph.applyCommands();
    graph.setTransportRunning(true);
    render(graph, 64);
    check(energy(graph.outputL(), kBlockSize) > 0.0f, "a note is held");

    graph.postRemove(1);
    graph.applyCommands();
    // Past the release and the declick both. There is no crossfade to make here: the
    // note has to be ended by the voice, because nothing else knows it is sounding.
    render(graph, 2000);
    check(nearSilent(graph.outputL(), kBlockSize), "and deleting the sequencer ends it");
}



// ---------------------------------------------------------------- modulation

/**
 * The loudest sample in a stretch of output, which through a VCA with nothing on its CV
 * input is its bias -- the parameter these tests modulate, because amplitude is the one
 * parameter the graph's output shows directly.
 */
float peakOf(const std::vector<float> &samples) {
    float worst = 0.0f;
    for (float s : samples) worst = std::max(worst, std::fabs(s));
    return worst;
}

/**
 * The level of each window of [blocks] blocks, in order.
 *
 * Eight blocks is 256 samples, more than one cycle of the 262Hz sine these tests use, so a
 * window's peak is the VCA's bias across it rather than wherever the wave happened to be.
 * That is what makes a fade measurable by how long it takes. Measuring its steepest sample
 * instead passed with the crossfade deleted: a bias that jumps only steps the output by as
 * much as the sine is at that one sample, which near a zero crossing is nothing.
 */
std::vector<float> levels(Graph &graph, int windows, int blocks = 8) {
    std::vector<float> out;
    for (int w = 0; w < windows; ++w) out.push_back(peakOf(render(graph, blocks)));
    return out;
}

/**
 * A sine through a gain to the output, with a held envelope as the modulator.
 *
 * Both halves had to be rebuilt when CV retired. The VCA is a Mix channel, which is what a
 * VCA always was here -- `o = in * level` -- and the constant modulator is an envelope
 * rather than a stopped sequencer's pitch output, which went with the monophonic
 * oscillator that was the only thing reading it.
 *
 * An envelope held open by a drone sits at its sustain level for as long as you like, so
 * its sustain is the knob that says what the modulator is worth. Attack and decay are set
 * to nothing so it arrives there at once, and callers still render past the change before
 * measuring, because the sustain segment glides rather than jumps.
 *
 * Measured through Out, whose limiter is transparent only well below full scale: a level of
 * 0.2 reads back as 0.198, but 0.6 already settles at 0.543. So a test that compares two
 * levels keeps both below 0.4, and one that has to go higher judges against what it measured.
 */
struct ModPatch {
    Graph graph;
    ModPatch() {
        graph.setSampleRate(48000);
        graph.postAdd(5, NodeType::Drone);
        graph.postAdd(1, NodeType::Osc);
        graph.postAdd(2, NodeType::Mix);
        graph.postAdd(3, NodeType::Out);
        graph.postAdd(4, NodeType::Env);
        graph.postSetStep(5, 0, 0, true); // one note, held for the whole test
        // A sine, so a step in level is not hidden by the wave's own edges, and a flat
        // envelope on it so the tone is steady rather than still decaying while measured.
        graph.postSetParam(1, 0, 3.0f);
        graph.postSetParam(1, 1, 0.001f);
        graph.postSetParam(1, 2, 0.001f);
        graph.postSetParam(1, 3, 1.0f);
        // The modulator is one segment that rises at once and then parks, so what it is
        // worth is a level and not a moment in a shape. Slots 1 and 2 are cleared because
        // a fresh Env arrives with the A/D/S/R default in it.
        graph.postSetSegment(4, 0, 0.001f, 0.002f, 0.0f, true);
        graph.postSetSegment(4, 1, 0.0f, 0.0f, 0.0f, false);
        graph.postSetSegment(4, 2, 0.0f, 0.0f, 0.0f, false);
        graph.postConnect(5, 0, 1, 0);
        graph.postConnect(5, 0, 4, 0);
        graph.postConnect(1, 0, 2, 0);
        graph.postConnect(2, 0, 3, 0);
        graph.applyCommands();
    }
    /**
     * What the modulator is worth, 0 to 1: the level the parked envelope holds at.
     *
     * Exactly zero is allowed now, where the ADSR this replaced could not take it: DaisySP's
     * envelope decaying toward a sustain of zero crossed below it and latched to idle, and
     * idle was only left on a rising gate -- so a modulator set to nothing once could never
     * be raised again. A segment that parks holds whatever level it names, including none,
     * and follows the level while it is parked there.
     */
    void level(float value) {
        graph.postSetSegment(4, 0, 0.001f, value, 0.0f, true);
        graph.applyCommands();
        // Past the 5ms glide onto the new level; see EnvNode::holdGlide_.
        render(graph, 480);
    }
};

/**
 * A sine through an Amp to the output, with a held envelope ready to patch into its `mod`.
 *
 * The envelope is ModPatch's: one segment that rises at once and parks, so what it is worth
 * is a level rather than a moment in a shape. Levels are kept below 0.4 through Out, whose
 * limiter is transparent only down there -- see ModPatch.
 */
struct AmpRig {
    Graph graph;
    AmpRig() {
        graph.setSampleRate(48000);
        graph.postAdd(5, NodeType::Drone);
        graph.postAdd(1, NodeType::Osc);
        graph.postAdd(2, NodeType::Amp);
        graph.postAdd(3, NodeType::Out);
        graph.postAdd(4, NodeType::Env);
        graph.postSetStep(5, 0, 0, true); // one note, held for the whole test
        graph.postSetParam(1, 0, 3.0f);   // a sine: a saw's own reset is a step, and a big one
        graph.postSetSegment(4, 0, 0.001f, 0.002f, 0.0f, true);
        graph.postSetSegment(4, 1, 0.0f, 0.0f, 0.0f, false);
        graph.postSetSegment(4, 2, 0.0f, 0.0f, 0.0f, false);
        graph.postConnect(5, 0, 1, 0);
        graph.postConnect(5, 0, 4, 0);
        graph.postConnect(1, 0, 2, 0);
        graph.postConnect(2, 0, 3, 0);
        // What GraphSync sends every new node: all of its knobs.
        graph.postSetParam(2, 0, 0.2f);
        graph.applyCommands();
        render(graph, 64);
    }
    void knob(float gain) {
        graph.postSetParam(2, 0, gain);
        graph.applyCommands();
    }
    void level(float value) {
        graph.postSetSegment(4, 0, 0.001f, value, 0.0f, true);
        graph.applyCommands();
        render(graph, 480); // past the 5ms glide and any 30ms fade
    }
    void patch() {
        graph.postConnect(4, 0, 2, 1);
        graph.applyCommands();
        render(graph, 64);
    }
    /** How loud the output is, over more than a cycle of the sine. */
    float loudness() { return peakOf(render(graph, 16)); }
};

/**
 * With nothing in `mod`, an Amp's knob is its gain -- and nothing else is, because the port
 * is not a second gain any more.
 *
 * Until format 15 the port read as 1.0 when idle and multiplied the knob, which was right on
 * its own and wrong beside an exposed gain: the same envelope patched into both was applied
 * twice. What arrives on the port now is the gain itself, the knob while it is idle.
 */
void anAmpsKnobIsItsGainWithNothingPatched() {
    std::printf("an amp's knob is its gain with nothing patched\n");
    AmpRig rig;
    const float at02 = rig.loudness();
    check(at02 > 0.05f, "an amp with nothing patched passes its audio");
    rig.knob(0.1f);
    render(rig.graph, 4);
    const float at01 = rig.loudness();
    check(std::fabs(at02 / at01 - 2.0f) < 0.1f,
          "and half the knob is half as loud, " + std::to_string(at02) + " against " +
                  std::to_string(at01));
    check(std::fabs(rig.graph.paramOf(2, 0) - 0.1f) < 0.001f, "and reports the knob as its gain");
}

/**
 * Patched, `mod` sweeps the gain between its brackets, every sample: the low bracket at 0,
 * the high one at 1. The low bracket is a floor and not silence -- a tremolo that never
 * closes is two brackets, where the port on its own could only ever sweep from nothing.
 */
void aPatchedAmpSweepsItsGainBetweenItsBrackets() {
    std::printf("a patched amp sweeps its gain between its brackets\n");
    AmpRig rig;
    rig.graph.postSetModRange(2, 0, 0.1f, 0.3f, false);
    rig.graph.applyCommands();
    rig.patch();

    rig.level(1.0f);
    check(std::fabs(rig.graph.paramOf(2, 0) - 0.3f) < 0.002f, "full modulation is the high bracket");
    const float top = rig.loudness();
    rig.level(0.0f);
    check(std::fabs(rig.graph.paramOf(2, 0) - 0.1f) < 0.002f, "none is the low bracket, not silence");
    const float floor = rig.loudness();
    rig.level(0.5f);
    check(std::fabs(rig.graph.paramOf(2, 0) - 0.2f) < 0.002f, "and half is halfway between");
    check(std::fabs(top / floor - 3.0f) < 0.15f,
          "and the audio follows the gain, " + std::to_string(top) + " against " +
                  std::to_string(floor));
    // The knob is not in it while something is patched: the brackets are the whole story.
    rig.knob(1.5f);
    render(rig.graph, 4);
    check(std::fabs(rig.graph.paramOf(2, 0) - 0.2f) < 0.002f, "and the knob waits for the cable to go");
}

/**
 * A range that never arrived sweeps from nothing up to the knob -- `in * mod * gain`, the VCA
 * this was before it had brackets. The interface always sends one, so this is the graph's
 * own fallback agreeing with PatchModule.drivenRange rather than a case the app reaches.
 */
void anAmpWithNoRangeSweepsFromNothingToItsKnob() {
    std::printf("an amp with no range sweeps from nothing to its knob\n");
    AmpRig rig;
    rig.knob(0.4f);
    rig.patch();
    rig.level(0.5f);
    check(std::fabs(rig.graph.paramOf(2, 0) - 0.2f) < 0.002f, "half an envelope on a 0.4 knob is 0.2");
    rig.level(0.0f);
    check(rig.graph.paramOf(2, 0) < 0.001f, "and a closed envelope closes it");
}

/**
 * Patching fades from the knob into the sweep, and unpatching fades back to it -- the graph's
 * ordinary 30ms crossfade, over the gain rather than over the signal. Without it, patching a
 * closed envelope into an open Amp drops a full sine to nothing in one sample.
 */
void patchingAndUnpatchingAnAmpFade() {
    std::printf("patching and unpatching an amp fade\n");
    AmpRig rig;
    rig.level(0.0f); // parked shut, so the patch takes the gain from the knob to nothing
    const float steady = maxStep(render(rig.graph, 64));

    auto joined = render(rig.graph, 2);
    rig.graph.postConnect(4, 0, 2, 1);
    rig.graph.applyCommands();
    const auto fading = render(rig.graph, 45); // 1440 frames: the whole crossfade
    joined.insert(joined.end(), fading.begin(), fading.end());
    check(maxStep(joined) < 1.5f * steady,
          "patching fades rather than steps, " + std::to_string(maxStep(joined)) + " against " +
                  std::to_string(steady));
    check(rig.loudness() < 0.001f, "and lands on the envelope, which is shut");

    auto back = render(rig.graph, 2);
    rig.graph.postDisconnect(4, 0, 2, 1);
    rig.graph.applyCommands();
    const auto opening = render(rig.graph, 45);
    back.insert(back.end(), opening.begin(), opening.end());
    check(maxStep(back) < 1.5f * steady,
          "unpatching fades back, " + std::to_string(maxStep(back)) + " against " +
                  std::to_string(steady));
    check(std::fabs(rig.graph.paramOf(2, 0) - 0.2f) < 0.001f, "and lands on the knob");
}

/**
 * The graph hands every node where the transport is (Node::setTiming), and a synced LFO reads
 * its phase off it: a saw at one cycle a beat reads how far through the beat the transport
 * is, from the top after a reset. Watched through the knob it sweeps, since that is where
 * anyone would see it.
 */
void aSyncedLfoFollowsTheTransportsBeat() {
    std::printf("a synced lfo follows the transport's beat\n");
    Graph graph;
    graph.setSampleRate(48000);
    graph.postAdd(2, NodeType::Lfo);
    graph.postAdd(3, NodeType::Mix);
    graph.postSetParam(2, 1, 0.0f); // saw, whose value is its phase
    graph.postSetParam(2, 2, 2.0f); // a quarter: a cycle a beat
    graph.postSetModRange(3, 0, 0.0f, 1.0f, false);
    graph.postConnectMod(2, 0, 3, 0);
    graph.postSetTempo(120.0f);
    graph.applyCommands();
    render(graph, 64); // stopped, and past the cable's fade-in

    auto apart = [](double a, double b) {
        const double d = std::fabs(a - b);
        return std::min(d, 1.0 - d);
    };
    const double blockBeats = kBlockSize * 2.0 / 48000.0;
    graph.setTransportRunning(true);
    int checked = 0;
    for (int b = 0; b < 3000; ++b) {
        graph.process(kBlockSize);
        if (b % 211 != 0) continue;
        const double start = graph.transportBeat() - blockBeats;
        const double phase = start - std::floor(start);
        check(apart(graph.paramOf(3, 0), phase) < 0.01,
              "the knob is where the beat is, at beat " + std::to_string(start));
        ++checked;
    }
    check(checked > 10, "and was looked at across many beats");

    graph.postResetTransport();
    graph.applyCommands();
    graph.process(kBlockSize);
    check(graph.paramOf(3, 0) < 0.01, "a reset starts its cycle again from the top");
}

/**
 * A delay synced to the transport keeps time while the transport is stopped, at the tempo the
 * graph was given -- which only works because the graph hands every node the tempo as well as
 * the running rate (Node::setTiming). The running rate is zero while stopped, and a node that
 * read it would make an eighth no length at all.
 */
void aSyncedDelayKeepsTheGraphsTempoWhileStopped() {
    std::printf("a synced delay keeps the graph's tempo while stopped\n");
    Graph graph;
    graph.setSampleRate(48000);
    graph.postAdd(1, NodeType::In);
    graph.postAdd(2, NodeType::Delay);
    graph.postAdd(3, NodeType::Out);
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(2, 0, 3, 0);
    graph.postSetParam(1, 0, 1.0f);  // the input at unity
    graph.postSetParam(2, 0, 3.0f);  // an eighth
    graph.postSetParam(2, 2, 0.0f);  // one echo
    graph.postSetParam(2, 3, 1.0f);  // and only the echo
    graph.postSetParam(3, 0, 0.25f); // well under the limiter
    graph.postSetTempo(90.0f);
    graph.applyCommands();
    render(graph, 64); // past every cable's fade-in, so the impulse meets a settled graph

    std::array<float, kBlockSize> click{};
    click[0] = 1.0f;
    const std::array<float, kBlockSize> quiet{};
    std::vector<float> out;
    for (int b = 0; b < 48000 / kBlockSize; ++b) {
        graph.setLiveInput(b == 0 ? click.data() : quiet.data());
        graph.process(kBlockSize);
        out.insert(out.end(), graph.outputL(), graph.outputL() + kBlockSize);
    }
    std::size_t at = 0;
    for (std::size_t i = 0; i < out.size(); ++i) {
        if (std::fabs(out[i]) > std::fabs(out[at])) at = i;
    }
    // Half a beat at 90bpm is a third of a second.
    check(at == 16000, "an eighth at 90bpm, stopped, lands at 16000, got " + std::to_string(at));
}

/**
 * Two note sources into one input, and a disconnect that names only one of them.
 *
 * Through a poly subpatch, because that is where two notes sound at once now: every synth
 * is monophonic, so merging two sequencers into an Osc would be the two of them fighting
 * over one voice. The merge itself is the graph's and is the same either way -- what the
 * subpatch adds is somewhere for the second note to go.
 *
 * [instances] of an Osc behind a PolyIn, summed, exactly as GraphSync flattens one.
 */
void polyRig(Graph &graph, int instances) {
    graph.postAdd(kEdge, NodeType::PolyIn);
    graph.postAdd(kSumNode, NodeType::PolySum);
    graph.postAdd(kOutNode, NodeType::Out);
    graph.postSetParam(kEdge, 0, static_cast<float>(instances));
    for (int k = 0; k < instances; ++k) {
        graph.postAdd(kFirstOsc + k, NodeType::Osc);
        graph.postConnect(kEdge, k, kFirstOsc + k, 0);
        graph.postConnect(kFirstOsc + k, 0, kSumNode, k);
    }
    graph.postConnect(kSumNode, 0, kOutNode, 0);
    // Below the limiter's knee, so two notes measure as two rather than as one squashed.
    graph.postSetParam(kOutNode, 0, 0.2f);
}

void twoSequencersMergeIntoOnePolySubpatch() {
    std::printf("two sequencers merge into one poly subpatch\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Steps);
    graph.postAdd(2, NodeType::Steps);
    polyRig(graph, 2);
    graph.postConnect(1, 0, kEdge, 0);
    graph.postConnect(2, 0, kEdge, 0); // the same input: a note input merges rather than replaces
    graph.postSetParam(2, 1, 700.0f);  // a fifth up, so the two are not the same note
    graph.postSetTempo(300.0f);        // and fast, so both keep starting notes throughout
    graph.applyCommands();
    graph.setTransportRunning(true);

    render(graph, 64);
    const auto both = render(graph, 256);
    const float withBoth = energy(both.data(), static_cast<int32_t>(both.size()));
    check(withBoth > 0.0f, "both sequencers reach the subpatch");

    // One cable, named at both ends. The other sequencer is patched to the same port and
    // must play on -- which is the whole reason a disconnect names its source.
    graph.postDisconnect(1, 2, kEdge, 0);
    graph.applyCommands();
    // Past everything the unpatched one left sounding, so what is measured is the other
    // sequencer still starting notes rather than the first one fading out. A disconnect
    // that took both sources with it reads as silence here.
    render(graph, 2000);
    const auto remaining = render(graph, 256);
    const float withOne = energy(remaining.data(), static_cast<int32_t>(remaining.size()));

    check(withOne > 0.25f * withBoth, "unpatching one leaves the other sounding");
    check(withOne < withBoth, "and takes only its own notes with it");
}

/**
 * An id belongs to the source that chose it, all the way through the graph.
 *
 * Two sequencers both counting their note ids from one: the second's first Off must not end
 * the first's first note. The boundary of a poly subpatch is where that is decided now --
 * PolyIn finds the instance holding a note by id *and* source -- so this is the end-to-end
 * check of what a node test pins in isolation.
 */
void anIdIsOnlyUniqueToItsOwnSource() {
    std::printf("an id is only unique to its own source\n");
    Graph graph;
    graph.setSampleRate(48000);

    graph.postAdd(1, NodeType::Steps);
    graph.postAdd(2, NodeType::Steps);
    polyRig(graph, 2);
    graph.postConnect(1, 0, kEdge, 0);
    graph.postConnect(2, 0, kEdge, 0);
    graph.postSetParam(1, 2, 1.0f); // half notes: one long note held across many short ones
    graph.postSetParam(2, 2, 5.0f); // 1/32, starting and ending inside it over and over
    graph.postSetParam(2, 1, 700.0f);
    graph.postSetTempo(240.0f);
    graph.applyCommands();
    graph.setTransportRunning(true);

    // Into the half note, past its first 1/32 companion.
    render(graph, 100);
    // Every block from here is inside the long note, and many 1/32 gaps fall in it. If the
    // short sequencer's Off had ended the long note -- both are counting their ids from one,
    // and only the source they are tagged with tells them apart -- those gaps would be
    // silent.
    bool everSilent = false;
    for (int i = 0; i < 150; ++i) {
        graph.process(kBlockSize);
        if (nearSilent(graph.outputL(), kBlockSize)) everSilent = true;
    }
    check(!everSilent, "the long note survives the short one's offs");
}

/**
 * A poly subpatch, as the interface flattens one: a note edge, two copies of what is
 * inside, and a summing node.
 *
 * Built by hand here because that is exactly what GraphSync sends -- the engine is handed a
 * flat graph and never learns a subpatch existed. What this checks is that the flat graph
 * *works*: that two notes down one cable end up on two different copies, sounding at the
 * same time, and that both reach the output through the sum.
 *
 * Each copy is an Osc with no envelope and an Amp, so each instance is a voice the way one
 * would actually be built; the Amps are left wide open, a knob of 1 with nothing in `mod`.
 */
void aFlattenedPolySubpatchSoundsTwoNotesAtOnce() {
    std::printf("a flattened poly subpatch sounds two notes at once\n");
    Graph graph;
    graph.setSampleRate(48000);

    constexpr int64_t kDrone = 1;
    constexpr int64_t kEdge = 2;
    constexpr int64_t kSum = 3;
    constexpr int64_t kOut = 4;
    // Instance k's two nodes. The interface's numbering is the module's id with the
    // instance in the high bits; any two distinct ids do the same job here.
    const int64_t osc[2] = {10, 20};
    const int64_t amp[2] = {11, 21};

    graph.postAdd(kDrone, NodeType::Drone);
    graph.postAdd(kEdge, NodeType::PolyIn);
    graph.postAdd(kSum, NodeType::PolySum);
    graph.postAdd(kOut, NodeType::Out);
    graph.postSetParam(kEdge, 0, 2.0f); // two voices
    graph.postConnect(kDrone, 0, kEdge, 0);
    for (int k = 0; k < 2; ++k) {
        graph.postAdd(osc[k], NodeType::Osc);
        graph.postAdd(amp[k], NodeType::Amp);
        graph.postConnect(kEdge, k, osc[k], 0);
        graph.postConnect(osc[k], 0, amp[k], 0);
        graph.postConnect(amp[k], 0, kSum, k);
        graph.postSetParam(amp[k], 0, 1.0f); // as GraphSync sends every knob of a new node
    }
    graph.postConnect(kSum, 0, kOut, 0);
    graph.postSetParam(kOut, 0, 0.2f); // below the limiter, so two voices measure as two
    graph.applyCommands();

    graph.postSetStep(kDrone, 0, 0, true); // one note held
    graph.applyCommands();
    render(graph, 40);
    const auto one = render(graph, 128);
    const float withOne = energy(one.data(), static_cast<int32_t>(one.size()));
    check(withOne > 0.0f, "one note reaches the output through the sum");

    graph.postSetStep(kDrone, 1, 7, true); // a fifth above it, on a second cell
    graph.applyCommands();
    render(graph, 40);
    const auto two = render(graph, 128);
    const float withTwo = energy(two.data(), static_cast<int32_t>(two.size()));
    // A quarter more, not twice: energy() here is the mean absolute value, and two tones
    // at unrelated frequencies add to about the root of two rather than to two. What the
    // margin has to exclude is the second note stealing the first's instance, which would
    // leave this unchanged.
    check(withTwo > 1.25f * withOne,
          "and a second note takes the other instance rather than stealing the first, " +
                  std::to_string(withTwo) + " against " + std::to_string(withOne));

    // Ending the first note leaves the second sounding: the edge sent each Off to the
    // instance holding that note, which is the whole of what it is for.
    graph.postSetStep(kDrone, 0, 0, false);
    graph.applyCommands();
    render(graph, 200);
    const auto rest = render(graph, 128);
    const float left = energy(rest.data(), static_cast<int32_t>(rest.size()));
    check(left > 0.5f * withOne && left < 1.5f * withOne, "one off leaves one note sounding");
}

void aModulatorDrivesAParameterAcrossItsRange() {
    std::printf("a modulator drives a parameter across its range\n");
    ModPatch m;
    m.graph.postSetParam(2, 0, 0.0f);
    m.graph.postSetModRange(2, 0, 0.1f, 0.3f, false);
    m.graph.postConnectMod(4, 0, 2, 0);
    m.level(0.0f);

    render(m.graph, 200); // past the fade in
    const float low = peakOf(render(m.graph, 64));
    m.level(1.0f);
    render(m.graph, 200);
    const float high = peakOf(render(m.graph, 64));

    check(low > 0.07f && low < 0.13f, "a modulator at nothing sits on the low end");
    check(high > 0.25f && high < 0.35f, "and at full on the high end");
    check(std::fabs(high / low - 3.0f) < 0.3f, "in the parameter's own units, not the modulator's");
}

void anExponentialRangeSweepsGeometrically() {
    std::printf("an exponential range sweeps geometrically\n");
    ModPatch m;
    m.graph.postSetModRange(2, 0, 0.1f, 0.4f, true);
    m.graph.postConnectMod(4, 0, 2, 0);
    m.level(0.5f);
    render(m.graph, 200);
    const float half = peakOf(render(m.graph, 64));

    // Halfway between 0.1 and 0.4 is 0.2 geometrically and 0.25 linearly. A cutoff knob is
    // exponential because hearing is, and a sweep across it has to be the same shape.
    check(std::fabs(half - 0.2f) < 0.02f, "halfway is the geometric middle of the range");
}

void aKnobMovedUnderAModulatorWaitsForItToLetGo() {
    std::printf("a knob moved under a modulator waits for it to let go\n");
    ModPatch m;
    m.graph.postSetParam(2, 0, 0.0f);
    m.graph.postSetModRange(2, 0, 0.0f, 0.5f, false);
    m.graph.postConnectMod(4, 0, 2, 0);
    m.level(1.0f);
    render(m.graph, 200);

    // Moved while modulated, to a value it has never had -- so returning to it can only
    // mean the move was remembered, where returning to zero would pass whether it was or not.
    m.graph.postSetParam(2, 0, 0.3f);
    m.graph.applyCommands();
    const float held = peakOf(render(m.graph, 64));
    check(held > 0.4f, "the modulated value holds while the knob moves");

    m.graph.postDisconnectMod(4, 0, 2, 0);
    m.graph.applyCommands();
    render(m.graph, 200);
    const float released = peakOf(render(m.graph, 64));
    check(released > 0.25f && released < 0.35f, "and unpatching returns it to where the knob now is");
}

void patchingAModulatorFadesRatherThanJumps() {
    std::printf("patching a modulator fades rather than jumps\n");
    ModPatch m;
    m.graph.postSetParam(2, 0, 0.1f);
    m.graph.postSetModRange(2, 0, 0.6f, 0.6f, false);
    m.level(0.0f);
    render(m.graph, 200);

    // 30ms is 1454 samples, nearly six windows. Measured, a fade in reads 0.14, 0.21, 0.34,
    // 0.45, 0.53 and then holds at 0.54 -- the limiter's reading of 0.6 -- and a fade out
    // mirrors it. Without a fade every window after the patch is already at its end, so
    // "partway" is judged against both ends rather than against a number.
    m.graph.postConnectMod(4, 0, 2, 0);
    m.graph.applyCommands();
    const auto in = levels(m.graph, 8);
    check(in[0] < 0.25f, "the first window after patching is still near the knob");
    check(in[3] > in[0] + 0.1f && in[3] < in[7] - 0.05f, "partway through, it is partway there");
    check(in[7] > 0.5f, "and it arrives");

    m.graph.postDisconnectMod(4, 0, 2, 0);
    m.graph.applyCommands();
    const auto out = levels(m.graph, 8);
    check(out[0] > 0.45f, "the first window after unpatching is still near the modulator");
    check(out[3] < out[0] - 0.1f && out[3] > out[7] + 0.1f, "partway back, it is partway back");
    check(out[7] < 0.15f, "and it arrives back at the knob");
}

void aModulatorIsEvaluatedBeforeTheKnobItTurns() {
    std::printf("a modulator is evaluated before the knob it turns\n");
    Graph graph;
    graph.setSampleRate(48000);
    // Slots chosen so nothing else orders them: the gain takes a low slot and the envelope
    // that modulates it a high one, so without the modulation edge the gain is ready, and
    // emitted, before the envelope has been reached.
    graph.postAdd(1, NodeType::Osc);
    graph.postAdd(2, NodeType::Mix);
    graph.postAdd(3, NodeType::Out);
    graph.postAdd(4, NodeType::Steps);
    graph.postAdd(5, NodeType::Env);
    graph.postAdd(6, NodeType::Drone);

    // A steady sine to be gated, held by a drone since nothing drones by itself.
    graph.postSetStep(6, 0, 0, true);
    graph.postSetParam(1, 0, 3.0f);
    graph.postSetParam(1, 1, 0.001f);
    graph.postSetParam(1, 2, 0.001f);
    graph.postSetParam(1, 3, 1.0f);
    graph.postConnect(6, 0, 1, 0);
    graph.postConnect(1, 0, 2, 0);
    graph.postConnect(2, 0, 3, 0);

    // The sequencer rests on its first step and sounds on its second, so the envelope --
    // and with it the gain -- is shut until that second step lands.
    graph.postSetStep(4, 0, 0, false);
    graph.postSetStep(4, 1, 0, true);
    graph.postSetParam(5, 0, 0.001f); // an instant attack, so the note is heard at once
    graph.postConnect(4, 0, 5, 0);
    graph.postSetParam(2, 0, 0.0f);
    graph.postSetModRange(2, 0, 0.0f, 1.0f, false);
    graph.postConnectMod(5, 0, 2, 0);
    graph.postSetTempo(300.0f);
    graph.applyCommands();
    graph.setTransportRunning(true);

    // A 1/8 at 300bpm is 4800 frames, exactly 150 blocks: the rest on the first step holds
    // the gain shut until the second lands on the first sample of block 150.
    render(graph, 150);
    check(nearSilent(graph.outputL(), kBlockSize), "shut while the first step rests");
    graph.process(kBlockSize);
    // Evaluated after the envelope, the gain hears the new note in the block it lands.
    // Before, it would read the previous block's value and stay shut for one more.
    check(!nearSilent(graph.outputL(), kBlockSize), "open in the very block the step lands");
}

void aDeletedModulatorHandsTheKnobBack() {
    std::printf("a deleted modulator hands the knob back\n");
    ModPatch m;
    m.graph.postSetParam(2, 0, 0.0f);
    m.graph.postSetModRange(2, 0, 0.5f, 0.5f, false);
    m.graph.postConnectMod(4, 0, 2, 0);
    m.graph.applyCommands();
    render(m.graph, 200);
    check(peakOf(render(m.graph, 64)) > 0.35f, "modulated open");

    m.graph.postRemove(4);
    m.graph.applyCommands();
    const float first = levels(m.graph, 1)[0];
    render(m.graph, 2000); // past the fade and the reap
    // The node lingers to render its own fade out; cut instead, the first window is shut.
    check(first > 0.35f, "deleting it fades rather than cuts");
    check(nearSilent(m.graph.outputL(), kBlockSize), "back to the knob, which is shut");

    // The slot it left is reused by whatever comes next, and must not still be modulating.
    m.graph.postAdd(5, NodeType::Steps);
    m.graph.postSetParam(5, 1, 1200.0f);
    m.graph.applyCommands();
    render(m.graph, 200);
    check(nearSilent(m.graph.outputL(), kBlockSize), "and a node reusing its slot inherits nothing");
}

void aModulatorPastFullStopsAtTheEndOfTheRange() {
    std::printf("a modulator past full stops at the end of the range\n");
    ModPatch m;
    m.graph.postSetModRange(2, 0, 0.1f, 0.3f, false);
    m.graph.postConnectMod(4, 0, 2, 0);
    m.level(2.0f); // twice full scale
    render(m.graph, 200);
    const float over = peakOf(render(m.graph, 64));
    m.level(-1.0f);
    render(m.graph, 200);
    const float under = peakOf(render(m.graph, 64));

    // Unclamped, twice full scale would land on 0.5, past the end the range was given, and
    // minus one on -0.1, which the VCA would shut on. A modulator says how far along a
    // range; it does not get to say "further than the end".
    check(over > 0.25f && over < 0.35f, "twice full scale is still the high end");
    check(under > 0.05f && under < 0.15f, "and below nothing is still the low end");
}

void aNoteOutputCannotModulate() {
    std::printf("a note output cannot modulate\n");
    ModPatch m;
    m.graph.postSetParam(2, 0, 0.5f);
    m.graph.postSetModRange(2, 0, 0.0f, 0.0f, false);
    m.graph.postConnectMod(4, 2, 2, 0); // Steps' notes output
    m.graph.applyCommands();
    render(m.graph, 200);
    // Accepted, it would pin the VCA at its low end of zero, reading a buffer nobody writes.
    check(peakOf(render(m.graph, 64)) > 0.35f, "the knob keeps its own value");
}

void theGraphReportsWhereAModulatedParameterHasGot() {
    std::printf("the graph reports where a modulated parameter has got\n");
    ModPatch m;
    m.graph.postSetModRange(2, 0, 0.1f, 0.3f, false);
    m.graph.postConnectMod(4, 0, 2, 0);
    m.level(1.0f);
    render(m.graph, 200);
    check(std::fabs(m.graph.paramOf(2, 0) - 0.3f) < 0.001f, "a modulated parameter reports its value");

    m.level(0.5f);
    render(m.graph, 200);
    check(std::fabs(m.graph.paramOf(2, 0) - 0.2f) < 0.001f, "and follows its modulator");

    check(std::isnan(m.graph.paramOf(99, 0)), "an id that is not here reports nothing");
    m.graph.postRemove(2);
    m.graph.applyCommands();
    check(std::isnan(m.graph.paramOf(2, 0)), "nor does one that has been deleted");
}

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
    aDroneFollowsTheScaleThroughTheGraph();
    aNewDestinationHearsWhatADroneIsAlreadyHolding();
    aNoteStartingAsItsCableConnectsStartsOnce();
    aNoteCableSoundsAndOrdersTheGraph();
    notesAndSignalsDoNotPatchToEachOther();
    aRemovedSourceEndsTheNotesItStarted();
    twoSequencersMergeIntoOnePolySubpatch();
    anIdIsOnlyUniqueToItsOwnSource();
    anAmpsKnobIsItsGainWithNothingPatched();
    aPatchedAmpSweepsItsGainBetweenItsBrackets();
    anAmpWithNoRangeSweepsFromNothingToItsKnob();
    patchingAndUnpatchingAnAmpFade();
    aSyncedDelayKeepsTheGraphsTempoWhileStopped();
    aSyncedLfoFollowsTheTransportsBeat();
    aFlattenedPolySubpatchSoundsTwoNotesAtOnce();
    aModulatorDrivesAParameterAcrossItsRange();
    anExponentialRangeSweepsGeometrically();
    aKnobMovedUnderAModulatorWaitsForItToLetGo();
    patchingAModulatorFadesRatherThanJumps();
    aModulatorIsEvaluatedBeforeTheKnobItTurns();
    aDeletedModulatorHandsTheKnobBack();
    aModulatorPastFullStopsAtTheEndOfTheRange();
    aNoteOutputCannotModulate();
    theGraphReportsWhereAModulatedParameterHasGot();

    std::printf("\n%d checks, %d failed\n", checks, failures);
    std::fflush(stdout);
    return failures == 0 ? 0 : 1;
}
