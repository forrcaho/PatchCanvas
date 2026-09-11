package io.github.forrcaho.patchcanvas

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
    fun collectGarbage()
}

/** The real one. */
object EngineCommands : GraphCommands {
    override fun addNode(id: Long, type: NodeType) { AudioEngine.addNode(id, type) }
    override fun removeNode(id: Long) { AudioEngine.removeNode(id) }
    override fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        AudioEngine.connect(srcId, srcPort, dstId, dstPort)
    }
    override fun disconnect(dstId: Long, dstPort: Int) { AudioEngine.disconnect(dstId, dstPort) }
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

    /** Forget what the engine has, so the next sync re-sends everything. */
    fun invalidate() {
        syncedNodes = emptyMap()
        syncedCables = emptySet()
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

        syncedNodes = nodes
        syncedCables = cables

        // Whatever the audio thread retired during the last block is ours to free.
        commands.collectGarbage()
    }
}
