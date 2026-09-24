package io.github.forrcaho.patchgarden

import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Offset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * Patch persistence.
 *
 * Hand-rolled against org.json rather than kotlinx.serialization. The schema is four
 * fields per entity, and a file on disk is untrusted input that needs validating either
 * way -- a stale patch naming a module type that no longer exists, or a connection whose
 * port index has moved, must load as "ignore that entry", never as a crash on launch.
 * With the validation written regardless, the plugin and dependency would buy nothing.
 *
 * Only free modules are stored. The rails are recreated by Patch's constructor and are
 * referenced by their reserved ids, so their cables survive a reload without their
 * geometry being persisted.
 */

/**
 * 2: the Clock module became the patch's tempo. 3: one scale became a list of them.
 * 4: parameters can be exposed for modulation, and cables can land on them.
 * 5: CV and gate retired, taking the monophonic Osc and the VCA with them.
 * 6: subpatches. Additive -- a format 5 file is a patch with no subpatches -- so 5 still reads.
 * 9: the redesign around subpatches. Groups became subpatches, so their type names in the
 * file changed; Osc and FM lost their envelopes, so their knob lists are shorter and every
 * index after the first moved; Poly and Amp arrived. Nothing older can be read.
 * 10: a dot's length is in quarter steps, and Seq lost its gate knob. A 9 reads as a
 * quarter of the music it is.
 * 11: a dot says how hard it is struck. Additive, and a 10 still reads: a dot without a
 * velocity comes back at full, which is what every note in the app sounded at.
 * 12: a Filter says its type and its slope. Additive in the same way -- a filter that names
 * neither was a 12dB lowpass, which is what it comes back as -- so 11 and 10 still read.
 * The bump is for the other direction: knobs are keyed by name, so an 11 build would read
 * a bandpass, ignore the two knobs it does not know, and autosave it as a lowpass.
 * 13: a Filter takes notes and tracks them. Additive again -- no track knob is no tracking,
 * and a port appended leaves every saved cable's index where it was. The bump is for the
 * older build, which would read a cable into a port it does not have, skip it quietly, and
 * autosave the patch without it.
 * 14: an Env is segments, and **14 reads nothing but 14**. The additive run ends here. An
 * envelope's four knobs are gone and what replaced them is not a renaming of them: A, D and
 * R were one-pole *time constants* toward targets they never reach, where a segment runs a
 * stated distance in a stated time. The mapping exists -- a release from sustain S really
 * lasts R*ln(1 + 100S), which is about four times the knob -- and using it would be a
 * conversion and not a restatement, which is exactly the line 10 was drawn on. An ADSR read
 * as four segments with the wrong curvature is a patch that loads, plays, and is not the
 * sound that was saved.
 * 15: Amp's `mod` port is its gain's own jack, sweeping it between brackets, and the gain
 * can no longer be exposed. 15 reads nothing but 15 as well: a 14 Amp whose gain was exposed
 * and patched had two modulators on one number, which nothing in 15 can say. Forrest's call,
 * with no patch worth keeping during development -- going clean was cheaper than a refusal
 * that looks inside the file.
 */
private const val FORMAT_VERSION = 15

/**
 * The older formats this build reads as they stand. See [upgrade].
 *
 * Each entry is a change that needs no conversion, only a default that restates what the
 * file already sounded like. Anything that would have to be *converted* is not on this list
 * and never will be.
 */
private val READABLE = setOf(FORMAT_VERSION)
private const val TAG = "PatchStore"

