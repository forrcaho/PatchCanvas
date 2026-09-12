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

    /**
     * Locally constructed rather than loaded: this is about the Scale type's arithmetic,
     * and the shipped .scl files have their own tests. One of each kind, because the
     * degenerate equal-division case hides bugs the unequal ones expose.
     */
    private val cases = listOf(
        Scale.equal("12-TET", 12),
        Scale.steps("Major", 12, listOf(2, 2, 1, 2, 2, 2, 1)),
        Scale.steps("Harmonic minor", 12, listOf(2, 1, 2, 2, 1, 3, 1)),
        Scale.ratios("Just major", listOf(1 to 1, 9 to 8, 5 to 4, 4 to 3, 3 to 2, 5 to 3, 15 to 8)),
        Scale.equal("Bohlen-Pierce", 13, 1.5849625f),
    )

    @Test
    fun `the chromatic scale is twelve equal steps to the octave`() {
        val c = Scale.Chromatic
        assertEquals(12, c.size)
        (0..12).forEach { d -> assertEquals("degree $d", d / 12f, c.octavesOf(d), 1e-6f) }
    }

    @Test
    fun `a degree one period up is exactly one period up`() {
        cases.forEach { scale ->
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
        cases.forEach { scale ->
            assertEquals("${scale.name}", -scale.period, scale.octavesOf(-scale.size), 1e-5f)
            assertTrue("${scale.name}", scale.octavesOf(-1) < 0f)
            assertTrue("${scale.name}", scale.octavesOf(-1) > -scale.period)
        }
    }

    @Test
    fun `every scale ascends within its period and starts at the root`() {
        cases.forEach { scale ->
            assertEquals("${scale.name} starts at the root", 0f, scale.degrees.first(), 1e-6f)
            scale.degrees.zipWithNext().forEach { (a, b) ->
                assertTrue("${scale.name} is not ascending: $a then $b", b > a)
            }
            assertTrue("${scale.name} degree exceeds its period", scale.degrees.last() < scale.period)
        }
    }

    @Test
    fun `a diatonic scale keeps its tones and semitones`() {
        val major = cases.first { it.name == "Major" }
        assertEquals(7, major.size)
        val semitones = (0 until major.size).map { d ->
            Math.round((major.octavesOf(d + 1) - major.octavesOf(d)) * 12f)
        }
        assertEquals(listOf(2, 2, 1, 2, 2, 2, 1), semitones)
    }

    @Test
    fun `a ratio scale places its degrees where the ratios say`() {
        val just = cases.first { it.name == "Just major" }
        assertEquals(Math.log(1.5) / Math.log(2.0), just.octavesOf(4).toDouble(), 1e-6)
    }

    @Test
    fun `Bohlen-Pierce repeats at a tritave, not an octave`() {
        val bp = cases.first { it.name == "Bohlen-Pierce" }
        assertEquals(13, bp.size)
        val ratio = Math.pow(2.0, bp.octavesOf(bp.size).toDouble())
        assertTrue("a turn of the scale was ${"%.4f".format(ratio)}:1", abs(ratio - 3.0) < 1e-4)
    }
}
