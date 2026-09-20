package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private sealed interface Cmd {
    data class Add(val id: Long, val type: NodeType) : Cmd
    data class Remove(val id: Long) : Cmd
    data class Connect(val src: Long, val srcPort: Int, val dst: Long, val dstPort: Int) : Cmd
    data class Disconnect(val src: Long, val srcPort: Int, val dst: Long, val dstPort: Int) : Cmd
    data class SetParam(val id: Long, val index: Int, val value: Float) : Cmd
    data class SetStep(val id: Long, val index: Int, val degree: Int, val gate: Boolean) : Cmd
    /** Each entry's scale name and its length in beats, which is what the engine gets, and each root. */
    data class SetScales(val entries: List<Pair<String, Int>>, val roots: List<Float>) : Cmd
    data class SetTempo(val bpm: Float) : Cmd
    data class SetModRange(
        val id: Long, val index: Int, val low: Float, val high: Float, val exponential: Boolean,
    ) : Cmd
    data class ConnectMod(val src: Long, val srcPort: Int, val dst: Long, val index: Int) : Cmd
    data class DisconnectMod(val src: Long, val srcPort: Int, val dst: Long, val index: Int) : Cmd
    data class SetFont(val id: Long, val font: Long) : Cmd
    data class SetDot(val id: Long, val slot: Int, val step: Int, val degree: Int, val length: Int) : Cmd
}

private class Recorder : GraphCommands {
    val log = mutableListOf<Cmd>()
    var collected = 0