fun Patch.toJson(): String {
    val modules = JSONArray()
    free.forEach { m ->
        val entry = JSONObject()
            .put("id", m.id)
            .put("type", m.type.name)
            .put("x", m.position.x.toDouble())
            .put("y", m.position.y.toDouble())
            .put("params", paramsOf(m))
            .put("steps", stepsOf(m))
            .put("mod", modOf(m))
        if (m.type.grid == GridKind.DOTS) entry.put("dots", dotsOf(m))
        if (m.type.grid == GridKind.ENVELOPE) entry.put("segments", segmentsOf(m))
        // Absent at the top level, so a patch with no subpatches writes exactly what format 5 did.
        m.name?.let { entry.put("name", it) }
        m.font?.let { entry.put("font", it) }
        if (m.parent != TOP) entry.put("parent", m.parent)
        if (m.type.box) {
            // The rails inside are not modules in the file: they carry no knobs and no
            // position, only ids for the cables inside to name, and the subpatch's ports.
            entry.put("in", subpatchRail(m.id, Types.SubpatchIn)?.id ?: -1L)
            entry.put("out", subpatchRail(m.id, Types.SubpatchOut)?.id ?: -1L)
            entry.put("inputs", portsOf(m.ports(PortDirection.INPUT)))
            entry.put("outputs", portsOf(m.ports(PortDirection.OUTPUT)))
            entry.put("knobs", knobsOf(m.subpatchPorts?.promoted.orEmpty()))
        }
        modules.put(entry)
    }

    // The rails hold knobs too -- output level, microphone gain -- and those are part of
    // the patch even though the rails' geometry is not.
    val rails = JSONArray()
    pinned.filter { it.type.params.isNotEmpty() }.forEach { m ->
        rails.put(JSONObject().put("id", m.id).put("params", paramsOf(m)))
    }

    val cables = JSONArray()
    connections.forEach { c ->
        // connect() normalizes every cable to output -> input, so the directions are an
        // invariant rather than data, and reload re-imposes them.
        val cable = JSONObject()
            .put("from", c.from.moduleId)
            .put("fromPort", c.from.index)
            .put("to", c.to.moduleId)
        // A modulation cable lands on a parameter, named the way the knobs are, so reordering
        // a module's parameters cannot move a modulator onto a different knob.
        if (c.to.dir == PortDirection.MOD) {
            cable.put("toParam", module(c.to.moduleId)?.type?.params?.getOrNull(c.to.index)?.name ?: "")
        } else {
            cable.put("toPort", c.to.index)
        }
        cables.put(cable)
    }

    val scaleList = JSONArray()
    scales.forEach { entry ->
        scaleList.put(
            JSONObject()
                .put("name", entry.scale.name)
                .put("bars", entry.bars)
                .put("beats", entry.beats)
                .put("root", entry.rootCents.toDouble())
        )
    }

    // inputEnabled is deliberately absent. Whether the microphone is listening is
    // runtime state, like the master output, not part of the document -- and persisting
    // it meant a force-stop or a crash with the mic on came back showing a live In rail
    // with no stream behind it, which toggling off and on was the only way to notice.
    // Older files carrying the field are simply ignored.
    return JSONObject()
        .put("version", FORMAT_VERSION)
        // Absent until a patch is named, so a file written before names is byte for byte what
        // it was.
        .apply { name?.let { put("name", it) } }
        .put("modules", modules)
        .put("rails", rails)
        .put("connections", cables)
        .put("scales", scaleList)
        .put("tempo", tempo.toDouble())
        .put("beatsPerBar", beatsPerBar)
        .toString()
}

/** The knobs promoted to a subpatch's edge, each as the module it belongs to and which knob. */
private fun knobsOf(refs: List<ParamRef>): JSONArray {
    val out = JSONArray()
    refs.forEach { out.put(JSONObject().put("module", it.moduleId).put("param", it.index)) }
    return out
}

private fun knobsFrom(array: JSONArray?): List<ParamRef> {
    val out = mutableListOf<ParamRef>()
    val source = array ?: return out
    for (i in 0 until source.length()) {
        val entry = source.optJSONObject(i) ?: continue
        val id = entry.optLong("module", -1L)
        val index = entry.optInt("param", -1)
        if (id >= 0L && index >= 0) out.add(ParamRef(id, index))
    }
    return out
}

/** A subpatch's ports, as name and kind. Positional: a port's index is what its cables name. */
private fun portsOf(ports: List<Port>): JSONArray {
    val out = JSONArray()
    ports.forEach { out.put(JSONObject().put("name", it.name).put("kind", it.kind.name)) }
    return out
}

private fun portsFrom(stored: JSONArray?): List<Port> {
    if (stored == null) return emptyList()
    return (0 until stored.length()).mapNotNull { i ->
        val p = stored.optJSONObject(i) ?: return@mapNotNull null
        val kind = SignalKind.entries.firstOrNull { it.name == p.optString("kind") } ?: return@mapNotNull null
        Port(p.optString("name", "port"), kind)
    }
}

