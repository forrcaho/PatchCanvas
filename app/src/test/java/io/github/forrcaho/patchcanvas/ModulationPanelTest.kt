package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the [ ] chips, the brackets and the panel's modulation jacks land, on the reference
 * device in landscape. Geometry that overlaps is a control that cannot be hit, and nothing
 * but a finger on glass or a test like this notices.
 */
class ModulationPanelTest {

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
    private val exposable = Types.palette.filter { it.rowParams.isNotEmpty() }

    /** The rows a panel shows for an ordinary module: its own, in order. */
    private fun rows(module: PatchModule) = module.type.rowParams.map { ParamRow(module, it) }

    /**
     * A finger on a drone's grid, at the reference device's size. The hit test and the
     * drawing derive their geometry separately, so a cell that lights and a cell that
     * toggles are two claims, and only one of them is testable without a screen.
     */
    @Test
    fun `a tap on a drone's grid lands on the cell under it`() {
        val patch = Patch()
        val drone = patch.add(Types.Drone, Offset.Zero)!!
        val scale = Scale.Chromatic
        val area = panelGrid(panel, d, Types.Drone)
        val window = droneWindow(drone, area, d, scale)
        val rows = window.rows
        val columns = droneColumns(scale)

        // The middle of every cell must find that cell and no other.
        repeat(rows) { row ->
            repeat(columns) { column ->
                val at = Offset(
                    area.left + (column + 0.5f) * (area.width / columns),
                    area.top + (row + 0.5f) * (area.height / rows),
                )
                val cell = panelCellAt(panel, d, drone, at, scale)
                assertEquals(
                    "row $row column $column",
                    droneDegree(window, row, column, scale),
                    cell?.first,
                )
                // A drone's cell is its own degree, which is what lets the tap that
                // toggles a sequencer's step toggle a drone's cell unchanged.
                assertEquals(cell?.first, cell?.second)
            }
        }

        assertNull("above the grid is not a cell", panelCellAt(panel, d, drone, Offset(area.left + 1f, area.top - 20f), scale))
    }

    @Test
    fun `every row's chip is inside the panel, beside its row and clear of the jack labels`() {
        exposable.forEach { type ->
            type.rowParams.forEach { i ->
                val chip = panelModChip(panel, d, type, i)
                val row = panelRow(panel, d, type, i)
                val name = "${type.name} ${type.params[i].name}"
                assertTrue("$name inside the panel", panel.contains(chip.topLeft) && panel.contains(chip.bottomRight))
                assertTrue("$name beside the row, not on it", chip.left >= row.right)
                assertTrue("$name level with its row", chip.top >= row.top && chip.bottom <= row.bottom)
                // An output jack's label is drawn inward from the panel's edge; "notes", the
                // longest, needs about 30dp after its 16dp inset.
                assertTrue("$name clear of the output labels", chip.right <= panel.right - 48f * d)
            }
        }
    }

    @Test
    fun `a chip is big enough to hit, and no two overlap`() {
        exposable.forEach { type ->
            val chips = type.rowParams.map { panelModChip(panel, d, type, it) }
            chips.forEach {
                assertTrue("${type.name} chip wide enough", it.width >= 40f * d)
                assertTrue("${type.name} chip tall enough", it.height >= 28f * d)
            }
            for (a in chips.indices) for (b in a + 1 until chips.size) {
                assertTrue("${type.name} chips $a and $b", !chips[a].overlaps(chips[b]))
            }
        }
    }

    @Test
    fun `a chip clears the interval chip in a sequencer's header`() {
        val interval = panelIntervalChip(panel, d)
        Types.Steps.rowParams.forEach {
            assertTrue(!panelModChip(panel, d, Types.Steps, it).overlaps(interval))
        }
    }

    @Test
    fun `the panel's modulation jacks clear the history buttons and each other`() {
        val buttons = frame.historyRect(true)
        exposable.forEach { type ->
            val jacks = type.rowParams.map { panelModPort(panel, d, type, it) }
            jacks.forEach {
                assertTrue("${type.name} jack right of the buttons", it.x > buttons.right + 22f * d)
                assertEquals(panel.bottom, it.y, 0.001f)
            }
            for (a in jacks.indices) for (b in a + 1 until jacks.size) {
                assertTrue(
                    "${type.name} jacks $a and $b apart",
                    kotlin.math.abs(jacks[a].x - jacks[b].x) >= PatchModule.PORT_PITCH * d,
                )
            }
        }
    }

    @Test
    fun `on an exposed row, a touch anywhere takes the nearer bracket`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        patch.expose(filter, 0, ModRange(400f, 2000f))
        val row = panelRow(panel, d, filter.type, 0)
        val param = filter.type.params[0]
        val low = panelBracketX(row, d, param, 400f, closing = false)
        val high = panelBracketX(row, d, param, 2000f, closing = true)
        val y = row.center.y
        val middle = (low + high) / 2f

