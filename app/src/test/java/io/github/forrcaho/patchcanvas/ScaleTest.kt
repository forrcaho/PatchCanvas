package io.github.forrcaho.patchcanvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * A scale is the only place a degree becomes a pitch, so everything downstream -- the
 * sync, the command, the engine -- is tuning-agnostic by construction. These assert the
 * property that buys: that a non-octave scale is not a special case.
 */
class ScaleTest {

    @Test
    fun `the chromatic scale is twelve equal steps to the octave`() {
        val c = Scale.Chromatic
        assertEquals(12, c.size)
        (0..12).forEach { d ->
            assertEquals("degree $d", d / 12f, c.octavesOf(d), 1e-6f)
        }
    }

    @Test
    fun `a degree one period up is exactly one period up`() {
        Scale.all.forEach { scale ->
            (0 until scale.size).forEach { d ->
                assertEquals(
                    "${scale.name} degree $d",
                    scale.octavesOf(d) + scale.period,
                    scale.octavesOf(d + scale.size),
                    1e-5f,
                )
            }
        }
    }

    @Test
    fun `negative degrees run below the root, not off the end of the table`() {
        Scale.all.forEach { scale ->
            assertEquals(
                "${scale.name}",
                -scale.period,
                scale.octavesOf(-scale.size),
                1e-5f,
            )
            // The degree just below the root is the top of the period beneath it.
            assertTrue("${scale.name}", scale.octavesOf(-1) < 0f)
            assertTrue("${scale.name}", scale.octavesOf(-1) > -scale.period)
        }
    }

    @Test
    fun `every scale ascends within its period and starts at the root`() {
        Scale.all.forEach { scale ->
            assertEquals("${scale.name} starts at the root", 0f, scale.degrees.first(), 1e-6f)
            scale.degrees.zipWithNext().forEach { (a, b) ->
                assertTrue("${scale.name} is not ascending: $a then $b", b > a)
            }
            assertTrue(
                "${scale.name} degree exceeds its period",
                scale.degrees.last() < scale.period,
            )
        }
    }

    /**
     * The one that proves the mechanism is real rather than 12-TET with extra steps:
     * Bohlen-Pierce repeats at the twelfth, so its period is a 3:1 frequency ratio and
     * an "octave" never appears in it at all.
     */
    /**
     * The reason the model is a list rather than a division count. A typo in a step
     * pattern gives a scale that is subtly out of tune rather than obviously broken, so
     * the sums are asserted rather than eyeballed.
     */
    @Test
    fun `step patterns sum to their division`() {
        fun check(name: String, divisions: Int, steps: List<Int>) {
            assertEquals("$name steps $steps", divisions, steps.sum())
            val scale = Scale.steps(name, divisions, steps)
            assertEquals("$name degree count", steps.size, scale.size)
            assertEquals("$name closes its period", 1f, scale.octavesOf(steps.size), 1e-6f)
        }
        check("Major", 12, listOf(2, 2, 1, 2, 2, 2, 1))
        check("Minor", 12, listOf(2, 1, 2, 2, 1, 2, 2))
        check("Harmonic minor", 12, listOf(2, 1, 2, 2, 1, 3, 1))
        check("Minor pent", 12, listOf(3, 2, 2, 3, 2))
        check("Whole tone", 12, listOf(2, 2, 2, 2, 2, 2))
    }

    /**
     * What the scale model is actually for. An equal division is the easy case; the
     * scales worth having are the ones whose character is the pattern of unequal steps,
     * so at least one must survive the round trip with its steps intact.
     */
    @Test
    fun `a diatonic scale keeps its tones and semitones`() {
        val major = Scale.byName("Major")!!
        assertEquals(7, major.size)
        val semitones = (0 until major.size).map { d ->
            Math.round((major.octavesOf(d + 1) - major.octavesOf(d)) * 12f)
        }
        assertEquals(listOf(2, 2, 1, 2, 2, 2, 1), semitones)
    }

    @Test
    fun `most of the shipped scales have unequal steps`() {
        val unequal = Scale.all.count { scale ->
            val gaps = (0 until scale.size).map { scale.octavesOf(it + 1) - scale.octavesOf(it) }
            gaps.max() - gaps.min() > 1e-5f
        }
        assertTrue("only $unequal of ${Scale.all.size} are unequal", unequal >= 5)
    }

    /** 9:8 and 10:9 are both whole tones and are not the same size. */
    @Test
    fun `just intonation has two different whole tones`() {
        val just = Scale.byName("Just major")!!
        val first = just.octavesOf(1) - just.octavesOf(0)
        val second = just.octavesOf(2) - just.octavesOf(1)
        assertTrue("both were $first", abs(first - second) > 1e-4f)
        // The syntonic comma, 81:80, is what separates them.
        val comma = Math.pow(2.0, (first - second).toDouble())
        assertTrue("ratio was $comma", abs(comma - 81.0 / 80.0) < 1e-3)
    }

    @Test
    fun `a ratio scale places its degrees where the ratios say`() {
        val just = Scale.byName("Just major")!!
        // The fifth, 3:2, is the fourth degree.
        assertEquals(Math.log(1.5) / Math.log(2.0), just.octavesOf(4).toDouble(), 1e-6)
    }

    @Test
    fun `Bohlen-Pierce repeats at a tritave, not an octave`() {
        val bp = Scale.byName("Bohlen-Pierce")
        assertNotNull(bp)
        bp!!
        assertEquals(13, bp.size)
        // 2^log2(3) == 3: a full turn of the scale multiplies frequency by three.
        val ratio = Math.pow(2.0, bp.octavesOf(bp.size).toDouble())
        assertTrue("a turn of the scale was ${"%.4f".format(ratio)}:1", abs(ratio - 3.0) < 1e-4)
    }

    @Test
    fun `19-TET divides the octave into nineteen, and none of them is a semitone`() {
        val t = Scale.byName("19-TET")!!
        assertEquals(19, t.size)
        assertEquals(1f, t.octavesOf(19), 1e-6f)
        t.degrees.drop(1).forEach { d ->
            assertTrue("$d lands on a semitone", abs(d * 12f - Math.round(d * 12f)) > 0.01f)
        }
    }

    @Test
    fun `scale names are unique, since a name is how one is stored`() {
        assertEquals(Scale.all.size, Scale.all.map { it.name }.toSet().size)
    }
}
