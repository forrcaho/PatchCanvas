# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

PatchGarden is a touch-first modular synthesizer for Android: a Compose canvas for
patching, and a C++/Oboe audio engine behind it. It was PatchCanvas until the redesign
around subpatches, and PatchMatryoshka briefly after it. **The name is settled, and on
2026-09-23 the `applicationId` and the package became `io.github.forrcaho.patchgarden`**
(the GitHub repo was renamed to match). That made it a different app to Android, so an
installed PatchCanvas does not update into it; the reference device's patch, SoundFont and
tunings were copied across over adb once. **Do not change the
`applicationId` again** -- keeping it fixed is what lets an installed copy update.
`PatchCanvas.kt` and the `PatchCanvas` composable keep their names because they are the
canvas, not the app. `ROADMAP.md` carries the plan, the phase-by-phase
reasoning, and the decisions that were made and reversed — read it before proposing
direction, starting with *Where it stands* at the top, which is the current answer wherever
a phase below it disagrees. This file is the operating manual.

## The design in brief

**Subpatches are the app.** A subpatch is a box holding a patch of its own, and the goal is
that it is the first thing anyone reaches for, not what they tidy up with afterwards. Weigh
a proposal against that: does it make building inside a box easier than building on the
open canvas, or does it make the box a detour? `ROADMAP.md` keeps the list of what still
works against it.

**A poly subpatch is the special kind**: the engine copies it once per note, so you build
one voice and the patch on screen stays the size of one. It is the only way anything is
patched per note.