    override fun addNode(id: Long, type: NodeType) { log += Cmd.Add(id, type) }
    override fun removeNode(id: Long) { log += Cmd.Remove(id) }
    override fun connect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        log += Cmd.Connect(srcId, srcPort, dstId, dstPort)
    }
    override fun disconnect(srcId: Long, srcPort: Int, dstId: Long, dstPort: Int) {
        log += Cmd.Disconnect(srcId, srcPort, dstId, dstPort)
    }
    override fun setParam(id: Long, index: Int, value: Float) {
        log += Cmd.SetParam(id, index, value)
    }
    override fun setStep(id: Long, index: Int, degree: Int, gate: Boolean) {
        log += Cmd.SetStep(id, index, degree, gate)
    }
    override fun setScales(entries: List<ScaleEntry>, beatsPerBar: Int) {
        log += Cmd.SetScales(
            entries.map { it.scale.name to it.lengthInBeats(beatsPerBar) },
            entries.map { it.rootCents },
        )
    }
    override fun setTempo(bpm: Float) { log += Cmd.SetTempo(bpm) }
    override fun setFont(id: Long, font: Long) { log += Cmd.SetFont(id, font) }
    override fun setDot(id: Long, slot: Int, step: Int, degree: Int, length: Int) {
        log += Cmd.SetDot(id, slot, step, degree, length)
    }
    override fun setModRange(id: Long, index: Int, low: Float, high: Float, exponential: Boolean) {
        log += Cmd.SetModRange(id, index, low, high, exponential)
    }
    override fun connectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int) {
        log += Cmd.ConnectMod(srcId, srcPort, dstId, index)
    }
    override fun disconnectMod(srcId: Long, srcPort: Int, dstId: Long, index: Int) {
        log += Cmd.DisconnectMod(srcId, srcPort, dstId, index)
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

    /**
     * The engine-facing half of subpatches: a playing patch subpatched, nested, unpacked or
     * re-subpatched is sent nothing, because the cables the engine has are the same cables. A
     * command here would be a crossfade, and every one of them is audible.
     */
    @Test
    fun `subpatching a playing patch sends the engine nothing`() {
        val f = SubpatchFixture()
        sync.sync(f.patch)
        rec.clear()

        val inner = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        sync.sync(f.patch)
        assertTrue("subpatching: ${rec.log}", rec.log.isEmpty())

        val outer = f.patch.makeSubpatch(setOf(inner.id, f.lfo.id))!!
        sync.sync(f.patch)
        assertTrue("nesting: ${rec.log}", rec.log.isEmpty())

        f.patch.unpack(outer)
        f.patch.unpack(inner)
        sync.sync(f.patch)
        assertTrue("unpacking: ${rec.log}", rec.log.isEmpty())
    }

    /**
     * Promoting a knob is the same promise as subpatching: it moves where a control is reached
     * from, not what it is. The engine holds the module inside either way, and a command
     * here would be a knob written twice or, worse, a node rebuilt under a playing patch.
     */
    @Test
    fun `promoting a knob to a subpatch's edge sends the engine nothing`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        sync.sync(f.patch)
        rec.clear()

        f.patch.enterScope(subpatch.id)
        assertTrue(f.patch.promote(f.filter, f.filter.type.rowParams.first()))
        sync.sync(f.patch)
        assertTrue("promoting: ${rec.log}", rec.log.isEmpty())

        // Turning it from the subpatch's panel is an ordinary parameter change, on the module
        // that really holds it -- the subpatch is not a node and cannot be sent one.
        val row = f.patch.panelRows(subpatch).single()
        row.owner.setParam(row.index, 4321f)
        sync.sync(f.patch)
        assertEquals(listOf(Cmd.SetParam(f.filter.id, row.index, 4321f)), rec.log)
    }

    @Test
    fun `a module inside a subpatch is synced like any other`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        sync.sync(f.patch)

        assertTrue(rec.log.none { it is Cmd.Add && (it.id == subpatch.id) })
        assertTrue(rec.log.any { it == Cmd.Add(f.osc.id, NodeType.Osc) })
        assertTrue(
            "a cable through the subpatch's ports arrives as the one it stands for",
            rec.log.contains(Cmd.Connect(f.filter.id, 0, OUT_ID, 0)),
        )

        rec.clear()
        f.filter.setParam(0, 2345f)
        sync.sync(f.patch)
        assertEquals(listOf<Cmd>(Cmd.SetParam(f.filter.id, 0, 2345f)), rec.log)
    }


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
            rec.log.none { it is Cmd.Disconnect && it.dst == OUT_ID && it.dstPort == 0 },
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
            rec.log.contains(
                Cmd.Disconnect(cable.from.moduleId, cable.from.index, cable.to.moduleId, cable.to.index)
            ),
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

    // ---------------------------------------------------------------- notes

    /** A sequencer's notes output into a voice, which is the patch this all exists for. */
    private fun withVoice(): Triple<Patch, PatchModule, PatchModule> {
        val patch = demoPatch()
        val steps = patch.free.first { it.type.stepCount > 0 }
        val voice = patch.add(Types.Osc, Offset.Zero)!!
        patch.connect(notesOut(steps), notesIn(voice))
        return Triple(patch, steps, voice)
    }

    private fun notesOut(module: PatchModule) = PortRef(
        module.id, PortDirection.OUTPUT,
        module.type.outputs.indexOfFirst { it.kind == SignalKind.NOTE },
    )

    private fun notesIn(module: PatchModule) = PortRef(
        module.id, PortDirection.INPUT,
        module.type.inputs.indexOfFirst { it.kind == SignalKind.NOTE },
    )

    @Test
    fun `a second source on a note input adds rather than replacing`() {
        val (patch, _, voice) = withVoice()
        sync.sync(patch)
        rec.clear()

        val second = patch.add(Types.Steps, Offset.Zero)!!
        patch.connect(notesOut(second), notesIn(voice))
        sync.sync(patch)

        // The rule that suppresses a disconnect before a replacement is about signal
        // inputs, which take one source. A note input merges, so nothing was replaced and
        // nothing should be dropped.
        assertTrue(
            "merging supersedes nothing",
            rec.log.none { it is Cmd.Disconnect },
        )
        assertTrue(rec.log.contains(Cmd.Connect(second.id, notesOut(second).index, voice.id, 0)))
    }

    @Test
    fun `swapping one note source for another still drops the one that left`() {
        val (patch, steps, voice) = withVoice()
        sync.sync(patch)
        rec.clear()

        // Both in one sync, which is the case the suppression rule has to get right: for
        // a signal input the arriving cable replaces the one leaving and the disconnect
        // would step the crossfade, but a note input merges -- so the cable that left has
        // to be said, or the engine goes on playing a sequencer nothing is patched to.
        val second = patch.add(Types.Steps, Offset.Zero)!!
        patch.connections.removeAll { it.from.moduleId == steps.id && it.to.moduleId == voice.id }
        patch.connect(notesOut(second), notesIn(voice))
        sync.sync(patch)

        assertEquals(
            listOf(Cmd.Disconnect(steps.id, notesOut(steps).index, voice.id, 0)),
            rec.log.filterIsInstance<Cmd.Disconnect>(),
        )
        assertTrue(rec.log.contains(Cmd.Connect(second.id, notesOut(second).index, voice.id, 0)))
    }

    @Test
    fun `unpatching one of several note sources names the one that went`() {
        val (patch, steps, voice) = withVoice()
        val second = patch.add(Types.Steps, Offset.Zero)!!
        patch.connect(notesOut(second), notesIn(voice))
        sync.sync(patch)
        rec.clear()

        patch.connections.removeAll { it.from.moduleId == steps.id && it.to.moduleId == voice.id }
        sync.sync(patch)

        // Both ends, because the port still has the other sequencer on it: a disconnect
        // that named only the port would take that one with it.
        assertEquals(
            listOf(Cmd.Disconnect(steps.id, notesOut(steps).index, voice.id, 0)),
            rec.log.filterIsInstance<Cmd.Disconnect>(),
        )
        assertTrue(
            "and the source that stayed is not resent",
            rec.log.none { it is Cmd.Connect },
        )
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

    /**
     * Read out of nodes.h rather than trusted. Every other cross-boundary contract here is
     * asserted, and this one was only claimed to be: a new module whose Kotlin id disagreed
     * with the engine's would build, install and be silently a different module -- or none.
     * Written when the catalog started growing again, since each new module is two enums
     * edited in two languages.
     */
    @Test
    fun `node type ids are the engine's`() {
        val header = java.io.File("src/main/cpp/nodes.h").readText()
        val body = header.substringAfter("enum class NodeType : int32_t {").substringBefore("};")
        val engine = Regex("""^\s*(\w+)\s*=\s*(\d+),""", RegexOption.MULTILINE)
            .findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertTrue("found the enum", engine.size >= 10)
        assertEquals(engine, NodeType.entries.associate { it.name to it.id })
    }

    /** The same, for the limits: a knob past kMaxParams is dropped by the engine silently. */
    @Test
    fun `the port and parameter limits are the engine's`() {
        val header = java.io.File("src/main/cpp/node.h").readText()
        fun constant(name: String) =
            Regex("""constexpr int32_t $name = (\d+);""").find(header)!!.groupValues[1].toInt()
        assertEquals(constant("kMaxParams"), MAX_PARAMS)
        assertEquals(constant("kMaxPorts"), MAX_PORTS)
    }

    @Test
    fun `the dot sequencer's limits are the engine's`() {
        val header = java.io.File("src/main/cpp/nodes.h").readText().substringAfter("class SeqNode")
        fun constant(name: String) =
            Regex("""constexpr int32_t $name = (\d+);""").find(header)!!.groupValues[1].toInt()
        assertEquals(constant("kSteps"), DOT_STEPS)
        assertEquals(constant("kMaxDots"), MAX_DOTS)
    }

    @Test
    fun `the note processors' limits are the engine's`() {
        val header = java.io.File("src/main/cpp/processors.h").readText().substringAfter("class EuclidNode")
        val steps = Regex("""constexpr int32_t kMaxSteps = (\d+);""").find(header)!!.groupValues[1].toInt()
        assertEquals(steps, EUCLID_STEPS)
        // The modes the engine counts to, read off its clamp.
        val source = java.io.File("src/main/cpp/processors.cpp").readText()
        val top = Regex("""case 0: mode_ = whole\(value, 0, (\d+)\)""").find(source)!!.groupValues[1].toInt()
        assertEquals(top + 1, ARP_MODES.size)
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
                // Stepped params quantize, so they only round trip at their own steps.
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

    /**
     * Every stepped parameter a finger drags or taps along a row. Not an SF's preset, which
     * is stepped only because it is a whole number: it has no row and no travel, and is
     * chosen from a page of names.
     */
    private fun stepped() =
        Types.byName.values.flatMap { it.params }
            .filter { it.curve == ParamCurve.STEPPED && it.choice != Choice.PRESET }

    @Test
    fun `the option count is the span plus one`() {
        assertEquals(4, wave.steps)
        // Not a literal: the length selector must offer every step the sequencer has.
        assertEquals(STEP_COUNT, len.steps)
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

/**
 * A sequence is patch data, so it has to travel every route the rest of the patch does:
 * to the engine, to the file, and back through an undo.
 */
class SequenceTest {

    private fun sequencer(patch: Patch) = patch.free.first { it.type.stepCount > 0 }

    private fun withSteps(): Pair<Patch, PatchModule> {
        val patch = demoPatch()
        return patch to sequencer(patch)
    }

    @Test
    fun `a new sequencer carries the default figure, not an empty one`() {
        val (_, steps) = withSteps()
        assertEquals(STEP_COUNT, steps.steps.size)
        assertTrue("every step starts open", steps.steps.all { it.on })
        assertTrue("not all one note", steps.steps.map { it.degree }.toSet().size > 1)
    }

    @Test
    fun `adding a sequencer sends every one of its steps`() {
        val recorder = Recorder()
        val (patch, steps) = withSteps()
        GraphSync(recorder).sync(patch)

        val sent = recorder.log.filterIsInstance<Cmd.SetStep>().filter { it.id == steps.id }
        assertEquals(STEP_COUNT, sent.size)
        assertEquals((0 until STEP_COUNT).toList(), sent.map { it.index })
    }

    @Test
    fun `only the step that changed is resent`() {
        val recorder = Recorder()
        val (patch, steps) = withSteps()
        val sync = GraphSync(recorder)
        sync.sync(patch)
        recorder.clear()

        steps.setStep(5, Step(9, on = false))
        sync.sync(patch)

        val sent = recorder.log.filterIsInstance<Cmd.SetStep>()
        assertEquals(1, sent.size)
        assertEquals(5, sent[0].index)
        assertEquals(false, sent[0].gate)
    }

    /** Steps cross as degrees. The engine resolves them, against the scale of the beat. */
    @Test
    fun `steps cross as degrees, not pitches`() {
        val recorder = Recorder()
        val (patch, steps) = withSteps()
        val sync = GraphSync(recorder)
        sync.sync(patch)
        recorder.clear()

        steps.setStep(0, Step(12))
        sync.sync(patch)
        assertEquals(12, recorder.log.filterIsInstance<Cmd.SetStep>().single().degree)
    }

    /**
     * A change of scale changes no step. The engine holds the tables, so the list goes
     * whole and not one step does: resending every step of every sequencer for each edit
     * to the list would be a flood of commands saying nothing.
     */
    @Test
    fun `changing the scale sends the list and no steps`() {
        val recorder = Recorder()
        val (patch, _) = withSteps()
        val sync = GraphSync(recorder)
        sync.sync(patch)
        recorder.clear()

        patch.scales = listOf(ScaleEntry(Scale.steps("Major", 12, listOf(2, 2, 1, 2, 2, 2, 1))))
        sync.sync(patch)

        assertTrue(recorder.log.filterIsInstance<Cmd.SetStep>().isEmpty())
        assertEquals(
            listOf(Cmd.SetScales(listOf("Major" to 16), listOf(0f))),
            recorder.log.filterIsInstance<Cmd.SetScales>(),
        )
    }

    /** Entries last bars and beats and the engine counts beats, so a new bar length is a new list. */
    @Test
    fun `a new bar length resends the list with new lengths`() {
        val recorder = Recorder()
        val (patch, _) = withSteps()
        val sync = GraphSync(recorder)
        patch.scales = listOf(
            ScaleEntry(Scale.Chromatic, bars = 2, beats = 1),
            ScaleEntry(Scale.equal("19-TET", 19)),
        )
        sync.sync(patch)
        recorder.clear()

        patch.beatsPerBar = 3
        sync.sync(patch)

        assertEquals(listOf(Cmd.SetScales(listOf("12-TET" to 7, "19-TET" to 12), listOf(0f, 0f))), recorder.log)
    }

    @Test
    fun `an entry never lasts zero beats`() {
        assertEquals(1, ScaleEntry(Scale.Chromatic, bars = 0, beats = 0).lengthInBeats(4))
    }

    /** A change of key alone is a change to the list, and goes to the engine like any other. */
    @Test
    fun `a change of key alone resends the list`() {
        val recorder = Recorder()
        val (patch, _) = withSteps()
        val sync = GraphSync(recorder)
        sync.sync(patch)
        recorder.clear()

        patch.scales = listOf(patch.scales.single().copy(rootCents = 700f))
        sync.sync(patch)

        assertEquals(listOf(Cmd.SetScales(listOf("12-TET" to 16), listOf(700f))), recorder.log)
    }

    @Test
    fun `a sequence survives a save and a reload`() {
        val (patch, steps) = withSteps()
        steps.setStep(0, Step(7, on = false))
        steps.setStep(15, Step(-5))

        val restored = patchFromJson(patch.toJson())!!
        val back = sequencer(restored)
        assertEquals(steps.steps.toList(), back.steps.toList())
    }

    @Test
    fun `a file written before sequences existed loads on the default figure`() {
        val (patch, _) = withSteps()
        val stripped = org.json.JSONObject(patch.toJson()).also { root ->
            val modules = root.getJSONArray("modules")
            for (i in 0 until modules.length()) modules.getJSONObject(i).remove("steps")
        }.toString()

        val back = sequencer(patchFromJson(stripped)!!)
        assertEquals(defaultSteps(back.type), back.steps.toList())
    }

    /**
     * The snapshot deliberately holds a step that is neither the default nor the current
     * value. A rebuilt PatchModule initializes to the default figure, so asserting
     * against the default would pass whether or not the sequence was carried across --
     * which is exactly what it did until a mutation check caught it.
     */
    @Test
    fun `undo puts a sequence back`() {
        val (patch, steps) = withSteps()
        val saved = Step(11, on = false)
        steps.setStep(3, saved)
        val snapshot = patch.toJson()

        steps.setStep(3, Step(2, on = true))
        assertTrue("the edit must differ from the default", defaultSteps(steps.type)[3] != saved)

        val changed = patch.replaceWith(patchFromJson(snapshot)!!)

        assertEquals(saved, sequencer(patch).steps[3])
        assertTrue("the sequencer is flagged for the pulse", steps.id in changed)
    }

    /** Resolved by name against the library on reload, which is how a scale is found again. */
    @Test
    fun `the scale list survives a save and a reload`() {
        val library = ScaleLibrary.of(File("src/main/assets/scales"))
        val (patch, _) = withSteps()
        patch.scales = listOf(
            ScaleEntry(library.byName("Major")!!, 4, 0, rootCents = 700f),
            ScaleEntry(library.byName("Minor pentatonic")!!, 2, 3, rootCents = -63.2f),
        )
        assertEquals(patch.scales, patchFromJson(patch.toJson(), library)!!.scales)
    }

    /**
     * An entry naming a scale whose file is gone keeps its place, in the fallback tuning,
     * so the list keeps its shape and its timing rather than losing a bar somewhere.
     */
    @Test
    fun `an entry naming a scale that is not installed keeps its place`() {
        val (patch, _) = withSteps()
        patch.scales = listOf(ScaleEntry(Scale.Chromatic, 2, 0), ScaleEntry(Scale.equal("Slendro", 5), 1, 2))
        val back = patchFromJson(patch.toJson(), ScaleLibrary.of(null))!!
        assertEquals(
            listOf(Triple("12-TET", 2, 0), Triple("12-TET", 1, 2)),
            back.scales.map { Triple(it.scale.name, it.bars, it.beats) },
        )
    }
}

/**
 * The tempo is patch data read by the snapshot flow in MainActivity, and it reaches the
 * engine through the same diff as everything else -- which has twice been where a new
 * piece of patch data silently failed to arrive.
 */
class TransportSyncTest {

    private val rec = Recorder()
    private val sync = GraphSync(rec)

    private fun tempos() = rec.log.filterIsInstance<Cmd.SetTempo>()

    @Test
    fun `the first sync sends the tempo`() {
        val patch = demoPatch().apply { tempo = 133f }
        sync.sync(patch)
        assertEquals(listOf(Cmd.SetTempo(133f)), tempos())
    }

    @Test
    fun `changing the tempo sends it once and nothing else`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        patch.tempo = 90f
        sync.sync(patch)

        assertEquals(listOf<Cmd>(Cmd.SetTempo(90f)), rec.log)
    }

    @Test
    fun `an unchanged tempo is not sent again`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()
        sync.sync(patch)
        assertTrue(tempos().isEmpty())
    }

    /** The engine keeps its transport across a restart, but not the rate it was told. */
    @Test
    fun `invalidate sends the tempo again`() {
        val patch = demoPatch()
        sync.sync(patch)
        rec.clear()

        sync.invalidate()
        sync.sync(patch)

        assertEquals(1, tempos().size)
    }

    /** Mirrors kIntervals in nodes.h. A mismatch is a button that plays the wrong length. */
    @Test
    fun `the interval selector offers exactly the divisions the engine has`() {
        assertEquals(ENGINE_INTERVALS, INTERVALS.size)
        assertEquals(ENGINE_DEFAULT_INTERVAL, DEFAULT_INTERVAL)

        val interval = Types.Steps.params[Types.Steps.intervalParam]
        assertEquals(INTERVALS.size, interval.steps)
        assertTrue("the interval belongs in the header", interval.header)
    }

    /** Mirrors kMinTempo and kMaxTempo in transport.h, so the chip and the sound agree. */
    @Test
    fun `the tempo range is the engine's`() {
        assertEquals(20f, TEMPO.min)
        assertEquals(300f, TEMPO.max)
    }

    @Test
    fun `nothing is left patched to a clock`() {
        assertTrue("Clock is gone from the palette", Types.palette.none { it.name == "Clock" })
        assertTrue(
            "and no module waits for a clock input",
            Types.byName.values.none { type -> type.inputs.any { it.name == "clock" } },
        )
    }

    private companion object {
        /** kIntervalCount and kDefaultInterval in nodes.h. */
        const val ENGINE_INTERVALS = 9
        const val ENGINE_DEFAULT_INTERVAL = 3
    }
}

/** The limits the engine's fixed tables impose. A disagreement fails silently, at the top of a scale. */
class ScaleLimitTest {

    /** kMaxDegrees and kMaxScaleEntries in scales.h. */
    @Test
    fun `the limits are the engine's`() {
        assertEquals(64, MAX_DEGREES)
        assertEquals(16, MAX_SCALE_ENTRIES)
    }

    @Test
    fun `a scale bigger than the engine's table is skipped, and one that just fits is not`() {
        val dir = kotlin.io.path.createTempDirectory("scales").toFile()
        try {
            fun edo(n: Int) = buildString {
                appendLine("! $n-EDO.scl")
                appendLine("$n equal divisions of the octave")
                appendLine(" $n")
                appendLine("!")
                (1..n).forEach { appendLine(" " + String.format(Locale.ROOT, "%.5f", it * 1200.0 / n)) }
            }
            File(dir, "64-EDO.scl").writeText(edo(64))
            File(dir, "65-EDO.scl").writeText(edo(65))

            val loaded = ScaleLibrary.of(dir).scales.associateBy { it.name }
            assertEquals("64 degrees fits", 64, loaded["64-EDO"]?.size)
            assertTrue("65 degrees does not", "65-EDO" !in loaded)
        } finally {
            dir.deleteRecursively()
        }
    }
    }

}

