package io.github.forrcaho.patchcanvas

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * SoundFonts: the ones the user puts in `soundfonts`, beside the scales and the subpatches.
 *
 * None ship with the app. GeneralUser GS was bundled for a day and taken out on 2026-09-19:
 * it is 32MB of the download for a file anyone who wants an SF module can fetch themselves,
 * and the README says where. So an SF module starts with no bank and asks for one.
 *
 * A font is loaded once, on a background thread -- parsing a bank converts every sample to
 * float, which for a large one is seconds on a phone -- and kept for the life of the
 * process. Every SF module playing it gets its own synth over the one copy of the samples,
 * built natively when GraphSync hands the module its font.
 */

private const val TAG = "PatchSoundFonts"
private const val EXTENSION = ".sf2"

/** One of a font's instruments, as the SF module's preset knob stores it. */
data class SoundFontPreset(val bank: Int, val program: Int, val name: String) {
    /** What the preset parameter holds: bank and program in one number the engine splits. */
    val code: Int get() = bank * 128 + program
}

/** A font the engine has parsed: its handle, and its instruments in the order a list shows them. */
class LoadedFont(val handle: Long, val presets: List<SoundFontPreset>) {
    fun presetFor(code: Int): SoundFontPreset? = presets.firstOrNull { it.code == code }
}

/** The preset parameter's value for [bank] and [program]. */
fun presetCode(bank: Int, program: Int): Int = bank * 128 + program

class SoundFontLibrary(val directory: File?) {

    /**
     * Fonts loaded so far, by name. Compose state, because GraphSync's flow reads it: a
     * module whose font finishes loading has to be handed it, and nothing else would say so.
     */
    val loaded = mutableStateMapOf<String, LoadedFont>()

    /**
     * Names that failed to load, so a bad file is tried once rather than on every sync.
     * State as well, so a panel saying "Loading" changes its mind when the load gives up.
     */
    private val failed = mutableStateListOf<String>()
    private val loading = mutableSetOf<String>()

    /** Every `.sf2` in the folder, by name. */
    fun names(): List<String> =
        directory?.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION, true) }
            .orEmpty()
            .map { it.name.dropLast(EXTENSION.length) }
            .sortedBy { it.lowercase() }

    /** Where to put them, for a panel with none to show. */
    fun folder(): String = directory?.absolutePath ?: "the soundfonts folder"

    /** Whether [name] was tried and could not be read. */
    fun failed(name: String): Boolean = name in failed

    /** The handles GraphSync hands to modules, by font name. */
    fun handles(): Map<String, Long> = loaded.mapValues { it.value.handle }

    /**
     * Loads [name] if it is not loaded, loading, or known to be bad. Suspends through the
     * parse on the IO dispatcher and publishes the result on the caller's.
     */
    suspend fun ensure(name: String) {
        if (name in loaded || name in loading || name in failed) return
        loading += name
        val font = withContext(Dispatchers.IO) { read(name)?.let { parse(name, it) } }
        loading -= name
        if (font == null) failed.add(name) else loaded[name] = font
    }

    private fun read(name: String): ByteArray? = try {
        directory?.let { File(it, name + EXTENSION) }?.takeIf { it.isFile }?.readBytes()
    } catch (e: Exception) {
        Log.w(TAG, "could not read $name", e)
        null
    }

    private fun parse(name: String, bytes: ByteArray): LoadedFont? {
        val started = System.nanoTime()
        val handle = AudioEngine.loadSoundFont(bytes)
        if (handle == 0L) {
            // SF3, a truncated download, or not a SoundFont at all. Said once, in the log.
            Log.w(TAG, "$name is not a SoundFont this build can read")
            return null
        }
        val presets = AudioEngine.soundFontPresets(handle)
            .sortedWith(compareBy({ it.bank }, { it.program }))
        Log.i(TAG, "$name: ${presets.size} presets in ${(System.nanoTime() - started) / 1_000_000}ms")
        return LoadedFont(handle, presets)
    }

    companion object {
        /**
         * The folder is `soundfonts` beside `scales` and `subpatches`, made here so it is there
         * to drop files into over USB.
         */
        fun load(context: Context): SoundFontLibrary = SoundFontLibrary(
            try {
                val base = context.getExternalFilesDir(null) ?: context.filesDir
                File(base, "soundfonts").apply { mkdirs() }
            } catch (e: Exception) {
                Log.w(TAG, "no soundfont directory", e)
                null
            },
        )
    }
}
