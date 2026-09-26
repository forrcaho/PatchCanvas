package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time divided one way for every module the transport times: any number of steps to a beat
 * from 1 to 16, whole beats from 2 to 16, and the quarter triplet -- so five to a beat is a
 * choice rather than a name nobody has -- with the LFO synced to it as the Delay already was.
 */
class IntervalTest {

    private fun interval(num: Int, den: Int) = INTERVALS.indexOf(Interval(num, den))

    @Test
    fun `every count to the beat and every whole number of beats is there, once`() {
        (1..16).forEach { n -> assertEquals("$n to a beat", 1, INTERVALS.count { it == Interval(1, n) }) }
        (2..16).forEach { k -> assertEquals("$k beats", 1, INTERVALS.count { it == Interval(k, 1) }) }
        assertEquals("and the quarter triplet stays", 1, INTERVALS.count { it == Interval(2, 3) })
        assertEquals("nothing twice", INTERVALS.size, INTERVALS.toSet().size)
    }

    /** A saved patch names an index, so the old entries keep theirs -- "free" among them. */
    @Test
    fun `the old entries kept their places`() {
        assertEquals(
            listOf(
                Interval(4, 1), Interval(2, 1), Interval(1, 1), Interval(1, 2), Interval(1, 4),
                Interval(1, 8), Interval(2, 3), Interval(1, 3), Interval(1, 6),
            ),
            INTERVALS.take(9),
        )
        assertEquals(9, FREE_INTERVAL)
        assertTrue(INTERVALS[FREE_INTERVAL].free)
    }

    @Test
    fun `a step with a note length's name wears it, and one without says its count`() {
        assertEquals("1/8", INTERVALS[interval(1, 2)].label)
        assertEquals("1/8T", INTERVALS[interval(1, 3)].label)
        assertEquals("1/64", INTERVALS[interval(1, 16)].label)
        assertEquals("5/beat", INTERVALS[interval(1, 5)].label)
        assertEquals("1/1", INTERVALS[interval(4, 1)].label)
        assertEquals("5 beats", INTERVALS[interval(5, 1)].label)
        assertEquals("free", INTERVALS[FREE_INTERVAL].label)
    }

    @Test
    fun `an LFO can be synced, is free until it is, and its rate means nothing while synced`() {
        assertTrue(Types.Lfo.canBeFree)
        val patch = Patch()
        val lfo = patch.add(Types.Lfo, Offset.Zero)!!
        val rate = Types.Lfo.params.indexOfFirst { it.name == "rate" }
        assertTrue("free by default, so an old LFO is the one it was", lfo.interval.free)
        assertTrue(lfo.isLive(rate))
        lfo.setParam(Types.Lfo.intervalParam, interval(1, 1).toFloat())
        assertFalse("synced, the rate is faint", lfo.isLive(rate))
        assertEquals("and the interval is appended, leaving rate and wave where they were",
            2, Types.Lfo.intervalParam)
    }

    // ------------------------------------------------------------------ the chooser

    private val frame = Frame(
        canvas = Size(2404f, 1080f), density = 2.4375f,
        insetLeft = 160f, insetTop = 54f, insetRight = 0f, insetBottom = 58f,
    )
    private val panel = panelRect(frame)
    private val d = frame.density

    @Test
    fun `the chooser offers every length once, and free only where something answers to it`() {
        val sequencer = intervalChooser(panel, d, 1f, free = Types.Seq.canBeFree).tiles.map { it.second }
        assertEquals((INTERVALS.indices - FREE_INTERVAL).toSet(), sequencer.toSet())
        assertEquals(sequencer.size, sequencer.toSet().size)
        val delay = intervalChooser(panel, d, 1f, free = Types.Delay.canBeFree).tiles.map { it.second }
        assertEquals(INTERVALS.indices.toSet(), delay.toSet())
    }

    @Test
    fun `steps to the beat run 1 to 16 along the top row, in order`() {
        val chooser = intervalChooser(panel, d, 1f, free = true)
        val top = chooser.tiles.take(16)
        assertEquals((1..16).map { interval(1, it) }, top.map { it.second })
        assertTrue("one row", top.all { it.first.top == top.first().first.top })
        assertTrue("left to right", top.zipWithNext().all { (a, b) -> a.first.left < b.first.left })
    }

