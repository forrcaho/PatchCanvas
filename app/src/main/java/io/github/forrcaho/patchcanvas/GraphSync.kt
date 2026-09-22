package io.github.forrcaho.patchcanvas

import android.util.Log

/**
 * Node type ids, mirroring the enum in nodes.h. The numbering is part of the JNI
 * contract, so append rather than reorder.
 */
enum class NodeType(val id: Int) {
    Unknown(0),
    // 1 was the monophonic Osc, retired when polyphony moved inside the synths and the
    // polyphonic one took its name. Polyphony has since moved out again, into the poly
    // subpatch, and Osc is monophonic once more -- but an id is retired for good. As with
    // 7 and 8.
    Filter(2),
    Env(3),
    Steps(4),
    Out(5),
    In(6),
    // 7 was Vca, retired with CV: its entire reason was a control-voltage input, and a
    // gain with a modulatable level is what Mix already is.
    // 8 was Clock, retired when the transport replaced it.
    Mix(9),
    /** The oscillator, called Voice for as long as a second, monophonic Osc existed. */
    Osc(10),
    Lfo(11),
    Drone(12),
    Pluck(13),
    Fm(14),
    Sf(15),
    Seq(16),
    Chance(17),
    Chord(18),
    Arp(19),
    Euclid(20),
    /** The VCA, back. Id 7 stays retired: that module took a control voltage. */
    Amp(21),
    /**
     * A poly subpatch's two edges: the note input shared out one per instance, and the
     * instance outputs summed back into one. Neither is a module -- nothing offers them in
     * a menu and no file names them. See Patch.engineGraph.
     */
    PolyIn(22),
    PolySum(23);

    companion object {
        fun of(type: ModuleType): NodeType = when (type.name) {
            "Osc" -> Osc
            "Filter" -> Filter
            "Env" -> Env
            "Steps" -> Steps
            "Out" -> Out
            "In" -> In
            "Mix" -> Mix
            "LFO" -> Lfo
            "Drone" -> Drone
            "Pluck" -> Pluck
            "FM" -> Fm
            "SF" -> Sf
            "Seq" -> Seq
            "Chance" -> Chance
            "Chord" -> Chord
            "Arp" -> Arp
            "Euclid" -> Euclid
            "Amp" -> Amp
            else -> Unknown
        }
    }
}

/**
 * Where graph commands go. An interface only so the diff below can be tested without a
 * device: the ordering it produces is the kind of thing that is silently wrong, and
 * welding it to JNI would leave nothing able to observe it.
 */
interface GraphCommands {
    fun addNode(id: Long, type: NodeType)
    fun removeNode(id: Long)
    fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int)
    /**
     * One cable, named at both ends.
     *
     * The source is named because a note input takes several of them: a disconnect that
     * only said which port would take the other sequencer with it.
     */
    fun disconnect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int)
    fun setParam(id: Long, index: Int, value: Float)
    /**
     * What an exposed parameter sweeps between, in its own units, and whether geometrically.
     * There is no clearing one: a parameter nothing modulates ignores its range.
     */
    fun setModRange(id: Long, index: Int, low: Float, high: Float, exponential: Boolean)
    /** A cable onto parameter [index] of [dstId]. A parameter takes one, so this replaces. */
    fun connectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int)
    fun disconnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int)
    /** One step of a sequence, as a degree: the engine resolves it against the scale list. */
    fun setStep(id: Long, index: Int, degree: Int, gate: Boolean)
    /** The patch's scale list, whole, with each entry's length worked out in beats. */
    fun setScales(entries: List<ScaleEntry>, beatsPerBar: Int)
    /** The transport's rate, in beats per minute. */
    fun setTempo(bpm: Float)
    /** Gives SF node [id] a synth over the loaded font [font], a native handle. */
    fun setFont(id: Long, font: Long)
    /** Dot [slot] of dot sequencer [id]; a length of 0 clears the slot. */
    fun setDot(id: Long, slot: Int, step: Int, degree: Int, length: Int, velocity: Float)

    /** Segment [slot] of envelope [id]; a time of 0 clears the slot. */
    fun setSegment(id: Long, slot: Int, time: Float, level: Float, curve: Float, sustain: Boolean)
    fun collectGarbage()
}

/**
 * The real one.
 *
 * Debug builds log every command that crosses, because "does this gesture disturb the
 * audio graph?" is otherwise answered by guessing. Anything that changes a cable makes a
 * 30ms crossfade, so a command arriving when none was expected is audible.
 */
