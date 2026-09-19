package io.github.forrcaho.patchcanvas

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * SoundFonts: the one the app ships, and any the user drops in beside the scales.
 *
 * A font is loaded once, on a background thread -- parsing GeneralUser GS converts 32MB of
 * samples to float, which is seconds on a phone -- and kept for the life of the process.
 * Every SF module playing it gets its own synth over the one copy of the samples, built
 * natively when GraphSync hands the module its font.
 */

/** The bank the app ships, and what a new SF module plays. */
const val DEFAULT_SOUNDFONT = "GeneralUser GS"

private const val TAG = "PatchSoundFonts"
private const val ASSET_DIR = "soundfonts"
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

class SoundFontLibrary(private val assets: AssetManager?, val directory: File?) {

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

    /** Every font there is, the shipped one first and then the user's, by name. */
    fun names(): List<String> {
        val bundled = try {
            assets?.list(ASSET_DIR).orEmpty().filter { it.endsWith(EXTENSION, true) }
        } catch (e: Exception) {
            emptyList()
        }.map { it.dropLast(EXTENSION.length) }
        val own = directory?.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION, true) }
            .orEmpty()
            .map { it.name.dropLast(EXTENSION.length) }
            .sortedBy { it.lowercase() }
        return (bundled + own).distinct()
    }

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
        val own = directory?.let { File(it, name + EXTENSION) }?.takeIf { it.isFile }
        own?.readBytes() ?: assets?.open("$ASSET_DIR/$name$EXTENSION")?.use { it.readBytes() }
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
         * The user's folder is `soundfonts` beside `scales` and `groups`, made here so it is
         * there to drop files into. The shipped bank is read from the APK rather than copied
         * out: it is 32MB, and a copy would double what the app takes on the phone.
         */
        fun load(context: Context): SoundFontLibrary = SoundFontLibrary(
            context.assets,
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
