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
        val rows = droneRows(area, d, scale)
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
                    droneDegree(drone, row, column, rows, scale),
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

        assertEquals(false, panelBracketAt(panel, d, filter, 0, Offset(low, y)))
        assertEquals(true, panelBracketAt(panel, d, filter, 0, Offset(high, y)))
        assertEquals(false, panelBracketAt(panel, d, filter, 0, Offset(middle - 5f, y)))
        assertEquals(true, panelBracketAt(panel, d, filter, 0, Offset(middle + 5f, y)))
        assertEquals("far along the bar is still the nearer one", true, panelBracketAt(panel, d, filter, 0, Offset(row.right - 1f, y)))
        assertNull("another row is not this one", panelBracketAt(panel, d, filter, 0, Offset(low, row.top - 40f * d)))
        assertNull(
            "an unexposed row has none",
            panelBracketAt(panel, d, filter, 1, Offset(low, panelRow(panel, d, filter.type, 1).center.y)),
        )
    }

    @Test
    fun `a bracket parked at the end of its bar is taken from beyond it`() {
        // A new range puts [ at the very end for any knob in the bottom fifth of its travel,
        // and a finger aiming at it from outside lands a little past the bar.
        val patch = Patch()
        val voice = patch.add(Types.Voice, Offset.Zero)!!
        val attack = voice.type.params[1]
        patch.expose(voice, 1, initialModRange(attack, attack.default))
        val row = panelRow(panel, d, voice.type, 1)
        val low = panelBracketX(row, d, attack, voice.modRanges.getValue(1).low, closing = false)
        assertEquals("the new range starts at the end of the bar", row.left, low, 0.5f)
        assertEquals(false, panelBracketAt(panel, d, voice, 1, Offset(row.left - 15f * d, row.center.y)))
    }

    @Test
    fun `an exposed row's knob cannot be moved by hand`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        val at = panelRow(panel, d, filter.type, 0).center
        assertEquals(0, panelKnobAt(panel, d, filter, at))
        patch.expose(filter, 0, ModRange(400f, 2000f))
        assertNull(panelKnobAt(panel, d, filter, at))
    }

    @Test
    fun `an exposed bar reads its range in its own units`() {
        assertEquals("[0.017s \u2013 0.522s]", rangeReading(Types.Voice.params[1], ModRange(0.017f, 0.522f)))
        assertEquals("[300Hz \u2013 3000Hz]", rangeReading(Types.Filter.params[0], ModRange(300f, 3000f)))
    }

    @Test
    fun `a range of one option still has its two brackets apart`() {
        val row = panelRow(panel, d, Types.Voice, 0)
        val wave = Types.Voice.params[0]
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

        patch.moveBracket(filter, panel, d, 0, closing = true, screenX = row.right)
        assertEquals(400f, filter.modRanges.getValue(0).low, 0.001f)
        assertEquals(18000f, filter.modRanges.getValue(0).high, 1f)

        patch.moveBracket(filter, panel, d, 0, closing = false, screenX = row.left)
        assertEquals(20f, filter.modRanges.getValue(0).low, 0.01f)
        assertEquals("and the knob stays where it was", 1000f, filter.params[0], 0f)
    }
}
