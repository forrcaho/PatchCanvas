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
