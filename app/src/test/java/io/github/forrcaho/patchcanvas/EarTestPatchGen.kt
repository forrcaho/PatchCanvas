package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Writes the Phase 10 listening patch, built through the model so the file is one the app
 * would have written itself.
 *
 * Throwaway: this is a generator, not a test of anything. It is a test only because the
 * model lives in the app module and this is the cheapest way to run against it.
 */
class EarTestPatchGen {

    private fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
    private fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)
    private val outL = PortRef(OUT_ID, PortDirection.INPUT, 0)
    private val outR = PortRef(OUT_ID, PortDirection.INPUT, 1)

    /** Collapses what was built, names it, parks it, and unplugs it from the master out. */
    private fun Patch.box(ids: Set<Long>, name: String, at: Offset): PatchModule {
        val subpatch = makeSubpatch(ids)!!
        subpatch.name = name
        subpatch.position = at
        disconnect(out(subpatch, 0))
        return subpatch
    }

    @Test
    fun `write the listening patch`() {
        val patch = Patch()
        patch.name = "Ear tests"
        patch.tempo = 120f

        // ---- 1. the 5ms gate ramp, with nothing else shaping the note.
        //
        // A sine straight out through a fixed gain, so every edge you hear is GateRamp and
        // nothing else. Short notes with a gap, low then high, since 5ms is a larger share
        // of a cycle down there.
        val seq1 = patch.add(Types.Seq, Offset(70f, 40f))!!
        val osc1 = patch.add(Types.Osc, Offset(235f, 40f))!!
        val amp1 = patch.add(Types.Amp, Offset(400f, 40f))!!
        seq1.setParam(0, 8f)   // len
        seq1.setParam(2, 3f)   // eighths
        for (step in 0 until 8) {
            // Half a step long: note, gap, note, gap.
            seq1.addDot(Dot(step, if (step % 2 == 0) -12 else 0, 2))
        }
        osc1.setParam(0, 3f)   // sine
        amp1.setParam(0, 0.4f) // clear of the limiter, so the edge is the only thing on trial
        check(patch.connect(out(seq1, 0), into(osc1, 0)))
        check(patch.connect(out(osc1, 0), into(amp1, 0)))
        check(patch.connect(out(amp1, 0), outL))
        val test1 = patch.box(setOf(seq1.id, osc1.id, amp1.id), "1 gate ramp", Offset(70f, 90f))

        // ---- 2. four voices summing, against Out's limiter.
        //
        // A four-note chord into a four-voice Poly, saws at full velocity: the loudest
        // honest thing this patch can ask the master stage for.
        val seq2 = patch.add(Types.Seq, Offset(70f, 40f))!!
        val chord2 = patch.add(Types.Chord, Offset(235f, 40f))!!
        val osc2 = patch.add(Types.Osc, Offset(400f, 40f))!!
        val env2 = patch.add(Types.Env, Offset(400f, 180f))!!
        val amp2 = patch.add(Types.Amp, Offset(565f, 40f))!!
        seq2.setParam(0, 16f)
        seq2.setParam(2, 3f)
        listOf(0, 5, 3, -2).forEachIndexed { i, degree ->
            // Three and a half steps, so a chord rings and then clears before the next.
            seq2.addDot(Dot(i * 4, degree, 14))
        }
        chord2.setParam(0, 4f)
        chord2.setParam(1, 7f)
        chord2.setParam(2, 11f) // a fourth note, so all four instances are asked for
        osc2.setParam(0, 0f)    // saw
        env2.setParam(2, 0.7f)  // S
        env2.setParam(3, 0.4f)  // R
        check(patch.connect(out(seq2, 0), into(chord2, 0)))
        check(patch.connect(out(chord2, 0), into(osc2, 0)))
        check(patch.connect(out(chord2, 0), into(env2, 0)))
        check(patch.connect(out(osc2, 0), into(amp2, 0)))
        check(patch.connect(out(env2, 0), into(amp2, 1)))
        check(patch.connect(out(amp2, 0), outL))
        val poly2 = patch.makeSubpatch(setOf(osc2.id, env2.id, amp2.id), Types.Poly)!!
        poly2.name = "Voice"
        poly2.position = Offset(400f, 40f)
        poly2.setParam(0, 4f)
        val test2 = patch.box(setOf(seq2.id, chord2.id, poly2.id), "2 four voices", Offset(330f, 90f))

        // ---- 3. stealing, with an Env rather than a built-in envelope holding the note.
        //
        // Three notes an event into two instances, each chord held past the start of the
        // next, so what gets taken is always a note still sounding.
        //
        // It was built the other way first -- short notes and a three-second release, so a
        // steal would land on a tail -- and the capture showed why that cannot work: a
        // synth closes its own 5ms gate at note off and MonoSynth frees the voice, so the
        // Amp has nothing left to shape and an Env's R is silent on an Osc. Overlapping
        // held notes are the only way to steal something audible.
        val seq3 = patch.add(Types.Seq, Offset(70f, 40f))!!
        val chord3 = patch.add(Types.Chord, Offset(235f, 40f))!!
        val osc3 = patch.add(Types.Osc, Offset(400f, 40f))!!
        val env3 = patch.add(Types.Env, Offset(400f, 180f))!!
        val amp3 = patch.add(Types.Amp, Offset(565f, 40f))!!
        seq3.setParam(0, 8f)
        seq3.setParam(2, 3f)
        listOf(0, 7, 3, 10).forEachIndexed { i, degree ->
            // Three steps long, a new one every two: each chord overlaps the next.
            seq3.addDot(Dot(i * 2, degree, 12))
        }
        chord3.setParam(0, 4f)
        chord3.setParam(1, 7f)
        chord3.setParam(2, 0f) // three notes into two voices
        osc3.setParam(0, 2f)   // triangle: a steal is easier to hear under fewer harmonics
        env3.setParam(0, 0.01f)
        env3.setParam(1, 0.3f)
        env3.setParam(2, 0.8f)
        env3.setParam(3, 0.2f) // R, which on an Osc is never heard; see above
        amp3.setParam(0, 0.7f)
        check(patch.connect(out(seq3, 0), into(chord3, 0)))
        check(patch.connect(out(chord3, 0), into(osc3, 0)))
        check(patch.connect(out(chord3, 0), into(env3, 0)))
        check(patch.connect(out(osc3, 0), into(amp3, 0)))
        check(patch.connect(out(env3, 0), into(amp3, 1)))
        check(patch.connect(out(amp3, 0), outL))
        val poly3 = patch.makeSubpatch(setOf(osc3.id, env3.id, amp3.id), Types.Poly)!!
        poly3.name = "Voice"
        poly3.position = Offset(400f, 40f)
        poly3.setParam(0, 2f)
        val test3 = patch.box(setOf(seq3.id, chord3.id, poly3.id), "3 stealing", Offset(70f, 250f))

        // ---- 4. a legato line on a monophonic synth, which steps.
        //
        // Every note overlaps the next by half a step, so the Env never closes and the only
        // thing that moves is the pitch -- with no glide between the two.
        val seq4 = patch.add(Types.Seq, Offset(70f, 40f))!!
        val osc4 = patch.add(Types.Osc, Offset(235f, 40f))!!
        val env4 = patch.add(Types.Env, Offset(235f, 180f))!!
        val amp4 = patch.add(Types.Amp, Offset(400f, 40f))!!
        seq4.setParam(0, 8f)
        seq4.setParam(2, 3f)
        listOf(0, 2, 4, 7, 9, 7, 4, 2).forEachIndexed { step, degree ->
            // Six quarter steps: a step and a half, so each note is still held when the
            // next one starts.
            seq4.addDot(Dot(step, degree, 6))
        }
        osc4.setParam(0, 2f)   // triangle
        env4.setParam(0, 0.02f)
        env4.setParam(1, 0.2f)
        env4.setParam(2, 0.85f)
        env4.setParam(3, 0.5f)
        amp4.setParam(0, 0.8f)
        check(patch.connect(out(seq4, 0), into(osc4, 0)))
        check(patch.connect(out(seq4, 0), into(env4, 0)))
        check(patch.connect(out(osc4, 0), into(amp4, 0)))
        check(patch.connect(out(env4, 0), into(amp4, 1)))
        check(patch.connect(out(amp4, 0), outL))
        val test4 = patch.box(setOf(seq4.id, osc4.id, env4.id, amp4.id), "4 legato step", Offset(330f, 250f))

        // The first test is the one plugged in; the other three are a tap away.
        check(patch.connect(out(test1, 0), outL))
        check(patch.connect(out(test1, 0), outR))

        listOf(test1, test2, test3, test4).forEach {
            assertEquals("every box offers exactly one output", 1, it.ports(PortDirection.OUTPUT).size)
        }

        val json = patch.toJson()
        // What the app will do with it on launch, done here where a failure is readable.
        val reloaded = patchFromJson(json)
        assertNotNull("the file this writes must be one this build reads", reloaded)
        assertEquals("and must survive the round trip byte for byte", json, reloaded!!.toJson())

        // Into the module's own build dir unless told otherwise, because the default has to
        // be somewhere that exists: this pointed at a scratch directory that had been
        // deleted by the next session, so the generator threw the moment it actually ran.
        val target = File(System.getProperty("earTestOut") ?: "build/ear-tests.json")
        target.parentFile?.mkdirs()
        target.writeText(json)
        println("wrote ${target.absolutePath}")
    }
}