        assertEquals(ParamRow(filter, 0) to false, panelBracketAt(panel, d, filter, rows(filter), Offset(low, y)))
        assertEquals(ParamRow(filter, 0) to true, panelBracketAt(panel, d, filter, rows(filter), Offset(high, y)))
        assertEquals(ParamRow(filter, 0) to false, panelBracketAt(panel, d, filter, rows(filter), Offset(middle - 5f, y)))
        assertEquals(ParamRow(filter, 0) to true, panelBracketAt(panel, d, filter, rows(filter), Offset(middle + 5f, y)))
        assertEquals(
            "far along the bar is still the nearer one",
            ParamRow(filter, 0) to true,
            panelBracketAt(panel, d, filter, rows(filter), Offset(row.right - 1f, y)),
        )
        assertNull("another row is not this one", panelBracketAt(panel, d, filter, rows(filter), Offset(low, row.top - 40f * d)))
        assertNull(
            "an unexposed row has none",
            panelBracketAt(panel, d, filter, rows(filter), Offset(low, panelRow(panel, d, filter.type, 1).center.y)),
        )
    }

    @Test
    fun `a bracket parked at the end of its bar is taken from beyond it`() {
        // A new range puts [ at the very end for any knob in the bottom fifth of its travel,
        // and a finger aiming at it from outside lands a little past the bar.
        val patch = Patch()
        // Env's A, which is where Osc's was before the envelopes left the synths: 5ms on a
        // range to 5s, which is the bottom of its travel.
        val env = patch.add(Types.Env, Offset.Zero)!!
        val attack = env.type.params[0]
        patch.expose(env, 0, initialModRange(attack, attack.default))
        val row = panelRow(panel, d, env.type, 0)
        val low = panelBracketX(row, d, attack, env.modRanges.getValue(0).low, closing = false)
        assertEquals("the new range starts at the end of the bar", row.left, low, 0.5f)
        assertEquals(
            ParamRow(env, 0) to false,
            panelBracketAt(panel, d, env, rows(env), Offset(row.left - 15f * d, row.center.y)),
        )
    }

    @Test
    fun `an exposed row's knob cannot be moved by hand`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        val at = panelRow(panel, d, filter.type, 0).center
        assertEquals(ParamRow(filter, 0), panelKnobAt(panel, d, filter, rows(filter), at))
        patch.expose(filter, 0, ModRange(400f, 2000f))
        assertNull(panelKnobAt(panel, d, filter, rows(filter), at))
    }

    @Test
    fun `an exposed bar reads its range in its own units`() {
        assertEquals("[0.017s \u2013 0.522s]", rangeReading(Types.Env.params[0], ModRange(0.017f, 0.522f)))
        assertEquals("[300Hz \u2013 3000Hz]", rangeReading(Types.Filter.params[0], ModRange(300f, 3000f)))
    }

    @Test
    fun `a range of one option still has its two brackets apart`() {
        val row = panelRow(panel, d, Types.Osc, 0)
        val wave = Types.Osc.params[0]
        val low = panelBracketX(row, d, wave, 2f, closing = false)
        val high = panelBracketX(row, d, wave, 2f, closing = true)
        assertTrue(high - low >= 40f * d)
        assertEquals(choiceBox(row, d, wave, 2).left, low, 0.001f)
        assertEquals(choiceBox(row, d, wave, 2).right, high, 0.001f)
    }

    @Test
    fun `dragging a bracket moves its own end and nothing else`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        filter.setParam(0, 1000f)
        patch.expose(filter, 0, ModRange(400f, 2000f))
        val row = panelRow(panel, d, filter.type, 0)

        patch.moveBracket(ParamRow(filter, 0), row, closing = true, screenX = row.right)
        assertEquals(400f, filter.modRanges.getValue(0).low, 0.001f)
        assertEquals(18000f, filter.modRanges.getValue(0).high, 1f)

        patch.moveBracket(ParamRow(filter, 0), row, closing = false, screenX = row.left)
        assertEquals(20f, filter.modRanges.getValue(0).low, 0.01f)
        assertEquals("and the knob stays where it was", 1000f, filter.params[0], 0f)
    }

    /**
     * The chip that promotes sits above the one that gives a jack, and both have to stay
     * inside the panel and clear of each other on every module -- an Osc has five rows, and
     * five rows are the case where the stack runs out of height.
     */
    @Test
    fun `the promote chip clears the jack chip and stays on the panel`() {
        exposable.forEach { type ->
            type.rowParams.forEach { index ->
                val row = panelRow(panel, d, type, index)
                val jack = panelModChipOn(row, d)
                val promote = panelPromoteChipOn(row, d)

                assertTrue("${type.name} $index: over the row", promote.right <= row.left)
                assertTrue("${type.name} $index: too short to hit", promote.height >= 18f * d)
                assertTrue("${type.name} $index: too narrow to hit", promote.width >= 30f * d)
                // The panel's input labels have the outer half of that gutter.
                assertTrue("${type.name} $index: over the jack labels", promote.left >= panel.left + 58f * d)
                assertEquals("level with the chip it mirrors", jack.top, promote.top, 0.001f)
            }
        }
    }

    /**
     * A subpatch's panel draws knobs that belong to modules inside it, so its rows are counted
     * rather than looked up by parameter. One row must land where one row lands.
     */
    @Test
    fun `a subpatch's rows sit where a module's rows would`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        val subpatch = patch.makeSubpatch(setOf(filter.id))!!
        patch.enterScope(subpatch.id)
        patch.promote(filter, 0)

        val rows = patch.panelRows(subpatch)
        assertEquals(1, rows.size)
        assertEquals(
            panelRowAt(panel, d, Types.Subpatch, 1, 0),
            panelRowAt(panel, d, Types.Subpatch, rows.size, 0),
        )
        // And the knob under it is the filter's, reached through the subpatch's panel.
        val at = panelRowAt(panel, d, Types.Subpatch, rows.size, 0).center
        assertEquals(ParamRow(filter, 0), panelKnobAt(panel, d, subpatch, rows, at))
    }

    /**
     * Eight rows, which is what FM needs, on the reference device. In one column each would
     * be 40dp with its label, reading and bar all inside -- so past five they go to two.
     */
    @Test
    fun `eight rows go to two columns that keep a finger's height and a long bar`() {
        val count = MAX_PARAMS
        val rows = (0 until count).map { panelRowAt(panel, d, Types.Mix, count, it) }

        rows.forEachIndexed { i, a ->
            assertTrue("row $i is ${a.height / d}dp tall", a.height >= 60f * d)
            assertTrue("row $i is only ${a.width / d}dp wide", a.width >= 200f * d)
            rows.forEachIndexed { j, b ->
                if (i != j) assertTrue("rows $i and $j overlap", !a.overlaps(b))
            }
        }
        // In the order read: down the left, then down the right.
        assertTrue(rows[0].left == rows[3].left && rows[4].left > rows[3].right)
        assertTrue(rows[1].top > rows[0].top && rows[4].top == rows[0].top)

        // Both chips sit clear of every row, the middle gutter included, and inside the panel.
        rows.forEach { row ->
            listOf(panelModChipOn(row, d), panelPromoteChipOn(row, d)).forEach { chip ->
                rows.forEach { other ->
                    assertTrue("a chip lands on a row", !chip.overlaps(other))
                }
                rows.forEach { other ->
                    if (other != row) {
                        listOf(panelModChipOn(other, d), panelPromoteChipOn(other, d)).forEach {
                            assertTrue("two chips overlap", !chip.overlaps(it))
                        }
                    }
                }
                assertTrue(chip.left >= panel.left && chip.right <= panel.right)
            }
        }
    }

    /**
     * Found on the phone: with two columns a knob took the whole panel's width to cross,
     * because travel was measured against the panel rather than the row. Each row's own ends
     * are its knob's.
     */
    @Test
    fun `a knob in either column travels its own row`() {
        // Eight rows, which is what only a subpatch's panel reaches now: no module has more
        // than four knobs since the envelopes left the synths.
        val rows = (0 until MAX_PROMOTED)
        rows.forEach { slot ->
            val bar = panelRowAt(panel, d, Types.Subpatch, rows.count(), slot)
            assertEquals("slot $slot at its left", 0f, panelKnobPosition(bar, bar.left), 0.001f)
            assertEquals("slot $slot at its right", 1f, panelKnobPosition(bar, bar.right), 0.001f)
            assertEquals("slot $slot halfway", 0.5f, panelKnobPosition(bar, bar.center.x), 0.001f)
        }
    }

    /**
     * Two columns split at half, reading down the left and then down the right.
     *
     * A parameter could once ask to start the second column, because FM's seven knobs
     * otherwise split ratio/index/fall/A and D/S/R and cut ADSR in two. FM has three knobs
     * now, so nothing declares a break and nothing can: the only panel that reaches two
     * columns is a subpatch's, whose rows are knobs promoted from the modules inside it.
     */
    @Test
    fun `past five rows the panel splits at half`() {
        val rects = (0 until MAX_PROMOTED).map { panelRowAt(panel, d, Types.Subpatch, MAX_PROMOTED, it) }
        val left = rects.filter { it.left == rects[0].left }
        assertEquals("half down the left", MAX_PROMOTED / 2, left.size)
        assertEquals("the rest down the right", MAX_PROMOTED / 2, rects.size - left.size)
        assertTrue("reading order is kept", rects.take(MAX_PROMOTED / 2).all { it.left == rects[0].left })
        assertTrue("both columns keep a finger's height", rects.all { it.height >= 60f * d })

        val seven = (0 until 7).map { panelRowAt(panel, d, Types.Subpatch, 7, it) }
        assertEquals("an odd count puts the extra row on the left", 4, seven.count { it.left == seven[0].left })
    }

    @Test
    fun `five rows or fewer stay in one column, as they were`() {
        (1..PANEL_ONE_COLUMN).forEach { count ->
            val rows = (0 until count).map { panelRowAt(panel, d, Types.Mix, count, it) }
            assertEquals(1, rows.map { it.left }.distinct().size)
            assertEquals(panel.width - 2f * PatchModule.PANEL_SIDE * d, rows[0].width, 0.01f)
        }
    }
}
