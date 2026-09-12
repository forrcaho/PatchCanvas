package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private sealed interface Cmd {
    data class Add(val id: Long, val type: NodeType) : Cmd
    data class Remove(val id: Long) : Cmd
    data class Connect(val src: Long, val srcPort: Int, val dst: Long, val dstPort: Int) : Cmd
    data class Disconnect(val dst: Long, val dstPort: Int) : Cmd
    data class SetParam(val id: Long, val index: Int, val value: Float) : Cmd
}

private class Recorder : GraphCommands {
    val log = mutableListOf<Cmd>()
    var collected = 0

    override fun addNode(id: Long, type: NodeType) { log += Cmd.Add(id, type) }
    override fun removeNode(id: Long) { log += Cmd.Remove(id) }
    override fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        log += Cmd.Connect(srcId, srcPort, dstId, dstPort)
    }
    override fun disconnect(dstId: Long, dstPort: Int) { log += Cmd.Disconnect(dstId, dstPort) }
    override fun setParam(id: Long, index: Int, value: Float) {
        log += Cmd.SetParam(id, index, value)
    }
    override fun collectGarbage() { collected++ }

    fun clear() { log.clear() }
}

/**
 * The diff is what keeps two representations of the same patch agreeing, and its
 * ordering is the part that would be silently wrong: the graph must never be asked to
 * reference a node that is not there yet, or to keep one that is going away.
 */
class GraphSyncTest {

    private val rec = Recorder()
    private val sync = GraphSync(rec)

    @Test
    fun `first sync sends every node and cable`() {
        val patch = demoPatch()
        sync.sync(patch)

        val adds = rec.log.filterIsInstance<Cmd.Add>()
        val connects = rec.log.filterIsInstance<Cmd.Connect>()
        assertEquals(patch.modules.size, adds.size)
        assertEquals(patch.connections.size, connects.size)
        // the rails are ordinary nodes to the engine
        assertTrue(adds.any { it.id == OUT_ID && it.type == NodeType.Out })
        assertTrue(adds.any { it.id == IN_ID && it.type == NodeType.In })
    }

    @Test
    fun `every add precedes every connect`() {
        sync.sync(demoPatch())
        val lastAdd = rec.log.indexOfLast { it is Cmd.Add }
        val firstConnect = rec.log.indexOfFirst { it is Cmd.Connect }
        assertTrue("add at $lastAdd, connect at $firstConnect", lastAdd < firstConnect)
    }

    @Test
    fun `an unchanged patch sends nothing`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        sync.sync(patch)
        assertTrue(rec.log.isEmpty())
    }

    @Test
    fun `moving a module is not a graph change`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        patch.free.first().position = Offset(999f, 999f)
        sync.sync(patch)
        assertTrue("position is not the audio graph's business", rec.log.isEmpty())
    }

    @Test
    fun `adding a module sends one add`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        val added = patch.add(Types.Osc, Offset.Zero)!!
        sync.sync(patch)
        // Its knobs follow, so assert the add rather than that nothing else happened.
        assertEquals(listOf(Cmd.Add(added.id, NodeType.Osc)), rec.log.filterIsInstance<Cmd.Add>())
    }

    @Test
    fun `removing a module drops its cables before the node`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        val filter = patch.free.first { it.type == Types.Filter }
        patch.remove(filter)
        sync.sync(patch)

        val lastDisconnect = rec.log.indexOfLast { it is Cmd.Disconnect }
        val remove = rec.log.indexOfFirst { it == Cmd.Remove(filter.id) }
        assertTrue("expected a disconnect", lastDisconnect >= 0)
        assertTrue("cables must drop first", lastDisconnect < remove)
    }

    @Test
    fun `re-patching an occupied input sends no disconnect`() {
        val patch = demoPatch()
        val a = patch.add(Types.Osc, Offset.Zero)!!
        patch.connect(PortRef(a.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        sync.sync(patch)
        rec.clear()

        val b = patch.add(Types.Osc, Offset.Zero)!!
        patch.connect(PortRef(b.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        sync.sync(patch)

        // Inputs are single-source, so the connect already replaces. Sending a
        // disconnect as well makes the engine fade the old source out to silence and the
        // new one in from silence, and since both arrive in the same drain the second
        // fade starts from silence rather than from what was playing -- which steps.
        // Letting the connect stand alone is what makes this an actual crossfade.
        assertTrue(
            "a replacement must not disconnect first",
            rec.log.none { it == Cmd.Disconnect(OUT_ID, 0) },
        )
        assertTrue(rec.log.contains(Cmd.Connect(b.id, 0, OUT_ID, 0)))
    }

    @Test
    fun `a cable removed outright still disconnects`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        val cable = patch.connections.first { it.to.moduleId == OUT_ID }
        patch.disconnect(cable.to)
        sync.sync(patch)

        assertTrue(
            "nothing replaced it, so the disconnect must still be sent",
            rec.log.contains(Cmd.Disconnect(cable.to.moduleId, cable.to.index)),
        )
    }

    @Test
    fun `invalidate forces a full resend`() {
        val patch = demoPatch()
        sync.sync(patch)
        val first = rec.log.size
        rec.clear()

        sync.invalidate()
        sync.sync(patch)
        assertEquals(first, rec.log.size)
    }

    @Test
    fun `a reloaded patch syncs like any other change`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        // what happens at launch: the same content arriving from disk
        val reloaded = patchFromJson(patch.toJson())!!
        sync.sync(reloaded)
        assertTrue("identical content should be a no-op", rec.log.isEmpty())
    }

    @Test
    fun `a new node has every knob sent`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        val added = patch.add(Types.Env, Offset.Zero)!!
        sync.sync(patch)

        // All four, not just the ones that differ from the engine's own defaults: the
        // C++ node's starting values are not required to agree with the declared ones.
        val sent = rec.log.filterIsInstance<Cmd.SetParam>().filter { it.id == added.id }
        assertEquals(Types.Env.params.size, sent.size)
    }

    @Test
    fun `turning one knob sends one command`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        val filter = patch.free.first { it.type == Types.Filter }
        filter.setParam(1, 0.8f)
        sync.sync(patch)

        assertEquals(listOf(Cmd.SetParam(filter.id, 1, 0.8f)), rec.log)
    }

    @Test
    fun `knobs that did not move send nothing`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        sync.sync(patch)
        assertTrue(rec.log.none { it is Cmd.SetParam })
    }

    @Test
    fun `knobs are sent after the node exists`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        val added = patch.add(Types.Osc, Offset.Zero)!!
        sync.sync(patch)

        val add = rec.log.indexOfFirst { it == Cmd.Add(added.id, NodeType.Osc) }
        val firstParam = rec.log.indexOfFirst { it is Cmd.SetParam && it.id == added.id }
        assertTrue("a knob cannot be set on a node that is not there", add < firstParam)
    }

    @Test
    fun `garbage is collected on every sync`() {
        val patch = demoPatch()
        sync.sync(patch)
        sync.sync(patch)
        assertEquals(2, rec.collected)
    }
}

