package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset

/**
 * A patch with every kind of cable a group has to carry across its edge: two note sources
 * merging into one input, audio, a modulator landing on an exposed parameter, and an output
 * fanning out to both rails and a mix. Grouping {osc, filter} out of it cuts all of them.
 */
internal class GroupFixture {
    val patch = Patch()
    val steps = patch.add(Types.Steps, Offset(0f, 0f))!!
    val drone = patch.add(Types.Drone, Offset(0f, 200f))!!
    val osc = patch.add(Types.Osc, Offset(200f, 0f))!!
    val filter = patch.add(Types.Filter, Offset(400f, 0f))!!
    val lfo = patch.add(Types.Lfo, Offset(200f, 200f))!!
    val mix = patch.add(Types.Mix, Offset(600f, 0f))!!
    val env = patch.add(Types.Env, Offset(400f, 200f))!!

    init {
        fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
        fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)
        patch.expose(filter, 0, ModRange(200f, 4000f))
        patch.expose(mix, 1, ModRange(0f, 1f))
        check(patch.connect(out(steps, 0), into(osc, 0)))
        check(patch.connect(out(drone, 0), into(osc, 0)))
        check(patch.connect(out(osc, 0), into(filter, 0)))
        check(patch.connect(out(lfo, 0), PortRef(filter.id, PortDirection.MOD, 0)))
        check(patch.connect(out(filter, 0), PortRef(OUT_ID, PortDirection.INPUT, 0)))
        check(patch.connect(out(filter, 0), PortRef(OUT_ID, PortDirection.INPUT, 1)))
        check(patch.connect(out(filter, 0), into(mix, 0)))
        check(patch.connect(out(steps, 0), into(env, 0)))
        check(patch.connect(out(env, 0), PortRef(mix.id, PortDirection.MOD, 1)))
    }
}
