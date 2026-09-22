package io.github.forrcaho.patchcanvas

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app is one full-bleed canvas. No chrome, no scaffold — anything drawn on
 * top would just be another thing for a finger to land on by accident. The context menu
 * and the I/O rails are drawn inside the canvas for that reason, not above it.
 *
 * (Phase 5 of the roadmap revises that principle deliberately, once modules need knobs.
 * It holds until then.)
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: PatchStore
    private lateinit var scales: ScaleLibrary
    private lateinit var subpatches: SubpatchLibrary
    private lateinit var soundFonts: SoundFontLibrary
    private lateinit var patch: Patch

    // The patch is owned here rather than by the composition so that onStop can save it
    // without reaching into Compose state from a lifecycle callback.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Whether the master output is open. A performance state, not part of the patch. */
    private val outputActive = mutableStateOf(false)

    private val graphSync = GraphSync()

    /**
     * Undo, fed from the autosave debounce below rather than from the edits themselves.
     *
     * That debounce already answers the hard question -- when is an edit finished -- and
     * a continuous knob drag arrives here as one entry instead of three hundred. It also
     * means what is undoable is exactly what is saved, which is the right scope and one
     * that cannot drift: modules, positions, cables and knobs. The camera, the open
     * panel, the master output and the microphone are absent from both, deliberately.
     */
    private val history = History()

    /**
     * Set when a permission grant arrives while the engine is down, and acted on in
     * onResume.
     *
     * Asking for a permission pauses the activity, and onPause stops the audio -- so the
     * grant callback runs when there is no output stream for the microphone to be read
     * alongside. Enabling directly from the callback simply fails.
     */
    private var enableInputOnResume = false

    /**
     * Watches for the output route moving.
     *
     * The speaker guard used to be checked once, when the microphone was switched on --
     * which left the dangerous state one gesture away: take the headphones out while the
     * mic is live and the device is suddenly listening to its own loudspeaker with
     * nothing noticing. A guard that only holds at the moment you pass it is not a guard.
     */
    private val routeWatcher = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = recheckRoute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = recheckRoute()
    }

    private fun recheckRoute() {
        if (patch.inputEnabled && outputIsOnSpeaker()) {
            patch.inputEnabled = false
            AudioEngine.stopInput()
            Toast.makeText(
                this,
                "Mic switched off - output moved to the speaker",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                enableInputOnResume = true
            } else {
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Seeds the bundled .scl files into a folder the user can add to, then reads
        // whatever is there. Before the patch loads, because the patch names a tuning.
        scales = ScaleLibrary.load(this)
        subpatches = SubpatchLibrary.load(this)
        soundFonts = SoundFontLibrary.load(this)
        store = PatchStore(this, scales)
        patch = store.load() ?: demoPatch()

        // Debug only. Armed before the engine starts, because the ring is allocated in
        // start() and freed in stop() -- which is what lets the audio thread write into
        // it without a lock.
        if (BuildConfig.DEBUG) {
            AudioEngine.armCapture(true, File(filesDir, "capture.wav").absolutePath)
        }

        // Ask for the panel's fastest mode. The reference device is 120Hz-capable but
        // idles its render rate at 60, and half the perceived latency of a tap is the
        // visual confirmation — there is no sense chasing 10ms of audio latency later
        // while the screen answers 17ms late.
        display?.supportedModes?.maxOfOrNull { it.refreshRate }?.let { fastest ->
            window.attributes = window.attributes.apply { preferredRefreshRate = fastest }
        }

        // Autosave. Serializing inside the snapshot means the string can never be torn
        // by an edit mid-write, and collectLatest plus a delay debounces the flood of
        // positions a single module drag produces. onStop covers the ordinary exit;
        // this covers being killed without one.
        scope.launch {
            snapshotFlow { patch.toJson() }
                .distinctUntilChanged()
                .collectLatest { json ->
                    delay(SAVE_DEBOUNCE_MS)
                    // Before the write, not after: the buttons should appear the moment
                    // the edit settles, not once the disk has caught up.
                    history.record(json)
                    withContext(Dispatchers.IO) { store.write(json) }
                }
        }

        // Topology, knobs, modulation ranges, sequences, the tuning and the tempo. Position is deliberately absent, so
        // dragging a module around does not re-sync -- the audio graph has no opinion
        // about where a module sits.
        //
        // Everything else the engine cares about has to be read here or it is never
        // sent. This has now caught two features: knobs updated the model, saved to
        // disk, and never reached the engine; and then the step grid did exactly the
        // same, edits landing in the file and in undo while the sound never changed.
        // A list rather than a Triple because the arity kept being the thing that made
        // adding one more feel like a bigger change than it is.
        scope.launch {
            snapshotFlow {
                listOf(
                    patch.modules.map { it.id to it.type.name },
                    // Flattened, as GraphSync reads them: re-subpatching changes the cables in the
                    // patch without changing a single one the engine has, and must not sync.
                    patch.engineConnections(),
                    patch.modules.map { it.params.toList() },
                    // A plain map replaced whole, so it compares by content as it stands.
                    patch.modules.map { it.modRanges },
                    // Steps, dots and segments together: see PatchModule.slotLists, which is
                    // the one place a new slot-indexed list has to be named for this flow.
                    patch.modules.map { it.slotLists },
                    patch.scales,
                    patch.beatsPerBar,
                    patch.tempo,
                    // Which font each SF plays, and which fonts have finished loading: a
                    // module is handed its font by the first sync after both are true.
                    patch.modules.map { it.font },
                    soundFonts.handles(),
                )
            }
                .distinctUntilChanged()
                .collect { graphSync.sync(patch, soundFonts.handles()) }
        }

        // Fonts load when a module names one: parsing a bank is seconds and tens of MB, and a
        // patch with no SF module should pay neither.
        scope.launch {
            snapshotFlow {
                patch.modules.filter { it.type == Types.Sf }.mapNotNull { it.font }.toSet()
            }
                .distinctUntilChanged()
                .collect { names -> names.forEach { launch { soundFonts.ensure(it) } } }
        }

        setContent {
            PatchCanvasApp(
                patch = patch,
                outputActive = outputActive.value,
                onToggleOutput = {
                    val on = !outputActive.value
                    outputActive.value = on
                    AudioEngine.setOutputEnabled(on)
                },
                onToggleInput = { toggleInput() },
                canUndo = history.canUndo,
                canRedo = history.canRedo,
                onUndo = { restore(history.undo()) },
                onRedo = { restore(history.redo()) },
                // Straight to the engine rather than through the patch: where the
                // transport has got to is a performance state, like the output switch,
                // so resetting it is neither saved nor undone.
                onResetTransport = { AudioEngine.resetTransport() },
                scales = scales.scales,
                library = subpatches,
                scaleLibrary = scales,
                soundFonts = soundFonts,
            )
        }
    }

    /**
     * Puts a snapshot back.
     *
     * Through the model, never around it: the edit lands in `Patch`, and the same
     * snapshotFlow that carries an ordinary edit carries this one to `GraphSync`, which
     * diffs it and sends only what actually changed. Undo is not a special case to the
     * engine, and there is no second path that could disagree with the first.
     *
     * A restored state is also the one `History` now considers current, so when it
     * arrives back through the autosave flow it compares equal and records nothing --
     * which is what stops an undo being pushed onto its own stack.
     */
    private fun restore(json: String?) {
        val snapshot = json?.let { patchFromJson(it, scales) } ?: return
        // Pulse whatever moved. At graph level a knob is not drawn at all, so without
        // this an undone parameter is a change in the sound with nothing on screen
        // accounting for it.
        patch.flash(patch.replaceWith(snapshot))
    }

    /**
     * The In rail's switch.
     *
     * Refuses while the output is on the speaker, because that is the one combination
     * that howls: the device's microphone hears the device's own loudspeaker. Into
     * headphones of any kind, wired or Bluetooth, there is no loop to close. The old
     * framing of this guard was "require headphones", which is the same test said less
     * accurately -- what matters is where the sound comes out, not what is plugged in.
     */
    private fun toggleInput() {
        if (patch.inputEnabled) {
            patch.inputEnabled = false
            AudioEngine.stopInput()
            return
        }

        if (outputIsOnSpeaker()) {
            Toast.makeText(
                this,
                "Use headphones before enabling the mic - the speaker would feed back",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) enableInput() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun enableInput() {
        if (AudioEngine.startInput()) {
            patch.inputEnabled = true
            AudioEngine.logInputStatus()
        } else {
            Toast.makeText(this, "Could not open the microphone", Toast.LENGTH_SHORT).show()
        }
    }

    private fun outputIsOnSpeaker(): Boolean {
        val manager = getSystemService(AudioManager::class.java) ?: return true
        val routed = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        // Anything that is not the built-in speaker puts the sound somewhere the
        // microphone cannot hear it well enough to run away.
        val elsewhere = routed.any {
            it.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )
        }
        return !elsewhere
    }

    override fun onResume() {
        super.onResume()
        // Audio lives only while the app is in front. A foreground service comes in
        // Phase 7; until then an instrument that keeps sounding after you leave it
        // would be a bug, not a feature.
        if (AudioEngine.start()) {
            AudioEngine.setOutputEnabled(outputActive.value)
            // The engine rebuilds its graph from empty on every start, so the shadow has
            // to forget too or the first sync would send nothing.
            graphSync.invalidate()
            graphSync.sync(patch)
            scope.launch {
                // The audio thread's tid exists only once the first callback has run,
                // which is a burst or two after requestStart returns.
                delay(HINT_ATTACH_DELAY_MS)
                AudioEngine.attachPerformanceHint()
                AudioEngine.logStatus()
            }

            // A grant that arrived while we were paused: the engine exists again now.
            if (enableInputOnResume) {
                enableInputOnResume = false
                enableInput()
            }
        }

        getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(
            routeWatcher,
            Handler(Looper.getMainLooper()),
        )
    }

    override fun onPause() {
        super.onPause()
        getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(routeWatcher)
        AudioEngine.stopInput()
        patch.inputEnabled = false
        AudioEngine.stop()
        // The stream is gone, so the lit Out rail would be lying.
        outputActive.value = false
    }

    override fun onStop() {
        super.onStop()
        store.write(patch.toJson())
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
        const val HINT_ATTACH_DELAY_MS = 200L
    }
}

@Composable
fun PatchCanvasApp(
    patch: Patch,
    outputActive: Boolean = false,
    onToggleOutput: () -> Unit = {},
    onToggleInput: () -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onResetTransport: () -> Unit = {},
    scales: List<Scale> = listOf(Scale.Chromatic),
    library: SubpatchLibrary? = null,
    scaleLibrary: ScaleLibrary = ScaleLibrary.of(null),
    soundFonts: SoundFontLibrary? = null,
) {
    // The canvas paints edge to edge, but the initial framing keeps the patch clear of
    // the cutout, the gesture bar and the corner radius. Measured on the reference
    // device in landscape: 66dp of cutout down one side, a 24dp gesture bar, and a 50dp
    // corner radius that will clip anything parked in a corner.
    PatchCanvas(
        patch = patch,
        safeArea = WindowInsets.safeDrawing.asPaddingValues(),
        outputActive = outputActive,
        onToggleOutput = onToggleOutput,
        onToggleInput = onToggleInput,
        canUndo = canUndo,
        canRedo = canRedo,
        onUndo = onUndo,
        onRedo = onRedo,
        onResetTransport = onResetTransport,
        scales = scales,
        library = library,
        scaleLibrary = scaleLibrary,
        soundFonts = soundFonts,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14171C)),
    )
}

@Preview(widthDp = 800, heightDp = 400, showBackground = true)
@Composable
private fun PatchCanvasPreview() {
    PatchCanvasApp(rememberDemoPatch())
}