/**
 * Invariants that span the JNI boundary. Each of these fails silently in production --
 * a module with no node type simply makes no sound, and a fifth port simply never
 * connects -- which is exactly why they are asserted here.
 */
class ModuleContractTest {

    @Test
    fun `every module type has an engine node`() {
        Types.byName.values.forEach { type ->
            assertTrue(
                "${type.name} maps to NodeType.Unknown, so it would be silent",
                NodeType.of(type) != NodeType.Unknown,
            )
        }
    }

    @Test
    fun `node type ids are distinct`() {
        val ids = NodeType.entries.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun `no module exceeds the engine's port limit`() {
        Types.byName.values.forEach { type ->
            assertTrue("${type.name} has ${type.inputs.size} inputs", type.inputs.size <= MAX_PORTS)
            assertTrue("${type.name} has ${type.outputs.size} outputs", type.outputs.size <= MAX_PORTS)
        }
    }

    @Test
    fun `every port is named and typed`() {
        Types.byName.values.forEach { type ->
            (type.inputs + type.outputs).forEach { port ->
                assertTrue("${type.name} has an unnamed port", port.name.isNotBlank())
            }
        }
    }

    @Test
    fun `the palette offers no pinned type`() {
        // Adding a second Out from the menu would be meaningless, and Patch.add refuses
        // it anyway -- so offering it would be a tile that silently does nothing.
        assertTrue(Types.palette.none { it.pinned != null })
    }

    @Test
    fun `the rails are the only pinned types`() {
        assertEquals(
            setOf(Types.Out, Types.In),
            Types.byName.values.filter { it.pinned != null }.toSet(),
        )
    }

    @Test
    fun `signal kinds survive a patch round trip`() {
        val patch = demoPatch()
        val restored = patchFromJson(patch.toJson())!!
        patch.connections.forEach { cable ->
            assertEquals(
                "cable from ${cable.from} changed kind",
                patch.kindOf(cable.from),
                restored.kindOf(cable.from),
            )
        }
    }
}

/** The knob abstraction itself: ranges, curves, and the round trip through a UI. */
class ParamTest {

    private fun allParams() = Types.byName.values.flatMap { it.params }

    @Test
    fun `every default sits inside its own range`() {
        Types.byName.values.forEach { type ->
            type.params.forEach { p ->
                assertTrue(
                    "${type.name}.${p.name} default ${p.default} outside ${p.min}..${p.max}",
                    p.default >= p.min && p.default <= p.max,
                )
            }
        }
    }