/** An exposed parameter, as the engine hears about it: its range, and cables landing on it. */
class ModulationSyncTest {

    private val rec = Recorder()
    private val sync = GraphSync(rec)

    private fun setup(): Triple<Patch, PatchModule, PatchModule> {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        val lfo = patch.add(Types.Lfo, Offset.Zero)!!
        return Triple(patch, filter, lfo)
    }

    private fun mod(m: PatchModule, index: Int) = PortRef(m.id, PortDirection.MOD, index)
    private fun out(m: PatchModule) = PortRef(m.id, PortDirection.OUTPUT, 0)

    private fun modCables() = rec.log.filter { it is Cmd.ConnectMod || it is Cmd.DisconnectMod }

    @Test
    fun `a range is sent before the cable that uses it`() {
        val (patch, filter, lfo) = setup()
        sync.sync(patch)
        rec.clear()

        patch.expose(filter, 0, ModRange(400f, 2000f))
        patch.connect(out(lfo), mod(filter, 0))
        sync.sync(patch)

        val range = rec.log.indexOf(Cmd.SetModRange(filter.id, 0, 400f, 2000f, true))
        val cable = rec.log.indexOf(Cmd.ConnectMod(lfo.id, 0, filter.id, 0))
        assertTrue("the range is sent", range >= 0)
        assertTrue("and the cable after it", cable > range)
        assertTrue("never as a port connect", rec.log.none { it is Cmd.Connect })
    }

