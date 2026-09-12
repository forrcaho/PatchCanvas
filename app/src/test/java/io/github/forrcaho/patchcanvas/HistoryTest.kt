package io.github.forrcaho.patchcanvas

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

    /**
     * Undo changes the document, not the view. The camera does not move and neither
     * should the panel -- and it is the case that matters most, because undoing a knob
     * is the one undo whose effect is only visible while its panel is open.
     */
    @Test
    fun `the open panel stays open across a restore`() {
        val live = demoPatch()
        val open = live.free.first { it.type.name == "Filter" }
        open.expanded = true
        val snapshot = live.toJson()

        live.replaceWith(patchFromJson(snapshot)!!)

        assertTrue(live.module(open.id)!!.expanded)
        assertEquals(1, live.modules.count { it.expanded })
    }

    @Test
    fun `a panel whose module the snapshot predates closes`() {
        val snapshot = demoPatch().toJson()
        val live = demoPatch()
        val added = live.add(Types.Mix, Offset(10f, 10f))!!
        added.expanded = true

        live.replaceWith(patchFromJson(snapshot)!!)

        assertNull(live.module(added.id))
        assertFalse(live.modules.any { it.expanded })
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


/**
 * The history buttons float over an open panel, so they have to sit somewhere the panel
 * is not using. This is the assertion behind that: the panel's knob rows are inset by
 * PANEL_SIDE and the buttons live in the corner outside it.
 */
class HistoryButtonGeometryTest {

    // The reference device in landscape: 2404x1080 at density 2.4375, with the cutout
    // down the leading edge and the gesture bar along the bottom.
    private val frame = Frame(
        canvas = Size(2404f, 1080f),
        density = 2.4375f,
        insetLeft = 160f,
        insetTop = 0f,
        insetRight = 0f,
        insetBottom = 58f,
    )

    @Test
    fun `the buttons do not overlap any knob row of any module type`() {
        val panel = panelRect(frame)
        val buttons = listOf(frame.historyRect(false), frame.historyRect(true))

        Types.byName.values.forEach { type ->
            val count = type.params.size
            repeat(count) { i ->
                val row = panelRow(panel, frame.density, i, count)
                buttons.forEach { button ->
                    assertTrue(
                        "${type.name} row $i overlaps a history button",
                        button.overlaps(row).not(),
                    )
                }
            }
        }
    }

    @Test
    fun `the buttons sit side by side and clear of the screen edges`() {
        val undo = frame.historyRect(false)
        val redo = frame.historyRect(true)

        assertEquals(undo.top, redo.top, 0.001f)
        assertTrue("redo must follow undo", redo.left > undo.right)
        assertTrue("inside the leading inset", undo.left >= frame.insetLeft)
        assertTrue("above the gesture bar", undo.bottom <= 1080f - frame.insetBottom)
    }
}


/**
 * What an undo pulses. The graph draws no parameters at all, so an undone knob is a
 * change in the sound with nothing on screen accounting for it; the pulse is the only
 * thing that says which module it came from, and pointing at the wrong one would be
 * worse than pointing at nothing.
 */
class RestoreChangeSetTest {

    /** Applies [edit] to a copy of the demo patch and returns what restoring it flags. */
    private fun changesAfter(edit: Patch.() -> Unit): Set<Long> {
        val live = demoPatch()
        val snapshot = live.toJson()
        live.edit()
        return live.replaceWith(patchFromJson(snapshot)!!)
    }

    @Test
    fun `an unchanged patch flags nothing`() {
        assertTrue(changesAfter { }.isEmpty())
    }

    @Test
    fun `a knob flags only its own module`() {
        val filter = demoPatch().free.first { it.type.name == "Filter" }.id
        assertEquals(
            setOf(filter),
            changesAfter { module(filter)!!.setParam(0, 77f) },
        )
    }

    @Test
    fun `a move flags only the module that moved`() {
        val osc = demoPatch().free.first { it.type.name == "Osc" }.id
        assertEquals(
            setOf(osc),
            changesAfter { module(osc)!!.position = Offset(1f, 1f) },
        )
    }

    @Test
    fun `a cable flags both of the modules it touches`() {
        val live = demoPatch()
        val osc = live.free.first { it.type.name == "Osc" }.id
        val vca = live.free.first { it.type.name == "VCA" }.id

        val flagged = changesAfter {
            connect(
                PortRef(osc, PortDirection.OUTPUT, 0),
                PortRef(vca, PortDirection.INPUT, 0),
            )
        }

        // The replaced cable's old source counts too: it lost a connection.
        assertTrue("the new source", osc in flagged)
        assertTrue("the destination", vca in flagged)
    }

    @Test
    fun `a module that appears on restore is flagged`() {
        val live = demoPatch()
        val snapshot = live.toJson()
        val doomed = live.free.first { it.type.name == "Env" }
        live.remove(doomed)

        assertTrue(doomed.id in live.replaceWith(patchFromJson(snapshot)!!))
    }

    @Test
    fun `a rail knob flags the rail`() {
        assertEquals(
            setOf(OUT_ID),
            changesAfter { module(OUT_ID)!!.setParam(0, 0.25f) },
        )
    }

    @Test
    fun `flashing nothing does not fire a pulse`() {
        val patch = demoPatch()
        val before = patch.flash.serial
        patch.flash(emptySet())
        assertEquals(before, patch.flash.serial)
    }

    @Test
    fun `the same set twice still fires, so undo and redo both pulse`() {
        val patch = demoPatch()
        patch.flash(setOf(1L))
        val first = patch.flash.serial
        patch.flash(setOf(1L))
        assertTrue(patch.flash.serial > first)
    }
}
