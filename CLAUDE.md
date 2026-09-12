# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

PatchCanvas is a touch-first modular synthesiser for Android: a Compose canvas for
patching, and a C++/Oboe audio engine behind it. `ROADMAP.md` carries the plan, the
phase-by-phase reasoning, and the decisions that were made and reversed — read it before
proposing direction. This file is the operating manual.

## Commands

```sh
./gradlew assembleDebug                 # build
./gradlew testDebugUnitTest             # JVM tests + both native suites
./gradlew nativeGraphTest               # just the C++ suites
./gradlew lint
./gradlew testDebugUnitTest --tests '*GraphSyncTest*'          # one class
./gradlew testDebugUnitTest --tests '*unchanged patch*'        # one test

# Kotlin test names are backtick-quoted sentences, so the filter takes them with
# spaces and no backticks -- a trailing wildcard after the name makes it match nothing.
./gradlew testDebugUnitTest --tests '*GraphSyncTest.an unchanged patch sends nothing'
```

Deploy and drive on a device:

```sh
./gradlew assembleDebug                 # ALWAYS before installing: testDebugUnitTest
                                        # compiles but does not reassemble the APK, so
                                        # installing after a test run ships the previous
                                        # build and the device shows you a bug you fixed
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop io.github.forrcaho.patchcanvas
adb shell am start -n io.github.forrcaho.patchcanvas/.MainActivity
adb logcat -d -s PatchAudio:V      # engine: stream state, latency, xruns
adb logcat -d -s PatchSync:V       # every command crossing to the graph (debug builds)
adb shell run-as io.github.forrcaho.patchcanvas cat files/patch.json
adb shell ls /sdcard/Android/data/io.github.forrcaho.patchcanvas/files/scales   # tunings
adb logcat -d -s PatchScales:V     # which .scl files loaded, and which were skipped
```

`adb shell sleep N` works; a foreground `sleep` on the host does not.

## Architecture

**Two representations of the patch, never the same object.** Kotlin's `Patch` is UI truth
in Compose state; the C++ `Graph` is audio truth on the callback thread. Everything
crosses as POD commands through a lock-free SPSC queue (`spsc_queue.h`).

**`GraphSync` diffs rather than hooks.** It compares a shadow of what the engine has
against the current `Patch` and emits the difference, so one path handles an edit, a file
loaded at launch, and eventually an undo. It is driven by a `snapshotFlow` in
`MainActivity` reading *ids, type names, cables and parameters* — deliberately not
positions. **Anything the engine cares about must be read there or it will never be
sent**; parameters were added to the model without being added to that flow, and the whole
feature was silently inert.

**Two coordinate spaces.** Free modules live in world space (dp) and move with the camera;
the I/O rails and the open module panel live in screen space (px) and do not. A cable can
therefore have one endpoint in each, which is why every cable is resolved through
`portScreen()` and drawn in screen space.

**One gesture loop**, not stacked detectors — Compose's built-in detectors each consume
events and fight over the pointer. `awaitEachGesture` decides once, on the first move,
what a gesture is. The open panel is the exception: it owns the screen, so it has its own
short loop rather than another outcome bolted into the canvas one.

| File | |
| --- | --- |
| `PatchCanvas.kt` | model, camera, gestures, drawing, panel — the bulk of the UI |
| `GraphSync.kt` | the diff, `NodeType` mirror, `GraphCommands` seam for tests |
| `PatchStore.kt` | JSON persistence, hand-rolled on `org.json` |
| `History.kt` | undo as a stack of serialised patches, plus `Patch.replaceWith` |
| `Scale.kt` | the tuning model: degrees in octaves, with a period |
| `ScalaFile.kt` | `.scl` parsing — untrusted input, every bad shape returns null |
| `ScaleLibrary.kt` | seeds the bundled scales and reads the user's folder |
| `graph.{h,cpp}` | command queue, topological sort, crossfades, node lifetime |
| `nodes.{h,cpp}` | the module set, DaisySP-backed |
| `audio_engine.{h,cpp}` | Oboe streams, ADPF, debug capture |

## Invariants

**The audio thread never allocates, frees, blocks, or calls into the JVM.** Nodes are
constructed on the UI thread and only a pointer crosses; retired nodes travel back over a
return queue. A JVM thread can be suspended by the garbage collector, which is why
`minSdk` is 33 — ADPF's per-cycle report needs the NDK's C entry point, and it is the only
call out the audio thread makes.

**World units are dp.** `DrawScope` draws in pixels, so raw floats and `Dp.toPx()` agree
only at density 1.0 — which is where `@Preview` renders and nowhere else. `Camera` folds
density into the single place that converts.

**Ports must never move.** A module's height is fixed; the open panel is screen space
precisely so opening one cannot shift its jacks and make every attached cable jump.

**Cycles are legal.** Whatever cannot be topologically ordered is appended, which costs
exactly one block of delay on the back edge — because output buffers are never cleared
between blocks. That persistence is the mechanism, not an optimisation.

**Every cable change crossfades** (30ms, smoothstep) between two *live* sources. Fading
from a captured value instead stops the waveform dead and glides DC to zero, which is a
thump rather than a click. A removed node therefore lingers until the fades reading it
finish.

