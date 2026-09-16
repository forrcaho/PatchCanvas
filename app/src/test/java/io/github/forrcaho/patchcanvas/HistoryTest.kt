package io.github.forrcaho.patchcanvas

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.io.File
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
    fun `undo puts the tempo and beats per bar back`() {
        val live = demoPatch()
        val snapshot = live.toJson()
        live.tempo = 211f
        live.beatsPerBar = 7

        live.replaceWith(patchFromJson(snapshot)!!)

        assertEquals(TEMPO.default, live.tempo, 0.0001f)
        assertEquals(BEATS_PER_BAR.default.toInt(), live.beatsPerBar)
        assertEquals(snapshot, live.toJson())
    }

    /**
     * A real bug, found while adding the tempo: replaceWith never copied the scale, so an
     * undone change of tuning kept the new tuning. The snapshot is taken in a scale other
     * than the default, because on the default the missing copy is invisible.
     */
    @Test
    fun `undo puts the scale back`() {
        val library = ScaleLibrary.of(File("src/main/assets/scales"))
        val major = library.byName("Major")!!
        val minor = library.byName("Minor")!!

        val live = demoPatch().apply { scales = listOf(ScaleEntry(major, 2, 1), ScaleEntry(minor)) }
        val snapshot = live.toJson()
        live.scales = listOf(ScaleEntry(minor))

        live.replaceWith(patchFromJson(snapshot, library)!!)

        assertEquals(
            listOf(Triple("Major", 2, 1), Triple("Minor", 4, 0)),
            live.scales.map { Triple(it.scale.name, it.bars, it.beats) },
        )
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
 * The root page: a tap near a degree means that degree, exactly; anywhere else means the
 * cent under the finger. The degrees are where keys are, and hard to land on by eye.
 */
class RootControlTest {

    private val frame = Frame(
        canvas = Size(2404f, 1080f),
        density = 2.4375f,
        insetLeft = 160f,
        insetTop = 54f,
        insetRight = 0f,
        insetBottom = 58f,
    )
    private val d = frame.density
    private val slider = rootSlider(frame.scaleRootPage(), d)
    private val major = Scale.steps("Major", 12, listOf(2, 2, 1, 2, 2, 2, 1))

    private fun xOf(cents: Float) = slider.left + slider.width * ROOT.positionOf(cents)

    @Test
    fun `a tap near a degree lands exactly on it`() {
        assertEquals(700f, rootAtTap(xOf(700f) + 5f * d, slider, d, major), 0f)
        assertEquals(700f, rootAtTap(xOf(700f) - 5f * d, slider, d, major), 0f)
    }

    /** In Major, 600 cents is a gap between two degrees -- far enough from both not to snap. */
    @Test
    fun `a tap between degrees lands where it touched`() {
        assertEquals(600f, rootAtTap(xOf(600f), slider, d, major), 0.5f)
    }

    /** The point of the snap: a degree that is not a whole number of cents is still exact. */
    @Test
    fun `a nineteen-tone degree is reachable exactly`() {
        val nineteen = Scale.equal("19-TET", 19)
        val degree = nineteen.octavesOf(3) * 1200f
        assertEquals(degree, rootAtTap(xOf(degree) + 1f * d, slider, d, nineteen), 0f)
    }

    @Test
    fun `the reading names the nearest note, and admits when it is only near`() {
        assertEquals("G", nearestNoteName(700f))
        assertEquals("B♭", nearestNoteName(-200f))
        assertEquals("≈G", nearestNoteName(694.7f))
        assertEquals("+700¢", formatCents(700f))
        assertEquals("−63.2¢", formatCents(-63.16f))
        assertEquals("0¢", formatCents(0.01f))
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
            type.rowParams.forEach { i ->
                val row = panelRow(panel, frame.density, type, i)
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
        // A second filter, because the demo already patches the oscillator into its
        // first one and re-making a cable that is already there changes nothing.
        val filter = live.add(Types.Filter, Offset(700f, 300f))!!.id

        val flagged = changesAfter {
            connect(
                PortRef(osc, PortDirection.OUTPUT, 0),
                PortRef(filter, PortDirection.INPUT, 0),
            )
        }

        // The replaced cable's old source counts too: it lost a connection.
        assertTrue("the new source", osc in flagged)
        assertTrue("the destination", filter in flagged)
    }

    @Test
    fun `a module that appears on restore is flagged`() {
        val live = demoPatch()
        val snapshot = live.toJson()
        val doomed = live.free.first { it.type.name == "Filter" }
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

/**
 * The scale chooser's geometry. It covers the panel body, so a tile that fell outside it
 * or overlapped its neighbour would be a scale you could not pick or could pick by
 * accident -- and neither is visible in a screenshot of the one you were aiming at.
 */
class ScaleChooserGeometryTest {

    private val frame = Frame(
        canvas = Size(2404f, 1080f),
        density = 2.4375f,
        insetLeft = 160f,
        insetTop = 54f,
        insetRight = 0f,
        insetBottom = 58f,
    )
    private val panel = panelRect(frame)

    @Test
    fun `the interval chip sits inside the panel header`() {
        val chip = panelIntervalChip(panel, frame.density)
        assertTrue("left of the panel", chip.left > panel.left)
        assertTrue("inside the right edge", chip.right <= panel.right)
        assertTrue("below the panel top", chip.top >= panel.top)
        assertTrue(
            "spills past the header",
            chip.bottom <= panel.top + PatchModule.PANEL_HEADER * frame.density,
        )
    }

    @Test
    fun `tiles stay inside the panel and never overlap`() {
        val tiles = panelTiles(panel, frame.density, 24)
        assertTrue("no tiles laid out", tiles.isNotEmpty())
        tiles.forEach { tile ->
            assertTrue("$tile escapes the panel", panel.contains(tile.topLeft))
            assertTrue("$tile escapes the panel", tile.right <= panel.right)
            assertTrue("$tile escapes the panel", tile.bottom <= panel.bottom)
        }
        tiles.forEachIndexed { i, a ->
            tiles.drop(i + 1).forEach { b ->
                assertTrue("$a overlaps $b", !a.overlaps(b))
            }
        }
    }

    /** The shipped library plus a few of the user's own must fit the picker without paging. */
    @Test
    fun `the whole shipped library fits on one page of the picker`() {
        val shipped = File("src/main/assets/scales").listFiles()?.size ?: 0
        assertTrue("no scale assets found", shipped >= 10)
        val picker = frame.scalePicker()
        val tiles = scalePickerTiles(picker, frame.density, shipped + 6)
        assertEquals(shipped + 6, tiles.size)
        tiles.forEach { assertTrue("$it escapes the picker", it.right <= picker.right && it.bottom <= picker.bottom) }
    }

    @Test
    fun `a tile is big enough to hit`() {
        val tile = scalePickerTiles(frame.scalePicker(), frame.density, 4).first()
        assertTrue("too narrow: ${tile.width / frame.density}dp", tile.width / frame.density >= 120f)
        assertTrue("too short: ${tile.height / frame.density}dp", tile.height / frame.density >= 40f)
    }

    @Test
    fun `the interval chip sits in the header, clear of the title`() {
        val interval = panelIntervalChip(panel, frame.density)
        assertTrue("spills past the header", interval.bottom <= panel.top + PatchModule.PANEL_HEADER * frame.density)
        // The title is centred; keeping the chips in the right-hand side keeps them off it.
        assertTrue("reaches the centred title", interval.left > panel.center.x + 60f * frame.density)
    }

    @Test
    fun `every interval fits on one page of tiles`() {
        assertEquals(INTERVALS.size, panelTiles(panel, frame.density, INTERVALS.size).size)
    }
}

/**
 * The transport floats over the graph and over an open panel alike, so it must not sit
 * on anything either of them needs -- a chip over a knob is a knob you cannot reach.
 */
class TransportGeometryTest {

    private val frame = Frame(
        canvas = Size(2404f, 1080f),
        density = 2.4375f,
        insetLeft = 160f,
        insetTop = 54f,
        insetRight = 0f,
        insetBottom = 58f,
    )
    private val d = frame.density
    private val panel = panelRect(frame)
    private val chip = frame.transportChip()
    private val card = frame.transportCard()

    @Test
    fun `the chip and its card clear the history buttons`() {
        listOf(frame.historyRect(false), frame.historyRect(true)).forEach { button ->
            assertFalse("chip overlaps $button", chip.overlaps(button))
            assertFalse("card overlaps $button", card.overlaps(button))
        }
    }

    @Test
    fun `the chip clears every panel control it floats over`() {
        assertFalse(chip.overlaps(panelIntervalChip(panel, d)))
        assertFalse(chip.overlaps(panelGrid(panel, d)))
        Types.byName.values.forEach { type ->
            type.rowParams.forEach { i ->
                assertFalse("${type.name} row $i", chip.overlaps(panelRow(panel, d, type, i)))
            }
            type.inputs.indices.forEach { i ->
                val jack = panelPort(panel, d, PortDirection.INPUT, i, type.inputs.size)
                assertFalse("${type.name} input $i", chip.inflate(8f * d).contains(jack))
            }
        }
    }

    @Test
    fun `the chip is inside the safe area and big enough to hit`() {
        assertTrue(chip.left >= frame.insetLeft)
        assertTrue(chip.top >= frame.insetTop)
        assertTrue("too short: ${chip.height / d}dp", chip.height / d >= 36f)
    }

    @Test
    fun `the card's controls stay inside it and apart`() {
        val tempo = transportTempoRow(card, d)
        val beats = transportBeatsRow(card, d)
        val reset = transportReset(card, d)
        listOf(tempo, beats, reset).forEach { part ->
            assertTrue("$part escapes the card", card.contains(part.topLeft) &&
                part.right <= card.right && part.bottom <= card.bottom)
        }
        assertFalse(tempo.overlaps(beats))
        assertFalse(beats.overlaps(reset))
        assertTrue("reset too small to hit", reset.height / d >= 36f && reset.width / d >= 64f)
    }

    @Test
    fun `the scale chip sits beside the transport chip, clear of the panel under it`() {
        val scale = frame.scaleChip()
        assertFalse(scale.overlaps(chip))
        assertEquals(chip.top, scale.top, 0.001f)
        assertTrue("reaches the centred panel title", scale.right < panel.center.x - 60f * d)
        assertFalse(scale.overlaps(panelGrid(panel, d)))
        assertFalse(scale.overlaps(panelIntervalChip(panel, d)))
        Types.byName.values.forEach { type ->
            type.rowParams.forEach { i ->
                assertFalse("${type.name} row $i", scale.overlaps(panelRow(panel, d, type, i)))
            }
        }
    }

    @Test
    fun `the scale card and its picker clear the history buttons and the gesture bar`() {
        val buttons = listOf(frame.historyRect(false), frame.historyRect(true))
        listOf(frame.scaleCard(1), frame.scaleCard(MAX_SCALE_ENTRIES), frame.scalePicker()).forEach { area ->
            buttons.forEach { assertFalse("$area overlaps $it", area.overlaps(it)) }
            assertTrue("$area runs under the gesture bar", area.bottom <= frame.canvas.height - frame.insetBottom)
        }
    }

    @Test
    fun `a long list scrolls rather than growing past the screen`() {
        val fit = frame.scaleRowsThatFit()
        assertTrue("at least four rows fit, got $fit", fit >= 4)
        assertEquals(frame.scaleCard(fit).height, frame.scaleCard(MAX_SCALE_ENTRIES).height, 0.001f)
    }

    @Test
    fun `an entry row's controls sit inside it, apart, and big enough to hit`() {
        val card = frame.scaleCard(4)
        val row = scaleCardRow(card, d, 0)
        val parts = scaleRowParts(row, d).all
        parts.forEach {
            assertTrue("$it escapes its row", it.left >= row.left && it.right <= row.right &&
                it.top >= row.top && it.bottom <= row.bottom)
            assertTrue("${it.width / d}x${it.height / d}dp", it.width / d >= 36f && it.height / d >= 36f)
        }
        parts.forEachIndexed { i, a -> parts.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) } }

        val add = scaleCardAdd(card, d)
        assertTrue("add sits below the last row", add.top >= scaleCardRow(card, d, 3).bottom)
        assertTrue("add escapes the card", add.bottom <= card.bottom)
    }

    @Test
    fun `the widened scale card still fits the screen`() {
        val card = frame.scaleCard(MAX_SCALE_ENTRIES)
        assertTrue("runs off the right: ${card.right} of ${frame.canvas.width}", card.right <= frame.canvas.width - frame.insetRight)
    }

    @Test
    fun `the root page's controls sit inside it, apart, and big enough to hit`() {
        val page = frame.scaleRootPage()
        val slider = rootSlider(page, d)
        val less = rootFineLess(page, d)
        val more = rootFineMore(page, d)
        listOf(slider, less, more).forEach {
            assertTrue("$it escapes the page", it.left >= page.left && it.right <= page.right && it.bottom <= page.bottom)
        }
        assertFalse(slider.overlaps(less))
        assertFalse(less.overlaps(more))
        assertTrue("slider too short to aim along: ${slider.width / d}dp", slider.width / d >= 500f)
    }

    /** Every beats-per-bar button wide enough for a finger. */
    @Test
    fun `the beats per bar buttons are big enough to hit`() {
        val row = transportBeatsRow(card, d)
        val width = (row.width - 5f * d * (BEATS_PER_BAR.steps - 1)) / BEATS_PER_BAR.steps
        assertTrue("buttons ${width / d}dp wide", width / d >= 30f)
    }
}
