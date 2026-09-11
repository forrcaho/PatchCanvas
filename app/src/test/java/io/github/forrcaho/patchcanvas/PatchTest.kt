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