**The model is a hybrid, split at that boundary.** Outside a poly subpatch it is Bespoke
Synth: notes are events with an on, an off and an id, cables are typed and connect like to
like, and something allocates. Inside it is Eurorack: one voice, one of everything, every
synth monophonic, and polyphony by copying -- except that the engine does the copying, and
`PolyIn` is the only allocator. `SF` is the one polyphonic source, because TinySoundFont
layers several of its voices under one note.

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
adb shell am force-stop io.github.forrcaho.patchgarden
adb shell am start -n io.github.forrcaho.patchgarden/.MainActivity
adb logcat -d -s PatchAudio:V      # engine: stream state, latency, xruns
adb logcat -d -s PatchSync:V       # every command crossing to the graph (debug builds)
adb logcat -d -s PatchGesture:V    # what the envelope editor made of each touch (debug builds)
adb shell run-as io.github.forrcaho.patchgarden cat files/patch.json
adb shell ls /sdcard/Android/data/io.github.forrcaho.patchgarden/files/scales   # tunings
adb shell ls /sdcard/Android/data/io.github.forrcaho.patchgarden/files/subpatches
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
| | `engineGraph()` in there is the flattening, poly subpatches included |
| `GraphSync.kt` | the diff over `engineGraph()`, `NodeType` mirror, `GraphCommands` seam |
| `PatchStore.kt` | JSON persistence, hand-rolled on `org.json` |
| `SoundFontStore.kt` | the user's `.sf2` banks in `soundfonts`, loaded on demand |
| `SubpatchStore.kt` | the subpatch library: a saved subpatch is a patch file holding one subpatch |
| `History.kt` | undo as a stack of serialized patches, plus `Patch.replaceWith` |
| `Scale.kt` | the tuning model: degrees in octaves, with a period |
| `ScalaFile.kt` | `.scl` parsing — untrusted input, every bad shape returns null |
| `ScaleLibrary.kt` | seeds the bundled scales and reads the user's folder |
| `graph.{h,cpp}` | command queue, topological sort, crossfades, node lifetime |
| | `kMaxNodes` is 256: a poly subpatch is flattened by copying |
| `transport.h` | musical time: one position every clocked node divides, header-only |
| `scales.h` | scale tables and the looping scale list; where a degree becomes a pitch |
| `nodes.{h,cpp}` | the module set, DaisySP-backed |
| `reverb.h` | Reverb's two algorithms, a Freeverb room and a Dattorro plate, header-only |
| `processors.{h,cpp}` | notes in, notes out: Chance, Chord, Arp, Euclid |
| `soundfont.{h,cpp}` | the SF node over TinySoundFont; a SoundFont loaded once and shared |
| `synth.h` | `MonoSynth` and `GateRamp`: one note's pitch, glide and declick |
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
Format 6 added subpatches, 7 the knobs promoted to a subpatch's edge, and 8 new modules with their
dots and fonts, none taking anything away, so 8 read 7, 6 and 5 as they stood -- the rule is against silent conversion, not
against a change that needs none. That additive run ended at 9, and **10 read nothing but
10**: 9 stores a dot's length in whole steps where 10 reads quarter steps, so every
note would come back a quarter of its length -- a sequence that still loads, still plays, and
is not the music that was written, which is the exact failure refusing exists to prevent.
**11, 12 and 13 all read 10**, so `READABLE` in `PatchStore.kt` is a set rather than a single
number. Each is additive in the strict sense -- a default that restates what the file already
sounded like, never a conversion. A dot that never said how hard it was struck was struck at
full (11); a `Filter` that names neither type nor slope was a 12dB lowpass (12); a `Filter`
with no `track` knob, and no cable into a note port it did not have, was following nothing
(13). The direction that needs no rule is the other one -- a 10 build refuses an 11 file on
the version alone, which is what stops it dropping every velocity and autosaving. **That run
ended at 14, which reads nothing but 14**: an `Env` is segments, and A/D/S/R cannot be
restated as segments, only converted. DaisySP's stages are one-pole *time constants* toward
targets they never reach -- a release from sustain S actually lasts `R*ln(1 + 100S)`, about
four times the knob -- where a segment covers a stated distance in a stated time. The mapping
exists and using it would be the silent conversion 10 was drawn against. **15 reads nothing
but 15 as well**: Amp's `mod` port became its gain's own jack, and a 14 Amp whose gain was
exposed *and* patched had two modulators on one number, which 15 cannot say. Forrest chose
refusing every 14 file over a refusal that looks inside the file, since nothing saved during
development is worth keeping. **A knob or
a port added to an existing module bumps the version too**, for that same reason: knobs are
keyed by name and port indices are positional, so an 11 build would read a bandpass, ignore
the two knobs it does not know, and autosave it as a lowpass. **Adding a module type bumps the version** even though
nothing needs converting: an older build reads an unknown type as retired, skips it, and
autosaves the patch without it -- the bump makes that build refuse the file instead. What
makes that affordable is that `PatchStore.load` moves a refused file to
`patch.rejected.json` before the empty patch opened in its place can be autosaved over it. **A refusal must
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
constraining it, because in hardware it is all voltage. That was Eurorack's cable, and the
project has left it -- what it kept of Eurorack is the monophonic voice inside a poly
subpatch, not the cable. CV and gate are now *modulation* and *pulse*, which are not
voltages and do not interchange, so `patchesTo` is like-to-like and a mismatch is refused.
Audio-rate modulation does not need the loophole — a module that wants it declares an
audio input, and `MODULATION` is applied once per block and could not carry it anyway.
**A module that wants to follow the note declares a note input**, which is the same rule
again and is how `Filter` tracks pitch: notes used to reach only the things that sound them,
so nothing in the app could make anything follow pitch at all. A general module turning notes
into modulation is the more composable answer and is still open; it lost here on precision,
since the tracking ratio would live in a bracket dragged on the target and one imprecise drag
gives 97% tracking, which drifts across the keyboard and reads as a tuning bug.