    @Test
    fun `no module declares more knobs than the engine can carry`() {
        Types.byName.values.forEach { type ->
            assertTrue(
                "${type.name} has ${type.params.size} params",
                type.params.size <= MAX_PARAMS,
            )
        }
    }

    @Test
    fun `position and value are inverses`() {
        allParams().forEach { p ->
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { t ->
                val roundTrip = p.positionOf(p.valueAt(t))
                // Stepped params quantise, so they only round trip at their own steps.
                if (p.curve != ParamCurve.STEPPED) {
                    assertEquals("${p.name} at $t", t, roundTrip, 0.001f)
                }
            }
        }
    }

    @Test
    fun `the travel ends where the range does`() {
        allParams().forEach { p ->
            // Relative, not absolute: an exponential knob over a 900:1 range round trips
            // through exp and ln in single precision, and lands within a couple of parts
            // per million rather than exactly. That is float arithmetic, not a defect.
            val tolerance = kotlin.math.max(kotlin.math.abs(p.max), 1f) * 1e-5f
            assertEquals("${p.name} min", p.min, p.valueAt(0f), tolerance)
            assertEquals("${p.name} max", p.max, p.valueAt(1f), tolerance)
        }
    }

    @Test
    fun `an exponential knob spends half its travel in the lower octaves`() {
        // The reason the curve exists: on a linear cutoff knob, everything below 2kHz --
        // which is most of what matters -- would live in the first tenth of the sweep.
        val cutoff = Types.Filter.params.first { it.name == "cutoff" }
        val middle = cutoff.valueAt(0.5f)
        assertTrue("midpoint was $middle", middle > 500f && middle < 1000f)
    }

    @Test
    fun `a stepped knob lands on whole values`() {
        val wave = Types.Osc.params.first { it.name == "wave" }
        (0..10).forEach { i ->
            val v = wave.valueAt(i / 10f)
            assertEquals("at $i", v, kotlin.math.round(v), 0.0001f)
        }
    }

    @Test
    fun `a module starts at its declared defaults`() {
        val module = PatchModule(999L, Types.Env, Offset.Zero)
        assertEquals(Types.Env.params.map { it.default }, module.params.toList())
    }
}

/**
 * A stepped parameter is a row of buttons, and the arithmetic behind it decides whether
 * every button is equally easy to press. round() -- which this used before -- gives the
 * first and last options half the width of the rest, so the two ends of every waveform
 * selector would be twice as hard to hit as the middle.
 */
class SteppedParamTest {

    private val wave = Types.Osc.params.first { it.name == "wave" }
    private val len = Types.Steps.params.first { it.name == "len" }

    private fun stepped() =
        Types.byName.values.flatMap { it.params }.filter { it.curve == ParamCurve.STEPPED }

    @Test
    fun `the option count is the span plus one`() {
        assertEquals(4, wave.steps)
        assertEquals(8, len.steps)
    }

    /**
     * Mirrors kWaves in nodes.cpp. An extra waveform added to the engine without a wider
     * range here is a button the interface can never offer; a wider range without the
     * engine is a button that silently selects the last waveform.
     */
    @Test
    fun `the waveform selector offers exactly the waveforms the engine has`() {
        assertEquals(ENGINE_WAVEFORMS, wave.steps)
        assertEquals(Choice.WAVE, wave.choice)
    }

    @Test
    fun `every option occupies the same width of travel`() {
        stepped().forEach { p ->
            val widths = (0 until p.steps).map { i ->
                // Walk the travel finely and count where each option wins.
                (0..1000).count { t -> p.indexOf(p.valueAt(t / 1000f)) == i }
            }
            val spread = widths.max() - widths.min()
            assertTrue("${p.name} option widths $widths", spread <= 2)
        }
    }

    @Test
    fun `the travel reaches every option, and only real ones`() {
        stepped().forEach { p ->
            val seen = (0..1000).map { p.valueAt(it / 1000f) }.toSortedSet()
            assertEquals("${p.name}", (0 until p.steps).map { p.min + it }.toSortedSet(), seen)
        }
    }

    @Test
    fun `the ends of the travel are the ends of the range`() {
        stepped().forEach { p ->
            assertEquals("${p.name} min", p.min, p.valueAt(0f), 0.0001f)
            assertEquals("${p.name} max", p.max, p.valueAt(1f), 0.0001f)
        }
    }

    @Test
    fun `a value maps back into its own option, not onto the edge`() {
        stepped().forEach { p ->
            (0 until p.steps).forEach { i ->
                val value = p.min + i
                assertEquals("${p.name} option $i", i, p.indexOf(p.valueAt(p.positionOf(value))))
            }
        }
    }

    private companion object {
        /** saw, square, triangle, sine -- kWaves in nodes.cpp */
        const val ENGINE_WAVEFORMS = 4
    }
}
