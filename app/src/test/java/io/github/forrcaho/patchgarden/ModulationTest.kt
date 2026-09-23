package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun out(m: PatchModule, i: Int = 0) = PortRef(m.id, PortDirection.OUTPUT, i)
private fun mod(m: PatchModule, param: Int) = PortRef(m.id, PortDirection.MOD, param)

private fun filterAndLfo(): Triple<Patch, PatchModule, PatchModule> {
    val patch = Patch()
    val filter = patch.add(Types.Filter, Offset(0f, 0f))!!
    val lfo = patch.add(Types.Lfo, Offset(0f, 200f))!!
    return Triple(patch, filter, lfo)
}

/**
 * A parameter with a jack: exposed from inside its module, patched from outside it.
 *
 * What a finger can and cannot make. What the engine does with a range is graph_test's.
 */
class ModulationTest {

    @Test
    fun `a parameter has no jack until it is exposed`() {
        val (patch, filter, lfo) = filterAndLfo()
        assertNull(patch.port(mod(filter, 0)))
        assertFalse(patch.connect(out(lfo), mod(filter, 0)))

        assertTrue(patch.expose(filter, 0, ModRange(400f, 2000f)))
        assertEquals("cut", patch.port(mod(filter, 0))?.name)
        assertTrue(patch.connect(out(lfo), mod(filter, 0)))
    }

    @Test
    fun `a modulator patches either way round, and a second replaces the first`() {
        val (patch, filter, lfo) = filterAndLfo()
        val other = patch.add(Types.Lfo, Offset.Zero)!!
        patch.expose(filter, 0, ModRange(400f, 2000f))

        assertTrue(patch.connect(mod(filter, 0), out(lfo)))
        assertEquals(Connection(out(lfo), mod(filter, 0)), patch.connections.single())

        assertTrue(patch.connect(out(other), mod(filter, 0)))
        assertEquals(Connection(out(other), mod(filter, 0)), patch.connections.single())
    }

    @Test
    fun `only a control output can modulate`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        val osc = patch.add(Types.Osc, Offset.Zero)!!
        val steps = patch.add(Types.Steps, Offset.Zero)!!
        val env = patch.add(Types.Env, Offset.Zero)!!
        patch.expose(filter, 0, ModRange(400f, 2000f))