object EngineCommands : GraphCommands {
    private const val TAG = "PatchSync"

    private inline fun trace(what: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, what())
    }

    override fun addNode(id: Long, type: NodeType) {
        trace { "add $id $type" }
        AudioEngine.addNode(id, type)
    }

    override fun removeNode(id: Long) {
        trace { "remove $id" }
        AudioEngine.removeNode(id)
    }

    override fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        trace { "connect $srcId[$srcPort] -> $dstId[$dstPort]" }
        AudioEngine.connect(srcId, srcPort, dstId, dstPort)
    }

    override fun disconnect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        trace { "disconnect $srcId[$srcPort] -> $dstId[$dstPort]" }
        AudioEngine.disconnect(srcId, srcPort, dstId, dstPort)
    }

    override fun setParam(id: Long, index: Int, value: Float) {
        trace { "param $id[$index] = $value" }
        AudioEngine.setParam(id, index, value)
    }

    override fun setModRange(id: Long, index: Int, low: Float, high: Float, exponential: Boolean) {
        trace { "range $id[$index] = $low..$high${if (exponential) " exp" else ""}" }
        AudioEngine.setModRange(id, index, low, high, exponential)
    }

    override fun connectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int) {
        trace { "modulate $srcId[$srcPort] -> $dstId.param[$index]" }
        AudioEngine.connectMod(srcId, srcPort, dstId, index)
    }

    override fun disconnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int) {
        trace { "unmodulate $srcId[$srcPort] -> $dstId.param[$index]" }
        AudioEngine.disconnectMod(srcId, srcPort, dstId, index)
    }

    override fun setStep(id: Long, index: Int, degree: Int, gate: Boolean) {
        trace { "step $id[$index] = degree $degree ${if (gate) "on" else "rest"}" }
        AudioEngine.setStep(id, index, degree, gate)
    }

    override fun setScales(entries: List<ScaleEntry>, beatsPerBar: Int) {
        trace {
            "scales " + entries.joinToString {
                "${it.scale.name} at ${it.rootCents}c for ${it.lengthInBeats(beatsPerBar)}"
            }
        }
        AudioEngine.setScales(entries, beatsPerBar)
    }

    override fun setTempo(bpm: Float) {
        trace { "tempo $bpm" }
        AudioEngine.setTempo(bpm)
    }

    override fun setDot(id: Long, slot: Int, step: Int, degree: Int, length: Int, velocity: Float) {
        trace { "dot $id[$slot] = step $step degree $degree for $length at $velocity" }
        AudioEngine.setDot(id, slot, step, degree, length, velocity)
    }

    override fun setSegment(
        id: Long, slot: Int, time: Float, level: Float, curve: Float, sustain: Boolean,
    ) {
        trace { "seg $id[$slot] = ${time}s to $level curve $curve${if (sustain) " sustain" else ""}" }
        AudioEngine.setSegment(id, slot, time, level, curve, sustain)
    }

    override fun setFont(id: Long, font: Long) {
        trace { "font $id = $font" }
        AudioEngine.setNodeFont(id, font)
    }

    override fun collectGarbage() { AudioEngine.collectGarbage() }
}

/**
 * Keeps the audio graph tracking the patch.
 *
 * Diffing a shadow copy rather than emitting commands at each mutation point, because
 * the same code then handles every way a patch can change -- an edit, a file loaded at
 * launch, and eventually an undo -- instead of each needing its own hook that can be
 * forgotten.
 *
 * Commands are ordered so the graph is never asked to reference something that is not
 * there yet: drop cables, then nodes, then add nodes, then make cables.
 */
class GraphSync(private val commands: GraphCommands = EngineCommands) {

    private var syncedNodes = emptyMap<Long, NodeType>()
    private var syncedCables = emptySet<Connection>()
    private var syncedParams = emptyMap<Long, List<Float>>()
    private var syncedRanges = emptyMap<Long, Map<Int, ModRange>>()
    private var syncedSteps = emptyMap<Long, List<Step>>()
    private var syncedScales: List<ScaleEntry>? = null
    private var syncedBeatsPerBar: Int? = null
    private var syncedTempo: Float? = null
    private var syncedFonts = emptyMap<Long, Long>()
    private var syncedDots = emptyMap<Long, List<Dot>>()
    private var syncedSegments = emptyMap<Long, List<EnvSegment>>()

