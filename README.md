# PatchGarden

A touch-first modular synthesizer for Android, built with Jetpack Compose.

What it is built around: a patch nests inside a patch nests inside a patch. A
**subpatch** is a box holding a patch of its own, with the same rails and the same
canvas inside it, and a **poly subpatch** is one the engine stamps out a copy of per
note -- monophonic on the inside, polyphonic from outside. A subpatch is meant to be
the first thing you reach for rather than a way to tidy up afterwards: make an empty one
from the add menu and build inside it, or collapse what is already on the canvas into
one. [`ROADMAP.md`](ROADMAP.md) opens with where the design stands. The app was called
PatchCanvas while the canvas was the idea, and briefly PatchMatryoshka for the nesting.
Since 2026-09-23 it is `io.github.forrcaho.patchgarden`, so Android treats it as a
different app from PatchCanvas: an installed PatchCanvas (v0.1.0 or a debug build)
will not update into it, and the two install side by side.

## Trying it

Download the APK from the latest [release](https://github.com/forrcaho/PatchGarden/releases)
and install it -- Android will ask you to allow installs from the app you downloaded it
with. It needs Android 13 or later, and it runs in landscape.

- **Sound starts off.** Tap the **Out** rail on the right edge to switch it on, and again to
  switch it off.
- **Long-press empty canvas** for the add menu. Long-press a module for its own menu:
  duplicate, rename, delete.
- **Patch by tapping**: tap an output, then an input. Tap an input that is already patched,
  then the same one again, to unplug it.
- **Tap a module** to open its panel, and tap outside the panel to close it. A number on a
  panel can be tapped to type it.

A first sound: add a **Drone**, an **Osc** and nothing else; patch the Drone's `notes` into
the Osc, and the Osc's `out` into both of Out's inputs; switch Out on; open the Drone and tap
a cell to hold a note. From there, **Poly** in the add menu is a box you build one voice in
and play several of -- an Osc, an Env and the Amp the Env opens is the usual inside.

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
compileSdk 37, minSdk 33.

Note that AGP 9 supplies Kotlin support itself, so there is no
`org.jetbrains.kotlin.android` plugin in the build files -- adding one is an error,
not a redundancy. The Compose compiler is applied as
`org.jetbrains.kotlin.plugin.compose`, versioned with Kotlin.

## Layout

| Path | |
| --- | --- |
| `app/src/main/java/io/github/forrcaho/patchgarden/PatchCanvas.kt` | Model, camera, gestures, drawing |
| `app/src/main/java/io/github/forrcaho/patchgarden/MainActivity.kt` | Full-bleed host for the canvas |

A new install, or a patch this version cannot read, opens on an empty canvas with only the
In and Out rails. Every synth but the SoundFont player is monophonic; polyphony is the box
around it.

## Tunings and SoundFonts

Both are files you put on the device, in the app's own folder on external storage --
`Android/data/io.github.forrcaho.patchgarden/files/` -- which is reachable over USB or
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
Their notices ship with every copy: in the APK as
[`THIRD_PARTY_NOTICES.txt`](app/src/main/assets/THIRD_PARTY_NOTICES.txt), and beside the APK on
each release. A screen in the app that shows them is still to come.