    @Test
    fun `a sweep is geometric exactly where the knob is`() {
        val (patch, filter, _) = setup()
        patch.expose(filter, 0, ModRange(400f, 2000f)) // cutoff: exponential
        patch.expose(filter, 1, ModRange(0.1f, 0.9f))  // resonance: linear
        sync.sync(patch)
        assertTrue(Cmd.SetModRange(filter.id, 0, 400f, 2000f, true) in rec.log)
        assertTrue(Cmd.SetModRange(filter.id, 1, 0.1f, 0.9f, false) in rec.log)
    }

    @Test
    fun `an unchanged range is not resent, and a moved bracket is`() {
        val (patch, filter, _) = setup()
        patch.expose(filter, 0, ModRange(400f, 2000f))
        sync.sync(patch)
        rec.clear()

        sync.sync(patch)
        assertTrue(rec.log.none { it is Cmd.SetModRange })

        patch.expose(filter, 0, ModRange(300f, 2000f))
        sync.sync(patch)
        assertEquals(
            listOf(Cmd.SetModRange(filter.id, 0, 300f, 2000f, true)),
            rec.log.filterIsInstance<Cmd.SetModRange>(),
        )
    }

    @Test
    fun `replacing a modulator sends only the new cable`() {
        val (patch, filter, lfo) = setup()
        val other = patch.add(Types.Lfo, Offset.Zero)!!
        patch.expose(filter, 0, ModRange(400f, 2000f))
        patch.connect(out(lfo), mod(filter, 0))
        sync.sync(patch)
        rec.clear()

        // One modulator per parameter, so this is a crossfade in the engine -- and a
        // disconnect sent alongside would fade to the knob and back instead.
        patch.connect(out(other), mod(filter, 0))
        sync.sync(patch)
        assertEquals(listOf(Cmd.ConnectMod(other.id, 0, filter.id, 0)), modCables())
    }

