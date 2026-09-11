# Roadmap

The goal is an instrument I actually play on my phone. Not a demo, not a library
with an app bolted on -- an app, which may shed reusable pieces along the way.

Two commitments shape everything below.

**The interaction thesis is the point.** Tap-to-connect, screen-space hit targets,
one unified gesture loop. Every phase either tests that thesis against real use or
gets out of its way. If the thesis turns out to be wrong at scale, that is a finding,
not a failure -- but it should be discovered with a finger on glass, not argued about.

**Timing comes from the frame counter.** The most stable clock on the device is the
audio device's own crystal. Any sequencer tick that originates from a `Handler`, a
coroutine, or `System.nanoTime` will jitter by up to a buffer no matter what sits
underneath it. So the sequencer lives *inside* the audio graph and counts frames.
This is a design decision made once, early, and never revisited.

## Stack

| Layer | Choice | License |
| --- | --- | --- |
| Transport | Oboe (AAudio, exclusive mode) | Apache-2.0 |
| DSP primitives | DaisySP core, no LGPL submodule | MIT |
| Graph runner | Hand-written C++ | -- |
| UI | Compose Canvas, existing | -- |

Rejected: SuperCollider (no maintained Android port; scsynth fails even in Termux
builds), Csound (maintained, but orchestra/score is the wrong shape for live
repatching), libpd (embeddable, but Pd's `connect`/`disconnect` is an editing path,
not a realtime one), VCV Rack's engine (GPL-3 and desktop-shaped, though RackDroid
proves the performance target is reachable on Android).

The common thread: none of them would supply the graph runner in a form matching our
model, so we would write it anyway on top of a large runtime we were fighting. What
they would genuinely save is the DSP math, and DaisySP supplies exactly that with no
runtime and no opinions.

Kotlin/AudioTrack was ruled out by the latency requirement. No allocation, no locks,
and no JNI into the JVM on the callback thread is not a style guideline; it is the
constraint that makes exclusive-mode MMAP viable.

## Reference target

**Pixel 10 Pro XL running GrapheneOS.** Development, latency measurement and every
"does this actually feel right" judgement happen here. Others are expected to be on
something broadly comparable; nothing below assumes a low-end device.

Measured over adb on 2026-09-10, not estimated:

| | |
| --- | --- |
| Android | 17 (SDK 37) -- exactly the current `compileSdk`/`targetSdk`, no lag |
| ABI | `arm64-v8a` **only** -- single-ABI NDK build, no 32-bit concerns |
| Display | 1080x2404 @ 390dpi = **443 x 986 dp**, density factor 2.4375 |
| Panel | 1344x2992 native; FHD+ is the shipping default to save battery |
| Refresh | 120 Hz capable, **currently rendering at 60** |
| Landscape insets | 66dp cutout side, 24dp gesture bar, 50dp corner radius |
| Audio | **48 kHz** native |
| MMAP | `aaudio.mmap_policy` = 2, `aaudio.mmap_exclusive_policy` = 2 -- both AUTO |
| Legacy path | HAL buffer 480 frames = 10 ms (`PRIMARY|FAST`) |
| **MMAP burst** | **96 frames = 2 ms**, measured in Phase 2 -- five times finer than the legacy path |
| **Output latency** | **4.2-5.9 ms**, exclusive MMAP, 0 xruns |

Two of those decide things. **Both MMAP policies are AUTO** (`NEVER`=1, `AUTO`=2,
`ALWAYS`=3), so exclusive-mode MMAP is permitted and will be attempted rather than
vendor-disabled -- the low-latency path is open. And the 10 ms legacy HAL buffer is what
a stream gets when MMAP *doesn't* engage, which is why Phase 2 verifies the mode rather
than assuming it.

The true MMAP burst size could not be read statically -- it needed a stream open. Phase
2 measured it at 96 frames, and AudioFlinger corroborates with an `AudioMmapOut` thread
carrying `AUDIO_OUTPUT_FLAG_MMAP_NOIRQ`. Exclusive mode is granted here, not merely
permitted.

