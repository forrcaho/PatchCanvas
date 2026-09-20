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
| `app/src/main/java/io/github/forrcaho/patchcanvas/PatchCanvas.kt` | Model, camera, gestures, drawing |
| `app/src/main/java/io/github/forrcaho/patchcanvas/MainActivity.kt` | Full-bleed host for the canvas |

`rememberDemoPatch()` supplies the starting patch — five modules, five cables.

## Tunings and SoundFonts

Both are files you put on the device, in the app's own folder on external storage --
`Android/data/io.github.forrcaho.patchcanvas/files/` -- which is reachable over USB or
from a file manager.

| Folder | What goes in it |
| --- | --- |
| `scales/` | Scala `.scl` tunings. The bundled ones are seeded here on first run; add your own beside them. |
| `soundfonts/` | SoundFont `.sf2` banks for the **SF** module. Nothing ships with the app. |
| `subpatches/` | Subpatches saved from the app, each a patch file holding one subpatch. |

A good free bank to start with is [GeneralUser GS](https://www.schristiancollins.com/generaluser)
by S. Christian Collins -- 32MB, 261 instruments and 13 drum kits, and its license allows
use in any project. Download it, unzip it, and copy `GeneralUser-GS.sf2` into `soundfonts/`;
the SF module lists it by its file name. SF3 (compressed) banks are not supported.

## License

MIT -- see [LICENSE](LICENSE).

Planned dependencies are permissively licensed and compatible: Oboe (Apache-2.0),
the DaisySP core (MIT, which itself bundles the Plaits and Soundpipe MIT notices)
and TinySoundFont (MIT). The `DaisySP-LGPL` submodule is deliberately *not* used -- clone DaisySP
without `--recursive` -- so nothing here carries a copyleft relinking obligation.
Shipping those notices in an in-app licenses screen is a release requirement, not
a courtesy.
