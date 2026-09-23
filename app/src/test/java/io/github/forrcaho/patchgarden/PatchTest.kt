package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The graph rules that the rest of the app is entitled to assume. */
class PatchModelTest {

    /** A module's notes output, found by kind so no test writes a port index down. */
    private fun notesOut(module: PatchModule) = PortRef(
        module.id, PortDirection.OUTPUT,
        module.type.outputs.indexOfFirst { it.kind == SignalKind.NOTE },
    )

    @Test
    fun `patch starts with both rails at reserved ids`() {
        val p = Patch()
        assertEquals(Types.Out, p.module(OUT_ID)?.type)
        assertEquals(Types.In, p.module(IN_ID)?.type)
        assertEquals(2, p.pinned.size)
        assertTrue(p.free.isEmpty())
    }

    @Test
    fun `an input takes one source, so re-patching replaces`() {
        val p = Patch()
        val a = p.add(Types.Osc, Offset.Zero)!!
        val b = p.add(Types.Osc, Offset.Zero)!!
        val target = PortRef(OUT_ID, PortDirection.INPUT, 0)

        p.connect(PortRef(a.id, PortDirection.OUTPUT, 0), target)
        p.connect(PortRef(b.id, PortDirection.OUTPUT, 0), target)

        assertEquals(1, p.connections.size)
        assertEquals(b.id, p.connections.single().from.moduleId)
    }

    @Test
    fun `a note input merges its sources rather than replacing them`() {
        val p = Patch()
        val a = p.add(Types.Steps, Offset.Zero)!!
        val b = p.add(Types.Steps, Offset.Zero)!!
        val voice = p.add(Types.Osc, Offset.Zero)!!
        val target = PortRef(voice.id, PortDirection.INPUT, 0)

        // Single source exists to stop signals summing where nobody asked for it. Merging
        // event streams hides nothing -- every note stays itself and arrives when it
        // arrived -- and a voice fed by two sequencers is the obvious patch.
        assertTrue(p.connect(notesOut(a), target))
        assertTrue(p.connect(notesOut(b), target))

        assertEquals(2, p.connections.size)
        assertEquals(setOf(a.id, b.id), p.connections.map { it.from.moduleId }.toSet())
    }

    @Test
    fun `patching a note cable that is already there takes it back`() {
        val p = Patch()
        val steps = p.add(Types.Steps, Offset.Zero)!!
        val voice = p.add(Types.Osc, Offset.Zero)!!
        val src = notesOut(steps)
        val target = PortRef(voice.id, PortDirection.INPUT, 0)

        p.connect(src, target)
        // Nothing replaces a note cable, so this is the only way a finger has to remove
        // one of several: unpatching the port removes all of them.
        assertTrue(p.connect(src, target))
        assertTrue(p.connections.isEmpty())
    }

    @Test
    fun `a cable runs only between ports of the same kind`() {
        val p = Patch()
        val steps = p.add(Types.Steps, Offset.Zero)!!
        val voice = p.add(Types.Osc, Offset.Zero)!!
        val osc = p.add(Types.Osc, Offset.Zero)!!
        val filter = p.add(Types.Filter, Offset.Zero)!!
        val env = p.add(Types.Env, Offset.Zero)!!
        val lfo = p.add(Types.Lfo, Offset.Zero)!!

        fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
        fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)
        val outL = PortRef(OUT_ID, PortDirection.INPUT, 0)

        // Steps: 0 notes. Osc: 0 notes in, audio out. Filter: 0 in, audio. Env: 0 notes in,
        // modulation out. Lfo: modulation out.
        assertFalse("audio is not a note", p.connect(out(osc, 0), into(voice, 0)))
        assertFalse("nor is modulation", p.connect(out(env, 0), into(voice, 0)))
        assertFalse("nor do notes go into audio", p.connect(out(steps, 0), outL))
        // Each of these was legal while typing was advisory.
        assertFalse("modulation is not audio", p.connect(out(env, 0), into(filter, 0)))
        assertFalse("and not at the rail either", p.connect(out(lfo, 0), outL))
        assertFalse("audio does not open an envelope", p.connect(out(filter, 0), into(env, 0)))
        assertTrue("nothing above was patched", p.connections.isEmpty())

