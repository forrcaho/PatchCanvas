package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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
        val voice = p.add(Types.Voice, Offset.Zero)!!
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
        val voice = p.add(Types.Voice, Offset.Zero)!!
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
        val voice = p.add(Types.Voice, Offset.Zero)!!
        val osc = p.add(Types.Osc, Offset.Zero)!!
        val filter = p.add(Types.Filter, Offset.Zero)!!
        val env = p.add(Types.Env, Offset.Zero)!!

        fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
        fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)
        val outL = PortRef(OUT_ID, PortDirection.INPUT, 0)

        // Steps: 0 pitch (modulation), 1 notes. Osc: 0 pitch and 1 fm, both modulation,
        // out audio. Filter: 0 in, audio. Env: 0 notes, out modulation. Voice: 0 notes.
        assertFalse("modulation is not a note", p.connect(out(steps, 0), into(voice, 0)))
        assertFalse("nor is audio", p.connect(out(osc, 0), into(voice, 0)))
        assertFalse("nor do notes go into audio", p.connect(out(steps, 1), outL))
        // Each of these was legal while typing was advisory.
        assertFalse("modulation is not audio", p.connect(out(env, 0), into(filter, 0)))
        assertFalse("nor does audio turn a knob", p.connect(out(osc, 0), into(osc, 1)))
        assertFalse("and modulation is not audio at the rail either", p.connect(out(env, 0), outL))
        assertTrue("nothing above was patched", p.connections.isEmpty())

        assertTrue("notes to notes", p.connect(out(steps, 1), into(voice, 0)))
        assertTrue("and an envelope is opened by them too", p.connect(out(steps, 1), into(env, 0)))
        assertTrue("modulation to modulation", p.connect(out(steps, 0), into(osc, 0)))
        assertTrue("audio to audio", p.connect(out(osc, 0), into(filter, 0)))
        assertEquals(4, p.connections.size)
    }

    /**
     * Pulse has no port anywhere in the catalogue: Env was the last thing taking a gate
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
    @Test
    fun `a module with a grid and no knobs still opens`() {
        assertTrue("a drone has nothing but its grid", Types.Drone.params.isEmpty())
        assertTrue("and it must still open", Types.Drone.hasPanel)
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
            "a drone's grid reaches further down than a sequencer's",
            panelGrid(panel, 1f, Types.Drone).bottom > panelGrid(panel, 1f, Types.Steps).bottom,
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
        val rows = droneRows(area, 1f, scale)

        // The bottom row of the first column is the lowest degree on screen, and the row
        // above it is one degree higher: pitch ascends up the screen.
        val bottom = droneDegree(drone, rows - 1, 0, rows, scale)
        val above = droneDegree(drone, rows - 2, 0, rows, scale)
        assertEquals(above, bottom + 1)

        // The same row one column right is the same degree an octave up, which is what
        // makes a column an octave rather than just the next twelve degrees.
        assertEquals(
            bottom + scale.size,
            droneDegree(drone, rows - 1, 1, rows, scale),
        )
    }

    @Test
    fun `a scale with many degrees trades columns for rows`() {
        // 22 degrees to a period: four octaves of it is 88 cells and there are 64, so it
        // gets two columns rather than running off the end of the grid.
        assertEquals(DRONE_OCTAVES, droneColumns(Scale.Chromatic))
        assertEquals(2, droneColumns(Scale.equal("22", 22)))
        assertEquals(1, droneColumns(Scale.equal("40", 40)))
        // Whatever the scale, no cell lands past the end.
        listOf(5, 12, 22, 40).forEach { size ->
            val scale = Scale.equal("$size", size)
            val p = Patch()
            val drone = p.add(Types.Drone, Offset.Zero)!!
            val rows = droneRows(droneArea(), 1f, scale)
            val top = droneDegree(drone, 0, droneColumns(scale) - 1, rows, scale)
            assertTrue("$size-degree scale reached cell $top", top < DRONE_CELLS)
        }
    }

    @Test
    fun `a drone's rows stay inside one period however far it is scrolled`() {
        val p = Patch()
        val drone = p.add(Types.Drone, Offset.Zero)!!
        val scale = Scale.Chromatic
        val rows = droneRows(droneArea(), 1f, scale)

        // Scrolling past the scale would show the octave the next column already holds.
        drone.gridBottom = 900
        assertEquals(scale.size - rows, droneBottom(drone, rows, scale))
        drone.gridBottom = -900
        assertEquals(0, droneBottom(drone, rows, scale))
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
    fun `connect normalises argument order to output then input`() {
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
    fun `a lone port sits centred in the body`() {
        val type = Types.Env // one input, one output
        val rect = boxFor(type)
        val at = portIn(rect, 1f, PortDirection.INPUT, 0, 1, PatchModule.portsBodyFor(type))
        val bodyCentre = PatchModule.HEADER + (rect.height - PatchModule.HEADER) / 2f
        assertEquals(bodyCentre, at.y, 0.001f)
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
        val one = PatchModule.heightFor(Types.Env)
        val two = PatchModule.heightFor(Types.Osc)
        assertTrue(two > one)
        assertEquals(PatchModule.HEADER + 2 * PatchModule.PORT_PITCH, two, 0.001f)
    }

    @Test
    fun `the port group is centred in the body at any count`() {
        // Spacing and centring are separate terms in portIn: index * pitch places the
        // ports, span only decides where the group starts. A pitch test alone leaves
        // the centring unpinned, so assert the group's midpoint lands on the body's.
        (1..5).forEach { n ->
            val h = PatchModule.HEADER + maxOf(PatchModule.MIN_BODY, n * PatchModule.PORT_PITCH)
            val rect = Rect(Offset.Zero, Size(PatchModule.WIDTH, h))
            val band = h - PatchModule.HEADER
            val first = portIn(rect, 1f, PortDirection.INPUT, 0, n, band).y
            val last = portIn(rect, 1f, PortDirection.INPUT, n - 1, n, band).y
            val bodyCentre = PatchModule.HEADER + (h - PatchModule.HEADER) / 2f
            assertEquals("n=$n", bodyCentre, (first + last) / 2f, 0.001f)
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
    fun `rows are minimised and then balanced`() {
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

    @Test
    fun `the menu clears the fingertip that opened it`() {
        val anchor = Offset(1200f, 800f)
        val r = menuLayout(add(4), anchor, d, screen).rect
        assertTrue("menu should sit above the press", r.bottom < anchor.y)
    }
}