**A filter's resonance is bounded by its drive, and the drive is not optional.**
`daisysp::Svf`'s only limit on resonance is a cubic term scaled by that drive, which
`FilterNode::prepare` set to zero for the life of the module: measured with a sine sitting on
the cutoff, the old maximum of 0.95 gave **39x** and 1.0 gave 1255x. `Out`'s limiter would
have held it, which is the problem -- as a brick wall over whatever else was playing. The
drive is 0.02, which costs nothing musically because it saturates the steady-state peak and
leaves the ring: an impulse rings 12ms at res 0.5 and past three seconds at 1.0, and those
numbers do not move with the drive at all. **This made existing patches tamer** and no version
check protects against it. The knob now reaches 1.0, where it stopped at 0.95, and that is
still not self-oscillation -- nothing here can make the damping negative, so a filter at full
resonance with no input is silent. The four kinds cost nothing because `Svf` computes all
four outputs every sample anyway; the steeper slope is a second `Svf` in series and **runs
even at 12dB**, where its output is discarded, because one resuming from a sample frozen
since the last slope change is a step at the one moment nothing is meant to happen.

**A tracked cutoff slides, and a jump is not a glide.** A note moves a cutoff octaves in one
block where a modulator moves it a little, and the first version caught that as five
discontinuities in ten seconds. The fix is a rate limit in octaves per block (0.05, so an
octave takes about 13ms), not an exponential smooth, which was tried and measured worse: a
smooth moves a quarter of the distance in the *first* block, and a quarter of four octaves is
a whole octave of coefficient in one step. Against a steady tone the step out of proportion to
the waveform went 8.9x jumping, 2.15x smoothed, 1.17x rate-limited. What remains is one event
per pass at res 0.6 and none at res 0.0, which is a resonator being retuned -- what a swept
resonant filter *is*, and a question for an ear rather than for `find_clicks.py`.

**Anything a node needs that is too big to build on the audio thread is a `Resource`**,
built on the interface's thread and handed across with `postSetResource`; what it replaces
comes back through `collectGarbage`, as a scale list does. An SF node's synth is the first:
its font loads in the background, so the node exists before its synth and must keep the
notes it is sent in the meantime.

**Every synth is monophonic, and there is one allocator.** A synth is a `Voice` inside
`MonoSynth` (`synth.h`), which owns pitch resolution, Off-by-source-and-id, glides and
`notesCut`; a new synth supplies only its sound. Polyphony is a poly subpatch around it,
and `PolyIn` is the only thing that chooses between voices -- idle first, then the oldest
released, then steal, which were `PolySynth`'s rules and were each paid for by a bug. It
adds one thing a synth does not need: a stolen instance is sent an Off first, since what is
inside it is an ordinary `Env` holding an ordinary note and nothing else would ever end it.
**The synths' own eight voices went deliberately**: leaving them would be two allocators
stacked with the inner one never choosing anything, and the whole design surface the
redesign set out to retire still sitting there. `SF` is the exception and keeps its 64 --
they are TinySoundFont's, and one note can use several of them at once for a layered
preset, so capping it to one would silence half of some instruments -- which makes `SF` the
only polyphonic source in the app. A voice says when it is finished, and a plucked string
finishes while still held, so a `Pluck` that rang out is not deaf until something lets go.
Any DaisySP code that calls `rand()` is edited before it is vendored -- Bionic's takes a
mutex.

**Retired modules stay retired, and a retired id is never reused.** `Osc` is what was
called `Voice`, and the second, monophonic oscillator that forced that rename is gone. The
name settled the worst collision in the project -- "voice" meant both the module and one of
the eight slots inside it -- and it has since settled itself: there are no slots any more,
so "voice" means one note's worth of sound and nothing else. `Filter` lost its cutoff jack and `Steps` its pitch
and gate outputs, so a sequencer says a note once rather than the same thing three ways.
`Env` lost all four of its knobs, which is a module keeping its id while ceasing to be
parameterised at all: its panel is a grid and `Types.Env.params` is empty.
The note port `Filter` has now is not that jack coming back: the cutoff is modulated by
exposing the parameter, like every other knob, and the note port says which pitch to follow.
Node ids 1, 7 and 8 are retired and never reused; `Osc` is id 10, where `Voice` was. **A
module can come back; its id cannot.** `Amp` is the VCA again, at id 21, because with the
envelopes out of the synths the pair you reach for is `Env` and the thing `Env` opens, and
that should be one cable rather than opening a `Mix`, exposing its level, setting brackets
and then patching. Id 7 stays dead all the same: that module took a control voltage, and
there is no such thing here.

