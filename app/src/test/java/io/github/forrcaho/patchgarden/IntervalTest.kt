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
 * Time divided one way for every module the transport times: a step is some beats divided into
 * some divisions, each from 1 to 16 -- Forrest's model, in which a quarter triplet (2 ÷ 3) and a
 * dotted quarter (3 ÷ 2) are ordinary choices rather than the special cases the first version
 * kept -- with the LFO synced to it as the Delay already was.
 */
class IntervalTest {

    @Test
    fun `every beats and divisions from 1 to 16 is written and read back as itself`() {
        (1..MAX_BEATS).forEach { beats ->
            (1..MAX_BEATS).forEach { divisions ->
                val step = Interval(beats, divisions)
                assertEquals(step, intervalOf(step.code.toFloat()))
            }
        }
        assertEquals("all 256 of them distinct", 256,
            (1..MAX_BEATS).flatMap { b -> (1..MAX_BEATS).map { Interval(b, it).code } }.toSet().size)
        // The same number node_test hands the engine as two beats in three: the formula is
        // written twice, once a side, and this is what holds the two to each other.
        assertEquals(82, Interval(2, 3).code)
        assertEquals(Interval(3, 2), intervalOf(97f))
        assertTrue("and free is still free", intervalOf(FREE_INTERVAL.toFloat()).free)
        assertEquals(FREE_INTERVAL, INTERVALS[FREE_INTERVAL].code)
    }

    /** An older file names an index; each still reads as the length it named. */
    @Test
    fun `an old index reads as the length it always was`() {
        assertEquals(Interval(1, 2), intervalOf(3f))
        assertEquals(Interval(2, 3), intervalOf(6f))
        assertEquals(Interval(1, 5), intervalOf(10f))
        assertEquals(Interval(16, 1), intervalOf(32f))
        assertTrue("every one of them a length the rows can say",
            INTERVALS.filter { !it.free }.all { it.num in 1..MAX_BEATS && it.den in 1..MAX_BEATS })
    }

    @Test
    fun `a step wears its note name, and says its fraction where it has none`() {
        assertEquals("1/8", Interval(1, 2).label)
        assertEquals("unreduced, still an eighth", "1/8", Interval(2, 4).label)
        assertEquals("1/4T", Interval(2, 3).label)
        assertEquals("1/4.", Interval(3, 2).label)
        assertEquals("1/8.", Interval(3, 4).label)
        assertEquals("1/1", Interval(4, 1).label)
        assertEquals("3÷5", Interval(3, 5).label)
        assertEquals("5 beats", Interval(5, 1).label)
        assertEquals("free", INTERVALS[FREE_INTERVAL].label)

        assertEquals("1 beat ÷ 3 = 1/8T, an eighth triplet", Interval(1, 3).readout())
        assertEquals("3 beats ÷ 2 = 1/4., a dotted quarter", Interval(3, 2).readout())
        assertEquals("5 beats ÷ 3", Interval(5, 3).readout())
    }

    @Test
    fun `a tap on a row changes that row and keeps the other`() {
        val eighth = Interval(1, 2)
        assertEquals(Interval(3, 2), eighth.with(IntervalPick.Beats(3)))
        assertEquals(Interval(1, 5), eighth.with(IntervalPick.Divisions(5)))
        assertTrue(eighth.with(IntervalPick.Free).free)
        val free = INTERVALS[FREE_INTERVAL]
        assertEquals("from free, the other row starts at 1", Interval(4, 1), free.with(IntervalPick.Beats(4)))
        assertEquals(Interval(1, 3), free.with(IntervalPick.Divisions(3)))
    }

