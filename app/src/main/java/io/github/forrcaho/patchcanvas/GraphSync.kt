package io.github.forrcaho.patchcanvas

import android.util.Log

/**
 * Node type ids, mirroring the enum in nodes.h. The numbering is part of the JNI
 * contract, so append rather than reorder.
 */
enum class NodeType(val id: Int) {
    Unknown(0),
    Osc(1),
    Filter(2),
    Env(3),
    Steps(4),
    Out(5),
    In(6),
    Vca(7),
    // 8 was Clock, retired when the transport replaced it, and deliberately not reused.
    Mix(9),
    Voice(10);

    companion object {
        fun of(type: ModuleType): NodeType = when (type.name) {
            "Osc" -> Osc
            "Filter" -> Filter
            "Env" -> Env
            "Steps" -> Steps
            "Out" -> Out
            "In" -> In
            "VCA" -> Vca
            "Mix" -> Mix
            "Voice" -> Voice
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
    /** One step of a sequence, as a degree: the engine resolves it against the scale list. */
    fun setStep(id: Long, index: Int, degree: Int, gate: Boolean)
    /** The patch's scale list, whole, with each entry's length worked out in beats. */
    fun setScales(entries: List<ScaleEntry>, beatsPerBar: Int)
    /** The transport's rate, in beats per minute. */
    fun setTempo(bpm: Float)
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
    private var syncedSteps = emptyMap<Long, List<Step>>()
    private var syncedScales: List<ScaleEntry>? = null
    private var syncedBeatsPerBar: Int? = null
    private var syncedTempo: Float? = null

    /** Forget what the engine has, so the next sync re-sends everything. */
    fun invalidate() {
        syncedNodes = emptyMap()
        syncedCables = emptySet()
        syncedParams = emptyMap()
        syncedSteps = emptyMap()
        syncedScales = null
        syncedBeatsPerBar = null
        syncedTempo = null
    }

    fun sync(patch: Patch) {
        val nodes = patch.modules.associate { it.id to NodeType.of(it.type) }
        val cables = patch.connections.toSet()

        // Note inputs are excluded: they merge rather than replace, so a new cable into
        // one supersedes nothing and the cable that left still has to be sent.
        val replaced = (cables - syncedCables)
            .map { it.to }
            .filter { patch.kindOf(it) != SignalKind.NOTE }
            .toSet()

        (syncedCables - cables).forEach {
            // A connect to the same input supersedes a disconnect, because signal inputs
            // are single-source. Sending both makes the engine fade the old source out to
            // silence and then fade the new one in from silence -- and since they arrive
            // in the same drain, the second fade starts from silence rather than from
            // what was playing, which steps. Letting the connect stand on its own is
            // what makes replacing a cable an actual crossfade.
            if (it.to !in replaced) {
                commands.disconnect(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
            }
        }

        (syncedNodes.keys - nodes.keys).forEach { commands.removeNode(it) }

        nodes.forEach { (id, type) ->
            val had = syncedNodes[id]
            if (had != type) {
                // A type change would be a different node wearing the same id.
                if (had != null) commands.removeNode(id)
                commands.addNode(id, type)
            }
        }

        (cables - syncedCables).forEach {
            commands.connect(it.from.moduleId, it.from.index, it.to.moduleId, it.to.index)
        }

        // Knobs last, and every knob of a node that was just added: the engine's node
        // starts at its own C++ defaults, which are not required to agree with the ones
        // declared here, and a patch loaded from disk has values for all of them.
        val params = patch.modules.associate { it.id to it.params.toList() }
        params.forEach { (id, values) ->
            val previous = syncedParams[id]
            values.forEachIndexed { index, value ->
                if (previous == null || previous.getOrNull(index) != value) {
                    commands.setParam(id, index, value)
                }
            }
        }

        // Sequences, on the same terms as knobs: only what changed, and everything for
        // a node that was just added. As degrees: the engine resolves each note against
        // the scale sounding on the beat it starts, so a change of scale resends the list
        // below and not a single step.
        val steps = patch.modules
            .filter { it.type.stepCount > 0 }
            .associate { it.id to it.steps.toList() }
        steps.forEach { (id, sequence) ->
            val previous = syncedSteps[id]
            sequence.forEachIndexed { index, step ->
                if (previous == null || previous.getOrNull(index) != step) {
                    commands.setStep(id, index, step.degree, step.on)
                }
            }
        }

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
        syncedSteps = steps
        syncedScales = patch.scales
        syncedBeatsPerBar = patch.beatsPerBar
        syncedTempo = patch.tempo

        // Whatever the audio thread retired during the last block is ours to free.
        commands.collectGarbage()
    }
}