**An envelope is segments, and a segment says where it is going, never where it starts.**
Each is a time, a level and a curve; it begins wherever the output already is, which is what
lets a note let go during the attack fall from the level it *reached* rather than stepping to
a sustain it never touched. At most one segment is marked sustain -- the editor calls it the
*release*, since what holds is the value at the segment's end: that node is drawn with an `R`
and everything after it sits on a blue ground -- and **none of them being
marked is a whole envelope** -- it runs to its end whatever the note does, which is what a
percussive patch wants and costs nothing on a synth anyway, since a voice is freed the moment
its gate ramp hits zero. A parked envelope keeps *following* its level over 5ms, because
dragging a sustain node is something you do with a note held down and an editor that is deaf
exactly then cannot be tuned by ear. The curve is `(1 - e^-at)/(1 - e^-a)`, which is 0 at 0
and 1 at 1 for every `a`, so bending a segment cannot move where it arrives; `envShape` in
Kotlin and `EnvNode` in C++ are **the same expression on purpose**, because an envelope that
sounds unlike its own picture is worse than one with no picture.

**A slot-indexed list crosses as one command, and there is one of everything for it.**
Steps, dots and segments are the same shape -- a positional list the interface edits and the
engine keeps a slot per entry -- so they share `SlotValue` (a tag plus a union of
`StepSlot`/`DotSlot`/`SegmentSlot`), one `Node::setSlot`, one `CommandType::SetSlot`, one
apply case, one JNI shim and one `GraphSync.diffSlots`. **The payload stays typed**: a node
reads `slot.dot.velocity`, because `nodes.cpp` is where the DSP is read and clarity there
beats the packing it would save. What is *not* typed is the JNI shim, whose ten arguments are
the union of all three kinds -- the one place in the crossing that is not self-describing, and
the reason `AudioEngine`'s three typed wrappers are the only callers. `SlotKind` is a
cross-boundary contract like `NodeType` and is asserted against `node.h` the same way; a
disagreement there would read a segment as a dot rather than merely dropping it.

**A long press in a pointer loop catches Compose's timeout, not kotlinx's.**
`AwaitPointerEventScope` overrides `withTimeout` and throws
`PointerEventTimeoutCancellationException`; catching `kotlinx.coroutines.TimeoutCancellationException`
next to it **compiles, never matches, and lets the exception end the gesture** -- so the long
press does nothing and says nothing about why. The canvas loop has always caught the right
one; the envelope's caught the wrong one for a build, and only a log of the exception's class
name found it.

**A panel editor that returns unconditionally must earn the gesture first.** Every grid's
loop in the panel ends in `return@awaitEachGesture`, so one reached without a bounds check
claims the whole screen -- the envelope's did, and swallowed the tap *outside* the panel that
is the only way to close one, leaving a panel with no door (the breadcrumb is deliberately
hidden while any panel is open). They share one `inEditor` gate now. The tap-only chips never
had this problem and do not need it: each carries a rect and none returns without hitting it.
It is loops that claim.

**A long press is what is done *to* a thing, and it never destroys on its own.** Across the
app a tap on a reading opens the keypad, a tap on a chip toggles it, and a drag adjusts what
the first move decided; the long press is the fourth. One harmless action happens directly (a
crumb renames, a pinned rail opens its panel); anything more is a menu, and a destructive
action is always a tile -- which is why a subpatch's jack gets a menu of one item. An envelope
node's removal was the bare long press, the one exception, until the release needed somewhere
to go; it is a menu now, like a module's. Check a new gesture against these four before
inventing a fifth.

