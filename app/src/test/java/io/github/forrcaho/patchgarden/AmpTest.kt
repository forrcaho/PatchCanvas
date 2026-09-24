package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Amp's `mod` port is its gain's own jack: one way in, where there were two.
 *
 * Until format 15 the port multiplied the knob and the knob could be exposed as well, so the
 * same envelope patched into both was applied twice -- `in * env * (0.6 + 0.8 * env)`, found
 * in the phone's own patch. Now the gain is a driven knob ([Param.drivenBy]): plain while the
 * port is empty, bracketed while it is patched, and never exposable.
 */
class AmpTest {

    private class Rig {
        val patch = Patch()
        val env = patch.add(Types.Env, Offset.Zero)!!
        val amp = patch.add(Types.Amp, Offset(200f, 0f))!!
        val mod = PortRef(amp.id, PortDirection.INPUT, 1)
        fun patchIt() = assertTrue(patch.connect(PortRef(env.id, PortDirection.OUTPUT, 0), mod))
    }

    @Test
    fun `an Amp's gain cannot be exposed, because its port already is its jack`() {
        val rig = Rig()
        assertTrue(rig.amp.isDriven(0))
        assertFalse(rig.amp.canExpose(0))
        assertFalse("refused, not quietly made", rig.patch.expose(rig.amp, 0, ModRange(0f, 1f)))
        assertTrue(rig.amp.exposed.isEmpty())
        assertEquals("and no band of jacks grows under it", PatchModule.heightFor(Types.Amp), rig.amp.height)
    }

    @Test
    fun `an Amp's gain is bracketed only while its port is patched`() {
        val rig = Rig()
        rig.amp.setParam(0, 0.8f)
        assertNull("with nothing in mod, the knob is the gain", rig.patch.rangeOf(rig.amp, 0))

        rig.patchIt()
        assertEquals(
            "patched, it sweeps from nothing up to the knob -- in * mod * gain",
            ModRange(0f, 0.8f), rig.patch.rangeOf(rig.amp, 0),
        )
        assertTrue("and nothing was stored to say so", rig.amp.modRanges.isEmpty())

        rig.patch.disconnect(rig.mod)
        assertNull("unpatched, the knob is the gain again", rig.patch.rangeOf(rig.amp, 0))
    }

    /**
     * The port is permanent, so its brackets belong to it rather than to whatever is in it:
     * swapping one envelope for another, or pulling the cable to listen without it, keeps them.
     */
    @Test
    fun `moved brackets outlive the cable, and come back with the next one`() {
        val rig = Rig()
        rig.patchIt()
        rig.patch.setRange(rig.amp, 0, ModRange(0.2f, 0.5f))
        rig.patch.disconnect(rig.mod)
        assertNull(rig.patch.rangeOf(rig.amp, 0))
        assertTrue("still not a jack", rig.amp.exposed.isEmpty())

        rig.patchIt()
        assertEquals(ModRange(0.2f, 0.5f), rig.patch.rangeOf(rig.amp, 0))
    }

    @Test
    fun `an Amp's brackets survive the file, and a cable into its gain as a jack does not`() {
        val rig = Rig()
        rig.patchIt()
        rig.patch.setRange(rig.amp, 0, ModRange(0.25f, 0.75f))
        val json = rig.patch.toJson()
        val back = patchFromJson(json)!!
        val amp = back.module(rig.amp.id)!!
        assertEquals(ModRange(0.25f, 0.75f), back.rangeOf(amp, 0))

        // Hand-edited: the envelope into the gain's jack as well as into mod -- the double
        // route this format exists to rule out. The file is untrusted; the cable is dropped.
        val doubled = json.replace(
            "\"connections\":[",
            "\"connections\":[{\"from\":${rig.env.id},\"fromPort\":0,\"to\":${rig.amp.id},\"toParam\":\"gain\"},",
        )
        assertTrue("the edit took", doubled != json)
        val wild = patchFromJson(doubled)
        assertNotNull(wild)
        assertTrue(
            "no cable reaches the gain but through mod",
            wild!!.connections.none { it.to.moduleId == rig.amp.id && it.to.dir == PortDirection.MOD },
        )
    }

    /** What every bracket, reading and knob hit test is handed, so it must match the drawing. */
    @Test
    fun `an Amp's knob is the hand's until its port is patched`() {
        val rig = Rig()
        val frame = Frame(
            canvas = androidx.compose.ui.geometry.Size(2404f, 1080f), density = 2.4375f,
            insetLeft = 160f, insetTop = 54f, insetRight = 0f, insetBottom = 58f,
        )
        val d = frame.density
        val panel = panelRect(frame)
        val rows = rig.patch.panelRows(rig.amp)
        val brackets = { row: ParamRow -> rig.patch.rangeOf(row.owner, row.index) }
        val on = panelRowAt(panel, d, Types.Amp, rows.size, 0).center

        assertEquals(ParamRow(rig.amp, 0), panelKnobAt(panel, d, rig.amp, rows, brackets, on))
        assertNull(panelBracketAt(panel, d, rig.amp, rows, brackets, on))
        rig.patchIt()
        assertNull("patched, the knob is the envelope's", panelKnobAt(panel, d, rig.amp, rows, brackets, on))
        assertNotNull("and the row's brackets are the hand's", panelBracketAt(panel, d, rig.amp, rows, brackets, on))
    }
}
