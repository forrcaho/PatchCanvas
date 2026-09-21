package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A poly subpatch: monophonic inside, and cloned on the way to the engine.
 *
 * Everything below is about the flattening, which is where this feature lives. The engine
 * learns nothing about polyphony from it -- it is handed a flat graph, as it always has
 * been -- so what has to be right is the set of nodes and cables [Patch.engineGraph]
 * produces, and that is checkable on the desk.
 */
class PolyTest {

    /** Seq -> Osc -> Out, with the Osc collapsed into a poly subpatch. */
    private class Rig(voices: Int = 4) {
        val patch = Patch()
        val seq = patch.add(Types.Seq, Offset(0f, 0f))!!
        val osc = patch.add(Types.Osc, Offset(200f, 0f))!!
        val poly: PatchModule

        init {
            fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
            fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)
            check(patch.connect(out(seq, 0), into(osc, 0)))
            check(patch.connect(out(osc, 0), PortRef(OUT_ID, PortDirection.INPUT, 0)))
            poly = patch.makeSubpatch(setOf(osc.id), Types.Poly)!!
            poly.setParam(0, voices.toFloat())
        }

        val graph: EngineGraph get() = patch.engineGraph()
        fun ids(): Set<Long> = graph.nodes.map { it.id }.toSet()
    }

    @Test
    fun `a poly subpatch is its contents once per voice, with a node at each edge`() {
        val rig = Rig(voices = 3)
        val graph = rig.graph

        val copies = graph.nodes.filter { it.module === rig.osc }
        assertEquals("three voices, three oscillators", 3, copies.size)
        assertEquals(
            "the first instance keeps the module's own id, so telemetry needs no translation",
            rig.osc.id,
            copies.first().id,
        )
        assertEquals(3, copies.map { it.id }.distinct().size)

        assertEquals(
            "the note edge is the box's own id",
            listOf(rig.poly.id),
            graph.nodes.filter { it.type == NodeType.PolyIn }.map { it.id },
        )
        assertEquals(
            "one summing node, for its one signal output",
            listOf(sumId(rig.poly.id, 0)),
            graph.nodes.filter { it.type == NodeType.PolySum }.map { it.id },
        )
    }

    @Test
    fun `notes reach the edge once and leave it one instance at a time`() {
        val rig = Rig(voices = 3)
        val graph = rig.graph
        val edge = PortRef(rig.poly.id, PortDirection.INPUT, 0)

        assertEquals(
            "the sequencer patches to the edge, not to the copies",
            setOf(Connection(PortRef(rig.seq.id, PortDirection.OUTPUT, 0), edge)),
            graph.cables.filter { it.to == edge }.toSet(),
        )
        assertTrue("and that edge merges its sources", edge in graph.noteInputs)

        (0 until 3).forEach { k ->
            assertTrue(
                "instance $k takes its share from output $k",
                Connection(
                    PortRef(rig.poly.id, PortDirection.OUTPUT, k),
                    PortRef(cloneId(rig.osc.id, k), PortDirection.INPUT, 0),
                ) in graph.cables,
            )
        }
        assertTrue(
            "and nothing reaches an instance the knob does not allow",
            graph.cables.none { it.from == PortRef(rig.poly.id, PortDirection.OUTPUT, 3) },
        )
    }

    @Test
    fun `every instance's sound is summed back into one cable`() {
        val rig = Rig(voices = 3)
        val graph = rig.graph
        val sum = sumId(rig.poly.id, 0)

        (0 until 3).forEach { k ->
            assertTrue(
                "instance $k lands on input $k of the sum",
                Connection(
                    PortRef(cloneId(rig.osc.id, k), PortDirection.OUTPUT, 0),
                    PortRef(sum, PortDirection.INPUT, k),
                ) in graph.cables,
            )
        }
        assertTrue(
            "and the sum is what Out hears",
            Connection(
                PortRef(sum, PortDirection.OUTPUT, 0),
                PortRef(OUT_ID, PortDirection.INPUT, 0),
            ) in graph.cables,
        )
        assertTrue(
            "no copy reaches Out directly",
            graph.cables.none { it.to.moduleId == OUT_ID && it.from.moduleId != sum },
        )
    }

    /**
     * Only notes are shared out. An audio or modulation cable into a poly subpatch is the
     * same signal for every instance, so it is patched to all of them -- the instances are
     * copies of one voice, not separate patches.
     */
    @Test
    fun `everything that is not a note is broadcast to every instance`() {
        val rig = Rig(voices = 3)
        val lfo = rig.patch.add(Types.Lfo, Offset(0f, 300f))!!
        rig.patch.enterScope(rig.poly.id)
        val filter = rig.patch.add(Types.Filter, Offset(0f, 0f))!!
        rig.patch.expose(filter, 0, ModRange(400f, 2000f))
        rig.patch.scope = TOP
        // A port on the box, fed from outside and landing on the cutoff jack inside it.
        assertTrue(
            rig.patch.addSubpatchPort(rig.poly.id, PortRef(filter.id, PortDirection.MOD, 0)),
        )
        val port = rig.poly.ports(PortDirection.INPUT).lastIndex
        assertTrue(
            rig.patch.connect(
                PortRef(lfo.id, PortDirection.OUTPUT, 0),
                PortRef(rig.poly.id, PortDirection.INPUT, port),
            ),
        )

        val graph = rig.graph
        (0 until 3).forEach { k ->
            assertTrue(
                "instance $k gets the same modulator",
                Connection(
                    PortRef(lfo.id, PortDirection.OUTPUT, 0),
                    PortRef(cloneId(filter.id, k), PortDirection.MOD, 0),
                ) in graph.cables,
            )
        }
        assertEquals("one LFO, not one per instance", 1, graph.nodes.count { it.module === lfo })
    }

    @Test
    fun `turning the voices knob adds and removes copies`() {
        val rig = Rig(voices = 2)
        assertEquals(2, rig.graph.nodes.count { it.module === rig.osc })
        rig.poly.setParam(0, 6f)
        assertEquals(6, rig.graph.nodes.count { it.module === rig.osc })
        rig.poly.setParam(0, 1f)
        assertEquals(1, rig.graph.nodes.count { it.module === rig.osc })
        assertEquals(
            "one voice is still the instance that keeps the module's id",
            setOf(rig.osc.id),
            rig.graph.nodes.filter { it.module === rig.osc }.map { it.id }.toSet(),
        )
    }

    /** Out of range either way, since the knob is what a file and an undo carry. */
    @Test
    fun `the voice count is clamped to what the engine has ports for`() {
        val rig = Rig()
        rig.poly.setParam(0, 99f)
        assertEquals(MAX_PORTS, rig.patch.voicesOf(rig.poly))
        rig.poly.setParam(0, 0f)
        assertEquals(1, rig.patch.voicesOf(rig.poly))
    }

    /** A plain subpatch inside a poly one is still nothing to the engine. */
    @Test
    fun `a plain subpatch inside a poly one still flattens away`() {
        val rig = Rig(voices = 2)
        rig.patch.enterScope(rig.poly.id)
        val inner = rig.patch.makeSubpatch(setOf(rig.osc.id))!!
        rig.patch.scope = TOP

        val graph = rig.graph
        val structural = graph.nodes.filter { it.module?.type?.structural == true }
        assertEquals(
            "the plain subpatch and its rails are nothing to the engine; only the poly edge is",
            listOf(NodeType.PolyIn),
            structural.map { it.type },
        )
        assertEquals("still two oscillators", 2, graph.nodes.count { it.module === rig.osc })
        (0 until 2).forEach { k ->
            assertTrue(
                "and instance $k is still wired end to end",
                Connection(
                    PortRef(rig.poly.id, PortDirection.OUTPUT, k),
                    PortRef(cloneId(rig.osc.id, k), PortDirection.INPUT, 0),
                ) in graph.cables,
            )
        }
    }

    /**
     * Instances would multiply, and the ids that stamp the copies out are one level deep on
     * purpose. Refused from every direction there is.
     */
    @Test
    fun `a poly subpatch may not contain another`() {
        val rig = Rig()
        rig.patch.enterScope(rig.poly.id)
        assertNull("not added inside one", rig.patch.add(Types.Poly, Offset.Zero))
        assertNull("nor made from a selection inside one", rig.patch.makeSubpatch(setOf(rig.osc.id), Types.Poly))
        rig.patch.scope = TOP

        assertNull(
            "nor wrapped around one",
            rig.patch.makeSubpatch(setOf(rig.poly.id), Types.Poly),
        )
        assertTrue("a plain subpatch around one is fine", rig.patch.makeSubpatch(setOf(rig.poly.id)) != null)
    }

    @Test
    fun `a poly subpatch survives the file and an undo, knob and all`() {
        val rig = Rig(voices = 6)
        val json = rig.patch.toJson()
        val back = patchFromJson(json)
        assertNotNull(back)
        val poly = back!!.modules.first { it.type == Types.Poly }
        assertEquals(6, back.voicesOf(poly))
        assertEquals("the same flat graph comes back", rig.graph.cables, back.engineGraph().cables)
        assertEquals("byte for byte, so an undo is not a new edit", json, back.toJson())
    }

    /**
     * A stack means several of this sound at once, and exactly two things do.
     *
     * Pinned as a set rather than checked one by one, so adding a third has to be a
     * decision. Every synth is monophonic, so the marker's job is to say where you do *not*
     * need a Poly around it: a poly subpatch, and SF, whose voices are TinySoundFont's and
     * cannot be capped to one because a single note can layer several of them.
     */
    @Test
    fun `only a poly subpatch and SF are drawn as stacks`() {
        val stacked = (Types.palette + Types.boxes.values + listOf(Types.Steps, Types.Out, Types.In))
            .filter { it.stacked }
            .map { it.name }
            .toSet()
        assertEquals(setOf("Poly", "SF"), stacked)
        assertFalse("a plain subpatch is one of one", Types.Subpatch.stacked)
        assertFalse("and a monophonic synth is not marked", Types.Osc.stacked)
    }

    /** The rails say what they are: one instance of several, not the box's own edge. */
    @Test
    fun `the rails inside a poly subpatch are instance rails`() {
        assertEquals("Instance in", Types.railName(Types.Poly, Types.SubpatchIn))
        assertEquals("Instance out", Types.railName(Types.Poly, Types.SubpatchOut))
        assertEquals("Subpatch in", Types.railName(Types.Subpatch, Types.SubpatchIn))
        assertEquals("Subpatch out", Types.railName(Types.Subpatch, Types.SubpatchOut))
    }

    /**
     * The demo is the design's own first example, so it is pinned here: what a fresh install
     * opens with is a poly subpatch, and opening it shows a voice anyone can read.
     */
    @Test
    fun `the patch a fresh install opens with is a poly subpatch`() {
        val patch = demoPatch()
        val voice = patch.modules.single { it.type == Types.Poly }
        assertEquals("Voice", voice.name)
        assertEquals(
            "an oscillator, an envelope and the amp it opens",
            setOf(Types.Osc, Types.Env, Types.Amp),
            patch.modules.filter { it.parent == voice.id && !it.isPinned }.map { it.type }.toSet(),
        )
        assertEquals(
            "one note input, since both modules inside take notes from the same sequencer",
            1,
            voice.ports(PortDirection.INPUT).size,
        )
        assertEquals("and one thing to say", 1, voice.ports(PortDirection.OUTPUT).size)

        val graph = patch.engineGraph()
        assertEquals(
            "four copies of each, by default",
            4,
            graph.nodes.count { it.module?.type == Types.Osc },
        )
        assertTrue("with a note edge", graph.nodes.any { it.type == NodeType.PolyIn })
        assertTrue("and a summing one", graph.nodes.any { it.type == NodeType.PolySum })
    }

    /** Its voices knob is its own, and the only knob either box has. */
    @Test
    fun `a poly subpatch's panel starts with its voices knob`() {
        val rig = Rig()
        val rows = rig.patch.panelRows(rig.poly)
        assertEquals(listOf(ParamRow(rig.poly, 0)), rows)
        assertEquals("voices", rows.first().param.name)
        assertFalse("and it takes no modulation jack", rig.poly.canExpose(0))

        rig.patch.enterScope(rig.poly.id)
        assertTrue(rig.patch.promote(rig.osc, 0))
        assertEquals(
            "a promoted knob joins it below",
            listOf(ParamRow(rig.poly, 0), ParamRow(rig.osc, 0)),
            rig.patch.panelRows(rig.poly),
        )
    }
}