**Nothing in the envelope editor is destructive on a tap.** A tap on a node used to remove
it, mirroring the dot grid, and it made the whole editor feel unreliable: a tap is what a
finger does when it means to *grab* something, so segments vanished while people were trying
to drag them -- one patch went from six to two without anyone meaning it. A removed dot costs
one tap to put back and a removed node costs its time and its curve, which is the asymmetry
that makes the same gesture right in one grid and wrong in the other. Removal is in the node's
long-press menu now; a tap on a node does nothing at all, and a tap on the *line* still adds
one. **The release stays off the tap too**, though moving it costs one tap to undo: the node
most often touched and left where it is is the release node itself, with a note held while its
level is tuned by ear, and a touch that never clears the slop is a tap -- which would let the
note go.

**A target must match what is drawn.** The envelope is drawn as a *filled* area under its
curve, and for two builds only a 53px band around the stroke responded -- so a finger landing
in the middle of the fill, which is the part that looks like the segment, did nothing at all.
**A segment owns its whole column** now. A *tap* still has to point at the line, because
adding a node changes what the envelope is made of where bending it is an adjustment; that is
the same line that makes removing one a long press. This is the third time this editor has
drawn one thing and targeted another -- the rails do not line up with the nodes either -- so
treat "what is drawn here, and is that what answers a finger?" as the question to ask of any
new one.

**`PatchGesture:V` says what the editor made of a touch.** Debug builds log, per touch, where
it landed, whether that resolved to a node, a segment, a rail cell or nothing, the distance to
the two nearest nodes and how far off the curve it was. The same habit as `PatchSync` tracing
every command: "what did my finger actually hit" is otherwise answered by guessing, and it is
what finally located a fault that two rounds of reading the code and one device pass had
missed -- three touches logged at 200 to 300px below a line the user believed they were on.
**Ask for the trace before theorising about a gesture.**

**Up bends the line up, whichever way the segment travels.** Curvature's sign is a fact
about *shape* -- leaves fast, arrives slow -- not about the screen: that shape puts a rising
segment's middle high and a falling segment's middle low, so mapping the finger straight onto
the number moves the line backwards on half of all segments. `envCurveAfterDrag` takes
`rising` for exactly this. The trap it was found by is worth more than the rule: **checking
that the number moved is not checking that the line followed the finger**, and a device pass
that read `PatchSync` and never looked at the picture confirmed the first while the second was
wrong. `EnvelopeTest` now asserts the drawn midpoint, uphill and down.

**A control whose whole range fits inside one drag reads as broken.** `ENV_CURVE_TRAVEL` was
90dp, which put the curvature's full -1 to +1 inside 439px against a curve area 631px tall, so
any real drag slammed it to a limit and left it there. The report was "the curvature won't
move", from a patch whose every segment was sitting at exactly 1.0 -- a control pinned at its
maximum looks exactly like a control that is dead. `EnvelopeTest` pins the *relationship*
rather than the number, since the number is a feel: one drag down the editor must not cross
the whole range. Curvature is dragged **relative** to where it already was, so a slow rate
costs nothing -- a second drag carries on from the first.

**An envelope editor's decisions each get their own target, never a mode.** A node carries a
time and a level and a drag moves both; the numbers are typed from a rail of levels above the
shape and a rail of times below it. This is Surge's split rather than Bespoke's, and the reason
is arithmetic: three quantities on one draggable point makes the control that picks between
them a mode, and a mode on a fingertip-sized target is a coin toss. **The two rails are laid
out differently because a time is a span and a level is a point.** A time cell is as wide as
its segment is long, floored at `envCellMin` so a 5ms attack still has something tappable that
still says "5ms" -- allocated by water-filling, so where no floor binds the cells land exactly
on the segment columns. A level chip is centered over its *node*, one floor wide, and moved
only as far as crowding requires: an isotonic fit, so a crowded pair shares the displacement
rather than one chip being shoved a whole width off its node. The top rail used to be a
`hold` cell per segment, which read as "this segment is held" when what holds is the one value
at its end. The keypad is in **milliseconds**, since nobody can drag a node to 12ms and everybody can tap it
and type 12. `MAX_SEGMENTS` is 8 and is a **cap**, not a scroll or a zoom: a scroll
needs a gesture that competes with dragging a node, and a zoom-to-fit puts the nodes closest
together exactly when the envelope gets interesting.

