package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Writes a patch for listening to the segment envelope, built through the model so the file
 * is one the app would have written itself.
 *
 * Throwaway, like [EarTestPatchGen]: a generator, not a test of anything. It is a test only
 * because the model lives in the app module and that is the cheapest way to run against it.
 *
 * Seq -> Osc -> Amp -> Out, with the same Seq opening an Env on the Amp's modulation port,
 * so what you hear is the envelope's shape and nothing else -- an Osc is one knob and the
 * Amp has no character of its own. The dots are long and well separated, because the point
 * is to hear a whole shape rather than a texture.
 */
class EnvTestPatchGen {

    private fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
    private fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)

    @Test
    fun `write the envelope listening patch`() {
        val patch = Patch()
        patch.name = "Env tests"

        val seq = patch.add(Types.Seq, Offset(60f, 60f))!!
        val osc = patch.add(Types.Osc, Offset(300f, 60f))!!
        val env = patch.add(Types.Env, Offset(300f, 220f))!!
        val amp = patch.add(Types.Amp, Offset(520f, 60f))!!

        // Four notes, two steps apart, each a whole step long: room for a slow shape to be
        // heard through and a gap between them so the release is not covered by the next.
        seq.setParam(0, 16f)
        listOf(0, 4, 8, 12).forEachIndexed { i, step ->
            seq.addDot(Dot(step, listOf(0, 4, 7, 12)[i], DOT_SUBSTEPS * 2))
        }
        // A sine, so the shape is the only thing moving.
        osc.setParam(0, 3f)

        // A shape no ADSR could have held: a slow curved rise, a dip, a second rise that
        // parks, and a long curved release. Five segments, so the cap is exercised too.
        env.segments.clear()
        env.segments.addAll(
            listOf(
                EnvSegment(0.25f, 1f, -0.7f),               // slow to start, then rushes up
                EnvSegment(0.15f, 0.35f, 0.6f),             // falls away fast
                EnvSegment(0.30f, 0.75f, -0.4f),            // and swells back
                EnvSegment(0.05f, 0.6f, 0f, sustain = true) // parks here while held
                ,
                EnvSegment(0.8f, 0f, 0.8f),                 // a long release that leaves fast
            ),
        )

        patch.connect(out(seq, 0), into(osc, 0))
        patch.connect(out(seq, 0), into(env, 0))
        patch.connect(out(osc, 0), into(amp, 0))
        patch.connect(out(env, 0), into(amp, 1))
        patch.connect(out(amp, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        patch.connect(out(amp, 0), PortRef(OUT_ID, PortDirection.INPUT, 1))

        val json = patch.toJson()
        // What the app will do with it on launch, done here where a failure is readable.
        val reloaded = patchFromJson(json)
        assertNotNull("the file this writes must be one this build reads", reloaded)
        assertEquals("and must survive the round trip byte for byte", json, reloaded!!.toJson())
        assertEquals(
            "the envelope must arrive with all five segments",
            5,
            reloaded.modules.first { it.type == Types.Env }.segments.size,
        )

        val target = File(System.getProperty("envTestOut") ?: "build/env-tests.json")
        target.parentFile?.mkdirs()
        target.writeText(json)
        println("wrote ${target.absolutePath}")
    }

    /**
     * The same envelope under a *held* note, which is the only way to measure its shape.
     *
     * A Seq's note ends, and when it does the Osc is freed and writes zeros -- the Phase 10
     * finding -- so everything from the sustain onwards is multiplied by silence and cannot
     * be captured at all. A Drone holds its note until something lets go, so the attack, the
     * dip, the swell and the park all play out into a tone that is still there.
     */
    @Test
    fun `write the held-note envelope patch`() {
        val patch = Patch()
        patch.name = "Env held"

        val drone = patch.add(Types.Drone, Offset(60f, 60f))!!
        val osc = patch.add(Types.Osc, Offset(300f, 60f))!!
        val env = patch.add(Types.Env, Offset(300f, 220f))!!
        val amp = patch.add(Types.Amp, Offset(520f, 60f))!!

        // One cell on, so exactly one note is held for as long as the patch runs.
        drone.setStep(0, drone.steps[0].copy(on = true))
        osc.setParam(0, 3f)

        env.segments.clear()
        env.segments.addAll(
            listOf(
                EnvSegment(0.25f, 1f, -0.7f),
                EnvSegment(0.15f, 0.35f, 0.6f),
                EnvSegment(0.30f, 0.75f, -0.4f),
                EnvSegment(0.05f, 0.6f, 0f, sustain = true),
                EnvSegment(0.8f, 0f, 0.8f),
            ),
        )

        patch.connect(out(drone, 0), into(osc, 0))
        patch.connect(out(drone, 0), into(env, 0))
        patch.connect(out(osc, 0), into(amp, 0))
        patch.connect(out(env, 0), into(amp, 1))
        patch.connect(out(amp, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        patch.connect(out(amp, 0), PortRef(OUT_ID, PortDirection.INPUT, 1))

        val json = patch.toJson()
        assertEquals("round trips", json, patchFromJson(json)!!.toJson())
        val target = File("build/env-held.json")
        target.parentFile?.mkdirs()
        target.writeText(json)
        println("wrote ${target.absolutePath}")
    }
}
