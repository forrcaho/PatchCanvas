package io.github.forrcaho.patchgarden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The Scala format's two traps, and the shipped files.
 *
 * A file on disk is untrusted input written by hand somewhere else, so every malformed
 * shape has to read as "skip this scale" rather than as a crash or, worse, a tuning that
 * is quietly wrong by one degree.
 */
class ScalaFileTest {

    private fun scl(vararg lines: String) = lines.joinToString("\n")

    @Test
    fun `the unison is implicit, so a seven-note file has seven degrees`() {
        val scale = parseScala(
            "Major",
            scl("! major.scl", "!", "Diatonic major", " 7", "!",
                " 200.0", " 400.0", " 500.0", " 700.0", " 900.0", " 1100.0", " 2/1"),
        )
        assertNotNull(scale)
        // Six listed degrees plus the implicit 1/1; the seventh line is the period.
        assertEquals(7, scale!!.size)
        assertEquals(0f, scale.degrees.first(), 1e-6f)
        assertEquals(1f, scale.period, 1e-6f)
        assertEquals(
            listOf(0, 2, 4, 5, 7, 9, 11),
            scale.degrees.map { Math.round(it * 12f) },
        )
    }

    /** The other trap: the last entry is the period, not a note you can play. */
    @Test
    fun `the last entry becomes the period rather than a degree`() {
        val bp = parseScala(
            "BP",
            scl("Bohlen-Pierce", " 3", " 400.0", " 800.0", " 3/1"),
        )!!
        assertEquals(3, bp.size)
        assertEquals(listOf(0f, 400f / 1200f, 800f / 1200f), bp.degrees)
        // A full turn multiplies frequency by three, not two.
        assertTrue(abs(Math.pow(2.0, bp.period.toDouble()) - 3.0) < 1e-4)
    }

    @Test
    fun `a blank description is a line, not an absence`() {
        // If the blank line were skipped, the count would be read as the description and
        // the first pitch as the count -- which parses, and is wrong by one degree.
        val scale = parseScala("x", scl("", " 2", " 600.0", " 2/1"))
        assertNotNull(scale)
        assertEquals(2, scale!!.size)
        assertEquals(0.5f, scale.degrees[1], 1e-6f)
    }

    @Test
    fun `cents and ratios are both understood, and a bare integer is a ratio`() {
        // 3/2 as a ratio, 1100 as cents, and 2 meaning 2/1 -- the three forms at once.
        val scale = parseScala("x", scl("mixed", " 3", " 3/2", " 1100.0", " 2"))!!
        assertEquals(0.5849625f, scale.degrees[1], 1e-6f)
        assertEquals(1100f / 1200f, scale.degrees[2], 1e-6f)
        assertEquals(1f, scale.period, 1e-6f)
    }

    /**
     * 1200 cents and 2/1 are the same pitch written two ways, so a file listing both is
     * claiming a degree that is not there. Caught by accident while writing the test
     * above, which is the sort of thing a hand-written scale will contain.
     */
    @Test
    fun `the same pitch written two ways is still a duplicate`() {
        assertNull(parseScala("x", scl("x", " 3", " 3/2", " 1200.0", " 2/1")))
    }

    @Test
    fun `trailing commentary on a pitch line is ignored`() {
        val scale = parseScala("x", scl("x", " 2", " 700.0 perfect fifth", " 2/1 octave"))
        assertNotNull(scale)
        assertEquals(700f / 1200f, scale!!.degrees[1], 1e-6f)
    }

    @Test
    fun `malformed files are skipped, not guessed at`() {
        fun bad(why: String, text: String) = assertNull(why, parseScala("x", text))

        bad("empty", "")
        bad("no count", scl("just a description"))
        bad("count is not a number", scl("x", " seven", " 2/1"))
        bad("fewer pitches than promised", scl("x", " 4", " 100.0", " 2/1"))
        bad("descending", scl("x", " 3", " 700.0", " 200.0", " 2/1"))
        bad("a repeated degree", scl("x", " 3", " 700.0", " 700.0", " 2/1"))
        bad("at or below the unison", scl("x", " 2", " 0.0", " 2/1"))
        bad("a negative ratio", scl("x", " 2", " -3/2", " 2/1"))
        bad("division by zero", scl("x", " 2", " 3/0", " 2/1"))
        bad("nonsense pitch", scl("x", " 2", " banana", " 2/1"))
        bad("absurd count", scl("x", " ${MAX_DEGREES + 1}", " 2/1"))
    }

    // ------------------------------------------------------------ the shipped files

    private fun shipped(): List<File> =
        File("src/main/assets/scales").listFiles()?.sortedBy { it.name } ?: emptyList()

    @Test
    fun `every shipped scale parses`() {
        val files = shipped()
        assertTrue("no scale assets found -- wrong working directory?", files.size >= 10)
        files.forEach { file ->
            assertTrue("${file.name} is not .scl", file.name.endsWith(".scl"))
            assertNotNull("${file.name} does not parse", parseScala(file.nameWithoutExtension, file.readText()))
        }
    }

    @Test
    fun `the shipped 12-TET is the same scale as the built-in fallback`() {
        val file = shipped().first { it.name == "12-TET.scl" }
        val parsed = parseScala(file.nameWithoutExtension, file.readText())!!
        assertEquals(Scale.Chromatic.name, parsed.name)
        assertTrue("the shipped file drifted from the fallback", parsed.soundsLike(Scale.Chromatic))
    }

    @Test
    fun `the shipped set is mostly scales with unequal steps`() {
        val unequal = shipped()
            .mapNotNull { parseScala(it.nameWithoutExtension, it.readText()) }
            .count { scale ->
                val gaps = (0 until scale.size).map { scale.octavesOf(it + 1) - scale.octavesOf(it) }
                gaps.max() - gaps.min() > 1e-5f
            }
        assertTrue("only $unequal shipped scales have unequal steps", unequal >= 6)
    }

    @Test
    fun `the shipped diatonic scales keep their tones and semitones`() {
        fun stepsOf(name: String): List<Int> {
            val file = shipped().first { it.name == "$name.scl" }
            val scale = parseScala(name, file.readText())!!
            return (0 until scale.size).map {
                Math.round((scale.octavesOf(it + 1) - scale.octavesOf(it)) * 12f)
            }
        }
        assertEquals(listOf(2, 2, 1, 2, 2, 2, 1), stepsOf("Major"))
        assertEquals(listOf(2, 1, 2, 2, 1, 2, 2), stepsOf("Minor"))
        assertEquals(listOf(2, 1, 2, 2, 1, 3, 1), stepsOf("Harmonic minor"))
        assertEquals(listOf(3, 2, 2, 3, 2), stepsOf("Minor pentatonic"))
    }

    /** Just intonation is the case an equal division cannot express at all. */
    @Test
    fun `the shipped just major has two sizes of whole tone`() {
        val file = shipped().first { it.name == "Just major.scl" }
        val just = parseScala("Just major", file.readText())!!
        val first = just.octavesOf(1) - just.octavesOf(0)
        val second = just.octavesOf(2) - just.octavesOf(1)
        val comma = Math.pow(2.0, (first - second).toDouble())
        assertTrue("ratio was $comma", abs(comma - 81.0 / 80.0) < 1e-4)
    }
}
