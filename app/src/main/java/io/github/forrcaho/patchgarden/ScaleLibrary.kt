package io.github.forrcaho.patchgarden

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The tunings available to a patch, read from `.scl` files on disk.
 *
 * They live under the app's external files directory, which is reachable over USB and
 * from any file manager without asking for a permission -- so adding a tuning is copying
 * a file into a folder, which is how people already have them. Nothing writes them from
 * inside the app: editing a scale on a phone is nobody's idea of a good time, and the
 * format exists precisely so that work can be done elsewhere.
 *
 * The bundled set is seeded into that folder rather than kept hidden in the APK, so the
 * folder is never empty and confusing, and so the shipped files double as the worked
 * examples for anyone writing their own. Seeding only fills in what is absent: a file
 * the user has edited is theirs, and an upgrade must not overwrite it.
 */
class ScaleLibrary private constructor(val directory: File?, scales: List<Scale>) {

    /**
     * Always at least one, and always including the fallback: a patch has to be able to
     * name a tuning even if the storage is unreadable.
     */
    val scales: List<Scale> = scales.ifEmpty { listOf(Scale.Chromatic) }

    fun byName(name: String): Scale? = scales.firstOrNull { it.name == name }

    /** The one a patch falls back to when its named scale is missing. */
    val default: Scale get() = byName(Scale.Chromatic.name) ?: scales.first()

    companion object {
        private const val TAG = "PatchScales"
        private const val ASSET_DIR = "scales"
        const val EXTENSION = ".scl"

        fun load(context: Context): ScaleLibrary {
            val dir = scaleDir(context)
            if (dir != null) seed(context, dir)
            return ScaleLibrary(dir, readAll(dir))
        }

        /** For tests and previews: whatever is in a directory, with no seeding. */
        fun of(dir: File?): ScaleLibrary = ScaleLibrary(dir, readAll(dir))

        private fun scaleDir(context: Context): File? = try {
            // getExternalFilesDir is null when the volume is unavailable, which is rare
            // and survivable: the app falls back to internal storage and the user simply
            // cannot drop files in.
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(base, "scales").apply { mkdirs() }
        } catch (e: Exception) {
            Log.w(TAG, "no scale directory", e)
            null
        }

        private fun seed(context: Context, dir: File) {
            try {
                context.assets.list(ASSET_DIR).orEmpty().forEach { name ->
                    val target = File(dir, name)
                    if (target.exists()) return@forEach
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not seed scales", e)
            }
        }

        private fun readAll(dir: File?): List<Scale> {
            val files = dir?.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION, true) }
                ?: return emptyList()
            return files
                .sortedBy { it.name.lowercase() }
                .mapNotNull { file ->
                    val name = file.name.dropLast(EXTENSION.length)
                    val scale = try {
                        parseScala(name, file.readText())
                    } catch (e: Exception) {
                        Log.w(TAG, "could not read ${file.name}", e)
                        null
                    }
                    // A bad file is skipped rather than fatal, and says so: the folder is
                    // user-writable, so a malformed scale is a thing that will happen.
                    if (scale == null) Log.w(TAG, "ignoring ${file.name}: not a usable scale")
                    scale
                }
                // Two files claiming one name would make the stored name ambiguous.
                .distinctBy { it.name }
        }
    }
}