**Copying a module copies its grid, and there is one function that does it.**
`PatchModule.copyGridFrom` -- because a module is copied in three places (undo's
`replaceWith`, `duplicate`, and `adoptSubpatch`) and when segments arrived all three were
copying dots and none knew about a second kind of grid. An undone envelope, a duplicated one
and one loaded from the library each came back as the default, silently, and only for the
module you had just been editing.

**No synth has an envelope.** `Osc` has one knob and `FM` three; what is left of the ADSR
is a 5ms gate ramp (`GateRamp` in `synth.h`) that keeps a note from starting or stopping
with a step in it. **A note's velocity rides that ramp, and must**: a voice taken by a
second note keeps its ramp open on purpose, so a velocity applied straight to the
oscillator's amplitude stepped the waveform by the whole difference between the two notes --
0.22 where the waveform's own largest step is 0.014, heard as clicking between abutting
notes and gone when every note was at full. The ramp takes a level outright while the gate
is shut, since a silent voice has nothing to step, and glides to it otherwise. An envelope built into a synth was *one envelope for every voice it had*
and could be patched to nothing else -- which is why an `Env` on FM's modulation index was
impossible, and why this redesign happened. Shaping is an `Env` inside a poly subpatch,
where there is one per note. `FM` lost Chowning's brightness-follows-loudness with it:
expose `index`, patch an `Env`, and the two envelopes no longer have to be one envelope.

**A knob has one way in.** `Amp`'s `mod` port multiplied its gain knob, and the knob could be
exposed as well, so the same envelope patched into both was applied twice -- found in the
phone's own patch as `in * env * (0.6 + 0.8 * env)`. The port is the knob's jack now: a
*driven* knob (`Param.drivenBy` in Kotlin, `Node::drivenParam` in C++) is plain while its port
is empty, grows brackets like an exposed knob's while it is patched, and cannot be exposed.
**The graph hands the node the parameter, not the signal**: every sample is the knob while
nothing is patched and the modulator swept between the brackets once something is, faded by
the ordinary crossfade -- because only the graph holds the knob, the range and the fade, and a
node mapping its own input could not tell a modulator resting at 1.0 from nothing patched.
That replaced `unityInputs()`, whose buffer of ones made an idle Amp open by making the knob
and the port two gains multiplied together. An unmoved range is from nothing up to the knob,
which is the VCA's `in * mod * gain`, and **GraphSync always sends a driven knob's effective
range** -- stored or not -- since no command removes a range and an undone bracket would
otherwise go on sounding. `Patch.rangeOf` is the one answer to "is this row bracketed", for
the drawing, the hit tests and the keypad alike, because only the patch can see the cable.

**A stack means several of this sound at once**, and exactly two types draw one:
`Types.Poly`, a voice the engine stamps out per note, and `Sf`, whose voices are
TinySoundFont's and cannot be capped to one because a single note can layer several of
them. Since every other synth is monophonic, the marker's job is to say where you do *not*
need a `Poly` around it. It promises multiplicity and not a canvas behind it -- only a
subpatch opens, and a stacked `SF` opens its panel like any other module. A poly subpatch's
rails stack with it, because in there you are looking at one instance of several. `PolyTest`
pins the set at two, so a third is a decision rather than a drift.

**A module's color is the kind of cable it sends** -- greens for notes, steel blues, warm grays and
grays for audio, purples for modulation -- in a shade of that family, never the cable
color itself. `ModuleColorTest` enforces it, so a new module's accent has to follow it.

**A saved subpatch is a patch file holding one subpatch.** The library (`SubpatchStore.kt`) writes
to `getExternalFilesDir/subpatches`, beside the scales, and reads back through `patchFromJson`
-- so a subpatch file gets the patch format's validation, its refusals and its version check
rather than a second copy of all three. Loading is a **copy**: `adoptSubpatch` allocates ids
in the receiving patch, which is the same routine that duplicates a subpatch, so the same file
loaded twice is two subpatches sharing nothing. Saving the whole patch subpatches a *copy* read
back from the patch's own file -- never the live patch, since unpacking re-adds the
boundary's cables at the end of the list and the reordered file became a phantom undo step.

