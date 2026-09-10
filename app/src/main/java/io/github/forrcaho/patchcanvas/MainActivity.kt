package io.github.forrcaho.patchcanvas

import android.os.Bundle
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
    private lateinit var patch: Patch

    // The patch is owned here rather than by the composition so that onStop can save it
    // without reaching into Compose state from a lifecycle callback.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Phase 2 scaffold: whether the test tone is sounding. Not part of the patch. */
    private val outputActive = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        store = PatchStore(this)
        patch = store.load() ?: demoPatch()

        // Ask for the panel's fastest mode. The reference device is 120Hz-capable but
        // idles its render rate at 60, and half the perceived latency of a tap is the
        // visual confirmation — there is no sense chasing 10ms of audio latency later
        // while the screen answers 17ms late.
        display?.supportedModes?.maxOfOrNull { it.refreshRate }?.let { fastest ->
            window.attributes = window.attributes.apply { preferredRefreshRate = fastest }
        }

        // Autosave. Serialising inside the snapshot means the string can never be torn
        // by an edit mid-write, and collectLatest plus a delay debounces the flood of
        // positions a single module drag produces. onStop covers the ordinary exit;
        // this covers being killed without one.
        scope.launch {
            snapshotFlow { patch.toJson() }
                .distinctUntilChanged()
                .collectLatest { json ->
                    delay(SAVE_DEBOUNCE_MS)
                    withContext(Dispatchers.IO) { store.write(json) }
                }
        }

        setContent {
            PatchCanvasApp(
                patch = patch,
                outputActive = outputActive.value,
                onToggleOutput = {
                    val on = !outputActive.value
                    outputActive.value = on
                    AudioEngine.setToneEnabled(on)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Audio lives only while the app is in front. A foreground service comes in
        // Phase 7; until then an instrument that keeps sounding after you leave it
        // would be a bug, not a feature.
        if (AudioEngine.start()) {
            AudioEngine.setToneEnabled(outputActive.value)
            scope.launch {
                // The audio thread's tid exists only once the first callback has run,
                // which is a burst or two after requestStart returns.
                delay(HINT_ATTACH_DELAY_MS)
                AudioEngine.attachPerformanceHint(this@MainActivity)
                AudioEngine.logStatus()
            }
        }
    }

    override fun onPause() {
        super.onPause()
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
