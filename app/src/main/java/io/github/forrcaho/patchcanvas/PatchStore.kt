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

private const val FORMAT_VERSION = 1
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
        )
    }

    val cables = JSONArray()
    connections.forEach { c ->
        // connect() normalises every cable to output -> input, so the directions are an
        // invariant rather than data, and reload re-imposes them.
        cables.put(
            JSONObject()
                .put("from", c.from.moduleId)
                .put("fromPort", c.from.index)
                .put("to", c.to.moduleId)
                .put("toPort", c.to.index)
        )
    }

    return JSONObject()
        .put("version", FORMAT_VERSION)
        .put("inputEnabled", inputEnabled)
        .put("modules", modules)
        .put("connections", cables)
        .toString()
}

/** Returns null for anything unreadable, so the caller can fall back to a fresh patch. */
fun patchFromJson(text: String): Patch? {
    return try {
        val root = JSONObject(text)
        if (root.optInt("version", -1) != FORMAT_VERSION) {
            Log.w(TAG, "unsupported patch version ${root.optInt("version", -1)}")
            return null
        }

        val patch = Patch()

        val modules = root.optJSONArray("modules") ?: JSONArray()
        for (i in 0 until modules.length()) {
            val m = modules.optJSONObject(i) ?: continue
            val type = Types.byName[m.optString("type")] ?: continue
            if (type.pinned != null) continue // rails already exist; never duplicate them
            val id = m.optLong("id", -1L)
            if (id < 0L || patch.module(id) != null) continue
            patch.adopt(
                PatchModule(
                    id,
                    type,
                    Offset(m.optDouble("x", 0.0).toFloat(), m.optDouble("y", 0.0).toFloat()),
                )
            )
        }

        val cables = root.optJSONArray("connections") ?: JSONArray()
        for (i in 0 until cables.length()) {
            val c = cables.optJSONObject(i) ?: continue
            val from = patch.portRefOrNull(
                c.optLong("from", -1L), PortDirection.OUTPUT, c.optInt("fromPort", -1)
            ) ?: continue
            val to = patch.portRefOrNull(
                c.optLong("to", -1L), PortDirection.INPUT, c.optInt("toPort", -1)
            ) ?: continue
            patch.connect(from, to)
        }

        patch.inputEnabled = root.optBoolean("inputEnabled", false)
        patch
    } catch (e: Exception) {
        Log.w(TAG, "could not read patch", e)
        null
    }
}

/** Null unless the module exists and actually has a port at that index and direction. */
private fun Patch.portRefOrNull(moduleId: Long, dir: PortDirection, index: Int): PortRef? {
    val module = module(moduleId) ?: return null
    if (index < 0 || index >= module.ports(dir).size) return null
    return PortRef(moduleId, dir, index)
}

class PatchStore(context: Context) {
    private val file = File(context.filesDir, "patch.json")
    private val temp = File(context.filesDir, "patch.json.tmp")

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

    fun load(): Patch? =
        try {
            if (file.exists()) patchFromJson(file.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "could not load patch", e)
            null
        }
}