Being a Pixel helps: it is the platform Oboe is developed against, and exclusive-mode
MMAP is most reliably available there. Being GrapheneOS matters more than it looks:

- **hardened_malloc is the system allocator**, and it is more expensive than stock. That
  does not touch the audio thread, which allocates nothing by design, but it does mean
  node construction on the UI side is not free -- and it will surface latent memory bugs
  in our C++ (or in DaisySP) that stock Android would quietly tolerate. Treat that as a
  feature.
- **MTE is available per-app**, which turns the riskiest part of Phase 3 into something
  testable. See that phase.
- **Sideloading is normal**, so the existing tag-driven signed-APK release flow in
  `RELEASING.md` is already the right distribution channel. No Play Store dependency,
  no store-policy surface, nothing to change.
- **The USB-C port can be locked down** when the device is locked, which is worth
  remembering before debugging USB MIDI in Phase 7 and blaming the code.

---

## Phase 0 -- Ground the repo

Bookkeeping that should not be discovered at release time.

- MIT `LICENSE`. *(done)*
- README package paths corrected after the `com.example` move. *(done)*
- `minSdk` 26 -> 33. The floor is 27, where Oboe first reaches AAudio and the OpenSL ES
  fallback disappears; 31 adds one storage model instead of a legacy branch (Phase 7
  export). It went to 33 in Phase 2 for ADPF -- see there, the reasoning is real rather
  than tidiness. Android 13 is 2022, which is inside "reasonably high-end" by any
  reading. *(done)*

## Phase 1 -- A patch editor worth using, still silent

**Code complete; only partly exercised on the device.** Everything below is written,
compiles clean and is covered by 28 unit tests. The unit fix and the port pitch were
verified on hardware by screenshot and tap test; the rails, the long-press menu and
reload have not been.

Everything here is independent of the audio engine, and all of it is load-bearing for
what follows. Right now you cannot tell `pitch` from `fm` on screen, cannot add a
module, and lose the patch on process death.

**Fix the unit system first.** `WIDTH`/`HEIGHT` and module positions are raw floats fed
to `DrawScope`, which means *pixels*; the touch radius is `24.dp` through `toPx()`,
which means density-scaled. The comment claiming "1 world unit ~ 1dp at scale 1.0" is
only true at density 1.0 -- which is why it looked right in `@Preview(widthDp = 800)`
and gets small and crowded on a real phone. Make world units dp, convert once at draw
time. Until this is done, thesis #2 is only half-implemented: the *target* is
screen-space but the *layout* is not.

**Then make crowding impossible by construction.** Measured on the reference device at
density 2.4375, the current constants land badly:

| | px | dp on this device |
| --- | --- | --- |
| Module | 116 x 84 | **47.6 x 34.5** |
| Port pitch, 2-port module | 42 | **17.2** |
| Touch radius | 58.5 | 24 |

The whole module is shorter than the 48dp minimum touch target, and two ports sit 17.2dp
apart inside a 24dp radius -- so both are within reach from anywhere on the module, and
`hitPort`'s nearest-wins decides on a midline 8.6dp from each. A fingertip contact patch
is 20-25dp across. At zoom 1.0 that is close to a coin flip; at 0.35 the ports are 6dp
apart. Thesis #2 does not currently survive contact with the reference device.

Fix it by sizing module height from port count at a fixed pitch of >=44dp rather than
dividing a constant height. Modules become variable-height; crowding stops being a case
to handle.

For scale: landscape is 986x443dp, or about 920x395dp once the 66dp cutout side, the
24dp gesture bar and the status bar are excluded. At 116dp wide on a ~186dp column pitch,
and a 2-port module ~112dp tall including its gap, that is roughly five columns by three
and a half rows -- **about 17 modules at zoom 1.0**. Comfortable, and it means panning is
not the constant tax feared in open question 2.