    @Test
    fun `un-exposing sends a modulation disconnect`() {
        val (patch, filter, lfo) = setup()
        patch.expose(filter, 0, ModRange(400f, 2000f))
        patch.connect(out(lfo), mod(filter, 0))
        sync.sync(patch)
        rec.clear()

        patch.unexpose(filter, 0)
        sync.sync(patch)
        assertEquals(listOf(Cmd.DisconnectMod(lfo.id, 0, filter.id, 0)), modCables())
        assertTrue(rec.log.none { it is Cmd.Disconnect })
    }

    @Test
    fun `a rebuilt engine hears every range again`() {
        val (patch, filter, _) = setup()
        patch.expose(filter, 0, ModRange(400f, 2000f))
        sync.sync(patch)
        rec.clear()

        sync.invalidate()
        sync.sync(patch)
        assertTrue(Cmd.SetModRange(filter.id, 0, 400f, 2000f, true) in rec.log)
    }
}

/**
 * An SF module is handed its font by the sync after both exist: the module, and the font
 * finished loading. Loading takes seconds, so the two arrive in either order, and a font
 * that is never sent is a module that never makes a sound -- silently, like every other
 * thing this flow once forgot to send.
 */
class SoundFontSyncTest {

    private val bank = 0x5F0L
    private val other = 0x6F0L