    @Test
    fun `an LFO can be synced, is free until it is, and its rate means nothing while synced`() {
        assertTrue(Types.Lfo.canBeFree)
        val patch = Patch()
        val lfo = patch.add(Types.Lfo, Offset.Zero)!!
        val rate = Types.Lfo.params.indexOfFirst { it.name == "rate" }
        assertTrue("free by default, so an old LFO is the one it was", lfo.interval.free)
        assertTrue(lfo.isLive(rate))
        lfo.setParam(Types.Lfo.intervalParam, Interval(1, 1).code.toFloat())
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
    fun `beats run 1 to 16 over divisions 1 to 16, and free only where something answers to it`() {
        val sequencer = intervalChooser(panel, d, 1f, free = Types.Seq.canBeFree).tiles
        val beats = sequencer.filter { it.second is IntervalPick.Beats }
        val divisions = sequencer.filter { it.second is IntervalPick.Divisions }
        assertEquals((1..16).map { IntervalPick.Beats(it) }, beats.map { it.second })
        assertEquals((1..16).map { IntervalPick.Divisions(it) }, divisions.map { it.second })
        assertTrue("beats on top", beats.all { b -> divisions.all { b.first.bottom < it.first.top } })
        assertTrue("left to right", beats.zipWithNext().all { (a, b) -> a.first.left < b.first.left })
        assertTrue("columns line up", beats.zip(divisions).all { (b, v) -> b.first.left == v.first.left })
        assertFalse(sequencer.any { it.second == IntervalPick.Free })

        val delay = intervalChooser(panel, d, 1f, free = Types.Delay.canBeFree).tiles
        val free = delay.single { it.second == IntervalPick.Free }.first
        assertTrue("free ends the beats row", free.top == beats.first().first.top && free.left > beats.last().first.right)
    }

    @Test
    fun `the chooser fits the panel at the reference device's text sizes, with room for a finger`() {
        listOf(1f, 1.5f).forEach { scale ->
            val chooser = intervalChooser(panel, d, scale, free = true)
            val tiles = chooser.tiles.map { it.first }
            tiles.forEach { tile ->
                assertTrue("$tile escapes the panel at $scale", panel.contains(tile.topLeft) &&
                    tile.right <= panel.right && tile.bottom <= panel.bottom)
                assertTrue("too narrow at $scale: ${tile.width / d}dp", tile.width / d >= 40f)
                assertTrue("too short at $scale: ${tile.height / d}dp", tile.height / d >= 40f)
            }
            tiles.forEachIndexed { i, a ->
                tiles.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) }
            }
            val header = panel.top + PatchModule.PANEL_HEADER * d
            assertTrue("the readout is below the header at $scale", chooser.readout.y >= header)
            assertTrue("and above the rows", tiles.all { it.top > chooser.readout.y })
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
            beatLines(32, Interval(1, 4), 4),
        )
    }

    /** Forrest's case: five beats to a bar, five steps to each. */
    @Test
    fun `five to a beat in a bar of five is marked in fives, and a bar every twenty-five`() {
        val lines = beatLines(50, Interval(1, 5), 5)
        assertEquals((1..9).map { it * 5 }, lines.map { it.first })
        assertEquals(listOf(25), lines.filter { it.second }.map { it.first })
    }

    @Test
    fun `steps of a beat or more mark only the bars, and a free interval nothing`() {
        assertEquals(listOf(2 to true, 4 to true, 6 to true), beatLines(8, Interval(2, 1), 4))
        assertEquals(listOf(4 to true), beatLines(8, Interval(1, 1), 4))
        assertEquals(
            "a quarter triplet lands on a beat every third step",
            listOf(3 to false, 6 to true, 9 to false),
            beatLines(12, Interval(2, 3), 4),
        )
        assertEquals(
            "a dotted eighth lands on a beat every fourth step",
            listOf(4 to false, 8 to false, 12 to false, 16 to true),
            beatLines(20, Interval(3, 4), 4).take(4).let { listOf(it[0], it[1], it[2], it[3]) },
        )
        assertTrue(beatLines(16, INTERVALS[FREE_INTERVAL], 4).isEmpty())
    }

    // ------------------------------------------------------------------ the file

    /**
     * 18 only added: a value under 64 is still read through the old table, so a 17 or a 16
     * reads as it stands -- its intervals name the same lengths, and its LFOs name no interval,
     * which is free, which is what they were.
     */
    @Test
    fun `an older file reads as it was written`() {
        listOf(16, 17).forEach { version ->
            val patch = Patch()
            val seq = patch.add(Types.Seq, Offset.Zero)!!
            val delay = patch.add(Types.Delay, Offset(0f, 200f))!!
            val lfo = patch.add(Types.Lfo, Offset(0f, 400f))!!
            seq.setParam(Types.Seq.intervalParam, 8f) // the old table's 1/16T
            delay.setParam(Types.Delay.intervalParam, FREE_INTERVAL.toFloat())
            val root = JSONObject(patch.toJson()).put("version", version)
            if (version == 16) {
                val modules = root.getJSONArray("modules")
                for (i in 0 until modules.length()) {
                    val m = modules.getJSONObject(i)
                    if (m.getString("type") == "LFO") m.getJSONObject("params").remove("interval")
                }
            }
            val read = patchFromJson(root.toString())
            assertNotNull("format $version", read)
            assertEquals(Interval(1, 6), read!!.module(seq.id)!!.interval)
            assertTrue(read.module(delay.id)!!.interval.free)
            assertTrue("an LFO that names no interval is free", read.module(lfo.id)!!.interval.free)
        }
    }

    @Test
    fun `beats and divisions survive a save, unreduced`() {
        val patch = Patch()
        val seq = patch.add(Types.Seq, Offset.Zero)!!
        val lfo = patch.add(Types.Lfo, Offset(0f, 200f))!!
        seq.setParam(Types.Seq.intervalParam, Interval(2, 4).code.toFloat())
        lfo.setParam(Types.Lfo.intervalParam, Interval(3, 2).code.toFloat())
        val json = patch.toJson()
        val read = patchFromJson(json)!!
        assertEquals(Interval(2, 4), read.module(seq.id)!!.interval)
        assertEquals(Interval(3, 2), read.module(lfo.id)!!.interval)
        assertEquals("byte for byte", json, read.toJson())
    }
}