    /**
     * One slot-indexed list, diffed against what the engine was last told, and the new
     * shadow to keep.
     *
     * Steps, dots and segments were three near-identical copies of this, and the copies had
     * already drifted: the steps one compared against `syncedSteps[id]` where the other two
     * compared against null for a node in [fresh]. Unreachable, because a module's type is a
     * `val` and an id is never reused by a different type -- but it is the drift, not the
     * bug, that is the point. A fourth list would have been a fourth copy and a fourth
     * chance, and the ones before it each cost a real defect somewhere on this path.
     *
     * [applies] picks the modules that have this list; [clear] is null for a fixed-length
     * one, whose slots are never vacated.
     */
    private fun <T> diffSlots(
        sounding: List<EngineNode>,
        fresh: Set<Long>,
        previousAll: Map<Long, List<T>>,
        applies: (ModuleType) -> Boolean,
        read: (PatchModule) -> List<T>,
        send: (Long, Int, T) -> Unit,
        clear: ((Long, Int) -> Unit)?,
    ): Map<Long, List<T>> {
        val now = sounding.filter { it.module?.type?.let(applies) == true }
            .associate { it.id to read(it.module!!) }
        now.forEach { (id, list) ->
            // Everything for a node that was just made: the engine's copy knows none of it.
            val previous = if (id in fresh) null else previousAll[id]
            list.forEachIndexed { slot, value ->
                if (previous?.getOrNull(slot) != value) send(id, slot, value)
            }
            if (clear != null) {
                for (slot in list.size until (previous?.size ?: 0)) clear(id, slot)
            }
        }
        return now
    }

    /** Forget what the engine has, so the next sync re-sends everything. */
    fun invalidate() {
        syncedNodes = emptyMap()
        syncedCables = emptySet()
        syncedParams = emptyMap()
        syncedRanges = emptyMap()
        syncedSteps = emptyMap()
        syncedScales = null
        syncedBeatsPerBar = null
        syncedTempo = null
        syncedFonts = emptyMap()
        syncedDots = emptyMap()
        syncedSegments = emptyMap()
    }

