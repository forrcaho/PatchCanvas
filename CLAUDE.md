# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

PatchCanvas is a touch-first modular synthesizer for Android: a Compose canvas for
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
`MainActivity` reading *ids, type names, cables, parameters and modulation ranges* — deliberately not
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
short loop rather than another outcome bolted into the canvas one. Renaming is the other:
text entry needs a real `BasicTextField` for its cursor, selection and IME, so it is a
composable on a scrim over the `Canvas`, and so is the number keypad -- the only two in
the app, and the reason `PatchCanvas` is wrapped in a `Box`. The keypad draws its own keys
rather than asking for a numeric IME, which would resize the window and slide the panel
being edited out from under it.

| File | |
| --- | --- |
| `PatchCanvas.kt` | model, camera, gestures, drawing, panel — the bulk of the UI |
| `GraphSync.kt` | the diff, `NodeType` mirror, `GraphCommands` seam for tests |
| `PatchStore.kt` | JSON persistence, hand-rolled on `org.json` |
| `History.kt` | undo as a stack of serialized patches, plus `Patch.replaceWith` |
| `Scale.kt` | the tuning model: degrees in octaves, with a period |
| `ScalaFile.kt` | `.scl` parsing — untrusted input, every bad shape returns null |
| `ScaleLibrary.kt` | seeds the bundled scales and reads the user's folder |
| `graph.{h,cpp}` | command queue, topological sort, crossfades, node lifetime |
| `transport.h` | musical time: one position every clocked node divides, header-only |
| `scales.h` | scale tables and the looping scale list; where a degree becomes a pitch |
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

**Ports must never move.** Side jacks are placed down from a module's top, never from its
height, and the open panel is screen space -- both so that nothing can shift a jack and make
every attached cable jump. A module's height does change in exactly one way: exposing a
parameter adds a band of modulation ports *below* the side jacks. Each parameter has a fixed
slot in that band rather than a packed one, because packing slides a port along whenever an
earlier parameter is exposed. A module never gets wider, since its outputs are on its right
edge.

**Cycles are legal.** Whatever cannot be topologically ordered is appended, which costs
exactly one block of delay on the back edge — because output buffers are never cleared
between blocks. That persistence is the mechanism, not an optimization.

**Every cable change crossfades** (30ms, smoothstep) between two *live* sources. Fading
from a captured value instead stops the waveform dead and glides DC to zero, which is a
thump rather than a click. A removed node therefore lingers until the fades reading it
finish.

**A patch file is refused, never migrated, and never destroyed.** Format 5 retired
modules rather than renaming fields, so an older file could only have been converted
*silently* -- a patch built around a VCA an envelope opened comes back as a filter fed by
nothing, quieter than it was left, reporting success. So `upgrade` is a version check and
nothing more; the migration ladder that walked 1 to 4 went with the formats it served.
Format 6 added groups and 7 the knobs promoted to a group's edge, neither taking anything
away, so 7 reads 6 and 5 as they stand -- the rule is against silent conversion, not
against a change that needs none. What
makes that affordable is that `PatchStore.load` moves a refused file to
`patch.rejected.json` before the demo patch can be autosaved over it. **A refusal must
never be a delete** -- check that still holds before adding another one.

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

**The engine never learns what a semitone is.** Scales cross as tables of octave offsets
(`hz = root * 2^octaves`) and sequencers send degrees, so an arbitrary scale costs
nothing. Degrees become octaves in exactly one place — `ScaleTable::octavesOf` in
`scales.h`, against the scale sounding on the beat a note starts, with that entry's root
(the key, in octaves from middle C) added — because only the audio thread knows that beat
to the sample. A note keeps that pitch unless its source sends a `Change`: only `Drone`
does, on each beat where a held note's pitch moved, and `OscNode` glides there over 30ms. Which scale is decided in integers from the tick's
count, never from the transport's floating position. A scale list crosses whole, as a
pointer built off the audio thread, like a node; both sides cap a scale at 64 degrees
(`MAX_DEGREES` / `kMaxDegrees`), and a larger `.scl` fails to parse rather than being cut
short in the engine. Scales are `.scl` files seeded
into `getExternalFilesDir/scales`, where a user can add their own; `Scale.Chromatic` is
the only one defined in code, and exists so the app still works when that folder is
unreadable. Tuning controls are in **cents**, never semitones: a semitone is a fact about 12-TET and
means nothing in the tunings these knobs still have to work in.

