package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keypad: what a key does to what has been typed, what an entry means for a knob, and
 * which number on the panel a finger takes hold of.
 *
 * The keys and the parsing are pure functions precisely so they can be tested here; the
 * composable over them is layout. The hit test is the part a screen would otherwise be
 * needed for, and it is the part that decides whether the low end of a range can be typed
 * at all.
 */
class KeypadTest {

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

    /** Stands in for the text measurer: every glyph the same width, which is enough to place one. */
    private val widthOf: (String) -> Float = { it.length * 9f * d }

    /** The rows a panel shows for an ordinary module: its own, in order. */
    private fun rows(module: PatchModule) = module.type.rowParams.map { ParamRow(module, it) }

    @Test
    fun `digits append, and the entry stops growing somewhere`() {
        var entry = ""
        "1234".forEach { entry = keypadEntry(entry, it.toString()) }
        assertEquals("1234", entry)

        repeat(20) { entry = keypadEntry(entry, "7") }
        assertEquals("nine digits and no more", MAX_ENTRY, entry.count { it.isDigit() })
    }

    @Test
    fun `the point, the sign and the back key`() {
        assertEquals("0.", keypadEntry("", "."))
        assertEquals("12.", keypadEntry("12", "."))
        assertEquals("a second point is not a number", "12.5", keypadEntry("12.5", "."))

        assertEquals("-40", keypadEntry("40", KEY_SIGN))
        assertEquals("40", keypadEntry("-40", KEY_SIGN))

        assertEquals("4", keypadEntry("40", KEY_BACK))
        assertEquals("", keypadEntry("4", KEY_BACK))
        assertEquals("backing off nothing is nothing", "", keypadEntry("", KEY_BACK))
        assertEquals("", keypadEntry("123", KEY_CLEAR))
    }

    @Test
    fun `an entry that is not yet a number means nothing, and OK does nothing with it`() {
        val param = Param("cutoff", 20f, 12000f, 800f, "Hz", ParamCurve.EXPONENTIAL)
        assertNull(keypadValue("", param))
        assertNull(keypadValue("-", param))
        assertNull(keypadValue(".", param))
        assertNotNull(keypadValue("0.", param))
    }

    @Test
    fun `a number past the end of the knob asks for the end of the knob`() {
        val param = Param("cutoff", 20f, 12000f, 800f, "Hz", ParamCurve.EXPONENTIAL)
        assertEquals(12000f, keypadValue("20000", param)!!, 0.001f)
        assertEquals(20f, keypadValue("1", param)!!, 0.001f)
        assertEquals(440f, keypadValue("440", param)!!, 0.001f)

        // Cents descend on some controls; the clamp must not depend on which way round.
        val descending = Param("detune", 1200f, -1200f, 0f, " cents")
        assertEquals(-1200f, keypadValue("-9000", descending)!!, 0.001f)
        assertEquals(1200f, keypadValue("9000", descending)!!, 0.001f)
    }

    @Test
    fun `a tap on a row's reading takes that row's value`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        val index = filter.type.rowParams.first()
        val param = filter.type.params[index]
        val row = panelRow(panel, d, filter.type, index)

        val text = param.format(filter.params[index])
        val at = Offset(row.right - widthOf(text) / 2f, row.top + 8f * d)
        assertEquals(ParamRow(filter, index) to ValueTarget.VALUE, panelValueAt(panel, d, filter, rows(filter), at, widthOf))

        // The bar below it is the knob's, and stays the knob's.
        assertNull(panelValueAt(panel, d, filter, rows(filter), Offset(at.x, row.bottom - 12f * d), widthOf))
        // So does the label at the row's left, which is not a number.
        assertNull(panelValueAt(panel, d, filter, rows(filter), Offset(row.left + 10f * d, at.y), widthOf))
    }

    @Test
    fun `an exposed row's two ends are told apart by where the finger landed`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        val index = filter.type.rowParams.first()
        val param = filter.type.params[index]
        patch.expose(filter, index, ModRange(param.min, param.max))
        val range = filter.modRanges.getValue(index)
        val row = panelRow(panel, d, filter.type, index)

        val whole = widthOf(rangeReading(param, range))
        val left = row.right - whole
        val y = row.top + 8f * d
        val lowMiddle = left + widthOf("[${param.format(range.low)}") / 2f
        val highMiddle = row.right - widthOf("${param.format(range.high)}]") / 2f

        assertEquals(ParamRow(filter, index) to ValueTarget.LOW, panelValueAt(panel, d, filter, rows(filter), Offset(lowMiddle, y), widthOf))
        assertEquals(ParamRow(filter, index) to ValueTarget.HIGH, panelValueAt(panel, d, filter, rows(filter), Offset(highMiddle, y), widthOf))
    }

    @Test
    fun `a stepped row offers nothing to type`() {
        val patch = Patch()
        val osc = patch.add(Types.Osc, Offset.Zero)!!
        val stepped = osc.type.rowParams.filter { osc.type.params[it].curve == ParamCurve.STEPPED }
        assertTrue("an Osc has a waveform to pick", stepped.isNotEmpty())
        stepped.forEach { index ->
            val row = panelRow(panel, d, osc.type, index)
            val at = Offset(row.right - 20f * d, row.top + 8f * d)
            // Its lit button is its reading, so there is no number over the row to take.
            assertNull(panelValueAt(panel, d, osc, rows(osc), at, widthOf))
        }
    }

    @Test
    fun `the tempo's reading is over its row and clear of its bar`() {
        val card = frame.transportCard()
        val row = transportTempoRow(card, d)
        val zone = transportTempoValue(card, d)

        assertTrue("the reading is right-aligned on the row", zone.right >= row.right)
        assertTrue(zone.contains(Offset(row.right - 20f * d, row.top + 8f * d)))
        // The bar sits at the bottom of the row and is dragged, not typed.
        val bar = Offset(row.center.x, row.bottom - PatchModule.PANEL_BAR * d / 2f - 4f * d)
        assertTrue(!zone.contains(bar))
    }

    @Test
    fun `a stepped knob with more options than fit is a bar, and takes a typed whole number`() {
        val length = Types.Seq.params[0]
        assertTrue("32 steps do not fit as buttons", !length.buttons)
        assertTrue("an Osc's four waves do", Types.Osc.params[0].buttons)
        assertEquals(8f, keypadValue("7.6", length)!!, 0f)

        val patch = Patch()
        val seq = patch.add(Types.Seq, Offset.Zero)!!
        val row = panelRow(panel, d, seq.type, 0)
        val text = length.format(seq.params[0])
        val at = Offset(row.right - widthOf(text) / 2f, row.top + 8f * d)
        assertEquals("its reading can be typed", ParamRow(seq, 0) to ValueTarget.VALUE, panelValueAt(panel, d, seq, rows(seq), at, widthOf))
    }
}
