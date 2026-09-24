package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11's last additions, as the interface sees them: an Osc's tune, and the Noise, Delay
 * and Reverb modules. The DSP is node_test's; what is here is the model, the file and the
 * contracts a new module has to keep.
 */
class CatalogTest {

    /**
     * 16 is additive over 15, so a 15 file opens as it stands -- and the one knob it cannot
     * name comes back at the value that restates what the file already sounded like.
     */
    @Test
    fun `a format 15 file still opens, with its Osc in tune`() {
        val patch = Patch()
        val osc = patch.add(Types.Osc, Offset.Zero)!!
        osc.setParam(0, 3f) // a sine, so the file says something about the Osc
        val current = patch.toJson()
        assertTrue(current.contains("\"version\":16"))

        // What a 15 build wrote: the same file, with no tune in it.
        val older = current.replace("\"version\":16", "\"version\":15").replace(",\"tune\":0", "")
        assertTrue("the edit took: $older", !older.contains("tune"))
        val back = patchFromJson(older)
        assertNotNull("15 is still read", back)
        val read = back!!.modules.first { it.type == Types.Osc }
        assertEquals("the waveform it did name", 3f, read.params[0])
        assertEquals("and in tune, which is what it was", 0f, read.params[1])
    }

    @Test
    fun `an Osc's tune is in cents, and can be exposed for vibrato`() {
        val tune = Types.Osc.params[1]
        assertEquals("tune", tune.name)
        assertEquals("¢", tune.unit)
        assertEquals(-TUNE_RANGE, tune.min)
        assertEquals(TUNE_RANGE, tune.max)
        assertEquals(0f, tune.default)
        val osc = Patch().add(Types.Osc, Offset.Zero)!!
        assertTrue("a modulator reaches it through an exposed jack", osc.canExpose(1))
    }
}