There is also a free demonstration of the unit bug: Settings -> Display -> full
resolution switches the panel to 1344x2992 and the density mapping to 480dpi. Screen
size in dp barely moves (443 -> 448), so a correct layout would not change at all --
but the current px-based modules will visibly shrink by 480/390 = 1.23x while the touch
targets stay put. Flip it once before the fix and once after.

- **Text on canvas** via `TextMeasurer`/`drawText` -- module names and port labels, with
  level-of-detail thresholds so labels drop out before they turn to mush. The names are
  already in the model and nothing renders them. Single biggest usability win available.
- **Add and delete modules.** Long-press empty canvas for a palette, long-press a module
  to delete or duplicate. `Patch.add` exists and only `rememberDemoPatch` calls it.
  Note this adds a fourth outcome to the gesture loop and needs a press timeout.
- **Persistence.** JSON to internal storage, autosave debounced, restored on launch.
  Built on `org.json` rather than kotlinx.serialization: a patch file is untrusted input
  needing entry-by-entry validation either way, and with that written the plugin and its
  Kotlin-version coupling buy nothing but risk. `nextId` turned out not to need storing --
  it is derived by advancing past the highest id adopted on load. Writes rename over a
  sibling so a kill mid-write cannot truncate the patch.
- **Insets.** The canvas is full-bleed with `enableEdgeToEdge()` and nothing accounts for
  any of it. Measured in landscape: **66dp** of cutout down one side (which side depends
  on rotation direction), a **24dp** gesture bar, and a **50dp** corner radius that clips
  anything in the corners. Draw the background full-bleed, keep modules inside the safe
  area.
- **Ask for 120 Hz.** The panel supports it and is currently rendering at 60. Gesture
  feedback at 8.3ms instead of 16.7ms is half the perceived latency budget of a tap, and
  it is the half that costs nothing to fix -- `Surface.setFrameRate()` or
  `preferredDisplayModeId`. Pointless to chase 10ms of audio latency while the visual
  confirmation lags by 17ms.

### Pinned I/O rails

`Out` was never really a module -- there is exactly one, a second is meaningless, and
deleting it should be impossible -- but it was an ordinary node that happened to be
special by convention. It and a new `In` are now welded to the viewport edges: unique,
unaddable, undeletable, position-less, and drawn at constant size so they stay reachable
at any zoom.

The direction is not arbitrary. `drawCable` has always computed its slack as
`(b.x - a.x) * 0.5`, so the geometry already assumed left-to-right flow; welding the
source left and the sink right makes that explicit. Rails are 64dp rather than a module's
116dp, since two full-width ones would cost a quarter of the landscape canvas forever.

This introduced a second coordinate space -- rails in screen px, free modules in world dp
-- so a cable can have one endpoint in each. Every cable is therefore resolved through
`portScreen()` and drawn in screen space. The hit test needed no changes at all to cope,
because it was already comparing in screen space: thesis #2 had prepared for this without
knowing it.

`In` is drawn dimmed and unpatchable until enabled, which it is not by default. See
Phase 4 for why the guard is a headphone check rather than the limiter.

*Done when:* building a patch from nothing on the phone is pleasant, and it is still
there tomorrow.

## Phase 2 -- First sound

**Done, and measured on the device.** `mmap=YES sharing=EXCLUSIVE perf=LOW_LATENCY
rate=48000 burst=96 buffer=192 latencyMs=4.2-5.9 xruns=0`. The 2 ms burst is the number
that could not be read statically, and it retires the Phase 3 risk: the bridge can be
designed against a real 2 ms budget rather than a hoped-for one.

Deliberately minimal, and deliberately before any architecture depends on it. The
purpose is to retire risk, not to make music.

- NDK and CMake into the Gradle build via `externalNativeBuild`.
- Oboe through its AAR's prefab support: `find_package(oboe REQUIRED CONFIG)`.
- One hardcoded sine to the output. No graph, no UI connection, no parameters.
- Query `PROPERTY_OUTPUT_SAMPLE_RATE` and `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`, run the
  graph at the device's native rate to avoid a resampler (**48 kHz** measured here), size
  buffers as a multiple of the reported burst, request `PerformanceMode::LowLatency` with
  `SharingMode::Exclusive`.