    @Test
    fun `the chooser fits the panel at the reference device's text sizes, with room for a finger`() {
        listOf(1f, 1.5f).forEach { scale ->
            val chooser = intervalChooser(panel, d, scale, free = true)
            val tiles = chooser.tiles.map { it.first }
            tiles.forEach { tile ->
                assertTrue("$tile escapes the panel at $scale", panel.contains(tile.topLeft) &&
                    tile.right <= panel.right && tile.bottom <= panel.bottom)
                assertTrue("below the header at $scale", tile.top >= panel.top + PatchModule.PANEL_HEADER * d)
                assertTrue("too narrow at $scale: ${tile.width / d}dp", tile.width / d >= 40f)
                assertTrue("too short at $scale: ${tile.height / d}dp", tile.height / d >= 40f)
            }
            tiles.forEachIndexed { i, a ->
                tiles.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) }
            }
            chooser.captions.forEach { (at, _) -> assertTrue(panel.contains(at)) }
        }
    }

    @Test
    fun `the interval chip grows with the text and stays in the header`() {
        val small = panelIntervalChip(panel, d, 1f)
        val large = panelIntervalChip(panel, d, 1.5f)
        assertEquals(1.5f * small.width, large.width, 0.5f)
        assertTrue(large.bottom <= panel.top + PatchModule.PANEL_HEADER * d)
        assertTrue("clear of the centered title", large.left > panel.center.x + 60f * d)
        assertTrue("and the lock beside it moves over", panelLockChip(panel, d, 1.5f).right < large.left)
    }

    // ------------------------------------------------------------------ beats on the grid

    @Test
    fun `a sixteenth grid is marked every four steps, and every sixteen is a bar`() {
        assertEquals(
            listOf(4 to false, 8 to false, 12 to false, 16 to true, 20 to false, 24 to false, 28 to false),
            beatLines(32, INTERVALS[interval(1, 4)], 4),
        )
    }

    /** Forrest's case: five beats to a bar, five steps to each. */
    @Test
    fun `five to a beat in a bar of five is marked in fives, and a bar every twenty-five`() {
        val lines = beatLines(50, INTERVALS[interval(1, 5)], 5)
        assertEquals((1..9).map { it * 5 }, lines.map { it.first })
        assertEquals(listOf(25), lines.filter { it.second }.map { it.first })
    }

    @Test
    fun `steps of a beat or more mark only the bars, and a free interval nothing`() {
        assertEquals(listOf(2 to true, 4 to true, 6 to true), beatLines(8, INTERVALS[interval(2, 1)], 4))
        assertEquals(listOf(4 to true), beatLines(8, INTERVALS[interval(1, 1)], 4))
        assertEquals(
            "a quarter triplet lands on a beat every third step",
            listOf(3 to false, 6 to true, 9 to false),
            beatLines(12, INTERVALS[interval(2, 3)], 4),
        )
        assertTrue(beatLines(16, INTERVALS[FREE_INTERVAL], 4).isEmpty())
    }

    // ------------------------------------------------------------------ the file

    /**
     * 17 only added: the table grew past its old end, and an LFO gained an interval. So a 16
     * reads as it stands -- its intervals index the same entries, and its LFOs name no
     * interval, which is free, which is what they were.
     */
    @Test
    fun `a format 16 file reads as it was written`() {
        val patch = Patch()
        val seq = patch.add(Types.Seq, Offset.Zero)!!
        val delay = patch.add(Types.Delay, Offset(0f, 200f))!!
        val lfo = patch.add(Types.Lfo, Offset(0f, 400f))!!
        seq.setParam(Types.Seq.intervalParam, interval(1, 6).toFloat())
        delay.setParam(Types.Delay.intervalParam, FREE_INTERVAL.toFloat())
        val root = JSONObject(patch.toJson()).put("version", 16)
        val modules = root.getJSONArray("modules")
        for (i in 0 until modules.length()) {
            val m = modules.getJSONObject(i)
            if (m.getString("type") == "LFO") m.getJSONObject("params").remove("interval")
        }
        val read = patchFromJson(root.toString())
        assertNotNull(read)
        assertEquals(INTERVALS[interval(1, 6)], read!!.module(seq.id)!!.interval)
        assertTrue(read.module(delay.id)!!.interval.free)
        assertTrue("an LFO that names no interval is free", read.module(lfo.id)!!.interval.free)
    }

    @Test
    fun `five to a beat and a synced LFO survive a save`() {
        val patch = Patch()
        val seq = patch.add(Types.Seq, Offset.Zero)!!
        val lfo = patch.add(Types.Lfo, Offset(0f, 200f))!!
        seq.setParam(Types.Seq.intervalParam, interval(1, 5).toFloat())
        lfo.setParam(Types.Lfo.intervalParam, interval(4, 1).toFloat())
        val json = patch.toJson()
        val read = patchFromJson(json)!!
        assertEquals(Interval(1, 5), read.module(seq.id)!!.interval)
        assertEquals(Interval(4, 1), read.module(lfo.id)!!.interval)
        assertEquals("byte for byte", json, read.toJson())
    }
}