/**
 * Knobs are keyed by name rather than position, so adding or reordering a module's
 * parameters cannot silently reassign a saved value to a different knob.
 */
private fun paramsOf(module: PatchModule): JSONObject {
    val out = JSONObject()
    module.type.params.forEachIndexed { i, p -> out.put(p.name, module.params[i].toDouble()) }
    return out
}

/**
 * A sequence, or nothing at all for the modules that are not sequencers.
 *
 * Positional rather than keyed by name, unlike parameters: a step's identity *is* its
 * position, so there is no reordering for a key to protect against.
 */
private fun stepsOf(module: PatchModule): JSONArray {
    val out = JSONArray()
    module.steps.forEach { step ->
        out.put(JSONObject().put("d", step.degree).put("on", step.on))
    }
    return out
}

/**
 * A dot sequencer's dots, each as [step, degree, length, velocity]: positional.
 *
 * The velocity is written even when it is full, so the file says what it means rather than
 * leaving a reader to know the default -- and so a byte-for-byte round trip holds, which is
 * what stops History recording a load as an edit.
 */
private fun dotsOf(module: PatchModule): JSONArray {
    val out = JSONArray()
    module.dots.forEach {
        // Through the float's own toString, because widening 0.3f to a double writes
        // 0.30000001192092896 into a file people read with `cat`. Both round-trip back to
        // the same float; only one of them is legible.
        out.put(
            JSONArray().put(it.step).put(it.degree).put(it.length)
                .put(it.velocity.toString().toDouble()),
        )
    }
    return out
}

/** Clamped, because the file is untrusted: a dot off the grid is placed on its last step. */
private fun restoreDots(module: PatchModule, stored: JSONArray?) {
    if (stored == null || module.type.grid != GridKind.DOTS) return
    for (i in 0 until minOf(stored.length(), MAX_DOTS)) {
        val d = stored.optJSONArray(i) ?: continue
        if (d.length() < 3) continue
        module.addDot(
            Dot(
                d.optInt(0).coerceIn(0, DOT_STEPS - 1),
                d.optInt(1),
                // In quarter steps since format 10; see DOT_SUBSTEPS.
                d.optInt(2, DOT_SUBSTEPS).coerceIn(1, DOT_STEPS * DOT_SUBSTEPS),
                // Absent in a format 10 file, where every note was struck at full.
                d.optDouble(3, 1.0).toFloat().takeIf { it.isFinite() }
                    ?.coerceIn(MIN_VELOCITY, 1f) ?: 1f,
            ),
        )
    }
}

/**
 * An envelope's segments, each as [time, level, curve, sustain]: positional, like a dot.
 *
 * The floats go through their own toString for the same reason velocity does -- widening
 * 0.3f to a double writes 0.30000001192092896 into a file people read with `cat` -- and the
 * sustain is written as 0 or 1 rather than a bool so the row is four numbers and nothing
 * has to know which position changes type.
 */
private fun segmentsOf(module: PatchModule): JSONArray {
    val out = JSONArray()
    module.segments.forEach {
        out.put(
            JSONArray()
                .put(it.time.toString().toDouble())
                .put(it.level.toString().toDouble())
                .put(it.curve.toString().toDouble())
                .put(if (it.sustain) 1 else 0),
        )
    }
    return out
}

private fun restoreSegments(module: PatchModule, stored: JSONArray?) {
    if (stored == null || module.type.grid != GridKind.ENVELOPE) return
    val read = mutableListOf<EnvSegment>()
    for (i in 0 until minOf(stored.length(), MAX_SEGMENTS)) {
        val d = stored.optJSONArray(i) ?: continue
        if (d.length() < 4) continue
        val time = d.optDouble(0, Double.NaN).toFloat()
        val level = d.optDouble(1, Double.NaN).toFloat()
        val curve = d.optDouble(2, Double.NaN).toFloat()
        if (!time.isFinite() || !level.isFinite() || !curve.isFinite()) continue
        read.add(
            EnvSegment(
                time.coerceIn(SEGMENT_MIN_TIME, SEGMENT_MAX_TIME),
                level.coerceIn(0f, 1f),
                curve.coerceIn(-1f, 1f),
                d.optInt(3) != 0,
            ),
        )
    }
    // An envelope with no readable segment keeps the default rather than becoming a module
    // that outputs nothing -- and one segment is the floor the editor enforces too.
    if (read.isEmpty()) return
    module.segments.clear()
    // At most one sustain, which the model guarantees and a hand-edited file may not.
    val first = read.indexOfFirst { it.sustain }
    read.forEachIndexed { i, seg ->
        module.segments.add(if (seg.sustain && i != first) seg.copy(sustain = false) else seg)
    }
}