- Restrict the NDK build to `arm64-v8a` -- the reference device reports no other ABI.
- ADPF, entirely native, which is why `minSdk` is 33.

  The framework has two halves. `createSession(tids, target)` declares that a thread has
  a deadline; `reportActualWorkDuration` tells the governor what each cycle actually
  cost. The second is the half that works: without it the governor guesses, and it
  guesses badly for audio, because a thread that wakes, does a short burst and sleeps
  looks idle -- so clocks drop, work migrates to little cores, and the next callback
  misses its deadline. That is exactly Tensor's failure mode.

  At API 31 `reportActualWorkDuration` exists only as a Java method, so calling it per
  callback means attaching the audio thread to the JVM. That is not a matter of JNI
  overhead: a JVM thread can be **suspended by the garbage collector**, and a thread
  parked at a safepoint is not filling the buffer. This is the reason the audio thread
  must never touch the JVM at all, and it does not relax at any API level.

  API 33 exposes `android/performance_hint.h` -- the same calls as plain C in
  `libandroid.so`, with no JVM attachment and no GC exposure. Session creation still
  happens on the main thread, since it is not realtime work and the audio thread's id
  only exists after the first callback.

  This is the single exception to the no-calls-out rule, earned by having a native entry
  point. Phase 3's bridge is unaffected: still a lock-free SPSC queue in each direction.
  And 33 makes the API callable, not the feature present -- a device whose power HAL
  lacks ADPF returns no manager, which is a normal answer rather than an error.
- Log measured round-trip latency and XRun count on the actual phone, and confirm the
  stream actually came back MMAP/exclusive rather than silently falling back to shared.

### What the device taught us that the desk could not

Two bugs survived a clean compile and were only found by using it.

The `Out` rail never lit while sounding: the highlight was drawn *before* the module
box, whose opaque fill painted straight over it. Ordering, invisible in review.

Closing the stream clicked. `stop()` was closing with the gain still up, so the last
buffer ended on an arbitrary non-zero sample and the next was silence -- a step
discontinuity, which is broadband. Ramping the gain down first fixed most of it but not
all, and the remainder was the more interesting half: the ramp was being *computed* but
not *played*. The written silence still sits in the stream buffer and the hardware
pipeline, and `requestStop` discards whatever has not been consumed, truncating the
tail. Fading and then draining one buffer plus the hardware path fixed it.

A hard kill -- force-stop, or installing over a running app -- still pops, and always
will: the process is gone, so nothing can run a fade.

*Done when:* it makes a sound, and the real latency number is known rather than hoped
for. *(done)*

## Phase 3 -- The bridge

The spine of the project, and the highest-risk design. Worth getting right before the
module set grows enough to make changing it expensive.

Two representations that are never the same object: the Kotlin `Patch` is UI truth and
lives in Compose state; a C++ `Graph` is audio truth and lives on the callback thread.

- **UI -> audio** is a lock-free SPSC ring buffer of POD command structs -- `AddNode`,
  `RemoveNode`, `Connect`, `Disconnect`, `SetParam`. The audio thread drains it at the
  top of each callback and applies changes only at block boundaries.
- **Allocation never happens on the audio thread.** Nodes are constructed on the UI side
  and only a pointer crosses. Destruction is deferred: dead nodes travel back over a
  return queue and are freed by the UI side.
- **Evaluation order** by topological sort over a preallocated scratch array. The graph
  is tens of nodes; recomputing in-callback on change is cheaper than shipping order
  across the queue and keeps one source of truth.
- **Feedback loops must work.** A modular synth without cycles is not one. Detect back
  edges during the sort and break them with an implicit one-block delay. This changes
  what a connection means, so it goes in now rather than as a retrofit.