**Signal types are enforced.** Audio, CV and gate used to color the cable without
constraining it, because in hardware it is all voltage. That was the Eurorack model and
the project has left it: CV and gate are now *modulation* and *pulse*, which are not
voltages and do not interchange, so `patchesTo` is like-to-like and a mismatch is refused.
Audio-rate modulation does not need the loophole — a module that wants it declares an
audio input, and `MODULATION` is applied once per block and could not carry it anyway.

**The catalog is seven modules, and every synth is polyphonic.** `Osc` is the
polyphonic one -- what was called `Voice` -- and the monophonic oscillator is gone, which
settled the worst naming collision in the project: "voice" now means only one of the eight
slots inside an `Osc`. `Vca` retired with CV, since a `Mix` channel is `in * level` and was
always a VCA with its level on a knob. `Filter` lost its cutoff jack and `Steps` its pitch
and gate outputs, so a sequencer says a note once rather than the same thing three ways.
Node ids 1, 7 and 8 are retired and never reused; `Osc` is id 10, where `Voice` was.

**A module's color is the kind of cable it sends** -- greens for notes, steel blues and
grays for audio, purples for modulation -- in a shade of that family, never the cable
color itself. `ModuleColorTest` enforces it, so a new module's accent has to follow it.

**Groups never reach the engine.** Every module is in one flat list with a `parent`
(`TOP`, or the id of the group it is in). A group is a module of type `Group` whose ports
are its own, stored in a `GroupPorts` it shares with the two pinned rails inside it
(`GroupIn` on the left, `GroupOut` on the right) -- so inside a group, the existing rail
drawing, hit testing and cables all apply unchanged. `GraphSync` reads
`engineModules` and `engineConnections()`, which follow any chain of group ports to the
real output at the far end. **Grouping or ungrouping a playing patch must send the engine
nothing**, and `GraphSyncTest` asserts exactly that. Which group you are looking at
(`Patch.scope`) is view state: not saved, not undone. **A knob reaches out through the
boundary the same way a cable does.** Inside a group, the chip beside a row promotes that
knob to the group's edge, and the group's panel -- opened from its menu, since a tap goes
inside -- draws it. What is stored is a `ParamRef`, never a copy: the value stays on the
module inside, so there is one number, the engine still reads the node that has it, and
promoting sends the engine nothing. `panelRows` is what every panel draws and hit-tests
against, which is why a group can show knobs its own type never declared. A group is named "Group N" -- one
past the highest number in use anywhere in the patch -- on a `PatchModule.name` that every
module has and that falls back to the type's name, so a file written before names still
draws "Group".

**Nothing carries a pulse yet, and the kind stays anyway.** `Env` was the last thing taking
a gate and it takes *notes* now: a pulse is an event with no duration, so it could never
say when to release, while a note already carries an on, an off and an id to match them by.
`Steps`' gate output went with it. `SignalKind.PULSE` is kept for a module that wants a
bare trigger — reset, retrigger, sample-start — and `PatchModelTest` pins the rule against
the kinds themselves, since there is no longer a pair of ports to try it on.

Note into a pulse input is the one designed conversion and is still refused, because
`graph.cpp`'s Connect case rejects note against non-note outright, so allowing it in the
model alone would make a cable the UI accepts and the engine silently drops.

**An envelope is legato.** A second note over a held one leaves the gate open rather than
re-striking, because sustain is what an envelope is for and re-attacking under a chord
turns it into a stutter. An Off is matched against *the source that sent it* as well as its
id: ids are each source's own and restart at 1 when a node is rebuilt, so two sequencers on
one envelope are both holding a note called 1 almost at once.

**A note cable connected while notes are held delivers them.** A source says each start
once, so the graph marks a new note cable *fresh* and, on its first block, asks the source
for `heldNotes` and hands those over as starts -- skipping any note the source is starting
in that same block. Without it a drone patched to a new oscillator is silent until its
cells are toggled. A node that can hold a note indefinitely must implement `heldNotes`.

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
