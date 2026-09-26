package io.github.forrcaho.patchgarden

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real PatchCanvas, driven by real pointer events through its real gesture loop.
 *
 * Every gesture fault this project has had was found by a finger, with a clean compile and a
 * green suite: undo buttons that drew and could not be hit, an envelope editor that swallowed
 * the tap closing its panel, a long press that caught the wrong timeout and did nothing. The
 * tests beside this one check the geometry and the model; none of them ever ran the loop that
 * decides what a touch *is*, and that is where each of those lived.
 *
 * Targets are found the way the drawing finds them -- the same Frame, panel and envelope
 * functions -- and outcomes are read off the model, so a test says "a finger here does this"
 * without knowing how the loop gets there.
 *
 * At the reference device's density, 2.4375, and never 1.0: world units are dp and the screen
 * is pixels, and the two agree only at a density of one, which is where a bug converting
 * between them hides.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 36 because Robolectric 4.17's image of 37 lacks InputManager.getInstance, which touch
// injection needs. Nothing the gesture loop does differs between the two.
@Config(sdk = [36], qualifiers = "w986dp-h443dp-land-390dpi")
class GestureTest {
    @get:Rule val compose = createComposeRule()

    /** A PatchCanvas filling the screen, with a camera the test can aim through. */
    private inner class Host(val patch: Patch = Patch()) {
        var canUndo by mutableStateOf(false)
        var undone = 0
        var outputSwitched = 0
        lateinit var camera: Camera

        init {
            compose.setContent {
                camera = rememberCamera()
                PatchCanvas(
                    patch, Modifier.fillMaxSize(),
                    canUndo = canUndo, onUndo = { undone++ },
                    onToggleOutput = { outputSwitched++ },
                    camera = camera,
                )
            }
            compose.waitForIdle()
        }

        val d get() = compose.density.density
        val frame: Frame
            get() {
                val size = compose.onRoot().fetchSemanticsNode().size
                return Frame(
                    Size(size.width.toFloat(), size.height.toFloat()), d,
                    0f, 0f, 0f, 0f, compose.density.fontScale,
                )
            }

        fun at(world: Offset) = camera.toScreen(world)
        fun body(m: PatchModule) = at(m.bounds.center)
        fun port(ref: PortRef) = portScreen(patch, ref, camera, frame)!!

        fun tap(p: Offset) {
            compose.onRoot().performTouchInput { click(p) }
            compose.waitForIdle()
        }

        /** Held still well past the canvas's own delay, which is longer than the platform's. */
        fun hold(p: Offset) {
            compose.onRoot().performTouchInput {
                down(p)
                advanceEventTime(viewConfiguration.longPressTimeoutMillis * 2)
                up()
            }
            compose.waitForIdle()
        }

        fun drag(from: Offset, to: Offset, steps: Int = 12) {
            compose.onRoot().performTouchInput {
                down(from)
                for (i in 1..steps) moveTo(from + (to - from) * (i / steps.toFloat()))
                up()
            }
            compose.waitForIdle()
        }

        /** The menu a long press at [anchor] opened, laid out as the canvas lays it out. */
        fun menu(anchor: Offset, targetId: Long?, node: Int = -1) = menuLayout(
            menuItems(patch, targetId, node = node), anchor, d, frame.canvas, frame.fontScale,
        )

        fun choose(anchor: Offset, targetId: Long?, item: MenuItem, node: Int = -1) {
            val tile = menu(anchor, targetId, node).tiles.firstOrNull { it.second == item }
            assertNotNull("$item is not on that menu", tile)
            tap(tile!!.first.center)
        }

        /** Presses a key on the number keypad, which is ordinary composables with labels. */
        fun key(label: String) {
            // The last match: the entry above the keys can read the same as a key.
            compose.onAllNodesWithText(label).let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
            compose.waitForIdle()
        }
    }

    private fun out(m: PatchModule, i: Int = 0) = PortRef(m.id, PortDirection.OUTPUT, i)
    private fun into(m: PatchModule, i: Int = 0) = PortRef(m.id, PortDirection.INPUT, i)

    // ------------------------------------------------------------------ the canvas

    @Test
    fun `a tap on one jack and then another patches a cable between them`() {
        val host = Host()
        val osc = host.patch.add(Types.Osc, Offset(40f, 40f))!!
        val filter = host.patch.add(Types.Filter, Offset(300f, 40f))!!
        compose.waitForIdle()

        host.tap(host.port(out(osc)))
        host.tap(host.port(into(filter)))
        assertTrue(Connection(out(osc), into(filter)) in host.patch.connections)

        // Audio into a note input is refused, not made.
        host.tap(host.port(out(osc)))
        host.tap(host.port(into(filter, 1)))
        assertFalse(host.patch.connections.any { it.to == into(filter, 1) })
    }

