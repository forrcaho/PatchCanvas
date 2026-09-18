package io.github.forrcaho.patchcanvas

import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Offset
import java.io.File

/*
 * The group library: groups saved to files, and loaded back into any patch.
 *
 * A saved group *is a patch file* -- one holding a single top-level group and whatever is
 * inside it. That is the whole design. The alternative was a format of its own, and it
 * would have had its own module serialization, its own validation and its own refusal
 * rules, all of them a copy of the ones next door that a format change would then have to
 * be made in twice. Reading one back goes through `patchFromJson`, so a group file gets the
 * same treatment a patch does: a type that no longer exists is skipped, a cable whose kinds
 * disagree refuses the whole file, and a version this build cannot read is refused rather
 * than migrated.
 *
 * Loading makes a copy, never a link. Forrest chose that on 2026-09-17: a linked instance
 * that changes in every patch when you edit the definition is the more powerful idea and
 * the more confusing one, and nothing here can yet show you where a definition is used.
 */

/** The file extension for a saved group. Plain JSON, and a patch file if you rename it. */
private const val GROUP_EXTENSION = ".json"
private const val TAG = "PatchGroups"

/**
 * This group and everything inside it, as a patch holding nothing else.
 *
 * The copy is made into a throwaway patch first rather than written straight out, so that
 * exactly one routine knows how a group is copied -- the same one that duplicates it.
 */
fun Patch.groupToJson(group: PatchModule, name: String? = group.name): String? {
    if (group.type != Types.Group) return null
    val lone = Patch()
    // [name] is what the saved copy is called, which is not always what the group in the
    // patch is called: saving "Filt Osc" to the library as "Bass voice" must not rename
    // the one you are still playing.
    lone.adoptGroup(this, group, Offset.Zero, TOP, name)
    return lone.toJson()
}

/**
 * The whole patch as one saved group, without disturbing the patch.
 *
 * Grouping everything is what this means -- the cables into Out become the group's outputs
 * and the ones out of In its inputs, which is exactly what [Patch.group] does with any
 * selection -- so it groups, serializes, and ungroups again. Every step of that is already
 * silent to the engine and the whole thing happens inside one snapshot, so nothing observes
 * the patch mid-flight: the autosave sees the state it started in and records nothing.
 */
fun Patch.patchToGroupJson(name: String): String? {
    val ids = modules.filter { !it.isPinned && it.parent == TOP }.map { it.id }.toSet()
    if (ids.isEmpty()) return null
    var json: String? = null
    androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
        val group = group(ids) ?: return@withMutableSnapshot
        group.name = name
        json = groupToJson(group)
        ungroup(group)
    }
    return json
}

/**
 * The group in a saved file, copied into this patch at [at], or null if the file does not
 * hold exactly one group.
 *
 * The ids in the file are the ones it was saved with and are never reused here: [adoptGroup]
 * allocates fresh ones, so loading the same group twice gives two independent groups.
 */
fun Patch.loadGroup(
    text: String,
    at: Offset,
    scales: ScaleLibrary = ScaleLibrary.of(null),
): PatchModule? {
    val source = patchFromJson(text, scales) ?: return null
    val loose = source.modules.filter { !it.isPinned && it.parent == TOP }
    val group = loose.singleOrNull()?.takeIf { it.type == Types.Group } ?: run {
        Log.w(TAG, "not a saved group: ${loose.size} modules at the top level")
        return null
    }
    return adoptGroup(source, group, at, scopeOrTop)
}

/**
 * The saved groups on disk, beside the scales.
 *
 * In `getExternalFilesDir/groups` at Forrest's choice, where the `.scl` files already live:
 * a saved group is something to copy off the phone, mail to someone, or drop in by hand,
 * and none of that is possible in app-private storage.
 */
class GroupLibrary(val directory: File?) {

    /** What is saved, by name, in the order a list should show them. */
    fun names(): List<String> =
        directory?.listFiles { f -> f.isFile && f.name.endsWith(GROUP_EXTENSION, true) }
            .orEmpty()
            .map { it.name.dropLast(GROUP_EXTENSION.length) }
            .sortedBy { it.lowercase() }

    fun exists(name: String): Boolean = fileFor(name)?.exists() == true

    fun read(name: String): String? = try {
        fileFor(name)?.takeIf { it.isFile }?.readText()
    } catch (e: Exception) {
        Log.w(TAG, "could not read group $name", e)
        null
    }

    /** Writes [json] under [name], replacing what is there. Returns whether it landed. */
    fun write(name: String, json: String): Boolean = try {
        val file = fileFor(name)
        if (file == null) {
            false
        } else {
            directory?.mkdirs()
            file.writeText(json)
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not save group $name", e)
        false
    }

    /**
     * [name] if it is free, or the first "name 2", "name 3" that is.
     *
     * What "Keep both" saves under, so that saving twice under one name never quietly
     * replaces the first -- the same reason a refused patch file is moved aside rather
     * than written over.
     */
    fun freeName(name: String): String {
        if (!exists(name)) return name
        var n = 2
        while (exists("$name $n") && n < 100) n++
        return "$name $n"
    }

    private fun fileFor(name: String): File? {
        val safe = safeName(name)
        return if (safe.isEmpty() || directory == null) null else File(directory, safe + GROUP_EXTENSION)
    }

    companion object {
        /**
         * A file name that cannot escape the folder or upset a file manager.
         *
         * The display name lives inside the file, on the group itself, so this only has to
         * be a stable handle -- "Bass/Lead" saving as "Bass_Lead" loses nothing you can see.
         */
        fun safeName(name: String): String =
            name.map { if (it.isLetterOrDigit() || it in " _-") it else '_' }
                .joinToString("")
                .trim()
                .take(MAX_NAME)

        fun load(context: Context): GroupLibrary = GroupLibrary(
            try {
                val base = context.getExternalFilesDir(null) ?: context.filesDir
                File(base, "groups").apply { mkdirs() }
            } catch (e: Exception) {
                Log.w(TAG, "no group directory", e)
                null
            },
        )
    }
}
