package io.github.forrcaho.patchcanvas

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    @Test
    fun `a fresh history offers nothing`() {
        val history = History()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
        assertNull(history.redo())
    }

    @Test
    fun `the first state recorded is a baseline, not something to undo to`() {
        val history = History()
        history.record("a")
        assertFalse(history.canUndo)
    }

    @Test
    fun `undo returns the previous state and offers a redo`() {
        val history = History()
        history.record("a")
        history.record("b")

        assertTrue(history.canUndo)
        assertEquals("a", history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals("b", history.redo())
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `repeated undo walks back through every state`() {
        val history = History()
        listOf("a", "b", "c", "d").forEach { history.record(it) }

        assertEquals("c", history.undo())
        assertEquals("b", history.undo())
        assertEquals("a", history.undo())
        assertNull(history.undo())
    }

    /**
     * The one that makes the whole design work. A restored state comes back around
     * through the same autosave flow that records ordinary edits, so if it were pushed
     * as a new state, undoing twice would land you back where you started and the stack
     * would grow without bound.
     */
    @Test
    fun `recording the state just restored is not a new edit`() {
        val history = History()
        history.record("a")
        history.record("b")

        history.record(history.undo()!!)

        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }

    @Test
    fun `an edit after an undo abandons the redo branch`() {
        val history = History()
        history.record("a")
        history.record("b")
        history.undo()

        history.record("c")

        assertFalse(history.canRedo)
        assertEquals("a", history.undo())
    }

    @Test
    fun `an unchanged state records nothing`() {
        val history = History()
        history.record("a")
        history.record("a")
        assertFalse(history.canUndo)
    }

    @Test
    fun `the oldest states fall off the end of the limit`() {
        val history = History(limit = 3)
        listOf("a", "b", "c", "d", "e").forEach { history.record(it) }

        // Four reachable states: the current one and three behind it.
        assertEquals("d", history.undo())
        assertEquals("c", history.undo())
        assertEquals("b", history.undo())
        assertNull(history.undo())
    }
}

/**
 * `replaceWith` has to leave the patch in a state that serialises *byte for byte* to the
 * snapshot it came from, or History's equality check fails and every undo pushes a
 * phantom entry onto its own stack.
 */
class ReplaceWithTest {

    @Test
    fun `a replaced patch serialises identically to its snapshot`() {
        val snapshot = demoPatch().toJson()

        val live = Patch()
        live.replaceWith(patchFromJson(snapshot)!!)

        assertEquals(snapshot, live.toJson())
    }

    @Test
    fun `replacing restores modules, knobs and cables over a different patch`() {
        val before = demoPatch()
        before.free.first { it.type.name == "Filter" }.setParam(0, 4321f)
        val snapshot = before.toJson()

        val live = demoPatch()
        live.remove(live.free.first { it.type.name == "Osc" })
        live.add(Types.Mix, Offset(900f, 900f))
        live.connections.clear()

        live.replaceWith(patchFromJson(snapshot)!!)

        assertEquals(before.free.map { it.id to it.type.name }, live.free.map { it.id to it.type.name })
        assertEquals(before.connections.toList(), live.connections.toList())
        assertEquals(4321f, live.free.first { it.type.name == "Filter" }.params[0], 0.001f)
    }

    @Test
    fun `the rails survive, and their knobs come back with the snapshot`() {
        val before = demoPatch()
        val level = before.module(OUT_ID)!!
        level.setParam(0, level.params[0] * 0.5f)
        val snapshot = before.toJson()

        val live = demoPatch()
        live.module(OUT_ID)!!.setParam(0, 1f)

        live.replaceWith(patchFromJson(snapshot)!!)

        assertEquals(2, live.pinned.size)
        assertNull(live.pinned.firstOrNull { it.id != OUT_ID && it.id != IN_ID })
        assertEquals(before.module(OUT_ID)!!.params[0], live.module(OUT_ID)!!.params[0], 0.001f)
    }

    /**
     * The audible one. `GraphSync` runs off a snapshot observer, so if the replacement
     * applied a mutation at a time it would see the instant after the cables were
     * cleared -- and an empty patch is a state the engine will faithfully render, fading
     * every voice out and back in. Undo would click.
     */
    @Test
    fun `a replacement is applied as one change, never as an empty patch`() {
        val live = demoPatch()
        val snapshot = patchFromJson(demoPatch().toJson())!!

        val observed = mutableListOf<Int>()
        val handle = Snapshot.registerApplyObserver { _, _ -> observed += live.connections.size }
        try {
            live.replaceWith(snapshot)
        } finally {
            handle.dispose()
        }

        assertTrue("the replacement was never observed at all", observed.isNotEmpty())
        assertFalse("an observer saw the patch with its cables gone", observed.contains(0))
    }

    @Test
    fun `a module added after the snapshot is gone once it is restored`() {
        val snapshot = demoPatch().toJson()
        val live = demoPatch()

        val added = live.add(Types.Mix, Offset(10f, 10f))!!
        live.connect(
            PortRef(added.id, PortDirection.OUTPUT, 0),
            PortRef(OUT_ID, PortDirection.INPUT, 0),
        )

        live.replaceWith(patchFromJson(snapshot)!!)

        assertNull(live.module(added.id))
        assertFalse(live.connections.any { it.from.moduleId == added.id })
        assertEquals(snapshot, live.toJson())
    }
}