    @Test
    fun `a module follows the finger, in dp, at a density that is not one`() {
        val host = Host()
        val osc = host.patch.add(Types.Osc, Offset(40f, 40f))!!
        compose.waitForIdle()
        val from = host.body(osc)

        host.drag(from, from + Offset(300f, 120f))
        assertEquals("across, in dp", 40f + 300f / host.d, osc.position.x, 1f)
        assertEquals("down, in dp", 40f + 120f / host.d, osc.position.y, 1f)
    }

    @Test
    fun `a drag on empty canvas moves the view and not the modules`() {
        val host = Host()
        val osc = host.patch.add(Types.Osc, Offset(40f, 40f))!!
        compose.waitForIdle()
        val pan = host.camera.pan
        val empty = host.at(Offset(500f, 300f))

        host.drag(empty, empty + Offset(-200f, 50f))
        assertEquals(Offset(40f, 40f), osc.position)
        assertEquals(-200f, host.camera.pan.x - pan.x, 2f)
        assertEquals(50f, host.camera.pan.y - pan.y, 2f)
    }

    /**
     * The first gesture fault, found on the device: the undo button drew as soon as there was
     * something to undo, and did not answer, because the gesture loop captured `canUndo` as it
     * was at launch -- false -- and never saw it change.
     */
    @Test
    fun `the undo button answers once there is something to undo`() {
        val host = Host()
        host.canUndo = true
        compose.waitForIdle()
        host.tap(host.frame.historyRect(redo = false).center)
        assertEquals(1, host.undone)
    }

    @Test
    fun `a long press on empty canvas is the add menu, and a tile adds that module there`() {
        val host = Host()
        val anchor = host.at(Offset(400f, 200f))
        host.hold(anchor)
        host.choose(anchor, null, MenuItem.Add(Types.Osc))

        val osc = host.patch.modules.single { it.type == Types.Osc }
        assertTrue("centered on the press", osc.bounds.contains(host.camera.toWorld(anchor)))
    }

    /**
     * At the reference device's text size, 1.5, where a tile grows to hold its label and the
     * add menu once ran a row off the bottom of the screen -- New patch was the tile below the
     * edge. The last tile is the one that tells.
     */
    @Test
    fun `at the reference device's text size, the add menu's last tile is still a tile`() {
        RuntimeEnvironment.setFontScale(1.5f)
        val host = Host()
        assertEquals("the setting reached the canvas", 1.5f, host.frame.fontScale, 0.001f)
        host.patch.add(Types.Osc, Offset(40f, 40f))!!
        compose.waitForIdle()

        val anchor = host.at(Offset(600f, 300f))
        host.hold(anchor)
        val tiles = host.menu(anchor, null).tiles
        val screen = androidx.compose.ui.geometry.Rect(Offset.Zero, host.frame.canvas)
        assertTrue(tiles.all { screen.contains(it.first.topLeft) && screen.contains(it.first.bottomRight - Offset(1f, 1f)) })
        host.choose(anchor, null, MenuItem.NewPatch)
        assertTrue("New patch cleared it", host.patch.free.isEmpty())
    }

    @Test
    fun `a long press on a module is its menu, and Delete removes it`() {
        val host = Host()
        val osc = host.patch.add(Types.Osc, Offset(40f, 40f))!!
        compose.waitForIdle()
        val anchor = host.body(osc)
        host.hold(anchor)
        host.choose(anchor, osc.id, MenuItem.Delete(osc.id))
        assertNull(host.patch.module(osc.id))
    }

    @Test
    fun `a tap on a module opens its panel, and a tap outside the panel closes it`() {
        val host = Host()
        val lfo = host.patch.add(Types.Lfo, Offset(40f, 40f))!!
        compose.waitForIdle()
        host.tap(host.body(lfo))
        assertTrue(lfo.expanded)

        val panel = panelRect(host.frame)
        host.tap(Offset(panel.center.x, (panel.bottom + host.frame.canvas.height) / 2f))
        assertFalse(lfo.expanded)
    }

