package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset

/**
 * A complete small instrument, for the tests that want a real patch to work on: Steps sends
 * notes into a poly subpatch called Voice -- an Osc, an Env and the Amp it opens, stamped out
 * four times by the engine -- whose sum goes through a Filter to both outputs.
 *
 * It was the patch a fresh install opened with until 2026-09-23, when the app started opening
 * on an empty canvas instead: it began with Steps, which the Add menu no longer offers, left
 * the Filter outside the Voice where it could track nothing, and did not fit a phone's screen.
 * As a fixture none of that matters, and fifty tests already know its shape.
 */
internal fun fixturePatch(): Patch =
    Patch().apply {
        val steps = add(Types.Steps, Offset(110f, 40f))!!
        val osc = add(Types.Osc, Offset(275f, 40f))!!
        val env = add(Types.Env, Offset(275f, 180f))!!
        val amp = add(Types.Amp, Offset(440f, 40f))!!
        val filter = add(Types.Filter, Offset(605f, 40f))!!

        fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
        fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)

        // Notes to both the oscillator and the envelope. One source, so collapsing these
        // into the box gives it one note input rather than two.
        connect(out(steps, 0), into(osc, 0))
        connect(out(steps, 0), into(env, 0))
        connect(out(osc, 0), into(amp, 0))
        connect(out(env, 0), into(amp, 1))
        connect(out(amp, 0), into(filter, 0))
        connect(out(filter, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        connect(out(filter, 0), PortRef(OUT_ID, PortDirection.INPUT, 1))

        makeSubpatch(setOf(osc.id, env.id, amp.id), Types.Poly)?.also {
            it.name = "Voice"
            it.position = Offset(275f, 40f)
        }
    }