/**
 * Every stored range, keyed by name like the knobs, each as its low and high: an exposed
 * knob's, and a driven knob's once its brackets have been moved.
 */
private fun modOf(module: PatchModule): JSONObject {
    val out = JSONObject()
    module.modRanges.keys.sorted().forEach { i ->
        val name = module.type.params.getOrNull(i)?.name ?: return@forEach
        val range = module.modRanges.getValue(i)
        out.put(name, JSONArray().put(range.low.toDouble()).put(range.high.toDouble()))
    }
    return out
}

/**
 * Clamped into the parameter's own range, because the file is untrusted: a bracket past the
 * end of a knob would sweep it somewhere the knob itself cannot go. Anything that is not two
 * numbers, or names a parameter that can have no range -- neither exposable nor driven -- is
 * dropped.
 */
private fun restoreMod(module: PatchModule, stored: JSONObject?) {
    if (stored == null) return
    val restored = mutableMapOf<Int, ModRange>()
    module.type.params.forEachIndexed { i, p ->
        val pair = stored.optJSONArray(p.name) ?: return@forEachIndexed
        if (pair.length() != 2 || !(module.canExpose(i) || module.isDriven(i))) return@forEachIndexed
        val lowest = minOf(p.min, p.max)
        val highest = maxOf(p.min, p.max)
        fun bracket(at: Int) = pair.optDouble(at, Double.NaN).toFloat()
            .takeIf { it.isFinite() }?.coerceIn(lowest, highest)
        val low = bracket(0) ?: return@forEachIndexed
        val high = bracket(1) ?: return@forEachIndexed
        restored[i] = ModRange(low, high)
    }
    module.modRanges = restored.toMap()
}

private fun restoreSteps(module: PatchModule, stored: JSONArray?) {
    if (stored == null) return
    for (i in 0 until minOf(stored.length(), module.steps.size)) {
        val s = stored.optJSONObject(i) ?: continue
        module.setStep(i, Step(s.optInt("d", 0), s.optBoolean("on", true)))
    }
}

private fun restoreParams(module: PatchModule, stored: JSONObject?) {
    if (stored == null) return
    module.type.params.forEachIndexed { i, p ->
        if (stored.has(p.name)) {
            module.setParam(i, stored.optDouble(p.name, p.default.toDouble()).toFloat())
        }
    }
}