    @Test
    fun `two fingers spreading zoom in, and move no module`() {
        val host = Host()
        val osc = host.patch.add(Types.Osc, Offset(40f, 40f))!!
        compose.waitForIdle()
        val center = host.at(Offset(500f, 250f))
        compose.onRoot().performTouchInput {
            pinch(
                center - Offset(60f, 0f), center - Offset(240f, 0f),
                center + Offset(60f, 0f), center + Offset(240f, 0f),
            )
        }
        compose.waitForIdle()
        assertTrue("zoomed in: ${host.camera.zoom}", host.camera.zoom > 1.5f)
        assertEquals(Offset(40f, 40f), osc.position)
    }

    @Test
    fun `a tap on the Out rail is its switch`() {
        val host = Host()
        host.tap(host.frame.railRect(host.patch.module(OUT_ID)!!).center)
        assertEquals(1, host.outputSwitched)
    }

    @Test
    fun `a subpatch is made by choosing its modules with taps, then Subpatch`() {
        val host = Host()
        val osc = host.patch.add(Types.Osc, Offset(40f, 40f))!!
        val filter = host.patch.add(Types.Filter, Offset(300f, 40f))!!
        val lfo = host.patch.add(Types.Lfo, Offset(40f, 260f))!!
        compose.waitForIdle()

        val anchor = host.at(Offset(600f, 300f))
        host.hold(anchor)
        host.choose(anchor, null, MenuItem.StartSubpatch(Types.Subpatch))
        host.tap(host.body(osc))
        host.tap(host.body(filter))
        host.tap(host.frame.selectionButton(done = true).center)

        val box = host.patch.modules.single { it.type == Types.Subpatch }
        assertEquals(box.id, osc.parent)
        assertEquals(box.id, filter.parent)
        assertEquals("the one not chosen stays out", TOP, lfo.parent)
    }

    @Test
    fun `a tap on a box goes inside, and a tap on the first crumb comes back out`() {
        val host = Host()
        val osc = host.patch.add(Types.Osc, Offset(40f, 40f))!!
        val box = host.patch.makeSubpatch(setOf(osc.id))!!
        compose.waitForIdle()
        host.tap(host.body(box))
        assertEquals(box.id, host.patch.scope)
        host.tap(host.frame.breadcrumbChip(0).center)
        assertEquals(TOP, host.patch.scopeOrTop)
    }

    @Test
    fun `a tap on a knob's reading opens the keypad, and what is typed is the knob`() {
        val host = Host()
        val filter = host.patch.add(Types.Filter, Offset(40f, 40f))!!
        compose.waitForIdle()
        host.tap(host.body(filter))
        val slot = filter.type.rowParams.indexOf(0)
        val row = panelRowAt(panelRect(host.frame), host.d, filter.type, filter.type.rowParams.size, slot)
        // The reading sits at the row's right end, at its top.
        host.tap(Offset(row.right - 10f * host.d, row.top + 8f * host.d))
        listOf("2", "5", "0", KEY_OK).forEach(host::key)
        assertEquals(250f, filter.params[0], 0.001f)
        assertTrue("the panel it was typed into is still open", filter.expanded)
    }

    // ------------------------------------------------------------------ the envelope editor

    /** An Env with its panel open, and where its editor is drawn. */
    private inner class EnvRig {
        val host = Host()
        val env = host.patch.add(Types.Env, Offset(40f, 40f))!!

        init {
            compose.waitForIdle()
            host.tap(host.body(env))
            check(env.expanded)
        }

        val grid get() = panelGrid(panelRect(host.frame), host.d, Types.Env)
        val geo get() = envGeometry(grid, env, host.d)
        fun node(i: Int) = envNodes(geo, env)[i]
    }

    /**
     * The envelope's loop once claimed every touch on the screen -- including the tap outside
     * the panel that is the only way to close one, with the breadcrumb hidden, so a panel with
     * no door.
     */
    @Test
    fun `a tap outside an envelope's panel still closes it`() {
        val rig = EnvRig()
        val panel = panelRect(rig.host.frame)
        rig.host.tap(Offset(panel.center.x, (panel.bottom + rig.host.frame.canvas.height) / 2f))
        assertFalse(rig.env.expanded)
    }

    /**
     * The long press on a node caught kotlinx's TimeoutCancellationException where the pointer
     * scope throws Compose's own, so it compiled, never matched, and the press did nothing.
     */
    @Test
    fun `a long press on an envelope node is its menu, and Remove takes the node away`() {
        val rig = EnvRig()
        val before = rig.env.segments.size
        assertTrue("the default has a node to remove", before > 1)
        val node = rig.node(1)
        rig.host.hold(node)
        rig.host.choose(node, rig.env.id, MenuItem.RemoveNode(rig.env.id, 1), node = 1)
        assertEquals(before - 1, rig.env.segments.size)
    }