    private val chosen = "A Bank"

    /** An SF with a bank chosen, as the panel's page sets it. */
    private fun sfPatch(): Pair<Patch, PatchModule> {
        val patch = Patch()
        val sf = patch.add(Types.Sf, Offset.Zero)!!
        sf.font = chosen
        return patch to sf
    }

    @Test
    fun `an SF with no bank is sent nothing at all`() {
        val patch = Patch()
        val sf = patch.add(Types.Sf, Offset.Zero)!!
        assertNull("a new SF has no bank: none ship with the app", sf.font)
        val rec = Recorder()
        GraphSync(rec).sync(patch, mapOf(chosen to bank))
        assertTrue(rec.log.any { it == Cmd.Add(sf.id, NodeType.Sf) })
        assertTrue("and nothing to play it with", rec.log.none { it is Cmd.SetFont })
    }

    @Test
    fun `an SF is sent its bank once that has loaded`() {
        val (patch, sf) = sfPatch()

        val rec = Recorder()
        val sync = GraphSync(rec)
        sync.sync(patch)
        assertTrue("nothing to send while the font loads", rec.log.none { it is Cmd.SetFont })

        rec.log.clear()
        sync.sync(patch, mapOf(chosen to bank))
        assertEquals(listOf(Cmd.SetFont(sf.id, bank)), rec.log.filterIsInstance<Cmd.SetFont>())

        rec.log.clear()
        sync.sync(patch, mapOf(chosen to bank))
        assertTrue("and not again", rec.log.isEmpty())
    }