        assertFalse("audio", patch.connect(out(osc), mod(filter, 0)))
        assertFalse("a gate", patch.connect(out(steps, 1), mod(filter, 0)))
        assertFalse("notes", patch.connect(out(steps, 2), mod(filter, 0)))
        assertTrue("an envelope", patch.connect(out(env), mod(filter, 0)))
    }

    @Test
    fun `an input cannot patch to a parameter's jack`() {
        val (patch, filter, _) = filterAndLfo()
        val osc = patch.add(Types.Osc, Offset.Zero)!!
        patch.expose(filter, 0, ModRange(400f, 2000f))
        assertFalse(patch.connect(PortRef(osc.id, PortDirection.INPUT, 0), mod(filter, 0)))
        assertTrue(patch.connections.isEmpty())
    }

    @Test
    fun `un-exposing takes the cable with it`() {
        val (patch, filter, lfo) = filterAndLfo()
        patch.expose(filter, 0, ModRange(400f, 2000f))
        patch.connect(out(lfo), mod(filter, 0))

        patch.unexpose(filter, 0)
        assertTrue(filter.modRanges.isEmpty())
        assertTrue(patch.connections.isEmpty())
    }

    @Test
    fun `deleting the modulator takes its cable with it`() {
        val (patch, filter, lfo) = filterAndLfo()
        patch.expose(filter, 0, ModRange(400f, 2000f))
        patch.connect(out(lfo), mod(filter, 0))
        patch.remove(lfo)
        assertTrue(patch.connections.isEmpty())
        assertEquals("the range belongs to the knob, and stays", 1, filter.modRanges.size)
    }

    @Test
    fun `header parameters and rails cannot be exposed`() {
        val patch = Patch()
        val steps = patch.add(Types.Steps, Offset.Zero)!!
        assertFalse("the interval", patch.expose(steps, Types.Steps.intervalParam, ModRange(0f, 1f)))
        assertFalse("a rail", patch.expose(patch.module(OUT_ID)!!, 0, ModRange(0f, 1f)))
        assertTrue(steps.modRanges.isEmpty())
        assertTrue(patch.module(OUT_ID)!!.modRanges.isEmpty())
    }

    @Test
    fun `exposing a parameter never moves a jack already on the module`() {
        val patch = Patch()
        val voice = patch.add(Types.Osc, Offset(40f, 60f))!!
        fun sides() = listOf(PortDirection.INPUT, PortDirection.OUTPUT).flatMap { dir ->
            val ports = voice.ports(dir)
            ports.indices.map { portIn(voice.bounds, 1f, dir, it, ports.size, voice.portsBody) }
        }
        fun jack(index: Int) = modPortIn(voice.bounds, 1f, voice.type, index, voice.portsBody)
        val before = sides()

        // The release is the fifth row, so its slot is on the band's second row: the band
        // appears two rows deep with a gap above it.
        patch.expose(voice, 4, ModRange(0.1f, 1f))
        val release = jack(4)
        assertEquals(before, sides())

        // The attack comes before it in the rows and must not push it along.
        patch.expose(voice, 1, ModRange(0.01f, 0.5f))
        assertEquals(before, sides())
        assertEquals(release, jack(4))

        // Taking the deeper one away shrinks the band without moving the attack.
        val attack = jack(1)
        patch.unexpose(voice, 4)
        assertEquals(attack, jack(1))
        assertEquals(before, sides())
    }

    @Test
    fun `the band grows downward, never wider`() {
        val patch = Patch()
        // Pluck, because its four knobs reach a second row of the band; Osc has one knob
        // now that its envelope has gone to Env.
        val voice = patch.add(Types.Pluck, Offset(40f, 60f))!!
        val closed = voice.bounds
        patch.expose(voice, 3, ModRange(0.1f, 1f))

        // Wider would move every output jack, since they sit on the right edge.
        assertEquals(closed.left, voice.bounds.left, 0f)
        assertEquals(closed.top, voice.bounds.top, 0f)
        assertEquals(closed.width, voice.bounds.width, 0f)
        assertEquals(closed.height + 2 * PatchModule.MOD_ROW, voice.bounds.height, 0.001f)
    }

    @Test
    fun `the deepest row of the band lies on the module's bottom edge`() {
        val patch = Patch()
        val voice = patch.add(Types.Pluck, Offset(40f, 60f))!!
        patch.expose(voice, 3, ModRange(0f, 1f)) // R: the second row
        val at = modPortIn(voice.bounds, 1f, voice.type, 3, voice.portsBody)
        assertEquals(voice.bounds.bottom, at.y, 0.001f)
    }

    @Test
    fun `modulation ports are never closer together than side jacks`() {
        for (type in Types.palette) {
            val box = Rect(Offset.Zero, Size(PatchModule.WIDTH, 400f))
            val jacks = type.rowParams.map {
                modPortIn(box, 1f, type, it, PatchModule.portsBodyFor(type))
            }
            for (i in jacks.indices) for (j in i + 1 until jacks.size) {
                assertTrue(
                    "${type.name}: slots $i and $j",
                    (jacks[i] - jacks[j]).getDistance() >= PatchModule.PORT_PITCH,
                )
            }
            jacks.forEach {
                assertTrue("${type.name} inside its width", it.x >= 0f && it.x <= PatchModule.WIDTH)
            }
        }
    }

    @Test
    fun `every label fits its slot`() {
        (Types.palette + listOf(Types.Out, Types.In)).forEach { type ->
            type.params.forEach {
                assertTrue("${type.name} ${it.name} is '${it.short}'", it.short.length <= 4)
            }
        }
        assertEquals("cut", Types.Filter.params[0].short)
        assertEquals("lvl", Types.Out.params[0].short)
    }

    @Test
    fun `a new range is heard at once, and inside the knob`() {
        for (type in Types.palette) for (i in type.rowParams) {
            val p = type.params[i]
            val range = initialModRange(p, p.default)
            val name = "${type.name} ${p.name}"
            assertTrue("$name has some width", range.low != range.high)
            val lowest = minOf(p.min, p.max)
            val highest = maxOf(p.min, p.max)
            assertTrue("$name low inside", range.low in lowest..highest)
            assertTrue("$name high inside", range.high in lowest..highest)
        }
    }
}