    /** A tap is what a finger does when it means to grab; it used to remove the node. */
    @Test
    fun `a tap on an envelope node changes nothing`() {
        val rig = EnvRig()
        val before = rig.env.segments.toList()
        rig.host.tap(rig.node(1))
        assertEquals(before, rig.env.segments.toList())
        assertTrue("and leaves the panel open", rig.env.expanded)
    }

    @Test
    fun `a tap on the line adds a node there`() {
        val rig = EnvRig()
        val before = rig.env.segments.size
        val x = (rig.geo.x(envFrom(rig.env, 0)) + rig.node(0).x) / 2f
        rig.host.tap(Offset(x, envCurveY(rig.geo, rig.env, 0, 0.5f)))
        assertEquals(before + 1, rig.env.segments.size)
    }

    /**
     * Up bends the line up, whichever way the segment travels, and anywhere in the segment's
     * column does it -- the fill under the line is what looks like the segment, and for two
     * builds only a band around the stroke answered.
     */
    @Test
    fun `a drag up in a segment's fill bends its line up`() {
        val rig = EnvRig()
        val segment = 0
        val left = rig.geo.x(envFrom(rig.env, segment))
        val right = rig.node(segment).x
        val midBefore = envCurveY(rig.geo, rig.env, segment, 0.5f)
        // Down in the fill, well below the line and clear of both nodes.
        val press = Offset((left + right) / 2f, (midBefore + rig.geo.area.bottom) / 2f)
        rig.host.drag(press, press - Offset(0f, 150f))
        val midAfter = envCurveY(rig.geo, rig.env, segment, 0.5f)
        assertTrue("the middle of the line rose: $midBefore -> $midAfter", midAfter < midBefore - 5f)
    }

    @Test
    fun `a tap on an envelope's time rail types that segment's time, in milliseconds`() {
        val rig = EnvRig()
        val rail = envTimeRail(rig.grid, rig.host.d)
        val edges = envCellEdges(rig.geo, rig.env, envCellMin(rig.host.d, rig.host.frame.fontScale))
        rig.host.tap(envCell(rail, edges, 0).center)
        listOf("1", "2", KEY_OK).forEach(rig.host::key)
        assertEquals(0.012f, rig.env.segments[0].time, 1e-6f)
    }

    // ------------------------------------------------------------------ the dot grid

    /** A Seq with its panel open, and where its grid is drawn. */
    private inner class SeqRig {
        val host = Host()
        val seq = host.patch.add(Types.Seq, Offset(40f, 40f))!!

        init {
            compose.waitForIdle()
            host.tap(host.body(seq))
            check(seq.expanded)
        }

        val panel get() = panelRect(host.frame)
        val grid get() = panelGrid(panel, host.d, Types.Seq)
        val rows get() = gridWindow(seq, grid, host.d, Scale.Chromatic).rows

        /** The middle of the cell [column] steps along and [row] rows down. */
        fun cell(column: Int, row: Int) = Offset(
            grid.left + (column + 0.5f) * grid.width / dotColumns(seq),
            grid.top + (row + 0.5f) * grid.height / rows,
        )

        fun degreeAt(at: Offset) = panelCellAt(panel, host.d, seq, at, Scale.Chromatic)!!.second
    }

    @Test
    fun `a tap on the dot grid adds a dot, and a tap on the dot takes it away`() {
        val rig = SeqRig()
        val at = rig.cell(3, 2)
        rig.host.tap(at)
        assertEquals(listOf(3 to rig.degreeAt(at)), rig.seq.dots.map { it.step to it.degree })
        rig.host.tap(at)
        assertTrue(rig.seq.dots.isEmpty())
    }

    @Test
    fun `a dot dragged across is stretched, in quarter steps`() {
        val rig = SeqRig()
        val at = rig.cell(2, 2)
        rig.host.tap(at)
        val before = rig.seq.dots.single().length
        // From the dot's right end to three steps further on.
        rig.host.drag(at, rig.cell(5, 2))
        assertTrue("stretched: $before -> ${rig.seq.dots.single().length}", rig.seq.dots.single().length > before)
        assertEquals("and still where it started", 2, rig.seq.dots.single().step)
    }

    @Test
    fun `with the dots locked, a drag down a dot is its velocity`() {
        val rig = SeqRig()
        val at = rig.cell(4, 3)
        rig.host.tap(at)
        val dot = rig.seq.dots.single()
        rig.host.tap(panelLockChip(rig.panel, rig.host.d).center)
        rig.host.drag(at, at + Offset(0f, 200f))
        val after = rig.seq.dots.single()
        assertEquals("not moved", dot.step to dot.degree, after.step to after.degree)
        assertTrue("softer: ${dot.velocity} -> ${after.velocity}", after.velocity < dot.velocity)
    }