        assertTrue("notes to notes", p.connect(out(steps, 0), into(voice, 0)))
        assertTrue("and an envelope is opened by them too", p.connect(out(steps, 0), into(env, 0)))
        assertTrue("audio to audio", p.connect(out(osc, 0), into(filter, 0)))
        assertEquals(3, p.connections.size)
    }

    /**
     * Pulse has no port anywhere in the catalog: Env was the last thing taking a gate
     * and it takes notes now. The kind stays, for a module that wants a bare trigger, and
     * so does the rule -- asserted here against the kinds themselves, since there is no
     * longer a pair of ports to try it on.
     */
    @Test
    fun `pulse keeps its rule although nothing carries one yet`() {
        assertTrue(Types.byName.values.none { type ->
            (type.inputs + type.outputs).any { it.kind == SignalKind.PULSE }
        })
        // Note into a pulse input is the one conversion the design allows, and it is
        // still refused: the engine rejects note against non-note outright, so a cable
        // allowed here would be dropped on the far side of the queue with nothing on
        // screen to say why. It arrives when a pulse is an event.
        assertFalse(SignalKind.NOTE.patchesTo(SignalKind.PULSE))
        assertFalse(SignalKind.PULSE.patchesTo(SignalKind.NOTE))
        assertTrue(SignalKind.PULSE.patchesTo(SignalKind.PULSE))
    }

    // ------------------------------------------------------------- the drone grid

    /** The panel's grid area at the reference device's landscape size. */
    private fun droneArea(): Rect = Rect(0f, 0f, 400f, 208f)

    /**
     * The gate was `params.isNotEmpty()`, from when knobs were the only thing a panel
     * held. A drone has a grid and no knobs, so its panel would not open and the grid it
     * exists for could not be reached -- the module took the tap and did nothing. Found by
     * tapping it on a screen, which is the only thing that could have.
     */
    /**
     * A grid and nothing else, which the Drone was until it took a transpose knob on
     * 2026-09-19. No module is that now; the rule stays for the next one that is.
     */
    private val gridOnly = ModuleType(
        "GridOnly", emptyList(), listOf(Port("notes", SignalKind.NOTE)), Color(0xFF91DA58),
        stepCount = DRONE_CELLS, grid = GridKind.DRONE,
    )

    @Test
    fun `a module with a grid and no knobs still opens`() {
        assertTrue("a module with nothing but its grid", gridOnly.params.isEmpty())
        assertTrue("must still open", gridOnly.hasPanel)
        Types.palette.forEach { type ->
            assertEquals(
                "${type.name} opens if and only if it has something to show",
                type.params.isNotEmpty() || type.grid != GridKind.NONE,
                type.hasPanel,
            )
        }
    }

    @Test
    fun `a grid with no knobs under it takes the whole panel body`() {
        val panel = Rect(0f, 0f, 800f, 400f)
        // A third of the screen saying nothing, with the thing being edited squeezed
        // above it, is what this avoids.
        assertTrue(
            "a knobless grid reaches further down than a sequencer's",
            panelGrid(panel, 1f, gridOnly).bottom > panelGrid(panel, 1f, Types.Steps).bottom,
        )
        assertEquals(
            "and a sequencer's is unchanged",
            panelGrid(panel, 1f).bottom,
            panelGrid(panel, 1f, Types.Steps).bottom,
        )
    }

    @Test
    fun `a fresh drone sounds nothing and each cell is its own degree`() {
        val p = Patch()
        val drone = p.add(Types.Drone, Offset.Zero)!!
        assertEquals(DRONE_CELLS, drone.steps.size)
        assertTrue("a drone that started holding a chord would be one you switch off",
            drone.steps.none { it.on })
        drone.steps.forEachIndexed { i, step -> assertEquals(i, step.degree) }
    }

    @Test
    fun `degrees ascend up the rows and octaves across the columns`() {
        val p = Patch()
        val drone = p.add(Types.Drone, Offset.Zero)!!
        val area = droneArea()
        val scale = Scale.Chromatic
        val window = droneWindow(drone, area, 1f, scale)
        val rows = window.rows

        // The bottom row of the first column is the lowest degree on screen, and the row
        // above it is one degree higher: pitch ascends up the screen.
        val bottom = droneDegree(window, rows - 1, 0, scale)
        val above = droneDegree(window, rows - 2, 0, scale)
        assertEquals(above, bottom + 1)

        // The same row one column right is the same degree an octave up, which is what
        // makes a column an octave rather than just the next twelve degrees.
        assertEquals(bottom + scale.size, droneDegree(window, rows - 1, 1, scale))
    }

    @Test
    fun `a scale with many degrees trades columns for rows`() {
        // 22 degrees to a period: four octaves of it is 88 cells and there are 64, so it
        // gets two columns rather than running off the end of the grid.
        assertEquals(DRONE_OCTAVES, droneColumns(Scale.Chromatic))
        assertEquals(2, droneColumns(Scale.equal("22", 22)))
        assertEquals(1, droneColumns(Scale.equal("40", 40)))
        // Whatever the scale, and however far it is scrolled, no cell lands past the end --
        // including a scale of 64, whose one column cannot scroll its top past cell 63.
        listOf(5, 7, 12, 22, 40, 64).forEach { size ->
            val scale = Scale.equal("$size", size)
            val drone = Patch().add(Types.Drone, Offset.Zero)!!
            drone.gridBottom = droneWindow(drone, droneArea(), 1f, scale).scrolledBy(1000)
            val window = droneWindow(drone, droneArea(), 1f, scale)
            val top = droneDegree(window, 0, droneColumns(scale) - 1, scale)
            assertTrue("$size-degree scale reached cell $top", top < DRONE_CELLS)
        }
    }

    @Test
    fun `a drone's bottom row is always a degree of its first period`() {
        val drone = Patch().add(Types.Drone, Offset.Zero)!!
        val scale = Scale.Chromatic
        drone.gridBottom = 900
        assertEquals(scale.size - 1, droneWindow(drone, droneArea(), 1f, scale).bottom)
        drone.gridBottom = -900
        assertEquals(0, droneWindow(drone, droneArea(), 1f, scale).bottom)
    }

    /**
     * The reported bug: the grid jumped when the scale changed. The drag wrote the scroll
     * position with no limit, so scrolling past the top of 12-TET stored an overshoot the
     * picture never showed, and a longer scale then clamped that overshoot somewhere else.
     * Driven through scrolledBy, which is exactly what the drag writes.
     */
    @Test
    fun `a drone keeps its bottom degree when the scale changes`() {
        val drone = Patch().add(Types.Drone, Offset.Zero)!!
        val area = droneArea() // eight rows at this size
        val twelve = Scale.Chromatic
        val twentyTwo = Scale.equal("22", 22)

        // A long drag upward, far past the top of 12-TET.
        drone.gridBottom = gridWindow(drone, area, 1f, twelve).scrolledBy(100)
        val shown = gridWindow(drone, area, 1f, twelve).bottom
        assertEquals("the top of 12-TET, and nothing stored beyond it", 11, drone.gridBottom)
        assertEquals(11, shown)

        assertEquals(
            "the same degree stays on the bottom row in a longer scale",
            shown, gridWindow(drone, area, 1f, twentyTwo).bottom,
        )
        assertEquals(
            "and coming back leaves it there too",
            shown, gridWindow(drone, area, 1f, twelve).bottom,
        )
    }

    @Test
    fun `dragging back moves the grid at once, with no overshoot to undo first`() {
        val drone = Patch().add(Types.Drone, Offset.Zero)!!
        val area = droneArea()
        drone.gridBottom = gridWindow(drone, area, 1f, Scale.Chromatic).scrolledBy(100)
        drone.gridBottom = gridWindow(drone, area, 1f, Scale.Chromatic).scrolledBy(-1)
        assertEquals(10, gridWindow(drone, area, 1f, Scale.Chromatic).bottom)
    }

    /**
     * What the phone showed: a scale list alternating 12-TET and a seven-degree scale
     * redrew the open grid every few bars, from as many rows as fit with the chosen degree
     * at the bottom to seven taller rows with degree 0 there. The rows now stay put and run
     * past the top of the short scale instead.
     */
    @Test
    fun `a scale shorter than the rows keeps the bottom degree and runs past its period`() {
        val drone = Patch().add(Types.Drone, Offset.Zero)!!
        drone.gridBottom = 4
        val twelve = droneWindow(drone, droneArea(), 1f, Scale.Chromatic)
        val seven = Scale.equal("7", 7)
        val short = droneWindow(drone, droneArea(), 1f, seven)

        assertEquals("the same bottom degree", 4, short.bottom)
        assertEquals("and the same rows, so nothing reshapes", twelve.rows, short.rows)

        // Eight rows from degree 4 of a seven-degree scale reach degree 11, which is degree
        // 4 of the next period: the top of one column is the bottom of the next.
        assertEquals(
            droneDegree(short, short.rows - 1, 1, seven),
            droneDegree(short, 0, 0, seven),
        )
    }

    @Test
    fun `a bottom past the end of a shorter scale lands on its last degree`() {
        val drone = Patch().add(Types.Drone, Offset.Zero)!!
        drone.gridBottom = 15 // a degree only a longer scale has
        assertEquals(15, droneWindow(drone, droneArea(), 1f, Scale.equal("22", 22)).bottom)
        assertEquals(11, droneWindow(drone, droneArea(), 1f, Scale.Chromatic).bottom)
    }

    @Test
    fun `a sequencer scrolls across its span and never loses a note beyond it`() {
        val steps = Patch().add(Types.Steps, Offset.Zero)!!
        val area = Rect(0f, 0f, 400f, 208f)
        val window = gridWindow(steps, area, 1f, Scale.Chromatic)
        assertEquals("three octaves below the key", -36, window.lowest)
        assertEquals("four above", 47, window.highest)
        assertEquals(window.lowest, window.scrolledBy(-1000))
        assertEquals(window.highest - window.rows + 1, window.scrolledBy(1000))

        // A note written far above the span widens it, so it can still be scrolled to.
        steps.setStep(0, Step(90))
        val wider = gridWindow(steps, area, 1f, Scale.Chromatic)
        assertEquals(90, wider.highest)
        assertEquals(90, wider.copy(bottom = wider.scrolledBy(1000)).top)
    }

    @Test
    fun `the scroll bar shows where the view is and is absent when nothing is hidden`() {
        val area = Rect(0f, 0f, 400f, 208f)
        assertNull(gridScrollBar(area, 1f, GridWindow(bottom = 0, rows = 8, lowest = 0, highest = 6)))

        val atTop = gridScrollBar(area, 1f, GridWindow(bottom = 14, rows = 8, lowest = 0, highest = 21))!!
        val atBottom = gridScrollBar(area, 1f, GridWindow(bottom = 0, rows = 8, lowest = 0, highest = 21))!!
        val (track, top) = atTop
        assertEquals("scrolled to the top, the thumb is at the top", track.top, top.top, 0.01f)
        assertEquals("scrolled to the bottom, at the bottom", track.bottom, atBottom.second.bottom, 0.01f)
        assertEquals("and the thumb is the share on screen", track.height * 8 / 22, top.height, 0.01f)
        assertTrue("beside the grid, not on it", track.left > area.right)
    }

    @Test
    fun `a sequencer's thumb keeps a size a finger can see`() {
        val area = Rect(0f, 0f, 400f, 208f)
        val (track, thumb) = gridScrollBar(area, 1f, GridWindow(bottom = 0, rows = 8, lowest = -500, highest = 500))!!
        assertEquals(16f, thumb.height, 0.01f)
        assertTrue(thumb.top >= track.top && thumb.bottom <= track.bottom)
    }

    @Test
    fun `an output may fan out to several inputs`() {
        val p = Patch()
        val osc = p.add(Types.Osc, Offset.Zero)!!
        val src = PortRef(osc.id, PortDirection.OUTPUT, 0)
        p.connect(src, PortRef(OUT_ID, PortDirection.INPUT, 0))
        p.connect(src, PortRef(OUT_ID, PortDirection.INPUT, 1))
        assertEquals(2, p.connections.size)
    }

    @Test
    fun `connect normalizes argument order to output then input`() {
        val p = Patch()
        val osc = p.add(Types.Osc, Offset.Zero)!!
        // deliberately passed input-first
        p.connect(PortRef(OUT_ID, PortDirection.INPUT, 0), PortRef(osc.id, PortDirection.OUTPUT, 0))
        val c = p.connections.single()
        assertEquals(PortDirection.OUTPUT, c.from.dir)
        assertEquals(PortDirection.INPUT, c.to.dir)
    }

    @Test
    fun `two ports of the same direction do not connect`() {
        val p = Patch()
        val a = p.add(Types.Osc, Offset.Zero)!!
        val b = p.add(Types.Osc, Offset.Zero)!!
        p.connect(
            PortRef(a.id, PortDirection.OUTPUT, 0),
            PortRef(b.id, PortDirection.OUTPUT, 0),
        )
        assertTrue(p.connections.isEmpty())
    }

    @Test
    fun `a module cannot patch into itself`() {
        val p = Patch()
        val f = p.add(Types.Filter, Offset.Zero)!!
        p.connect(
            PortRef(f.id, PortDirection.OUTPUT, 0),
            PortRef(f.id, PortDirection.INPUT, 0),
        )
        assertTrue(p.connections.isEmpty())
    }

    @Test
    fun `pinned types cannot be added or deleted`() {
        val p = Patch()
        assertNull(p.add(Types.Out, Offset.Zero))
        assertNull(p.add(Types.In, Offset.Zero))
        assertEquals(2, p.modules.size)

        p.remove(p.module(OUT_ID)!!)
        assertNotNull(p.module(OUT_ID))
        assertNull(p.duplicate(p.module(IN_ID)!!))
    }

    @Test
    fun `removing a module takes its cables with it`() {
        val p = Patch()
        val osc = p.add(Types.Osc, Offset.Zero)!!
        val filter = p.add(Types.Filter, Offset.Zero)!!
        p.connect(PortRef(osc.id, PortDirection.OUTPUT, 0), PortRef(filter.id, PortDirection.INPUT, 0))
        p.connect(PortRef(filter.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        assertEquals(2, p.connections.size)

        p.remove(filter)
        assertTrue(p.connections.isEmpty())
        assertNull(p.module(filter.id))
    }

    @Test
    fun `duplicate produces a distinct module of the same type`() {
        val p = Patch()
        val osc = p.add(Types.Osc, Offset(10f, 10f))!!
        val copy = p.duplicate(osc)!!
        assertEquals(osc.type, copy.type)
        assertTrue(copy.id != osc.id)
        assertTrue(copy.position != osc.position)
    }

    @Test
    fun `the input rail is unusable until enabled`() {
        val p = Patch()
        val inPort = PortRef(IN_ID, PortDirection.OUTPUT, 0)
        assertFalse(p.portUsable(inPort))
        assertTrue(p.portUsable(PortRef(OUT_ID, PortDirection.INPUT, 0)))

        p.inputEnabled = true
        assertTrue(p.portUsable(inPort))
    }
}

/** Port layout is the thing the whole tap-to-connect thesis rests on. */
class PortGeometryTest {

    private fun boxFor(type: ModuleType) =
        Rect(Offset.Zero, Size(PatchModule.WIDTH, PatchModule.heightFor(type)))

    @Test
    fun `adjacent ports are exactly one pitch apart`() {
        val type = Types.Osc // two inputs
        val rect = boxFor(type)
        val band = PatchModule.portsBodyFor(type)
        val a = portIn(rect, 1f, PortDirection.INPUT, 0, 2, band)
        val b = portIn(rect, 1f, PortDirection.INPUT, 1, 2, band)
        assertEquals(PatchModule.PORT_PITCH, b.y - a.y, 0.001f)
    }

    @Test
    fun `pitch is independent of how many ports there are`() {
        val rect = Rect(Offset.Zero, Size(PatchModule.WIDTH, PatchModule.heightFor(Types.Osc)))
        (2..5).forEach { n ->
            val tall = Rect(Offset.Zero, Size(PatchModule.WIDTH, PatchModule.HEADER + n * PatchModule.PORT_PITCH))
            val band = tall.height - PatchModule.HEADER
            val first = portIn(tall, 1f, PortDirection.INPUT, 0, n, band)
            val second = portIn(tall, 1f, PortDirection.INPUT, 1, n, band)
            assertEquals("n=$n", PatchModule.PORT_PITCH, second.y - first.y, 0.001f)
        }
        assertTrue(rect.height > 0f)
    }

    @Test
    fun `a lone port sits centered in the body`() {
        val type = Types.Env // one input, one output
        val rect = boxFor(type)
        val at = portIn(rect, 1f, PortDirection.INPUT, 0, 1, PatchModule.portsBodyFor(type))
        val bodyCenter = PatchModule.HEADER + (rect.height - PatchModule.HEADER) / 2f
        assertEquals(bodyCenter, at.y, 0.001f)
    }

    @Test
    fun `inputs sit on the left edge and outputs on the right`() {
        val rect = boxFor(Types.Osc)
        val band = PatchModule.portsBodyFor(Types.Osc)
        assertEquals(rect.left, portIn(rect, 1f, PortDirection.INPUT, 0, 2, band).x, 0.001f)
        assertEquals(rect.right, portIn(rect, 1f, PortDirection.OUTPUT, 0, 1, band).x, 0.001f)
    }

    @Test
    fun `height grows with port count and never crowds below the pitch`() {
        // Drone has one port and Mix has four, which is the widest spread the catalog
        // still offers now that every synth is one note input and one audio output.
        val one = PatchModule.heightFor(Types.Drone)
        val four = PatchModule.heightFor(Types.Mix)
        assertTrue(four > one)
        assertEquals(PatchModule.HEADER + 4 * PatchModule.PORT_PITCH, four, 0.001f)
    }

    @Test
    fun `the port subpatch is centered in the body at any count`() {
        // Spacing and centering are separate terms in portIn: index * pitch places the
        // ports, span only decides where the subpatch starts. A pitch test alone leaves
        // the centering unpinned, so assert the subpatch's midpoint lands on the body's.
        (1..5).forEach { n ->
            val h = PatchModule.HEADER + maxOf(PatchModule.MIN_BODY, n * PatchModule.PORT_PITCH)
            val rect = Rect(Offset.Zero, Size(PatchModule.WIDTH, h))
            val band = h - PatchModule.HEADER
            val first = portIn(rect, 1f, PortDirection.INPUT, 0, n, band).y
            val last = portIn(rect, 1f, PortDirection.INPUT, n - 1, n, band).y
            val bodyCenter = PatchModule.HEADER + (h - PatchModule.HEADER) / 2f
            assertEquals("n=$n", bodyCenter, (first + last) / 2f, 0.001f)
        }
    }

    @Test
    fun `the unit multiplier scales layout without changing pitch ratio`() {
        val d = 2.4375f // the reference device
        val rect = Rect(Offset.Zero, Size(PatchModule.RAIL_WIDTH * d, PatchModule.heightFor(Types.Out) * d))
        val band = PatchModule.portsBodyFor(Types.Out) * d
        val a = portIn(rect, d, PortDirection.INPUT, 0, 2, band)
        val b = portIn(rect, d, PortDirection.INPUT, 1, 2, band)
        assertEquals(PatchModule.PORT_PITCH * d, b.y - a.y, 0.001f)
    }
}

/**
 * Menu layout is pure arithmetic that is tedious to check by hand and easy to get
 * subtly wrong at a screen edge, which is exactly the case for pinning it in a test.
 */
class MenuLayoutTest {

    private val d = 2.4375f                       // the reference device
    private val screen = Size(2404f, 1080f)       // its landscape canvas, in px

    private fun add(n: Int) = Types.palette.take(n).map { MenuItem.Add(it) }

    @Test
    fun `rows are minimized and then balanced`() {
        // Asserted as the invariant rather than a specific shape, so widening the column
        // cap does not falsify the test it was meant to satisfy.
        (1..12).forEach { n ->
            val layout = menuLayout(
                List(n) { MenuItem.Add(Types.palette[it % Types.palette.size]) },
                Offset(1200f, 540f), d, screen,
            )
            val rows = layout.tiles.map { it.first.top }.distinct().size
            val cols = layout.tiles.map { it.first.left }.distinct().size

            val expectedRows = (n + MENU_COLS - 1) / MENU_COLS
            assertEquals("rows for n=$n", expectedRows, rows)
            assertTrue("no empty row for n=$n", (rows - 1) * cols < n)
            assertTrue("every item placed for n=$n", layout.tiles.size == n)
        }
    }

    @Test
    fun `the real palette leaves no lonely orphan`() {
        val layout = menuLayout(
            Types.palette.map { MenuItem.Add(it) }, Offset(1200f, 540f), d, screen,
        )
        val rows = layout.tiles.groupBy { it.first.top }
        if (rows.size > 1) {
            val last = rows.entries.maxByOrNull { it.key }!!.value.size
            assertTrue("last row holds $last of ${Types.palette.size}", last > 1)
        }
    }

    @Test
    fun `a small menu stays on one row`() {
        val two = menuLayout(
            listOf(MenuItem.Duplicate(1L), MenuItem.Delete(1L)),
            Offset(1200f, 540f), d, screen,
        )
        assertEquals(1, two.tiles.map { it.first.top }.distinct().size)
    }

    @Test
    fun `every tile lies inside the menu panel`() {
        val layout = menuLayout(add(4), Offset(1200f, 540f), d, screen)
        layout.tiles.forEach { (tile, _) ->
            assertTrue(tile.left >= layout.rect.left && tile.right <= layout.rect.right)
            assertTrue(tile.top >= layout.rect.top && tile.bottom <= layout.rect.bottom)
        }
    }

    @Test
    fun `the menu is clamped on screen wherever it is opened`() {
        val corners = listOf(
            Offset(0f, 0f),
            Offset(screen.width, 0f),
            Offset(0f, screen.height),
            Offset(screen.width, screen.height),
            Offset(screen.width / 2f, 0f),
        )
        corners.forEach { anchor ->
            val r = menuLayout(add(4), anchor, d, screen).rect
            assertTrue("left at $anchor", r.left >= 0f)
            assertTrue("top at $anchor", r.top >= 0f)
            assertTrue("right at $anchor", r.right <= screen.width)
            assertTrue("bottom at $anchor", r.bottom <= screen.height)
        }
    }

    /**
     * The reference device runs at font scale 1.5, where 12sp labels are 18dp tall in
     * tiles sized for 12: "Save patch..." spilled into the tile beside it. Tiles grow with
     * the setting, and the biggest menu there is -- every module, both boxes twice over,
     * Load and Save patch -- still has to fit the screen at that size, wherever it is
     * opened.
     */
    @Test
    fun `at a large text size the tiles grow and the whole menu still fits`() {
        val everything = Types.palette.map { MenuItem.Add(it) } +
            listOf(
                MenuItem.Add(Types.Subpatch), MenuItem.Add(Types.Poly),
                MenuItem.StartSubpatch(Types.Subpatch), MenuItem.StartSubpatch(Types.Poly),
                MenuItem.OpenLibrary, MenuItem.Save(null),
            )
        val normal = menuLayout(everything, Offset(1200f, 540f), d, screen)
        val large = menuLayout(everything, Offset(1200f, 540f), d, screen, textScale = 1.5f)

        val tile = { l: MenuLayout -> l.tiles.first().first }
        assertEquals(tile(normal).width * 1.5f, tile(large).width, 0.5f)
        assertEquals(tile(normal).height * 1.5f, tile(large).height, 0.5f)

        listOf(Offset(0f, 0f), Offset(screen.width, screen.height), Offset(1200f, 540f)).forEach { anchor ->
            val r = menuLayout(everything, anchor, d, screen, textScale = 1.5f).rect
            assertTrue("fits across at $anchor", r.left >= 0f && r.right <= screen.width)
            assertTrue("fits down at $anchor", r.top >= 0f && r.bottom <= screen.height)
        }
        // A smaller text size does not shrink the tiles below what a finger needs.
        assertEquals(tile(normal).width, tile(menuLayout(everything, Offset.Zero, d, screen, 0.85f)).width, 0.5f)
    }

    @Test
    fun `the menu clears the fingertip that opened it`() {
        val anchor = Offset(1200f, 800f)
        val r = menuLayout(add(4), anchor, d, screen).rect
        assertTrue("menu should sit above the press", r.bottom < anchor.y)
    }
}