**Anything sized to hold a label reads `Frame.fontScale`.** Labels are sp, boxes are dp, and
the reference device runs at font scale 1.5; a menu tile sized for 12sp text overflowed
there and nowhere else. Grow the box with the setting rather than shrinking the text back
against it.

**A poly subpatch is monophonic inside and cloned on the way out.** One of everything in
there, and `GraphSync` hands the engine `voices` copies of the lot, plus a `PolyIn` that
shares the notes out one per instance and a `PolySum` per signal output that adds them back
up. Instance 0 *keeps the module's own id*, which is not a detail: telemetry -- where a
sequencer is, where a modulated knob is -- is asked for by module id, so numbering from the
original means every one of those reads the first instance without knowing instances exist.
Later instances carry their number in bits 48 and up, where a patch's ids never reach.
**Only notes are shared out**; audio and modulation are broadcast to every instance,
because the instances are copies of one voice rather than separate patches. A poly
subpatch's note *output* merges at the destination rather than through a node, since that
is what a note input already does. **A poly subpatch may not contain another** -- instances
would multiply and the id space is one level deep on purpose -- and that is refused from
every direction: added inside one, made from a selection inside one, wrapped around one,
duplicated or loaded into one. `kMaxPorts` is 8 because a port is an instance at those two
edge nodes, which makes 8 the voice limit too; no module declares more than four.

**Subpatches never reach the engine.** Every module is in one flat list with a `parent`
(`TOP`, or the id of the subpatch it is in). A subpatch is a module of type `Subpatch` whose ports
are its own, stored in a `SubpatchPorts` it shares with the two pinned rails inside it
(`SubpatchIn` on the left, `SubpatchOut` on the right) -- so inside a subpatch, the existing rail
drawing, hit testing and cables all apply unchanged. `GraphSync` reads `engineGraph()`, which follows any chain of
subpatch ports to the real output at the far end and stamps out a poly subpatch's copies. A subpatch's ports are stored, never derived from the cables, so
unplugging one leaves the jack to plug back into -- but a port whose jack *inside* stops
existing (its parameter unexposed, its module deleted) is dropped, since nothing could
reach it again. Dropping one renumbers every cable that named a later port: indices are
positional, and stale ones fail silently because both ends are wrong by the same amount,
so the engine hears the right thing while the jack draws off the end of the box. **Subpatching or unpacking a playing patch must send the engine
nothing**, and `GraphSyncTest` asserts exactly that. Which subpatch you are looking at
(`Patch.scope`) is view state: not saved, not undone. **A knob reaches out through the
boundary the same way a cable does.** Inside a subpatch, the chip beside a row promotes that
knob to the subpatch's edge, and the subpatch's panel -- opened from its menu, since a tap goes
inside -- draws it. What is stored is a `ParamRef`, never a copy: the value stays on the
module inside, so there is one number, the engine still reads the node that has it, and
promoting sends the engine nothing. `panelRows` is what every panel draws and hit-tests
against, which is why a subpatch can show knobs its own type never declared. A subpatch is named "Subpatch N" -- one
past the highest number in use anywhere in the patch -- on a `PatchModule.name` that every
module has and that falls back to the type's name, so a file written before names still
draws "Subpatch".

**A dot carries its own velocity, and the lock is what a vertical drag means.** Every voice
already consumed velocity -- an `Osc` as amplitude, a `Pluck` as strike accent, an `FM` as
both index and output, so on an FM it has always meant brightness -- and every source wrote
1.0, so the feature was a number nobody could choose. A drag on a dot decides its axis once,
on the first move, as the canvas loop does: across is the length, down the grid is the
degree. The lock chip on the panel pins the dots in place, which leaves a vertical drag
nothing to move and it sets velocity instead, drawn as how much of the dot is filled. That
it is stated as a *lock* rather than as a velocity mode is the whole reason it reads: "can a
dot move" is a fact about the dots, where "what does a vertical drag mean" is a fact about
the tool, and only the first is something a finger is already asking. A long-press per note
was the alternative and was rejected on the arithmetic -- sixteen notes is sixteen
long-presses, six seconds of waiting before any of the drags.

