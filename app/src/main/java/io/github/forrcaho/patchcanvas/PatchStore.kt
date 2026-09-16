package io.github.forrcaho.patchcanvas

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
 */
private const val FORMAT_VERSION = 4
private const val TAG = "PatchStore"

fun Patch.toJson(): String {
    val modules = JSONArray()
    free.forEach { m ->
        modules.put(
            JSONObject()
                .put("id", m.id)
                .put("type", m.type.name)
                .put("x", m.position.x.toDouble())
                .put("y", m.position.y.toDouble())
                .put("params", paramsOf(m))
                .put("steps", stepsOf(m))
                .put("mod", modOf(m))
        )
    }

    // The rails hold knobs too -- output level, microphone gain -- and those are part of
    // the patch even though the rails' geometry is not.
    val rails = JSONArray()
    pinned.filter { it.type.params.isNotEmpty() }.forEach { m ->
        rails.put(JSONObject().put("id", m.id).put("params", paramsOf(m)))
    }

    val cables = JSONArray()
    connections.forEach { c ->
        // connect() normalises every cable to output -> input, so the directions are an
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
        .put("modules", modules)
        .put("rails", rails)
        .put("connections", cables)
        .put("scales", scaleList)
        .put("tempo", tempo.toDouble())
        .put("beatsPerBar", beatsPerBar)
        .toString()
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

/** Exposed parameters, keyed by name like the knobs, each as its low and high. */
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
 * numbers, or names a parameter that cannot be exposed, is dropped.
 */
private fun restoreMod(module: PatchModule, stored: JSONObject?) {
    if (stored == null) return
    val restored = mutableMapOf<Int, ModRange>()
    module.type.params.forEachIndexed { i, p ->
        val pair = stored.optJSONArray(p.name) ?: return@forEachIndexed
        if (pair.length() != 2 || !module.canExpose(i)) return@forEachIndexed
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
        patch.tempo = root.optDouble("tempo", TEMPO.default.toDouble()).toFloat()
            .coerceIn(TEMPO.min, TEMPO.max)
        patch.beatsPerBar = root.optInt("beatsPerBar", BEATS_PER_BAR.default.toInt())
            .coerceIn(BEATS_PER_BAR.min.toInt(), BEATS_PER_BAR.max.toInt())

        val modules = root.optJSONArray("modules") ?: JSONArray()
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            val type = Types.byName[m.optString("type")] ?: continue
            if (type.pinned != null) continue // rails already exist; never duplicate them
            val id = m.optLong("id", -1L)
            if (id < 0L || patch.module(id) != null) continue
            val module = PatchModule(
                id,
                type,
                Offset(m.optDouble("x", 0.0).toFloat(), m.optDouble("y", 0.0).toFloat()),
            )
            restoreParams(module, m.optJSONObject("params"))
            // Absent in files written before sequences were editable, which leaves the
            // module on the same default figure it used to have compiled in.
            restoreSteps(module, m.optJSONArray("steps"))
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

        patch
    } catch (e: Exception) {
        Log.w(TAG, "could not read patch", e)
        null
    }
}

/**
 * Brings an older file up to the current format, or returns null for one this build
 * cannot read -- a newer format, or no version at all.
 *
 * One step per format change, applied in order, so each step only has to know the format
 * immediately before it and a file several versions old walks up through all of them.
 */
private fun upgrade(root: JSONObject): JSONObject? {
    var version = root.optInt("version", -1)

    if (version == 1) {
        // The Clock module became the transport. Only its tempo needs moving: the module
        // itself is no longer a type and is skipped like any unknown one, and the cable
        // into Steps' old clock input points at a port that no longer exists, so it is
        // dropped by the same check that drops any out-of-range cable.
        val modules = root.optJSONArray("modules") ?: JSONArray()
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            val bpm = m.optJSONObject("params")?.takeIf { m.optString("type") == "Clock" && it.has("bpm") }
                ?: continue
            root.put("tempo", bpm.optDouble("bpm"))
            break
        }
        version = 2
    }

    if (version == 2) {
        // One scale became a list of them. The scale there was becomes the only entry,
        // whose length does not matter while it is the only one.
        val name = root.optString("scale", Scale.Chromatic.name)
        root.put(
            "scales",
            JSONArray().put(JSONObject().put("name", name).put("bars", 4).put("beats", 0)),
        )
        root.remove("scale")
        version = 3
    }

    if (version == 3) {
        // Modulation arrived. Nothing to convert: a file from before it has no parameter
        // exposed, which is what an absent "mod" already reads as.
        version = 4
    }

    if (version != FORMAT_VERSION) {
        Log.w(TAG, "unsupported patch version ${root.optInt("version", -1)}")
        return null
    }
    return root
}

/** Null unless the module exists and actually has a port at that index and direction. */
private fun Patch.portRefOrNull(moduleId: Long, dir: PortDirection, index: Int): PortRef? {
    val module = module(moduleId) ?: return null
    if (dir == PortDirection.MOD) {
        return if (index in module.modRanges) PortRef(moduleId, dir, index) else null
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
     * only answer to null is the demo patch -- and the next autosave would write that over
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
