package io.github.forrcaho.patchcanvas

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A module's color is the family of the cable it sends. Written down as a test because the
 * accents drifted into meaning nothing once already: each was picked when its module was
 * added, and by the time anyone looked, one matched what its module sent, one what it took,
 * and one a signal kind that no longer existed.
 */
class ModuleColorTest {

    private fun distance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return dr * dr + dg * dg + db * db
    }

    @Test
    fun `every module is colored by the kind of cable it sends`() {
        Types.byName.values.filter { it.outputs.isNotEmpty() }.forEach { type ->
            val sends = type.outputs.map { it.kind }.toSet()
            assertEquals("${type.name} sends one kind", 1, sends.size)
            val nearest = SignalKind.entries.minBy { distance(type.accent, it.cable) }
            assertEquals("${type.name}'s color", sends.single(), nearest)
        }
    }

    @Test
    fun `no module is exactly a cable color`() {
        Types.byName.values.forEach { type ->
            SignalKind.entries.forEach { kind ->
                assertTrue("${type.name} is the $kind cable's color", type.accent != kind.cable)
            }
        }
    }

    @Test
    fun `modules sending the same kind can be told apart`() {
        val palette = Types.palette
        palette.forEach { a ->
            palette.filter { it !== a }.forEach { b ->
                // A small but visible step: about 0.1 of the way across the color cube.
                assertTrue("${a.name} and ${b.name} look alike", distance(a.accent, b.accent) > 0.004f)
            }
        }
    }
}