/** An exposed parameter is part of the document: it saves, loads, and undoes. */
class ModulationJsonTest {

    private fun patched(): Triple<Patch, PatchModule, PatchModule> {
        val (patch, filter, lfo) = filterAndLfo()
        patch.expose(filter, 0, ModRange(400f, 2000f))
        patch.expose(filter, 1, ModRange(0.8f, 0.1f)) // inverted, which is allowed
        patch.connect(out(lfo), mod(filter, 0))
        return Triple(patch, filter, lfo)
    }

    @Test
    fun `ranges and the cables on them survive a reload`() {
        val (patch, filter, _) = patched()
        val restored = patchFromJson(patch.toJson())!!

        assertEquals(filter.modRanges, restored.module(filter.id)!!.modRanges)
        assertEquals(patch.connections.toSet(), restored.connections.toSet())
        assertEquals(patch.toJson(), restored.toJson())
    }

    @Test
    fun `a modulation cable is saved against the parameter's name`() {
        val (patch, _, _) = patched()
        val cable = JSONObject(patch.toJson()).getJSONArray("connections").getJSONObject(0)
        assertEquals("cutoff", cable.getString("toParam"))
        assertFalse(cable.has("toPort"))
    }

    @Test
    fun `a cable to a parameter that is not exposed is skipped`() {
        val (patch, filter, _) = patched()
        val root = JSONObject(patch.toJson())
        val modules = root.getJSONArray("modules")
        for (i in 0 until modules.length()) {
            val m = modules.getJSONObject(i)
            if (m.getLong("id") == filter.id) m.put("mod", JSONObject())
        }
        val restored = patchFromJson(root.toString())
        assertNotNull(restored)
        assertTrue(restored!!.connections.isEmpty())
    }

    @Test
    fun `a range past its knob is clamped, and nonsense is dropped`() {
        val (patch, filter, _) = patched()
        val root = JSONObject(patch.toJson())
        val modules = root.getJSONArray("modules")
        for (i in 0 until modules.length()) {
            val m = modules.getJSONObject(i)
            if (m.getLong("id") != filter.id) continue
            m.put(
                "mod",
                JSONObject()
                    .put("cutoff", JSONArray().put(1.0).put(99999.0))
                    .put("res", JSONArray().put("loud").put(0.5)),
            )
        }
        val restored = patchFromJson(root.toString())!!.module(filter.id)!!
        assertEquals(ModRange(20f, 18000f), restored.modRanges[0])
        assertNull(restored.modRanges[1])
    }

    @Test
    fun `a file with no mod section loads with nothing exposed`() {
        // Written by hand rather than aged into an older version: a format 3 file is
        // refused outright now, and what this is really about is that an absent "mod"
        // reads as "nothing exposed" rather than as a parse failure.
        val patch = Patch().apply { add(Types.Filter, Offset.Zero) }
        val root = JSONObject(patch.toJson())
        root.getJSONArray("modules").getJSONObject(0).remove("mod")
        val restored = patchFromJson(root.toString())
        assertNotNull(restored)
        assertTrue(restored!!.free.single().modRanges.isEmpty())
    }

    @Test
    fun `undo puts ranges and their cables back exactly`() {
        val snapshot = patched().first.toJson()
        val live = Patch()
        live.replaceWith(patchFromJson(snapshot)!!)
        assertEquals(snapshot, live.toJson())
    }

    @Test
    fun `an undone range flags its module`() {
        val (patch, filter, _) = patched()
        val live = patchFromJson(patch.toJson())!!
        patch.expose(filter, 0, ModRange(100f, 200f))
        val changed = live.replaceWith(patch)
        assertTrue(filter.id in changed)
    }
}
