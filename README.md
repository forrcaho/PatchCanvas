# PatchCanvas

A touch-first modular-synth patching canvas for Android, built with Jetpack Compose.

The interaction model departs from the drag-a-cable convention in three ways
(see the comment at the top of `PatchCanvas.kt`):

1. **Tap-to-connect.** Tap an output, then tap an input. No sustained drag with your
   finger covering the target. Tapping empty space or the same port cancels.
2. **Screen-space hit targets.** A port is always ~24dp of glass regardless of zoom,
   so patching still works when zoomed out.
3. **One unified gesture loop.** A single `awaitEachGesture` decides on the first
   move whether the gesture is a tap, a module drag, a pan, or a pinch — rather than
   stacking Compose's built-in detectors, which fight over the same pointer.

## Building

```sh
./gradlew assembleDebug
```

Builds on JDK 25 (the system default) with no `JAVA_HOME` override. Android Studio
uses its own bundled JBR.

Toolchain: AGP 9.3.2, Gradle 9.7.1, Kotlin 2.4.10, Compose BOM 2026.08.00,
compileSdk 37, minSdk 26.

Note that AGP 9 supplies Kotlin support itself, so there is no
`org.jetbrains.kotlin.android` plugin in the build files -- adding one is an error,
not a redundancy. The Compose compiler is applied as
`org.jetbrains.kotlin.plugin.compose`, versioned with Kotlin.

## Layout

| Path | |
| --- | --- |
| `app/src/main/java/com/example/patch/PatchCanvas.kt` | Model, camera, gestures, drawing |
| `app/src/main/java/com/example/patch/MainActivity.kt` | Full-bleed host for the canvas |

`rememberDemoPatch()` supplies the starting patch — five modules, five cables.