/** Returns null for anything unreadable, so the caller can fall back to a fresh patch. */
fun patchFromJson(text: String, scales: ScaleLibrary = ScaleLibrary.of(null)): Patch? {
    return try {
        val root = upgrade(JSONObject(text)) ?: return null

        val patch = Patch()
        val stored = root.optJSONArray("scales") ?: JSONArray()
        val entries = (0 until minOf(stored.length(), MAX_SCALE_ENTRIES)).mapNotNull { i ->
            val e = stored.optJSONObject(i) ?: return@mapNotNull null
            ScaleEntry(
                // An unknown name falls back rather than dropping the entry, so a list
                // naming a scale that has since been removed keeps its shape and its timing.
                scales.byName(e.optString("name")) ?: scales.default,
                e.optInt("bars", 4).coerceIn(0, MAX_ENTRY_BARS),
                e.optInt("beats", 0).coerceIn(0, MAX_ENTRY_BEATS),
                // Absent in files saved before keys existed, which were all in C.
                e.optDouble("root", 0.0).toFloat().coerceIn(-TUNE_RANGE, TUNE_RANGE),
            )
        }
        patch.scales = entries.ifEmpty { listOf(ScaleEntry(scales.default)) }
        // Clamped because the file is untrusted: the engine would clamp an absurd tempo
        // too, but then the chip and the sound would disagree about what it is.
        patch.name = root.optString("name").takeIf { it.isNotBlank() }?.take(MAX_NAME)
        patch.tempo = root.optDouble("tempo", TEMPO.default.toDouble()).toFloat()
            .coerceIn(TEMPO.min, TEMPO.max)
        patch.beatsPerBar = root.optInt("beatsPerBar", BEATS_PER_BAR.default.toInt())
            .coerceIn(BEATS_PER_BAR.min.toInt(), BEATS_PER_BAR.max.toInt())

        val modules = root.optJSONArray("modules") ?: JSONArray()
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            val name = m.optString("type")
            val type = Types.byName[name] ?: Types.boxes[name] ?: continue
            if (type.pinned != null) continue // rails already exist; never duplicate them
            val id = m.optLong("id", -1L)
            if (id < 0L || patch.module(id) != null) continue
            val shared = if (type.box) {
                SubpatchPorts().also {
                    it.inputs.addAll(portsFrom(m.optJSONArray("inputs")))
                    it.outputs.addAll(portsFrom(m.optJSONArray("outputs")))
                    it.promoted.addAll(knobsFrom(m.optJSONArray("knobs")))
                }
            } else {
                null
            }
            val module = PatchModule(
                id,
                type,
                Offset(m.optDouble("x", 0.0).toFloat(), m.optDouble("y", 0.0).toFloat()),
                shared,
            )
            module.name = m.optString("name").takeIf { it.isNotBlank() }?.take(MAX_NAME)
            module.font = m.optString("font").takeIf { it.isNotBlank() }
            module.parent = m.optLong("parent", TOP)
            if (shared != null) {
                // The rails come back under the ids the cables inside were saved against.
                for ((key, railType) in listOf("in" to Types.SubpatchIn, "out" to Types.SubpatchOut)) {
                    val railId = m.optLong(key, -1L)
                    if (railId < 0L || patch.module(railId) != null) continue
                    patch.adopt(PatchModule(railId, railType, Offset.Zero, shared).also { it.parent = id })
                }
            }
            restoreParams(module, m.optJSONObject("params"))
            // Absent in files written before sequences were editable, which leaves the
            // module on the same default figure it used to have compiled in.
            restoreSteps(module, m.optJSONArray("steps"))
            restoreDots(module, m.optJSONArray("dots"))
            restoreSegments(module, m.optJSONArray("segments"))
            // Before the cables, which can only land on a parameter already exposed.
            restoreMod(module, m.optJSONObject("mod"))
            patch.adopt(module)
        }

        val rails = root.optJSONArray("rails") ?: JSONArray()
        for (i in 0 until rails.length()) {
            val r = rails.optJSONObject(i) ?: continue
            val module = patch.module(r.optLong("id", -1L)) ?: continue
            if (!module.isPinned) continue
            restoreParams(module, r.optJSONObject("params"))
        }

        // A parent that is not a subpatch in this file, or a loop of subpatches inside each other,
        // puts the module at the top rather than somewhere nothing can reach.
        patch.modules.filter { !it.isPinned && it.parent != TOP }.forEach { module ->
            val seen = mutableSetOf(module.id)
            var at = module.parent
            while (at != TOP) {
                val subpatch = patch.module(at)
                if (subpatch?.type?.box != true || !seen.add(at)) {
                    module.parent = TOP
                    break
                }
                at = subpatch.parent
            }
        }

        val cables = root.optJSONArray("connections") ?: JSONArray()
        for (i in 0 until cables.length()) {
            val c = cables.optJSONObject(i) ?: continue
            val from = patch.portRefOrNull(
                c.optLong("from", -1L), PortDirection.OUTPUT, c.optInt("fromPort", -1)
            ) ?: continue
            val toId = c.optLong("to", -1L)
            val to = if (c.has("toParam")) {
                val index = patch.module(toId)?.type?.params
                    ?.indexOfFirst { it.name == c.optString("toParam") } ?: -1
                patch.portRefOrNull(toId, PortDirection.MOD, index)
            } else {
                patch.portRefOrNull(toId, PortDirection.INPUT, c.optInt("toPort", -1))
            } ?: continue
            // A cable whose ports both still exist but whose kinds now disagree means
            // this file was written while typing was advisory. Refused rather than
            // dropped, because dropping it is silent and permanent: the patch loads
            // looking fine, the next autosave writes it back without that cable, and
            // what it used to do is gone with no record that it ever did it.
            //
            // A port that no longer exists is a different case and still skips, which is
            // what the version 1 migration above depends on -- the cable into Steps' old
            // clock input is meant to disappear quietly, because the module it named is
            // gone too and there is nothing left to be wrong about.
            if (!patch.connect(from, to)) {
                Log.w(TAG, "refusing patch: $from -> $to is not a legal cable")
                return null
            }
        }

        // A subpatch port with nothing on either side goes here too, so a file written before
        // that rule -- or by hand -- opens as clean as an edit leaves it.
        patch.sweepUnusedSubpatchPorts()
        patch
    } catch (e: Exception) {
        Log.w(TAG, "could not read patch", e)
        null
    }
}

