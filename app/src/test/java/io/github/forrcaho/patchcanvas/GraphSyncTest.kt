package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private sealed interface Cmd {
    data class Add(val id: Long, val type: NodeType) : Cmd
    data class Remove(val id: Long) : Cmd
    data class Connect(val src: Long, val srcPort: Int, val dst: Long, val dstPort: Int) : Cmd
    data class Disconnect(val dst: Long, val dstPort: Int) : Cmd
}

private class Recorder : GraphCommands {
    val log = mutableListOf<Cmd>()
    var collected = 0

    override fun addNode(id: Long, type: NodeType) { log += Cmd.Add(id, type) }
    override fun removeNode(id: Long) { log += Cmd.Remove(id) }
    override fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        log += Cmd.Connect(srcId, srcPort, dstId, dstPort)
    }
    override fun disconnect(dstId: Long, dstPort: Int) { log += Cmd.Disconnect(dstId, dstPort) }
    override fun collectGarbage() { collected++ }

    fun clear() { log.clear() }
}

/**
 * The diff is what keeps two representations of the same patch agreeing, and its
 * ordering is the part that would be silently wrong: the graph must never be asked to
 * reference a node that is not there yet, or to keep one that is going away.
 */
class GraphSyncTest {

    private val rec = Recorder()
    private val sync = GraphSync(rec)

    @Test
    fun `first sync sends every node and cable`() {
        val patch = demoPatch()
        sync.sync(patch)

        val adds = rec.log.filterIsInstance<Cmd.Add>()
        val connects = rec.log.filterIsInstance<Cmd.Connect>()
        assertEquals(patch.modules.size, adds.size)
        assertEquals(patch.connections.size, connects.size)
        // the rails are ordinary nodes to the engine
        assertTrue(adds.any { it.id == OUT_ID && it.type == NodeType.Out })
        assertTrue(adds.any { it.id == IN_ID && it.type == NodeType.In })
    }

    @Test
    fun `every add precedes every connect`() {
        sync.sync(demoPatch())
        val lastAdd = rec.log.indexOfLast { it is Cmd.Add }
        val firstConnect = rec.log.indexOfFirst { it is Cmd.Connect }
        assertTrue("add at $lastAdd, connect at $firstConnect", lastAdd < firstConnect)
    }

    @Test
    fun `an unchanged patch sends nothing`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        sync.sync(patch)
        assertTrue(rec.log.isEmpty())
    }

    @Test
    fun `moving a module is not a graph change`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        patch.free.first().position = Offset(999f, 999f)
        sync.sync(patch)
        assertTrue("position is not the audio graph's business", rec.log.isEmpty())
    }

    @Test
    fun `adding a module sends one add`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        val added = patch.add(Types.Osc, Offset.Zero)!!
        sync.sync(patch)
        assertEquals(listOf(Cmd.Add(added.id, NodeType.Osc)), rec.log)
    }

    @Test
    fun `removing a module drops its cables before the node`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        val filter = patch.free.first { it.type == Types.Filter }
        patch.remove(filter)
        sync.sync(patch)

        val lastDisconnect = rec.log.indexOfLast { it is Cmd.Disconnect }
        val remove = rec.log.indexOfFirst { it == Cmd.Remove(filter.id) }
        assertTrue("expected a disconnect", lastDisconnect >= 0)
        assertTrue("cables must drop first", lastDisconnect < remove)
    }

    @Test
    fun `re-patching an occupied input disconnects before connecting`() {
        val patch = demoPatch()
        val a = patch.add(Types.Osc, Offset.Zero)!!
        patch.connect(PortRef(a.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        sync.sync(patch)
        rec.clear()

        val b = patch.add(Types.Osc, Offset.Zero)!!
        patch.connect(PortRef(b.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        sync.sync(patch)

        val disconnect = rec.log.indexOfFirst { it == Cmd.Disconnect(OUT_ID, 0) }
        val connect = rec.log.indexOfFirst { it == Cmd.Connect(b.id, 0, OUT_ID, 0) }
        assertTrue("expected the old cable dropped", disconnect >= 0)
        assertTrue("and dropped before the new one lands", disconnect < connect)
    }

    @Test
    fun `invalidate forces a full resend`() {
        val patch = demoPatch()
        sync.sync(patch)
        val first = rec.log.size
        rec.clear()

        sync.invalidate()
        sync.sync(patch)
        assertEquals(first, rec.log.size)
    }

    @Test
    fun `a reloaded patch syncs like any other change`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        // what happens at launch: the same content arriving from disk
        val reloaded = patchFromJson(patch.toJson())!!
        sync.sync(reloaded)
        assertTrue("identical content should be a no-op", rec.log.isEmpty())
    }

    @Test
    fun `garbage is collected on every sync`() {
        val patch = demoPatch()
        sync.sync(patch)
        sync.sync(patch)
        assertEquals(2, rec.collected)
    }
}