    @Test
    fun `a font already loaded goes with the node that is added`() {
        val (patch, sf) = sfPatch()
        val rec = Recorder()
        GraphSync(rec).sync(patch, mapOf(chosen to bank))
        val add = rec.log.indexOf(Cmd.Add(sf.id, NodeType.Sf))
        val font = rec.log.indexOf(Cmd.SetFont(sf.id, bank))
        assertTrue("added, then given its font", add >= 0 && font > add)
    }

    @Test
    fun `changing the font sends the new one, and a restart sends it again`() {
        val (patch, sf) = sfPatch()
        val rec = Recorder()
        val sync = GraphSync(rec)
        val fonts = mapOf(chosen to bank, "Other" to other)
        sync.sync(patch, fonts)

        rec.log.clear()
        sf.font = "Other"
        sync.sync(patch, fonts)
        assertEquals(listOf(Cmd.SetFont(sf.id, other)), rec.log.filterIsInstance<Cmd.SetFont>())

        // The engine restarting forgets every node, and its synth with it.
        rec.log.clear()
        sync.invalidate()
        sync.sync(patch, fonts)
        assertEquals(listOf(Cmd.SetFont(sf.id, other)), rec.log.filterIsInstance<Cmd.SetFont>())
    }

    @Test
    fun `nothing but an SF is sent a font`() {
        val patch = demoPatch()
        val rec = Recorder()
        GraphSync(rec).sync(patch, mapOf(chosen to bank))
        assertTrue(rec.log.none { it is Cmd.SetFont })
    }
}