- **Fixed internal block size** (32 or 64 frames) regardless of the callback's frame
  count, giving a stable control rate independent of device buffer quirks.

The deferred-destruction scheme above -- a pointer crossing threads, freed later by the
other side -- is precisely the design that fails as a use-after-free six months later,
under a finger, on stage. The reference device can prove it instead: enable MTE for this
app in GrapheneOS's Settings -> Security. hardened_malloc sets a dedicated tag on freed
slots, so a stale node pointer faults deterministically at the moment of use rather than
corrupting audio somewhere downstream. Develop with it on.

Note this is a deliberate opt-in: GrapheneOS enables MTE by default only for apps
*without* bundled native libraries, and from Phase 2 onward this app has one.

*Done when:* tapping a cable changes what you hear, with no clicks and no dropouts.

## Phase 4 -- Modules worth patching

**Signal typing.** `PortRef` is `(moduleId, dir, index)` with no notion of what flows
through it. Introduce audio/CV/gate: it gates connection validity, colors cables, and
finally makes the demo patch legible.

Inputs stay single-source -- `connect` already replaces an occupied input. That is a
design choice (a patch stays readable, no hidden summing) and it makes an explicit
mixer and mult module necessary rather than optional.

| Module | DaisySP | Notes |
| --- | --- | --- |
| Osc | `Oscillator` | `WAVE_POLYBLEP_*` -- naive saws alias audibly |
| Filter | `Svf`, `Ladder` | both MIT; `moogladder` is the LGPL one, skip it |
| Env | `Adsr` | |
| VCA | -- | currently missing, and nothing shapes amplitude without it |
| Clock | -- | frame-counted; this is the stable clock |
| Steps | -- | clocked sequencer, pitch CV + gate out |
| Mix / Mult | -- | forced by single-source inputs |
| Out | `Limiter` | pinned right; plus a DC blocker |
| In | -- | pinned left; mic capture, `RECORD_AUDIO`, off by default |

The limiter is not polish. A feedback patch can reach full scale instantly, and this is
an instrument used with headphones.

It is not, however, the guard for the mic. A limiter prevents clipping, not feedback --
it will happily limit a howl to a very loud steady tone. Mic into speaker is a guaranteed
loop, mic into headphones is not, so enabling the `In` rail gates on a headphone route
plus the `RECORD_AUDIO` grant. That is worth more than any amount of DSP.

## Phase 5 -- Playability

Modules have ports but no knobs, which means nothing is tunable and the instrument is
not yet an instrument. Parameter editing is the second hard touch problem after
patching, and it directly contradicts a stated principle: `MainActivity` currently
argues for "no chrome, no scaffold -- anything drawn on top would just be another thing
for a finger to land on by accident."

Start by revising that principle rather than working around it. Tap a module to open a
bottom sheet of sliders: fully decoupled from the gesture loop, safe on a small screen,
and it lets parameters ship without risking the patching model. Then evaluate on-canvas
knobs with vertical drag as a second pass, knowing it adds a fifth outcome to a gesture
loop that was built to avoid exactly that kind of contention.

Also here: per-input attenuverters, without which CV routing is unusable in practice.

## Phase 6 -- Subpatches

A phone screen holds about 17 modules at zoom 1.0. A patch worth playing will exceed
that, and panning around a flat sheet of forty nodes is a worse problem than the one
tap-to-connect set out to solve. Conceptually coherent groupings are the answer:
build a voice out of Osc, Env, VCA and Filter, then treat it as one node.

**The rails already generalise, and that is the whole design.** `In` and `Out` mean
"the boundary of this scope". At the top level that boundary happens to be the audio
device; inside a group it is the group's own ports. Navigating into a subpatch is the
same canvas with the same rails, so the mechanism for defining a composite's
interface already exists and is already tested. Audulus -- touch-first modular on a
tablet, the closest prior art to this project -- uses exactly this shape.

