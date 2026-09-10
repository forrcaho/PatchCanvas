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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * The whole app is one full-bleed canvas. No chrome, no scaffold — anything drawn on
 * top would just be another thing for a finger to land on by accident.
 *
 * (Phase 5 of the roadmap revises that principle deliberately, once modules need knobs.
 * It holds until then.)
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Ask for the panel's fastest mode. The reference device is 120Hz-capable but
        // idles its render rate at 60, and half the perceived latency of a tap is the
        // visual confirmation — there is no sense chasing 10ms of audio latency later
        // while the screen answers 17ms late.
        display?.supportedModes?.maxOfOrNull { it.refreshRate }?.let { fastest ->
            window.attributes = window.attributes.apply { preferredRefreshRate = fastest }
        }

        setContent { PatchCanvasApp() }
    }
}

@Composable
fun PatchCanvasApp() {
    val patch = rememberDemoPatch()
    // The canvas paints edge to edge, but the initial framing keeps the patch clear of
    // the cutout, the gesture bar and the corner radius. Measured on the reference
    // device in landscape: 66dp of cutout down one side, a 24dp gesture bar, and a 50dp
    // corner radius that will clip anything parked in a corner.
    PatchCanvas(
        patch = patch,
        safeArea = WindowInsets.safeDrawing.asPaddingValues(),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14171C)),
    )
}

@Preview(widthDp = 800, heightDp = 400, showBackground = true)
@Composable
private fun PatchCanvasPreview() {
    PatchCanvasApp()
}