/** Dots cross by slot: only what changed, a cleared slot for one that went, all of them for a new node. */
class DotSyncTest {

    @Test
    fun `dots cross by slot, and only when they change`() {
        val patch = Patch()
        val seq = patch.add(Types.Seq, Offset.Zero)!!
        seq.addDot(Dot(0, 0, 2))
        seq.addDot(Dot(4, 7, 1))
        val rec = Recorder()
        val sync = GraphSync(rec)
        sync.sync(patch)
        assertEquals(
            listOf(Cmd.SetDot(seq.id, 0, 0, 0, 2), Cmd.SetDot(seq.id, 1, 4, 7, 1)),
            rec.log.filterIsInstance<Cmd.SetDot>(),
        )

        rec.log.clear()
        seq.setDotLength(1, 3)
        sync.sync(patch)
        assertEquals(listOf(Cmd.SetDot(seq.id, 1, 4, 7, 3)), rec.log.filterIsInstance<Cmd.SetDot>())

        // The first goes: the second moves to slot 0, and slot 1 is cleared.
        rec.log.clear()
        seq.removeDot(0)
        sync.sync(patch)
        assertEquals(
            listOf(Cmd.SetDot(seq.id, 0, 4, 7, 3), Cmd.SetDot(seq.id, 1, 0, 0, 0)),
            rec.log.filterIsInstance<Cmd.SetDot>(),
        )

        rec.log.clear()
        sync.sync(patch)
        assertTrue("nothing new, nothing sent", rec.log.isEmpty())
    }
}
