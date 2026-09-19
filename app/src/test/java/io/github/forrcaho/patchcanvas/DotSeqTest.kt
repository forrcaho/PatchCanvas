package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dot sequencer's grid and what the file keeps of it. Playing the dots is the node
 * tests'; this is where a finger lands and what it changes.
 */
class DotSeqTest {

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

    private fun seq(): Pair<Patch, PatchModule> {
        val patch = Patch()
        return patch to patch.add(Types.DotSeq, Offset.Zero)!!
    }

    @Test
    fun `a tap lands on the step and degree under it, as many steps as the loop is long`() {
        val (_, seq) = seq()
        val area = panelGrid(panel, d, seq.type)
        listOf(4, 16, 32).forEach { length ->
            seq.setParam(0, length.toFloat())
            assertEquals(length, dotColumns(seq))
            val window = gridWindow(seq, area, d, Scale.Chromatic)
            repeat(length) { column ->
                val at = Offset(area.left + (column + 0.5f) * area.width / length, area.top + 0.5f * area.height / window.rows)
                assertEquals(column to window.top, panelCellAt(panel, d, seq, at, Scale.Chromatic))
            }
        }
        assertNull("outside the grid is not a cell", panelCellAt(panel, d, seq, Offset(area.left - 5f, area.center.y)))
    }

    @Test
    fun `a dot covers every step it lasts, and grows only as far as there is room`() {
        val (_, seq) = seq()
        seq.addDot(Dot(2, 5, 3))
        seq.addDot(Dot(8, 5, 1))
        seq.addDot(Dot(3, 9, 1))
        assertEquals(0, seq.dotAt(2, 5))
        assertEquals(0, seq.dotAt(4, 5))
        assertEquals(-1, seq.dotAt(5, 5))
        assertEquals("another degree is another dot", 2, seq.dotAt(3, 9))
        assertEquals("up to the next dot at its degree", 6, seq.dotRoom(0))
        assertEquals("and the last runs to the end of the loop", 16 - 8, seq.dotRoom(1))
    }

    @Test
    fun `a sequencer holds so many dots and no more`() {
        val (_, seq) = seq()
        repeat(MAX_DOTS) { assertTrue(seq.addDot(Dot(it % DOT_STEPS, it / DOT_STEPS))) }
        assertFalse(seq.addDot(Dot(0, 99)))
        assertEquals(MAX_DOTS, seq.dots.size)
    }

    @Test
    fun `dots round-trip through the file, and a file off the grid is clamped onto it`() {
        val (patch, seq) = seq()
        seq.addDot(Dot(0, 0, 4))
        seq.addDot(Dot(31, -3, 1))
        val json = patch.toJson()
        assertTrue(json.contains("\"version\":8"))
        assertEquals(seq.dots.toList(), patchFromJson(json)!!.modules.first { it.type == Types.DotSeq }.dots.toList())

        val wild = json.replace("[31,-3,1]", "[99,-3,500]")
        assertEquals(Dot(DOT_STEPS - 1, -3, DOT_STEPS), patchFromJson(wild)!!.modules.first { it.type == Types.DotSeq }.dots[1])
    }

    @Test
    fun `a version 7 file still reads, and a duplicate and an undo keep the dots`() {
        val (patch, seq) = seq()
        seq.addDot(Dot(1, 2, 3))
        val seven = patch.toJson().replace("\"version\":8", "\"version\":7")
        assertEquals(listOf(Dot(1, 2, 3)), patchFromJson(seven)!!.modules.first { it.type == Types.DotSeq }.dots.toList())

        assertEquals(listOf(Dot(1, 2, 3)), patch.duplicate(seq)!!.dots.toList())

        val before = patch.toJson()
        seq.removeDot(0)
        patch.replaceWith(patchFromJson(before)!!)
        assertEquals(listOf(Dot(1, 2, 3)), patch.module(seq.id)!!.dots.toList())
        assertEquals("the undo is byte for byte", before, patch.toJson())
    }
}