**A dot's length is its duration, in quarter steps.** `Seq` had a `gate` knob for one day:
it took Steps' place in the Add menu, a dot's length was whole steps, nothing could be
shorter than one, and the knob shortened the last step of every note at once. A length in
quarter steps says that per note and says more, so the knob went -- Steps' half step is a
length of 2. This is Bespoke's model and was `DotSeq`'s own design: **a gap between two
notes is made by shortening the first**, and it is drawn that way, a half-step note being
half a cell wide. The whole steps of a length are counted in ticks, so a stopped transport
holds a note as it holds a `Steps` note; the part step left over is counted in frames from
the tick it starts on. A note shorter than one step has no whole steps at all, so its part
starts on the tick it does -- which is why `SeqNode::onTick` counts the tails out *after*
the starts rather than beside the ends.

**A knob that means nothing is faint, not gone.** `Param.liveWhen` names another knob and the
value it must hold -- a Delay's time is live only while its interval is "free" -- and such a
row is drawn at a third of its brightness and answers no finger while it does not apply.
Hiding it would move every row under it, and leaving it bright would be a control nobody is
listening to.

**Timed in beats but not by the transport reads `tempo_`.** `setTiming` hands every node the
running rate, which is zero while stopped so anything stepping in beats holds still, *and*
the tempo, which is not. A synced Delay reads the second: an eighth is an eighth long whether
or not anything is playing, and reading the first made a stopped delay no length at all.

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
re-striking, because sustain is what an envelope is for and re-attacking under a held note
turns it into a stutter. Inside a poly subpatch that case is rarer than it was -- `PolyIn`
gives each note its own instance and sends an Off before it steals one -- but an `Env` fed
by two sources, or by a chord on one instance, still meets it. An Off is matched against *the source that sent it* as well as its
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
enum, `SlotKind` mirrors `SlotKind`, and `MAX_PORTS`/`MAX_PARAMS`/`MAX_SEGMENTS` mirror
`kMaxPorts`/`kMaxParams`/`kMaxSegments`. A mismatch there fails silently in production.
Each is read out of the header by the test rather than copied into it.

## Diagnosing audio

Debug builds keep a rolling 10s capture of exactly what reaches the stream, written on
stop. Play, background the app, then:

```sh
adb exec-out run-as io.github.forrcaho.patchgarden cat files/capture.wav > /tmp/c.wav
python3 tools/find_clicks.py /tmp/c.wav          # discontinuities + boundary alignment
python3 ~/musicode/rust/cursive/tools/audio_analyze.py /tmp/c.wav --png /tmp/s.png
```

`find_clicks.py` reports whether events land on the inner block (32), device burst (96) or
stream buffer (192) — on a boundary implicates the plumbing, irregular implicates the DSP,
and it now prints that as a verdict rather than leaving it to be read off the gaps.
**Measure with a sine source.** A saw or a square steps full scale once a cycle by design
and the outlier test reports every one of them: a four-octave saw line read as 39
discontinuities, all of them the waveform, which cost an afternoon of suspecting a filter
that was innocent. The verdict line now says so when a run is evenly spaced at an audible
rate.

**The alignment check was off by one until 2026-09-21 and could never fire.** Hits index the
*difference* array, so a step arriving at sample n was reported at n-1 and the modulo test
asked whether n-1 was a multiple of 32 — it answered 31, every time, for the life of the
file. Anything that read 0% aligned before that date read it for that reason. Two clean
captures have also shown the engine innocent of clicks that were real to the ear; the
reference device listens over **Bluetooth A2DP**, where packet loss sounds exactly like
that. The tool is a good witness about plumbing and a poor one about filters.

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