    /**
     * [fonts] is which SoundFonts are loaded, by name, as native handles. An SF module whose
     * font is not among them yet is sent nothing and stays silent; the sync after its font
     * lands hands it over.
     */
    fun sync(patch: Patch, fonts: Map<String, Long> = emptyMap()) {
        // The patch flattened. A plain subpatch and its rails are not nodes, and a cable
        // through its ports arrives as the one cable it stands for -- so subpatching modules
        // that are already playing sends the engine nothing at all. A poly subpatch is
        // flattened by copying: as many of everything inside it as it has voices, with a node
        // at each of its edges. Either way the engine is handed a flat graph.
        val graph = patch.engineGraph()
        val sounding = graph.nodes
        val nodes = sounding.associate { it.id to it.type }
        val cables = graph.cables

        // Note inputs are excluded: they merge rather than replace, so a new cable into
        // one supersedes nothing and the cable that left still has to be sent. Asked of the
        // flattened graph, because half of these are ports on nodes no module has.
        val replaced = (cables - syncedCables)
            .map { it.to }
            .filter { it !in graph.noteInputs }
            .toSet()

        (syncedCables - cables).forEach {
            // A connect to the same input supersedes a disconnect, because signal inputs
            // are single-source. Sending both makes the engine fade the old source out to
            // silence and then fade the new one in from silence -- and since they arrive
            // in the same drain, the second fade starts from silence rather than from
            // what was playing, which steps. Letting the connect stand on its own is
            // what makes replacing a cable an actual crossfade.
            if (it.to !in replaced) {
                if (it.to.dir == PortDirection.MOD) {
                    commands.disconnectMod(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
                } else {
                    commands.disconnect(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
                }
            }
        }

        (syncedNodes.keys - nodes.keys).forEach { commands.removeNode(it) }

        val fresh = mutableSetOf<Long>()
        nodes.forEach { (id, type) ->
            val had = syncedNodes[id]
            if (had != type) {
                // A type change would be a different node wearing the same id.
                if (had != null) commands.removeNode(id)
                commands.addNode(id, type)
                fresh += id
            }
        }

        // Ranges before the cables that use them. The engine ignores a modulator on a
        // parameter that has no range yet, so the other order would be silent rather than
        // wrong -- but only until the range arrived, and a queue drained partway through a
        // sync would let a block render in between. Every range of a node that was just
        // made, because the engine's node knows none of them.
        val ranges = sounding.associate { it.id to it.module?.modRanges.orEmpty() }
        sounding.forEach { node ->
            val type = node.module?.type ?: return@forEach
            val previous = if (node.id in fresh) null else syncedRanges[node.id]
            node.module.modRanges.forEach { (index, range) ->
                if (previous?.get(index) != range) {
                    val exponential = type.params.getOrNull(index)?.curve == ParamCurve.EXPONENTIAL
                    commands.setModRange(node.id, index, range.low, range.high, exponential)
                }
            }
        }

        (cables - syncedCables).forEach {
            if (it.to.dir == PortDirection.MOD) {
                commands.connectMod(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
            } else {
                commands.connect(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
            }
        }

        // A font before the knobs, though either order sounds the same: the node applies
        // its preset whenever either arrives. Resent to a node that was just made, and to
        // one whose font changed or has only now finished loading.
        val wanted = sounding.filter { it.module?.type == Types.Sf }
            .mapNotNull { n -> n.module?.font?.let { name -> fonts[name] }?.let { n.id to it } }
            .toMap()
        wanted.forEach { (id, handle) ->
            if (id in fresh || syncedFonts[id] != handle) commands.setFont(id, handle)
        }

        // Knobs last, and every knob of a node that was just added: the engine's node
        // starts at its own C++ defaults, which are not required to agree with the ones
        // declared here, and a patch loaded from disk has values for all of them.
        val params = sounding.associate { it.id to it.module?.params.orEmpty().toList() }
        params.forEach { (id, values) ->
            val previous = syncedParams[id]
            values.forEachIndexed { index, value ->
                if (previous == null || previous.getOrNull(index) != value) {
                    commands.setParam(id, index, value)
                }
            }
        }

        // The slot-indexed lists, all three through one pass. A sequence's steps are as
        // degrees: the engine resolves each note against the scale sounding on the beat it
        // starts, so a change of scale resends the list below and not a single step.
        val steps = diffSlots(
            sounding, fresh, syncedSteps,
            applies = { it.stepCount > 0 },
            read = { it.steps.toList() },
            send = { id, slot, step -> commands.setStep(id, slot, step.degree, step.on) },
            // Fixed length: a sequencer always has every step, so none is ever vacated.
            clear = null,
        )
        val dots = diffSlots(
            sounding, fresh, syncedDots,
            applies = { it.grid == GridKind.DOTS },
            read = { it.dots.toList() },
            send = { id, slot, dot ->
                commands.setDot(id, slot, dot.step, dot.degree, dot.length, dot.velocity)
            },
            clear = { id, slot -> commands.setDot(id, slot, 0, 0, 0, 1f) },
        )
        val segments = diffSlots(
            sounding, fresh, syncedSegments,
            applies = { it.grid == GridKind.ENVELOPE },
            read = { it.segments.toList() },
            send = { id, slot, seg ->
                commands.setSegment(id, slot, seg.time, seg.level, seg.curve, seg.sustain)
            },
            // A time of 0 vacates the slot, and because segments are contiguous the engine
            // reads the first vacated one as the end of the envelope.
            clear = { id, slot -> commands.setSegment(id, slot, 0f, 0f, 0f, false) },
        )

        // The scale list, whole, when it or the bar length changes: entries last bars and
        // beats, and the engine counts only beats.
        if (syncedScales != patch.scales || syncedBeatsPerBar != patch.beatsPerBar) {
            commands.setScales(patch.scales, patch.beatsPerBar)
        }

        // The transport's rate, on a change of value alone. Not a node, so not part of the
        // diff above -- and only the rate: where the transport has got to belongs to the
        // engine, and is not the patch's to send.
        if (syncedTempo != patch.tempo) commands.setTempo(patch.tempo)

        syncedNodes = nodes
        syncedCables = cables
        syncedParams = params
        syncedRanges = ranges
        syncedSteps = steps
        syncedScales = patch.scales
        syncedBeatsPerBar = patch.beatsPerBar
        syncedTempo = patch.tempo
        syncedFonts = wanted
        syncedDots = dots
        syncedSegments = segments

        // Whatever the audio thread retired during the last block is ours to free.
        commands.collectGarbage()
    }
}