/**
 * The file if this build can read it, or null if it cannot.
 *
 * There is no migration any more, and that is the point rather than an omission. Format 5
 * retired the monophonic Osc, the VCA, Filter's cutoff jack and Steps' pitch and gate
 * outputs, and a format 4 file could only have been walked up to it *silently*: a patch
 * built around a VCA an envelope opened would come back as a filter fed by nothing,
 * quieter than it was left, with the load reporting success. So every older file is
 * refused, and [PatchStore.load] moves a refused file to patch.rejected.json rather than
 * letting the empty patch opened in its place overwrite it -- refusing costs the file nothing. Decided
 * 2026-09-15.
 *
 * The ladder that was here walked 1 to 2 to 3 to 4, one step per change, and went with
 * them: every one of those steps ended at a version this build now refuses, so none of
 * them could ever run again. The next format change writes a fresh one, and the shape to
 * copy is in this file's history.
 */
private fun upgrade(root: JSONObject): JSONObject? {
    val version = root.optInt("version", -1)
    // Nothing older, and for the reason every refusal here exists: the conversion would be
    // silent and the patch would be quietly not the one that was saved. A format 8 file
    // names a "Group", which this build reads as a retired type and skips, taking everything
    // inside it. A 9 stores a dot's length in whole steps where this build reads quarter
    // steps, so every note would come back a quarter of its length -- a sequence that still
    // loads, still plays and is not the music that was written.
    if (version !in READABLE) {
        Log.w(TAG, "unsupported patch version $version")
        return null
    }
    return root
}

/** Null unless the module exists and actually has a port at that index and direction. */
private fun Patch.portRefOrNull(moduleId: Long, dir: PortDirection, index: Int): PortRef? {
    val module = module(moduleId) ?: return null
    if (dir == PortDirection.MOD) {
        return if (module.isExposed(index)) PortRef(moduleId, dir, index) else null
    }
    if (index < 0 || index >= module.ports(dir).size) return null
    return PortRef(moduleId, dir, index)
}

class PatchStore(context: Context, private val scales: ScaleLibrary) {
    private val file = File(context.filesDir, "patch.json")
    private val temp = File(context.filesDir, "patch.json.tmp")
    private val rejected = File(context.filesDir, "patch.rejected.json")

    /**
     * Written to a sibling and renamed, so a kill mid-write leaves the previous patch
     * intact rather than a truncated file that loads as nothing.
     */
    fun write(json: String) {
        try {
            temp.writeText(json)
            if (!temp.renameTo(file)) {
                file.writeText(json)
                temp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not save patch", e)
        }
    }

    /**
     * The patch on disk, or null for one this build will not read.
     *
     * A refused file is moved aside rather than left where it is, because the caller's
     * only answer to null is an empty patch -- and the next autosave would write that over
     * the file it just refused. "Refuse" would then mean "destroy", which is not what
     * refusing is for: the point of it is that a file this build cannot read honestly is
     * left alone instead of half-converted.
     *
     * One slot, overwritten each time. Keeping every rejected file would need a policy for
     * clearing them out, and the one worth having back is the one that was just refused.
     */
    fun load(): Patch? =
        try {
            if (!file.exists()) {
                null
            } else {
                val patch = patchFromJson(file.readText(), scales)
                if (patch == null) setAside()
                patch
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not load patch", e)
            null
        }

    private fun setAside() {
        try {
            rejected.delete()
            if (file.renameTo(rejected)) Log.w(TAG, "patch refused; kept at ${rejected.name}")
        } catch (e: Exception) {
            Log.w(TAG, "could not set the refused patch aside", e)
        }
    }
}
