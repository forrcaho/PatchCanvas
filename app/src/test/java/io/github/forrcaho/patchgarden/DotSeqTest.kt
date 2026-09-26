package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        return patch to patch.add(Types.Seq, Offset.Zero)!!
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
        seq.addDot(Dot(2, 5, 3 * DOT_SUBSTEPS))
        seq.addDot(Dot(8, 5, DOT_SUBSTEPS))
        seq.addDot(Dot(3, 9, DOT_SUBSTEPS))
        assertEquals(0, seq.dotAt(2, 5))
        assertEquals(0, seq.dotAt(4, 5))
        assertEquals(-1, seq.dotAt(5, 5))
        assertEquals("another degree is another dot", 2, seq.dotAt(3, 9))
        assertEquals("up to the next dot at its degree", 6 * DOT_SUBSTEPS, seq.dotRoom(0))
        assertEquals("and the last runs to the end of the loop", (16 - 8) * DOT_SUBSTEPS, seq.dotRoom(1))
    }

    /**
     * A dot's length is its duration, in quarter steps, which is what took Seq's `gate` knob
     * away: the knob shortened the last step of every note at once, and a length says it per
     * note. Steps' half step is a length of 2.
     */
    @Test
    fun `a dot can be shorter than a step, and still covers the step it is in`() {
        val (_, seq) = seq()
        seq.addDot(Dot(2, 5, 2)) // half a step
        assertEquals("one step long on the grid", 1, seq.dots[0].stepsSpanned)
        assertEquals("and the step it is in is its own", 0, seq.dotAt(2, 5))
        assertEquals("but no further", -1, seq.dotAt(3, 5))

        seq.setDotLength(0, 1)
        assertEquals("a quarter step is the shortest there is", 1, seq.dots[0].length)
        seq.setDotLength(0, 0)
        assertEquals(1, seq.dots[0].length)

        assertTrue("and Seq has no gate knob to do it globally", Types.Seq.params.none { it.name == "gate" })
        assertEquals("three knobs, one of them the header's interval", 3, Types.Seq.params.size)
    }

    /** Where a stretch measures to: quarter steps across the grid, clamped to it. */
    @Test
    fun `a stretch lands on the quarter step under the finger`() {
        val (_, seq) = seq()
        val area = panelGrid(panel, d, seq.type)
        val columns = dotColumns(seq)
        val per = area.width / (columns * DOT_SUBSTEPS)
        listOf(0, 1, 2, 3, 7, columns * DOT_SUBSTEPS - 1).forEach { q ->
            assertEquals(q, dotSubstepAt(area, columns, area.left + (q + 0.5f) * per))
        }
        assertEquals("past the left edge holds at the first", 0, dotSubstepAt(area, columns, area.left - 99f))
        assertEquals(
            "and past the right at the last",
            columns * DOT_SUBSTEPS - 1,
            dotSubstepAt(area, columns, area.right + 99f),
        )
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
        seq.addDot(Dot(31, -3, 1, 0.25f))
        val json = patch.toJson()
        assertTrue(json.contains("\"version\":18"))
        assertEquals(seq.dots.toList(), patchFromJson(json)!!.modules.first { it.type == Types.Seq }.dots.toList())

        val wild = json.replace("[31,-3,1,0.25]", "[99,-3,500,7]")
        assertEquals(
            Dot(DOT_STEPS - 1, -3, DOT_STEPS * DOT_SUBSTEPS, 1f),
            patchFromJson(wild)!!.modules.first { it.type == Types.Seq }.dots[1],
        )
    }

    /**
     * The additive run that let 11, 12 and 13 all read a 10 file ended at 14.
     *
     * This test read a version-10 file and asserted every dot came back at full, which was
     * right for as long as velocity was the only thing that had changed. Format 14 makes an
     * envelope segments, and an ADSR cannot be restated as segments -- only converted -- so
     * 14 reads nothing but 14 and the older file is refused whole. Kept as a refusal rather
     * than deleted, because "this version is not read" is the assertion that stops the
     * additive habit creeping back in.
     *
     * Asserted for every version this build used to accept, so raising FORMAT_VERSION
     * without thinking about the ladder fails here.
     */
    @Test
    fun `a file from before segments is refused, not converted`() {
        val (patch, seq) = seq()
        seq.addDot(Dot(2, 5, 6, 0.3f))
        val current = patch.toJson()
        assertNotNull("the current version still opens", patchFromJson(current))
        listOf(10, 11, 12, 13, 14).forEach { version ->
            val older = current.replace("\"version\":18", "\"version\":$version")
            assertNull("format $version must be refused", patchFromJson(older))
        }
    }

    @Test
    fun `a dot's velocity is set by a drag, relative to where it already was`() {
        val (_, seq) = seq()
        seq.addDot(Dot(0, 0))
        assertEquals("a new dot is struck at full", 1f, seq.dots[0].velocity)

        seq.setDotVelocity(0, 0.5f)
        assertEquals(0.5f, seq.dots[0].velocity)

        // Never to silence: a dot at zero would draw and take its step with no way to tell
        // it was there, and a dot nobody wants is removed with a tap.
        seq.setDotVelocity(0, -2f)
        assertEquals(MIN_VELOCITY, seq.dots[0].velocity)
        seq.setDotVelocity(0, 9f)
        assertEquals(1f, seq.dots[0].velocity)
    }

    @Test
    fun `a dot moves to another step and degree, and stops at what is in the way`() {
        val (_, seq) = seq()
        seq.addDot(Dot(0, 0, 2 * DOT_SUBSTEPS))
        seq.addDot(Dot(6, 4, DOT_SUBSTEPS))

        assertTrue(seq.moveDot(0, 3, 4))
        assertEquals(Dot(3, 4, 2 * DOT_SUBSTEPS), seq.dots[0])

        // Two dots at one degree cannot overlap -- the second's start would be heard as
        // nothing -- so the move is refused and the dot stays where the finger last left it.
        assertFalse("into the one at step 6", seq.moveDot(0, 5, 4))
        assertEquals(Dot(3, 4, 2 * DOT_SUBSTEPS), seq.dots[0])
        assertTrue("but past it is fine", seq.moveDot(0, 7, 4))

        // Off the end of the loop holds at the last step rather than leaving the grid.
        seq.moveDot(0, 99, 4)
        assertEquals(dotColumns(seq) - 1, seq.dots[0].step)
    }

    @Test
    fun `the lock is view state, and changes no patch`() {
        val (patch, seq) = seq()
        seq.addDot(Dot(0, 0))
        val before = patch.toJson()
        seq.dotsLocked = true
        assertEquals("a lock is not part of the instrument", before, patch.toJson())
    }

    @Test
    fun `a duplicate and an undo keep the dots`() {
        val (patch, seq) = seq()
        seq.addDot(Dot(1, 2, 3))
        assertEquals(listOf(Dot(1, 2, 3)), patch.duplicate(seq)!!.dots.toList())

        val before = patch.toJson()
        seq.removeDot(0)
        patch.replaceWith(patchFromJson(before)!!)
        assertEquals(listOf(Dot(1, 2, 3)), patch.module(seq.id)!!.dots.toList())
        assertEquals("the undo is byte for byte", before, patch.toJson())
    }

    /** The patterns node_test checks EuclidNode::hit against, so the drawing and the sound agree. */
    @Test
    fun `a Euclid draws the pattern it plays`() {
        fun pattern(steps: Int, pulses: Int, rotate: Int) =
            (0 until steps).joinToString("") { if (euclidHit(it, steps, pulses, rotate)) "x" else "." }
        assertEquals("x..x..x.", pattern(8, 3, 0))
        assertEquals(5, pattern(8, 5, 0).count { it == 'x' })
        assertEquals("the same turned by one, as node_test has it", "..x..x.x", pattern(8, 3, 1))
        assertEquals("the rhythm, not a pitch grid: nothing to tap", null,
            Patch().add(Types.Euclid, Offset.Zero)!!.let { panelCellAt(panel, d, it, panelGrid(panel, d, it.type).center) })
    }

    @Test
    fun `a note is named with its octave, in the key it sounds in`() {
        assertEquals("C4", noteWithOctave(0f))
        assertEquals("C3", noteWithOctave(-1200f))
        assertEquals("B3", noteWithOctave(-100f))
        assertEquals("off the twelve-tone grid it says so", "\u2248C4", noteWithOctave(20f))
        assertEquals("C3", degreeName(-12, Scale.Chromatic, 0f))
        assertEquals("G4", degreeName(7, Scale.Chromatic, 0f))
        assertEquals("a key of D moves every name", "E4", degreeName(2, Scale.Chromatic, 200f))
        assertTrue("Euclid's degree is read as a note", Types.Euclid.params.first { it.name == "degree" }.degree)
    }

    @Test
    fun `Seq took Steps' place in the menu, and Steps still loads`() {
        assertTrue(Types.Seq in Types.palette)
        assertTrue("not offered", Types.Steps !in Types.palette)
        val patch = fixturePatch()
        assertTrue("the demo patch has one", patch.modules.any { it.type == Types.Steps })
        val back = patchFromJson(patch.toJson())!!
        assertEquals("and it comes back", patch.modules.count { it.type == Types.Steps }, back.modules.count { it.type == Types.Steps })
        assertEquals("with its sequence", patch.modules.first { it.type == Types.Steps }.steps.toList(), back.modules.first { it.type == Types.Steps }.steps.toList())
    }

    @Test
    fun `a file from the night Seq was called DotSeq opens as Seq`() {
        val (patch, seq) = seq()
        seq.addDot(Dot(0, 3, 2))
        val old = patch.toJson().replace("\"type\":\"Seq\"", "\"type\":\"DotSeq\"")
        assertTrue("the edit found it", old.contains("DotSeq"))
        val back = patchFromJson(old)!!.modules.first { it.type == Types.Seq }
        assertEquals(listOf(Dot(0, 3, 2)), back.dots.toList())
        assertTrue("and saves under its new name", patchFromJson(old)!!.toJson().contains("\"type\":\"Seq\""))
    }
}