    // ------------------------------------------------------------------ the interval chooser

    /**
     * Opens [module]'s chooser from its header chip, taps [picks] in turn -- it stays open between
     * them, since a step is two choices -- and closes it with a tap away from the tiles.
     */
    private fun Host.chooseInterval(module: PatchModule, vararg picks: IntervalPick) {
        val panel = panelRect(frame)
        tap(panelIntervalChip(panel, d, frame.fontScale).center)
        val chooser = intervalChooser(panel, d, frame.fontScale, module.type.canBeFree)
        picks.forEach { pick ->
            val tile = chooser.tiles.firstOrNull { it.second == pick }
            assertNotNull("$pick is not offered", tile)
            tap(tile!!.first.center)
        }
        tap(chooser.readout + Offset(5f * d, 5f * d))
    }

    @Test
    fun `a sequencer is set to five to a beat by its divisions alone`() {
        val rig = SeqRig()
        rig.host.chooseInterval(rig.seq, IntervalPick.Divisions(5))
        assertEquals("one beat, the default, into five", Interval(1, 5), rig.seq.interval)
        assertTrue("and the panel is still open, on its grid", rig.seq.expanded)
    }

    @Test
    fun `a quarter triplet is two beats in three, chosen with the chooser open throughout`() {
        val rig = SeqRig()
        rig.host.chooseInterval(rig.seq, IntervalPick.Beats(2), IntervalPick.Divisions(3))
        assertEquals(Interval(2, 3), rig.seq.interval)
    }

    @Test
    fun `a tap away from the chooser's tiles closes it and changes nothing`() {
        val rig = SeqRig()
        val before = rig.seq.interval
        rig.host.chooseInterval(rig.seq)
        assertEquals(before, rig.seq.interval)
        // Closed: the next tap lands on the grid, as it would with no chooser ever opened.
        rig.host.tap(rig.cell(1, 1))
        assertEquals(1, rig.seq.dots.size)
    }

    @Test
    fun `an LFO synced from its header leaves its rate faint, and free brings it back`() {
        val host = Host()
        val lfo = host.patch.add(Types.Lfo, Offset(40f, 40f))!!
        compose.waitForIdle()
        host.tap(host.body(lfo))
        val rate = Types.Lfo.params.indexOfFirst { it.name == "rate" }
        assertTrue(lfo.isLive(rate))

        host.chooseInterval(lfo, IntervalPick.Beats(4))
        assertEquals("from free, divisions start at one", Interval(4, 1), lfo.interval)
        assertFalse(lfo.isLive(rate))

        host.chooseInterval(lfo, IntervalPick.Free)
        assertTrue(lfo.interval.free)
        assertTrue(lfo.isLive(rate))
    }

    // ------------------------------------------------------------------ subpatch controls

    @Test
    fun `the jack chip on a subpatch's Controls panel gives the box a jack`() {
        val host = Host()
        val filter = host.patch.add(Types.Filter, Offset(40f, 40f))!!
        val box = host.patch.makeSubpatch(setOf(filter.id))!!
        box.subpatchPorts!!.promoted += ParamRef(filter.id, 0)
        compose.waitForIdle()

        val anchor = host.body(box)
        host.hold(anchor)
        host.choose(anchor, box.id, MenuItem.Controls(box.id))
        assertTrue(box.expanded)

        val rows = host.patch.panelRows(box)
        val row = panelRowAt(panelRect(host.frame), host.d, box.type, rows.size, 0)
        host.tap(panelModChipOn(row, host.d).center)
        assertEquals(1, box.subpatchPorts!!.inputs.size)
        assertTrue(filter.isExposed(0))
    }

    @Test
    fun `inside a box, the Controls chip beside the breadcrumb opens its panel`() {
        val host = Host()
        val filter = host.patch.add(Types.Filter, Offset(40f, 40f))!!
        val box = host.patch.makeSubpatch(setOf(filter.id))!!
        box.subpatchPorts!!.promoted += ParamRef(filter.id, 0)
        compose.waitForIdle()

        host.tap(host.body(box))
        assertEquals("a tap on a box goes inside", box.id, host.patch.scope)
        host.tap(host.frame.breadcrumbChip(host.patch.scopePath().size).center)
        assertTrue(box.expanded)
    }
}
