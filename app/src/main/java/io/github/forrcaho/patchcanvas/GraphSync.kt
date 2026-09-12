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
    Clock(8),
    Mix(9);

    companion object {
        fun of(type: ModuleType): NodeType = when (type.name) {
            "Osc" -> Osc
            "Filter" -> Filter
            "Env" -> Env
            "Steps" -> Steps
            "Out" -> Out
            "In" -> In
            "VCA" -> Vca
            "Clock" -> Clock
            "Mix" -> Mix
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
    fun disconnect(dstId: Long, dstPort: Int)
    fun setParam(id: Long, index: Int, value: Float)
    /** One step of a sequence. Pitch in octaves from the root, not degrees. */
    fun setStep(id: Long, index: Int, pitch: Float, gate: Boolean)
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

    override fun disconnect(dstId: Long, dstPort: Int) {
        trace { "disconnect $dstId[$dstPort]" }
        AudioEngine.disconnect(dstId, dstPort)
    }

    override fun setParam(id: Long, index: Int, value: Float) {
        trace { "param $id[$index] = $value" }
        AudioEngine.setParam(id, index, value)
    }

    override fun setStep(id: Long, index: Int, pitch: Float, gate: Boolean) {
        trace { "step $id[$index] = $pitch ${if (gate) "on" else "rest"}" }
        AudioEngine.setStep(id, index, pitch, gate)
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
class GraphSync(
    private val commands: GraphCommands = EngineCommands,
    /**
     * The tuning degrees are read against. Held here because this is the one place a
     * degree becomes a pitch; everything downstream deals in octaves.
     */
    private val scale: Scale = Scale.Chromatic,
) {

    private var syncedNodes = emptyMap<Long, NodeType>()
    private var syncedCables = emptySet<Connection>()
    private var syncedParams = emptyMap<Long, List<Float>>()
    private var syncedSteps = emptyMap<Long, List<Step>>()

    /** Forget what the engine has, so the next sync re-sends everything. */
    fun invalidate() {
        syncedNodes = emptyMap()
        syncedCables = emptySet()
        syncedParams = emptyMap()
        syncedSteps = emptyMap()
    }

    fun sync(patch: Patch) {
        val nodes = patch.modules.associate { it.id to NodeType.of(it.type) }
        val cables = patch.connections.toSet()

        val replaced = (cables - syncedCables).map { it.to }.toSet()

        (syncedCables - cables).forEach {
            // A connect to the same input supersedes a disconnect, because inputs are
            // single-source. Sending both makes the engine fade the old source out to
            // silence and then fade the new one in from silence -- and since they arrive
            // in the same drain, the second fade starts from silence rather than from
            // what was playing, which steps. Letting the connect stand on its own is
            // what makes replacing a cable an actual crossfade.
            if (it.to !in replaced) commands.disconnect(it.to.moduleId, it.to.index)
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
        // a node that was just added. Degrees become octaves here and nowhere else --
        // past this point the engine has no idea a scale was involved.
        val steps = patch.modules
            .filter { it.type.stepCount > 0 }
            .associate { it.id to it.steps.toList() }
        steps.forEach { (id, sequence) ->
            val previous = syncedSteps[id]
            sequence.forEachIndexed { index, step ->
                if (previous == null || previous.getOrNull(index) != step) {
                    commands.setStep(id, index, scale.octavesOf(step.degree), step.on)
                }
            }
        }

        syncedNodes = nodes
        syncedCables = cables
        syncedParams = params
        syncedSteps = steps

        // Whatever the audio thread retired during the last block is ours to free.
        commands.collectGarbage()
    }
}