**The audio graph never sees them.** Flatten before crossing into C++: a three-voice
patch is twelve nodes in one flat topological sort, not a tree of nested processors.
This is stated here so Phase 3 is not built in anticipation of nesting it does not
need. The only cost is that each instance's internal modules need distinct runtime
ids, which is an id-mapping problem rather than an architectural one.

**Two features, not one.** *Grouping* collapses these particular modules into one
box: one instance, purely organisational, and enough on its own to solve the screen
problem. *Abstraction* defines a reusable type that can be stamped out many times,
each instance with its own state -- considerably more work, needing a definition
library and a file format that separates definitions from instances. Grouping is the
natural first half of abstraction, so neither choice wastes the other.

**Why after parameters.** A Voice macro whose filter cutoff cannot be reached from
outside is half a feature. Exposing a knob through the boundary matters as much as
exposing a port, and that needs parameters to exist.

**Nothing to build early.** A composite is a `ModuleType` carrying an inner patch
definition; `PatchModule`, `PortRef` and the connection model are unchanged, because
each scope is just another flat graph. The one item with a deadline is the file
format -- definitions and instances must be separable, and that wants deciding before
Phase 5 hardens the schema. The loader already refuses unknown versions, so a bump is
clean.

A cheaper partial win, available any time: collapsing a module to a title-only strip
buys back a good deal of the same screen space for far less work.

## Phase 7 -- App-ness

- Patch library: name, save, load, duplicate, browse.
- Undo/redo. Falls out of Phase 3's command structs nearly free if they are designed to
  be invertible -- worth spending ten minutes on then rather than a refactor here.
- Foreground service so audio survives backgrounding and screen-off. An instrument that
  stops when the screen times out is not one.
- Record to a file.
- In-app open-source licenses screen. MIT requires the notice ship with the binary;
  DaisySP alone brings three (DaisySP, Plaits, Soundpipe) and Oboe brings Apache-2.0.
- Turn `isMinifyEnabled` on for release and confirm nothing reflective breaks.
- MIDI in over USB/BLE via `android.media.midi`, if it still seems worth it by then.

---

## Testing

28 tests as of Phase 1, against a suite that previously had a `junit` dependency and
nothing else. Covered: the graph invariants, the port geometry, and every malformed-input
path through the loader.

Worth doing once rather than assuming -- check the suite can actually fail.
Reintroducing the original 17.2dp port spacing fails three tests. Doing that also exposed
a real gap, since spacing and centring are separate terms in `portIn` and only spacing
was pinned. Mutation-checking a new test area is now the habit.

Still to cover:

- Camera and hit-test math. Pure functions, and the most novel code in the project.
- The C++ graph, via a host-side binary that runs it offline and compares buffers.
  Audio bugs are miserable to diagnose on a device; catching them on the desktop is
  worth the build plumbing.
- Gesture classification through `ComposeTestRule.performTouchInput`, once the loop
  stops changing shape.

## Open questions

These are genuinely unresolved, and the roadmap is arranged so each gets answered by
use rather than by argument.

1. **Does tap-to-connect hold up at 20+ modules?** The thesis is proven at five. Phase 1
   is the first honest test.
2. **Is constant panning worse than the problem it solved?** Drag-a-cable was rejected
   partly for occlusion. If a phone-sized viewport means panning between every port
   pair, that trade may not pay. Measured at ~17 modules on screen at zoom 1.0, which
   is more headroom than feared -- but Phase 6 exists because a patch worth playing
   will exceed it. A minimap or collapsed modules are the cheaper interim answers.
3. **Does the unified gesture loop survive?** It already needs long-press (Phase 1) and
   may need knob-drag (Phase 5). At some point a single `awaitEachGesture` becomes the
   tangle it was written to avoid. Watch for it.
4. **Is single-source input the right call?** Replacing an occupied input keeps a patch
   readable and avoids hidden summing, but it makes a mult mandatory for things hardware
   modular does implicitly. It may prove to be one tap too many in practice.
