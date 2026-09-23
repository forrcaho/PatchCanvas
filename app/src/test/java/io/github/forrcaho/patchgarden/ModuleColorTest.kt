package io.github.forrcaho.patchgarden

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A module's color is the family of the cable it sends. Written down as a test because the
 * accents drifted into meaning nothing once already: each was picked when its module was
 * added, and by the time anyone looked, one matched what its module sent, one what it took,
 * and one a signal kind that no longer existed.
 *
 * Distances are CIE76 delta E in Lab, which tracks what an eye sees far better than plain
 * RGB. The first version of this test used RGB and passed shades that looked the same on
 * the phone.
 */
class ModuleColorTest {

    private fun lab(c: Color): Triple<Double, Double, Double> {
        fun linear(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        val r = linear(c.red)
        val g = linear(c.green)
        val b = linear(c.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun deltaE(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    /** An accent as its border actually reaches the screen: part-transparent over the fill. */
    private fun asBorder(accent: Color): Color = Color(
        red = MODULE_BORDER_ALPHA * accent.red + (1 - MODULE_BORDER_ALPHA) * ModuleFill.red,
        green = MODULE_BORDER_ALPHA * accent.green + (1 - MODULE_BORDER_ALPHA) * ModuleFill.green,
        blue = MODULE_BORDER_ALPHA * accent.blue + (1 - MODULE_BORDER_ALPHA) * ModuleFill.blue,
    )

    @Test
    fun `every module is colored by the kind of cable it sends`() {
        Types.byName.values.filter { it.outputs.isNotEmpty() }.forEach { type ->
            val sends = type.outputs.map { it.kind }.toSet()
            assertEquals("${type.name} sends one kind", 1, sends.size)
            val ranked = SignalKind.entries.sortedBy { deltaE(type.accent, it.cable) }
            assertEquals("${type.name}'s color", sends.single(), ranked[0])
            // Clearly, not by a hair: a shade halfway between two families says neither.
            val margin = deltaE(type.accent, ranked[1].cable) - deltaE(type.accent, ranked[0].cable)
            assertTrue("${type.name} is only $margin nearer its own cable", margin >= 10.0)
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

    /**
     * Fifteen, measured on the border as drawn. On the phone, pairs at 7 to 11 were
     * indistinguishable at module size; the shades now in use are at least 16.9 apart.
     */
    @Test
    fun `any two modules' borders look different on screen`() {
        val palette = Types.palette
        palette.forEach { a ->
            palette.filter { it !== a }.forEach { b ->
                val distance = deltaE(asBorder(a.accent), asBorder(b.accent))
                assertTrue("${a.name} and ${b.name} borders are only $distance apart", distance >= 15.0)
            }
        }
    }
}
