package com.example.patch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

/**
 * The whole app is one full-bleed canvas. No chrome, no scaffold — anything drawn on
 * top would just be another thing for a finger to land on by accident.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PatchCanvasApp() }
    }
}

@Composable
fun PatchCanvasApp() {
    val patch = rememberDemoPatch()
    PatchCanvas(
        patch = patch,
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