**Undo restores through the model, in one snapshot.** A snapshot is the autosave JSON;
restoring it parses back to a `Patch` and `replaceWith` copies it into the live one, so
the ordinary `snapshotFlow` -> `GraphSync` path carries it to the engine and undo is not a
special case there. `replaceWith` wraps the whole replacement in
`Snapshot.withMutableSnapshot` because the observer would otherwise see the moment after
the cables are cleared, and the engine renders an empty patch faithfully -- fading every
voice out and back in. **Anything that changes what `toJson` emits changes what is
undoable**, which is the intent; the byte-identical round trip is what stops `History`
from recording an undo as a new edit, and `ReplaceWithTest` asserts it.

**Compose's state collections are not their plain equivalents.** `SnapshotStateList`
does not implement structural equality, so `a.params == b.params` is an identity check
that is always false -- which made every module in the patch pulse on every undo.
`toList()` both sides before comparing. Treat any `==` on a snapshot collection as a bug
until proven otherwise.

**State read inside the gesture loop must go through `rememberUpdatedState`.** The
`pointerInput(Unit)` block captures its closure exactly once and never restarts, so a
plain value captured there is frozen at whatever it was during the first composition. The
draw lambda is rebuilt every recomposition and has no such problem -- which is the trap:
the undo buttons drew correctly and were simply not hittable, because the hit test was
still reading `canUndo == false` from launch. Callbacks are safe (they delegate); values
are not.

**Tuning lives on one side of the boundary.** Pitch crosses as octaves
(`hz = root * 2^octaves`), so the engine never learns what a semitone is and an arbitrary
scale costs nothing. Degrees become octaves in exactly one place — `GraphSync` — and
everything downstream is tuning-agnostic by construction. Scales are `.scl` files seeded
into `getExternalFilesDir/scales`, where a user can add their own; `Scale.Chromatic` is
the only one defined in code, and exists so the app still works when that folder is
unreadable. **`Steps.transp` is still declared in semitones and quietly assumes 12-TET** —
it is the one thing left that does.

**Signal types are advisory.** Audio, CV and gate colour the cable and the port; any
output may patch to any input. In hardware it is all voltage, and audio-rate modulation
lives in exactly the connections enforcement would forbid. Do not "fix" this.

**Inputs take one source.** A connect already replaces, so a replacement must send only
the connect — sending a disconnect too makes the engine fade to silence and back, which
steps. This is why `Mix` exists and why there is no `Mult`: outputs already fan out.

## Vendored DaisySP

`app/src/main/cpp/vendor/daisysp` is a copied subset, MIT, documented in its own README.
**Do not convert it to a submodule**: DaisySP carries `DaisySP-LGPL` as *its* submodule, so
`git clone --recursive` would pull the LGPL half and acquire a relinking obligation. Clone
upstream without `--recursive` when updating. It builds as its own CMake target with
`-w` and is included as `SYSTEM`, so upstream is held to upstream's warning settings.

## Testing

Three suites, all run by `testDebugUnitTest`: JVM tests, `graph_test` and `node_test`. The
C++ suites compile on the host under **ASan and UBSan** because `graph.cpp` and `nodes.cpp`
depend on nothing from Android or Oboe; they are skipped where there is no host compiler.

**Mutation-check a new test area.** Reintroduce the bug it should catch and confirm it
fails. This found a missing `Graph` destructor, a declick ramp that still clicked, and a
frequency measurement that reported 493, 411, 313 or 259 cycles for the same signal
depending only on a threshold.

**Tests confirm fixes; they have not once found the bug.** Every defect that mattered —
the invisible rail highlight, four separate transients, the mic permission race, knobs
never reaching the engine — was found by a person using it on hardware, with a clean
compile and a green suite. Verify on the device, and say plainly when something has not
been.

Cross-boundary contracts are asserted rather than trusted: `NodeType` mirrors the C++
enum, `MAX_PORTS`/`MAX_PARAMS` mirror `kMaxPorts`/`kMaxParams`. A mismatch there fails
silently in production.

## Diagnosing audio

Debug builds keep a rolling 10s capture of exactly what reaches the stream, written on
stop. Play, background the app, then:

```sh
adb exec-out run-as io.github.forrcaho.patchcanvas cat files/capture.wav > /tmp/c.wav
python3 tools/find_clicks.py /tmp/c.wav          # discontinuities + boundary alignment
python3 ~/musicode/rust/cursive/tools/audio_analyze.py /tmp/c.wav --png /tmp/s.png
```

`find_clicks.py` reports whether events land on the inner block (32), device burst (96) or
stream buffer (192) — on a boundary implicates the plumbing, irregular implicates the DSP.
Two clean captures have now shown the engine innocent of clicks that were real to the ear;
the reference device listens over **Bluetooth A2DP**, where packet loss sounds exactly
like that.

**`latencyMs` from the engine is the AAudio stream's, not end to end.** Bluetooth adds
100ms or more it cannot see. Exclusive MMAP at a 96-frame burst is real, but it is the
local leg.

## Working agreements

- Commit and push straight to `main`; this is a single-developer repo. Branch only if that
  changes, or for genuinely independent work.
- Commit messages explain *why*, including approaches tried and rejected — the history is
  a design record, and several entries exist to stop a wrong idea being re-derived.
- `ROADMAP.md` is updated alongside the code, including where the plan turned out wrong.
- Device verification needs the phone unlocked; GrapheneOS cuts USB data when it locks,
  and `adb install -r` force-stops the app (which is how a persisted-mic-state bug was
  first seen).
