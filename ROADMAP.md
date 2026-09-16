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

**The model is Bespoke Synth, not Eurorack.** Eurorack supplied a lot of the early
vocabulary -- 1V/oct, gates, "it is all voltage" -- and while the two agreed nothing
depended on which was the model. They disagree about notes: a Eurorack cable carries one
signal, so polyphony means copying voices, where Bespoke passes notes as events and lets
whatever sounds them allocate the voices. Where they part, Bespoke's shape wins -- though
not its names, and not its internal MIDI. See Phase 6, where notes became events, and
Phase 7, where CV and gate follow them out.

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

**The measured latency is the stream's, not end to end.** `latencyMs` comes from AAudio
and covers our leg only. Over Bluetooth A2DP the transport adds a hundred milliseconds
or more that the number cannot see, so a route check belongs with any latency claim.
Exclusive MMAP at a 96-frame burst is real; it is the local leg. Wired or speaker is the
only honest test, and worth doing before Phase 5, where latency starts to matter to the
hands.

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
  remembering before debugging USB MIDI in Phase 8 and blaming the code.

---

## Phase 0 -- Ground the repo

Bookkeeping that should not be discovered at release time.

- MIT `LICENSE`. *(done)*
- README package paths corrected after the `com.example` move. *(done)*
- `minSdk` 26 -> 33. The floor is 27, where Oboe first reaches AAudio and the OpenSL ES
  fallback disappears; 31 adds one storage model instead of a legacy branch (Phase 8
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

**Done, and verified on the device.** Patching a cable changes what you hear, live, with
no dropout and no click. 22 native checks and 44 JVM tests.

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

### Declicking, which was most of the work

Changing a cable swaps one signal for another between two samples, and that step is
broadband -- the same defect that made closing the stream pop, now at every patch. Each
input port is a 30ms smoothstep crossfade between its old source and its new one.

Six wrong answers on the way, most of which sounded better than the last while still
being wrong:

- A ramp whose origin follows the output restarts from a new place at every block
  boundary. The origin must stay fixed for the ramp's duration.
- 2ms is shorter than one cycle of a bass note, so fading a signal in over it is itself
  a transient. A linear ramp is continuous in value but not in slope, and those corners
  are audible. Smoothstep over 30ms fixes both.
- Asymmetric lengths were a wrong theory: 10ms sounded clean on the phone's speaker and
  was plainly audible on earbuds. The shorter side was not better, only harder to hear.
- **The one that mattered:** fading out from a frozen *value* stops the waveform dead and
  glides a DC level to zero. Not a click, a thump. The fade has to be a true crossfade
  between two live sources. The test counts zero crossings, because "it fades" and "it
  fades as a signal" are different claims and only the second is quiet.
- That forces deferred removal: a node cannot be freed the moment it is removed, because
  whatever it fed is still crossfading out of it.
- Which exposed a crash the sanitizer caught at once -- reaping happens per block but
  the evaluation order was rebuilt only per callback, so a freed slot stayed in the order.

Replacing a source was the last one, and it was not in the engine at all. `GraphSync`
sent disconnect *and* connect, so the second fade began from the silence the first had
aimed at. Inputs are single-source, so a connect already replaces; the disconnect was
redundant and harmful. That is what makes swapping a cable a real crossfade.

*Done when:* tapping a cable changes what you hear, with no clicks and no dropouts.
*(done)*

## Phase 4 -- Modules worth patching

**Done, verified on the device.** 22 graph checks, 22 node checks, 53 JVM tests.

The demo patch is now an instrument rather than a test tone -- Clock drives Steps, Steps
plays Osc and fires Env, Env opens a VCA, and the VCA feeds both channels.

**DaisySP is vendored, not a submodule.** Six files under `app/src/main/cpp/vendor/`,
because DaisySP's own repository carries `DaisySP-LGPL` as *its* submodule: anyone
running `git clone --recursive` here would pull the half we deliberately excluded and
acquire a relinking obligation without choosing to. It also keeps offline and CI builds
free of a configure-time download. Built as its own target with headers included as
SYSTEM, so upstream is held to upstream's warning settings rather than ours.

**Signal typing is advisory, not enforced.** Audio, CV and gate colour the cable and the
port, and any output may still patch to any input.

This roadmap originally said types would gate connection validity. That was wrong. In
hardware modular it is all just voltage, and patching audio into a CV input is a
technique rather than a mistake -- audio-rate modulation lives there, and refusing it
would make this less modular than the thing it models. The colour says what to expect;
the cable decides what happens. The oscillator updates its frequency per sample rather
than per block precisely so that stays real.

Phase 6 confines this to signals. Note events are typed, because an event is not a
voltage and has nothing sensible to do arriving at an audio input.

Inputs stay single-source -- `connect` already replaces an occupied input. That is a
design choice (a patch stays readable, no hidden summing) and it is what makes an
explicit mixer necessary rather than optional.

| Module | DaisySP | Notes |
| --- | --- | --- |
| Osc | `Oscillator` | `WAVE_POLYBLEP_*` -- naive saws alias audibly |
| Filter | `Svf`, `Ladder` | both MIT; `moogladder` is the LGPL one, skip it |
| Env | `Adsr` | |
| VCA | -- | closed without CV, as hardware is |
| Clock | -- | frame-counted; this is the stable clock |
| Steps | -- | clocked sequencer, pitch CV + gate out |
| Mix | -- | forced by single-source inputs; nothing else can sum |
| Out | `Limiter` | pinned right; plus a DC blocker |
| In | -- | pinned left; live microphone, +18dB, off by default |

**There is no Mult**, despite this table once listing one. A mult exists in hardware
because a physical jack takes one plug; here an output already fans out to as many
inputs as you like, since each input stores its own source. Only summing ever needed a
module.

The limiter is not polish. A feedback patch can reach full scale instantly, and this is
an instrument used with headphones.

It is not, however, the guard for the mic. A limiter prevents clipping, not feedback --
it will happily limit a howl to a very loud steady tone. Mic into speaker is a guaranteed
loop, mic into headphones is not, so enabling the `In` rail gates on a headphone route
plus the `RECORD_AUDIO` grant. That is worth more than any amount of DSP.

### Growing the library

The vendored DaisySP tree already holds far more than the eight modules above, all MIT:
`KarplusString` (Emilie Gillet's, not the LGPL `pluck`), `stringvoice`, `modalvoice`,
`resonator`; `wavefolder`, `overdrive`, `decimator`, `chorus`, `flanger`, `phaser`,
`pitchshifter`; `fm2`, `formantosc`, `harmonic_osc`, `oscillatorbank`, `vosim`; the
drum voices; the noise sources; and `delayline`. Availability is not the constraint.

**What to add is decided by what cannot be built from something else.** Once subpatches
land, a voice you assemble is a node, so the library grows itself -- and a fixed module
is only worth shipping when it is a primitive, or when the implementation quality is the
point. By that test a **delay line** is the highest-leverage thing missing: it yields
Karplus-Strong, comb filtering, flanging, chorus and echo from one primitive. Against
that, a prebuilt `KarplusString` is one sound -- but a good one, and fiddly to get right
from parts, which is exactly when a curated module earns its place.

Some variety is already paid for: `Svf` has low, high, band, notch and peak taps, so a
mode parameter turns one module into five filters.

**Fix the cost of adding one before adding the fifth.** A module currently touches five
places across two languages -- a C++ node class, the C++ enum, the Kotlin enum, a
`ModuleType`, and the palette -- and the two enums must agree. There is already a test
asserting they do, which is a smell rather than a solution. The engine should own the
catalogue (name, ports, signal kinds) and hand it to Kotlin at startup, with the UI
supplying only colour and category. Then a module is one declaration, and a whole class
of silent mismatch stops being possible.

Two limits will bite as it grows: `kMaxPorts` is 4, and a mixer wants eight inputs;
names must fit a 74dp tile at 12sp, which is about eight characters. That second one is
not a problem but a discipline, and it is the Eurorack one -- panels say Pluck, Fold,
Rings, Warps, for exactly the same reason.

### The microphone

It opens on the same low-latency path as the output -- MMAP exclusive, 48kHz, 96-frame
burst -- with `InputPreset::Unprocessed`, so no automatic gain or noise suppression is
applied to something being used as a synth source. That rawness is why it needs +18dB of
its own; Phase 5 should make that a knob.

**It asks for the device's microphone, never the headset's.** Requesting the headset mic
on a Bluetooth Classic link forces the connection from A2DP over to SCO, which drops
everything being monitored to 8 or 16kHz. Measured on the reference device: with the
built-in mic open, `Bluetooth SCO on` stayed false and `A2DP suspended` stayed false --
the link is untouched and the earbuds keep music quality.

Two bugs found by using it, both of a kind no test would have suggested:

- Asking for a permission **pauses the activity**, and `onPause` stops the engine -- so
  the grant callback ran with no output stream for the microphone to be read alongside,
  and enabling always failed the first time. It now defers to `onResume`.
- The speaker guard was checked once, when the mic was switched on. That left the
  dangerous state one gesture away: take the headphones out and the device is listening
  to its own loudspeaker with nothing noticing. A guard that only holds at the moment you
  pass it is not a guard, so an `AudioDeviceCallback` now watches the route and switches
  the mic off when the output moves to the speaker.

`inputEnabled` was also being persisted with the patch, so a crash or a force-stop with
the mic on came back showing a live In rail with no stream behind it. Whether the
microphone is listening is runtime state, like the master output, and is no longer
written to the file.

### What the node tests caught

Three of the nineteen only passed after the *test* was fixed, and each was a wrong
assumption about the DSP rather than a typo:

- Counting a saw's resets by threshold is unreliable, because polyBLEP smears that edge.
  The same signal reported 493, 411, 313 or 259 cycles depending only on where the
  threshold sat. Zero crossings in one direction are unambiguous. Before that, looking
  for a *downward* jump found none at all -- DaisySP's saw descends and resets upward,
  which read as an oscillator producing silence rather than one running upside down.
- `DcBlock`'s time constant is ~100ms, not the ~20ms assumed, so "settles to nothing"
  needed four times the window. The envelope's exponential tail likewise outlives its
  nominal release.
- The clock is asserted by *interval*, not count: it starts its first beat on sample
  zero, which is a tick but not an edge, and a count says nothing about regularity.
  Because it counts frames rather than consulting a timer, 24000 frames a beat is exact,
  so the test asserts it exactly.

### Route changes

Plugging headphones in or out closed the stream and left it closed until the app was
backgrounded and resumed. It now reopens on the new route, and deliberately does not
reset the graph: a route change must not cost you your patch.

## Phase 5 -- Playability

**Parameters are done and on the device.** Every module has knobs, declared with the
thing they describe -- range, curve, unit -- and crossing to the engine in real units so
a node uses what it is given and the interface can say "1000Hz" rather than "0.63".
Frequencies and times are exponential because hearing is.

### The panel

An opened module takes the whole screen bar a border, with its jacks on the edges and a
stub of cable running off past each connected one: enough to say what is attached, not
enough to pretend you can trace it. Following a cable means closing the panel, which is
the trade that buys knobs this size.

This roadmap called for a bottom sheet, and that was wrong. On a 443dp-tall landscape
phone a sheet of four sliders takes 45% of the canvas and is modal, hiding the patch you
are listening to while you turn the knob. Growing modules in place was the other
candidate and was also wrong: it costs the dense view permanently, for controls only
wanted one module at a time. A panel costs nothing at rest.

Because it is screen space, a module in the canvas never changes size -- its jacks never
move and no cable ever jumps, which growing in place would have caused. It owns the
screen while open, so it has its own short gesture loop rather than another outcome
bolted into the canvas one.

### The panel needs more than one control

A horizontal bar is the right default and the wrong universal:

- **Stepped parameters are radio buttons.** Done. A bar cannot show what the options
  are, which is tolerable for a length and useless for a waveform: dragging to pick
  "square" out of four unlabelled positions asks you to know the order by heart.

  The waveforms are drawn rather than named -- the shape is the name, and reading it
  needs no translation from the word "saw". All four glyphs are sampled from a function
  rather than hand-drawn as paths, so they stay consistent with each other and the
  near-vertical edges of the saw and square read as vertical at this size.

  **Two cycles, phased the way these glyphs are conventionally read.** One cycle was
  wrong: a single descending ramp is a slope, not a sawtooth, since the reset is the part
  that names it. Phasing them all to start and end at zero was wrong for the same kind of
  reason -- it is right for sine and triangle, but it put the saw's reset in the middle of
  the glyph and the square's edges at the quarter and three-quarter points, which reads as
  an off-centre pulse. The saw starts at the top of a ramp and the square starts high, so
  both switch on the cycle boundary.

  **The saw glyph descends**, because that is what comes out: DaisySP's polyblep saw
  computes the rising ramp and multiplies by -1, confirmed both in `oscillator.cpp` and
  in a capture of the real output. The conventional rising glyph would be prettier and
  wrong.

  The maths behind the buttons matters more than it looks. `valueAt` floors rather than
  rounds, because rounding gives the first and last options half the width of the rest --
  so the two ends of every selector would be twice as hard to hit as the middle. And the
  option count is asserted against the engine's waveform table: an extra waveform in
  `nodes.cpp` without a wider range here is a button the interface can never offer.
- **`Steps` has a grid.** Done: sixteen columns of step, rows of scale degree, tap to
  place a note and tap it again to make it a rest. Verified end to end on the device --
  a figure drawn on the grid comes back out of the capture as the pitches it was drawn
  as, looping at the clock's tempo.

  **Rows are degrees, not semitones**, which is what makes it work for a diatonic scale
  at all: seven rows to the octave, every one a note you meant, and no way to land
  between them. In an equal division it degenerates to a piano roll.

  Every row carries its degree number in the gutter, and the tonic of each period is
  tinted and bold. The tint alone was not enough -- it stops orienting you the moment
  you scroll past it, which on a nineteen-degree scale is most of the time.

  The grid is anchored to its **bottom** row rather than its top. Anchoring to the top
  meant guessing how many rows would fit, and being one out put the tonic exactly one
  row below the fold, so the landmark the tint exists to provide was the one thing never
  drawn. From the bottom, the lowest note of the figure sits on the last row and no
  guess is needed.

  **Tunings are Scala `.scl` files**, seeded into a folder on external storage that any
  file manager can reach without a permission. Chosen because the format already exists
  and there are thousands of scales written in it -- inventing one here would mean asking
  people to retype work that is already done. Nothing writes them: editing a tuning on a
  phone is nobody's idea of a good time, and the format exists so that work can happen
  elsewhere. The bundled set is seeded rather than hidden in the APK so the folder is
  never empty, and so the shipped files double as worked examples; seeding only fills in
  what is absent, because a file the user edited is theirs.

  Two things about the format catch people out and both are asserted: the unison is
  implicit and never listed, and the *last* entry is the period rather than a playable
  degree -- which is exactly the period this model already had, and the reason
  Bohlen-Pierce needs no special case. A blank description line is a line and not an
  absence, which if skipped parses happily and is wrong by one degree.

  Verified end to end: a hand-written five-note Slendro dropped into the folder loads,
  and grid degrees 0, 2, 4, 6 reach the engine as 0.0, 0.4, 0.8 and 1.2 octaves -- the
  last correctly wrapping past the period. A deliberately malformed file alongside it is
  skipped with a log line.

  **Four things learned by using it.** The loop length was invisible: sixteen columns
  always drew the same whether six were playing or all of them, so the boundary is now a
  line and the columns past it are properly dark rather than faintly dim. They still hold
  their notes, because coming back from a short loop to a long one and finding the old
  bars intact is worth keeping.

  **A rest holds the pitch before it.** The first version emitted the rest's own
  remembered degree, on the reasoning that a rest "still holds its pitch" -- which was
  precisely backwards. The remembered degree exists so switching the step back on
  restores the note that was there; it is not a note, nobody can see it, and emitting it
  made the pitch jump for no visible reason. Holding is also what a hardware sequencer's
  pitch output does, being a sample-and-hold that a rest simply never clocks.

  Reported as "the rectangle disappears but the note still plays", and the report had two
  causes. The visible one was this. The other was a patch with no `Env` and no `VCA` and
  the gate output unpatched, where nothing controls amplitude and a gate has nothing to
  act on -- correct modular behaviour, and worth knowing that the grid gives no hint of
  it.

  A silenced step used to draw a grey box at the pitch it remembered. That was a lie
  about what you would hear -- a rest is the absence of a note, not a note in another
  colour -- so it draws nothing now. The degree is still remembered underneath, which is
  what lets tapping the same cell bring the note back.

  A note scrolled out of view left a column looking empty, which was indistinguishable
  from a rest. Notes above or below the visible rows now leave a triangle on the edge
  they went past.

  And the sequencer shows what it is playing. That needed the first value to travel back
  up out of the engine: commands go down a queue because they must all arrive and in
  order, but a playhead is the opposite -- only the newest matters and a missed update is
  a frame nobody saw -- so it is an atomic the audio thread publishes and the interface
  polls per frame, only while a sequencer's panel is open.

  **The tuning is chosen from a chip in the sequencer's header**, which opens a page of
  tiles over the panel body. The scale belongs to the patch rather than to the module,
  but the header of a sequencer is where you are standing when you want it -- the grid's
  rows *are* the scale. Two sequencers share one tuning, which is the intent: a patch has
  a key the way it has a tempo. Each tile carries the degree count, because that is what
  visibly changes about the grid, and the period when it is not the octave, because a
  tuning that does not repeat at the octave is the thing most worth knowing before you
  pick it.

  Tiles rather than a scrolling list: four columns by four rows holds twenty-odd scales
  without paging, which covers the shipped set and a generous number of the user's own.
  A test asserts the whole shipped library plus six more fits, and that no tile overlaps
  its neighbour.

  **Transposition is in cents**, and so is the oscillator's tune. A semitone is a fact
  about twelve-tone equal temperament and means nothing in 19-TET or Bohlen-Pierce, where
  those knobs still have to work; cents are a logarithmic unit of pitch belonging to no
  tuning in particular. Range is two octaves either way, which is also enough to reach a
  full turn of a non-octave scale -- a tritave is 1902 cents, and a transpose that ran out
  before the scale repeated would be the wrong control.

  The sliders are **ticked at the degrees of the current scale**, taller at the tonic,
  drawn under the bar where the name and the reading are not. Nothing snaps: cents are
  continuous on purpose, and a knob that jumped to the nearest degree would make the
  cent-sized adjustments the unit exists for impossible. The marks say where the notes
  are; the hand decides whether to land on one.

  Verified by measurement: asking for 700 cents produces 391.966Hz against a theoretical
  391.995Hz, an error of 0.13 cents.

- **Superseded, kept for the reasoning:** Verified working end to end on the device at last: given a
  `Clock` into its gate input, it steps the pattern at exactly the clock's tempo
  (measured +10, +12, +10, +7, +3, 0, +3, +7 semitones from middle C, one step every
  333ms at 180bpm, with the envelope shaping each note). Nothing is broken; there is
  simply no way to change the notes. It plays a hardcoded pentatonic figure with no way
  to edit it, which is the clearest case for the panel: sixteen steps of pitch and gate is
  unrepresentable on a 116dp module and unremarkable across a screen. This is the
  argument that settled the panel design in the first place.
- An envelope would read better as a draggable ADSR curve than four bars.

So the panel is a **per-module-type editor surface**, not a generic list of sliders.

### Still outstanding

Per-input **attenuverters**, without which CV routing is unusable in practice: with
advisory typing, a full-scale CV into a cutoff sweeps six octaves, and the only control
over that today is whatever drives the cable. **Superseded in direction:** Phase 6 adopts
modulation onto controls, where depth is a range stored on the target in its own units,
and defers it on the gesture.

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

Also here: per-input attenuverters -- superseded in direction, as above.

## Undo

Done and verified on the device.

**One stack of whole patches, not a log of inverted commands.** A patch serialises to a
couple of kilobytes of the JSON the autosave already produces, so fifty states cost
nothing and there are no inverses to get wrong -- the inverse you forget is a silent
corruption rather than a crash, and every new module type would be another chance to
forget one.

**Fed from the autosave debounce**, which is the part that makes it usable rather than
merely correct. That 500ms already answers the hard question -- when is an edit finished
-- so a continuous knob drag arrives as one undo step instead of three hundred, with no
coalescing logic of its own.

**Scope is whatever is in the file**, which turns out to be exactly right: modules,
positions, cables, knobs. The camera, the open panel, the master output switch and the
microphone are absent from the file and from undo alike. None of them is an edit to the
patch, and an undo that moved the camera or silenced the output would feel like a fault.

**A restored state becomes the current state before it is applied**, so when it comes
back around through the autosave flow it compares equal and records nothing. That is
what stops an undo being pushed onto its own stack, without a re-entrancy flag and the
race that comes with one.

**The replacement is applied inside one mutable snapshot.** Not tidiness: `GraphSync`
watches the patch through a snapshot observer, and applied a mutation at a time it would
see the instant after the cables are cleared. An empty patch is a state the engine
renders faithfully, fading every voice out and back in -- undo would click. The test for
this asserts no observer ever sees the patch with its cables gone, and it is the one
test here that catches something you would otherwise only hear.

**Restoring goes through the model, never around it.** The snapshot lands in `Patch` and
the same `snapshotFlow` that carries an ordinary edit carries this one to `GraphSync`,
which diffs it and sends only what actually changed. Undo is not a special case to the
engine.

Two buttons in the bottom-left, drawn in the canvas in screen space like the rails,
hidden when there is nothing to undo or redo. The corner is the one nothing else claims:
the In rail is centred on the left edge and the gesture bar is already excluded by the
inset. Rejected: a two-finger tap (fights the pinch), a three-finger swipe
(undiscoverable), and a long-press menu entry (two gestures deep for the control you
reach for when the last thing you did was wrong).

Hidden rather than greyed, because a disabled control promises that something could
happen there; at the start of a session nothing could.

The device found the bug the suite could not, again. The buttons drew correctly and did
nothing, because `pointerInput(Unit)` captures its closure once: the hit test was reading
`canUndo` as it stood at launch, which is false, while the draw lambda -- rebuilt every
recomposition -- had the truth. `rememberUpdatedState` fixes it, and it is now an
invariant in CLAUDE.md because every future on-canvas control will meet it.

Verified after the fix, with the master output live: an undo that repatches Out L sent
exactly one command, `connect 102[0] -> 1[0]`, and the redo one the other way. No
disconnect, no teardown -- the atomic snapshot held, so `GraphSync` never saw the patch
with its cables cleared. In the capture the transitions are a 30ms monotonic ramp, and
the largest single-sample step across them is 0.0762, identical to the steady-state
saw and to the channel that was never touched. Undo is silent.

**The buttons float over an open panel**, in the same screen position they occupy on the
graph. Confined to the graph they were close to useless for the case that needs them
most: a knob change is only visible while its panel is open, so undoing one meant closing
the panel, undoing where nothing could be seen, and reopening to find out what happened.
Same position in both contexts, so the control never moves under a thumb -- and the
panel's knob rows are inset by `PANEL_SIDE`, which leaves that corner free. Asserted, not
assumed: a geometry test checks the buttons against every module type's rows.

The buttons overhang the panel's bottom edge, where a tap would otherwise be read as
tapping away to close, so the panel's gesture loop checks them before the knobs and
before that dismissal.

For the same reason `replaceWith` carries the open panel across by id. Undo changes the
document, not the view -- the camera does not move and neither should the thing you are
looking at. Free modules are rebuilt as new objects, so without it every undo slammed the
panel shut at exactly the moment you wanted to watch.

**An undo pulses whatever it disturbed**, for 450ms, in warm white over the module's
outline. The graph draws no parameters at all, so an undone knob was a change in the
sound with nothing on screen accounting for it -- "what did that?" with no answer.

Re-opening the module's panel was the other candidate and was rejected: opening a panel
is a big view change and undo is a small, repeatable action, so walking back four steps
would become panel-opens, panel-closes, different-panel-opens. The pulse is non-modal,
costs nothing when you are not looking at it, and works uniformly for every kind of
change rather than only the parameter case -- a moved module, a repatched cable (both
ends), a module that reappears. If you want the detail, the module is right there to tap.

The change set is computed before anything moves and `replaceWith` returns it rather than
pulsing anything itself, so loading a patch from a file can stay silent while an undo does
not.

History is in memory only, so it starts empty each launch. A restore-across-restart would
need the stack in the file, and that is a patch-library question rather than an undo one.

## Phase 6 -- Notes, time and tuning

**Designed, not built.** Settled in discussion on 2026-09-12, before any code, because it
reverses several things recorded above.

The goal that forced it is a polyphonic sequencer in the shape of Bespoke's
`dotsequencer` -- a grid where a column can hold a chord -- and the current engine cannot
express one. That is structural rather than a missing feature: every port is one
32-sample buffer, an input has one source, `Steps` stores one pitch per step and emits one
pitch and one gate, and an `Env` has one envelope for its one gate. Polyphony in that
model means building a voice and copying it, which is where Phase 7's "a three-voice
patch is twelve nodes" came from.

### Notes are events

**Built and verified on the device** -- 69 graph checks, 77 node checks, 171 JVM tests.
Measured, not heard: nobody has yet listened to it. Everything below the horizontal rule was written before any
code and has held; what follows the rule is what building it changed. Audio and control
signals stay exactly as they are, advisory typing included. Notes are typed, because an
event is not a voltage -- this is where "it is all voltage" stops applying.

```cpp
struct NoteEvent {
    uint32_t id;        // chosen by the source; Off finds its note by this
    uint16_t offset;    // sample within the block
    uint8_t  kind;      // On, Off -- Change reserved for per-note expression
    int32_t  degree;    // step in the current scale, running past the period
    float    cents;     // glide, bend, pitch tracking
    float    velocity;  // 0..1
};
```

**Not MIDI.** Bespoke uses MIDI messages internally because people plug MIDI devices into
computers; nobody does that to a phone often enough to shape the core around it. MIDI in,
if it ever lands, translates at the edge.

- **Every note has an id.** MIDI finds a note-off's note by matching pitch, which is
  untenable once pitch is continuous -- it means comparing floats for equality -- and
  awkward even without that: Bespoke's DotSequencer turns off "any colliding pitches"
  before starting a note. SuperCollider's client picks node ids so it can address a synth
  in the same bundle that creates it, and CLAP carries a `note_id`. Two dots at the same
  pitch are two notes.
- **On and off, not start and duration.** A sequencer knows how long a note is; a finger
  landing on glass does not. A sequencer schedules its own off.
- **Pitch is a scale degree plus cents**, resolved against the global scale by the voice
  that sounds it. This moves degree-to-octave conversion out of `GraphSync` and into the
  engine, which now holds scale tables -- but the engine still never learns what a
  semitone is, which was the point of that rule. Octaves on the wire was the other
  candidate and lost: every scale-aware note operation (up a step, quantise to the key)
  would become a nearest-degree search on floats.

  SuperCollider separates a `Tuning` from a `Scale` chosen within it, which buys an
  accidental that means the same thing in every tuning. Rejected: one `.scl` per mode is
  how these files are actually used. The cost is that a note outside the mode is either a
  cents offset or a different scale.
- **`Change` is reserved and unimplemented.** It is per-note expression -- what MPE does by
  giving each note its own MIDI channel, since MIDI has no id; here the id already does
  the channel's job. The only likely producer is a touch keyboard (open question 6), where
  Android already reports every finger separately. Reserving the kind now keeps that from
  being a format change later.

**Delivery mirrors audio.** Each note output has a preallocated event buffer, filled
during its node's `process()` and read by whatever it feeds, in topological order; a back
edge delivers one block late, exactly as a buffer does. Nothing allocates.

**Repatching a note cable cannot crossfade** -- there is no signal to fade. What it must do
instead is end what it started: disconnecting a source sends `Off` for every note it has
sounding through that input, or the voice hangs.

**Whether a note input takes several sources is open.** Single-source exists to stop
hidden summing of signals; merging two event streams hides nothing, and Bespoke allows it.

**Voices are allocated by whatever sounds the notes**, from a preallocated pool, keyed by
note id. Where the voices come from is the open part: a curated synth module with its
voices built in, as Bespoke does, or a patched voice from Phase 7 stamped out N times --
SuperCollider's "a note is an instance", with the instances made in advance because the
audio thread cannot make them. Notes are the first step either way.

---

**What a note carries that the design did not say.** Two fields, both because of decisions
taken here rather than second thoughts:

- **The whole beat it starts on.** Which scale a note sounds in is decided in integers from
  a tick's count and never from the transport's floating position -- and only the clocked
  node that emitted the event knows that count. Resolving it again in the voice would mean
  asking the transport where it is, in floating point, which is exactly the arithmetic the
  scale section exists to avoid. So the decision travels with the note.
- **Which source it came from**, stamped by the graph as it merges. A note input takes
  several sources and each picks its ids as though it were alone, so two sequencers into
  one voice collide on every id without it. It is a source's *slot* and not its position
  in the list, so unpatching one does not renumber the notes another still has sounding.
  A voice keys what it is playing on the pair, and `notesCut(port, source)` ends exactly
  one source's notes.

**A note input takes several sources, and merges them.** The open question is answered:
single source exists to stop signals summing where nobody asked, and merging event streams
hides nothing -- every note stays itself and arrives when it arrived. Bespoke allows it and
a voice fed by two sequencers is the obvious patch. The interface follows: a second note
cable adds rather than replacing, and patching a pair that is already patched removes that
one cable, which is the only way a finger has to take back one of several. `GraphSync`'s
rule that a connect supersedes a disconnect is now limited to signal inputs -- on a note
input nothing was replaced, so the cable that left still has to be said.

**Note ports share the port index space with signal ports.** A cable is a cable to
everything that routes one, so the command queue, the topological sort, the file format
and the interface's model needed no second notion of a port. What differs is the buffer
type, that there is no crossfade, and that the ports are typed: a mask per node says which
indices carry notes, and the one place a patch is refused for what it carries is
`Patch.connect`. Refusing leaves the port armed rather than disarming silently -- a tap
that did nothing and forgot itself would look like a tap that was never seen. **Whether
that reads as a refusal or as a bug is a judgement for the phone.**

**Voices come from a curated module.** `Voice` is eight voices of oscillator and envelope,
allocated by note id and source, summed like Mix -- a chord is louder than a note, which is
true of every instrument. The other candidate, a patched voice from Phase 7 stamped out N
times, is not ruled out and can coexist; this one can exist now. A note takes a free voice,
then the oldest already released, and only then steals one still held, softly. The name is
the collision the naming pass already knows about.

`kMaxParams` is five, because an envelope needs all of A, D, S and R and the waveform is a
fifth control. Nothing is stored per parameter, so it bounds a command index and nothing
else; the panel divides its body by the rows it has, so they get shorter rather than
overlapping. A sequencer is where that runs out, its grid already taking two thirds.

**A test found a bug for once, which is worth recording because the pattern here is the
opposite.** Every note of a chord starts on the same sample, and a voice was only counted
as taken once its envelope had processed a sample -- so three notes at one offset all took
voice zero, each overwriting the last, and a chord sounded like one note. Caught by the
test asserting three notes sound, before any of it reached a device. The fix is a flag set
when the voice is claimed rather than a question asked of the envelope.

**On the reference device**, against the patch that was already on the phone -- two
sequencers at 136bpm through a two-entry scale list with a key change in it:

- **The same sequence, said twice.** One `Steps` into an `Osc` on the left channel and the
  same `Steps` into a `Voice` over a note cable on the right. Of 37 windows sampled across
  ten seconds, 30 agree to *exactly* 0.0 cents. The other seven are the measurement rather
  than the signal: five are the autocorrelation locking an octave down, one is a window
  straddling a note boundary, one a rest -- which the two paths render differently on
  purpose, since a rest holds the pitch on a CV output and starts no note at all on a note
  output.
- **The key change lands on the note path too.** The capture happens to contain a switch:
  everything before 3.7s is the same figure exactly 500 cents below what follows, which is
  the second entry's root. The two paths agree across it, so the beat carried on the event
  picks the same table entry as the sequencer's own arithmetic.
- **No clicks.** With the voice on a sine, where every step is a real discontinuity rather
  than a waveform's own edge, `find_clicks.py` reports **zero** over ten seconds of notes
  starting, ending and stealing voices. With two sequencers merged it reports one, at a
  sample on no boundary at all -- and the samples either side are a clean sine through its
  steepest point, flagged only because a note had just raised the amplitude past a
  threshold derived from the quieter passage.
- **Polyphony, on hardware.** Two merged sequencers at different intervals and lengths, into
  one voice: up to four notes at once, and two or more in eighteen of nineteen windows.
- Exclusive MMAP throughout, 96-frame burst, 4.1-5.7ms, **0 xruns** with the voice running.
- The five-row panel fits, and the first row's label clears its buttons -- by one pixel at
  this density, which is tight enough to be worth knowing before a sixth row is considered.

**Not done: hearing it.** Everything above is measured from captures and screenshots. The
defects that have mattered in this project were all found by a person playing it, and
nobody has played this yet.

An emulator settles the rest cheaply. A Pixel 8 AVD opens AAudio *shared* with no MMAP, a
960-frame burst and 86-115ms, so it says nothing about latency, clicks or timing -- but it
runs the app, and the same two captures on it agreed the same way. Worth knowing, since it
cost a confusing minute: it runs landscape, and its gesture bar swallows a tap near the
bottom of the screen, which reads exactly like a tap the app ignored.

### The transport

**Built and verified on the device.** 45 graph checks, 39 node checks, 149 JVM tests.

On the reference device, 2026-09-12:

- The real format 1 patch on the phone -- a Clock at 150bpm into a four-step arpeggio --
  upgraded on launch: `tempo 150.0` reached the engine, the Clock and its cable were gone,
  and the autosave rewrote the file as format 2.
- In a capture played, paused and resumed, every note onset sat on the 9600-frame grid
  of a 1/8 step at 150bpm, on both sides of the pause. Switched to 1/16, 38 consecutive
  onsets were 4800 frames apart. No boundary-aligned discontinuities, 0 xruns.
- The card: dragging the tempo sends whole beats per minute as it moves; reset restarts
  the position at bar one; two undos put back beats per bar and then the tempo, and the
  engine received the restored tempo; the undo button stayed reachable with the card open.
- The position survived the app being backgrounded and the graph rebuilt.

**Two defects the host could not see, both found in screenshots.** The panel drew the
interval as a row of buttons laid over the length row, because the knob loop still walked
every parameter while the hit test walked only the rows -- so it showed intervals where
taps set the length. The geometry tests assert rectangles, not what is drawn in them. And
the card's "beats per bar" label ran into its buttons, since a measured text height
includes line spacing; the row is 8dp taller.

Heard by ear: two Steps modules at different intervals play in sync. The same session
also noticed that the tuning chip changes every sequencer while living inside one --
which is the case for moving it out of the sequencer header, below.

**One position, owned by the engine** -- a tempo and a beat anchored at a frame count, in
`transport.h`. The commitment at the top of this document is unchanged; only its owner
moves, from a `Clock` node to the graph. Beats per bar lives in the patch, for reading
the position as bars; nothing in the engine divides by it yet.

**Intervals are ratios, not doubles.** A node's boundary count is floor(beat * den / num),
and because rounding is monotonic a beat and the triplet starting on it land on the same
frame -- asserted over ten minutes at 127bpm, where every beat is also a sixteenth and a
triplet on the very same frame, and every beat lands within one frame of exact. One frame
rather than zero, because every 127th beat falls exactly on a frame and can round a hair
late; every division of that beat is late by the same frame. Mutation-checked: counting a
truncated period per interval, which is what two Clocks did, puts beat 1270 210 frames
late.

**A tick carries its count**, so a sequencer's step is the count modulo its length rather
than a tally of ticks it has seen. That is what makes reset and resume need nothing from
the nodes.

**The output switch is play and pause.** Off stops time where it is, and on picks up from
there; the transport also survives the graph being rebuilt when the app comes back to the
front. A note sounding when time stops holds rather than running out, so resuming
continues the same note. Reset is a button in the transport's card and goes straight to
the engine -- a performance action, like the output switch, so not saved and not undone.

**A clocked module picks an interval instead of taking a cable** -- whole note through
64th, triplets, dotted, and multiples of a bar -- from a chip in its header, as Bespoke's
dropdown does. That makes a fast lead against a slow bass, or a polyrhythm, a setting
rather than a patch. Every division is computed from the one shared position, so no two
can drift. Two `Clock` nodes could: each truncates its own period, and 127bpm is 22677.16
frames a beat. Starting the transport puts everything on bar 1 together, which nothing
does today.

`Clock` is gone, and so is `Steps`' clock input. The file format is now 2, and a format 1
file upgrades rather than being refused: its Clock's tempo becomes the patch's, and the
module and its cable drop out through the checks that already skip unknown types and
missing ports. Nothing in format 1 was worth keeping; the upgrade step exists as the
pattern the next format change copies.

**Note length is fixed at half the step**, not the knob this section planned. It used to
be the width of the clock's gate, and a replacement knob would be a third row in the
roughly 106dp a sequencer panel leaves for knobs on the reference device, where two rows
already fill it. The dot sequencer carries a length on every note, which is where the
control belongs. The interval is chosen from a chip in the panel header, beside the
tuning, with a page of tiles like the tuning's.

**Found on the way:** `replaceWith` never copied the scale, so undoing a change of tuning
kept the new tuning. The byte-identical round-trip test could not see it, because both
sides were in the default tuning. Fixed alongside the tempo, which travels the same path,
with a test taken in a non-default scale and mutation-checked.

**Pulses are designed for and not built.** Bespoke has a third event type carrying only
timing -- `OnPulse(time, velocity, flags)`, with flags for reset, backward, random and so
on -- which is how it does rhythm the transport cannot: chance, delays, hocketing. Not now:
each pulse input costs screen space, and the need is unproven. What keeps the door open is
small: a clocked module advances through one entry point, "tick at sample offset *k*". The
transport calls it now; a pulse cable would call the same thing later, over the same event
path as notes with a different payload.

### The scale, and changing it

**Built and verified on the device.** 55 graph checks, 45 node checks, 157 JVM tests.

- `scales.h` holds the tables. `Steps` sends degrees, and resolves each note against the
  scale of the beat it starts on. The list crosses as one pointer built off the audio
  thread, with the list it replaces handed back to be freed -- ASan's leak check at exit
  is what makes that a test. The file format is 3; a format 2 file's scale becomes a
  one-entry list, and the phone's own did.
- **Which scale a note gets is decided in integers.** A note's beat is
  floor(count * num / den) of the tick that started it, and a switch lands only on a whole
  beat, so a note on the switch beat takes the new scale with no rounding anywhere. The
  triplet on the switch beat is the case floating arithmetic could get wrong, and has its
  own test. Mutation-checked: switching a beat late fails four checks. Recording the beat
  on a rest as well as a note fails the held-note check -- but only once that test looked
  a block later, because its first version read the one sample the bug could not reach.
- **One degree limit, not two.** The `.scl` parser already refused scales over 128 degrees,
  for the grid's sake. It is 64 now, mirroring `kMaxDegrees`, so a scale the engine would
  have cut short at the top fails to load and says so instead.
- On the device: a list of Harmonic minor and 12-TET, one bar each at 100bpm, captured and
  pitch-tracked. Every note on beats 4-7 and 12-15 was in 12-TET and every other in
  Harmonic minor, including the notes on the switch beats themselves. A first attempt
  with Major proved nothing, because the patch's figure used only degrees Harmonic minor
  and Major share. A burst of screenshots taken on the phone showed the chip alternating
  between the two entries at the same rate.
- The chip's label ran off both ends on the device -- "Harmonic minor · 1 of 2". The chip
  is wider, and a long name now gives way to an ellipsis while which entry is playing
  never does.

**The grid does not reflow, and that is the answer.** Looked at on the device while a
list cycled between scales of different sizes: nothing visibly changes. The grid shows
the scale being edited rather than the one playing, which settles the question the section
below left open -- by use, not by argument, which is what it was arranged for.

**Keys change the same way.** A `.scl` file holds degrees and no reference pitch, and
degree 0 had been middle C, hard-coded, with nothing but per-module transposes to move
it. Each entry now carries a root, in cents above middle C, added where a degree becomes
a pitch -- so a key change every few bars is a list of entries, lands on its beat, and a
held note keeps the key it started in, all without a mechanism of its own. Stored as
`root` in each entry; files without one were in C, so no format change.

The card steps a root by 100 cents, which reaches every key a twelve-note scale is written
in. Tapping the value opens a page with a slider ticked at the entry's degrees, where a
tap within 8dp of a mark lands on it exactly and a drag never snaps -- the user's design,
so a degree like 19-TET's 189.5 cents is exact without making the cents between marks
unreachable. The reading gives the frequency and the nearest twelve-tone letter, marked
"≈" when the root is not on that grid. `.kbm` keyboard mappings, Scala's own answer, were
not needed for this.

Built and passing on the host -- 58 graph checks, 48 node checks, 165 JVM tests --
mutation-checked by ignoring the root in the table, never snapping, and not saving the
root.

**Verified on the device.** A list of 12-TET in C and 12-TET in G, a bar each at 122bpm,
pitch-tracked from a capture of a five-step quarter-note figure: every change landed
exactly four beats apart, every note in the G bars sat seven semitones above its C
counterpart -- and the one rest that fell on the first beat of a G bar held the C note
before it rather than jumping to G, which is the held-note rule for keys seen in sound.
On the root page, a tap 3dp from the 500-cent mark set exactly 500, a drag set 844 and
did not snap, and -1 cent made it 843. The page reads "+844¢ · 426.0 Hz · ≈A♭".

A wrong turn worth recording, since it looked like a bug and was not: the first scripted
run of these taps assumed a one-entry list, and the list on the phone had grown to two.
A tap meant for "add" opened the second entry's picker, and the next tap chose Meantone.
Drive the device from what a screenshot shows, not from what the last session left.

**The scale belongs to the patch**, as Phase 5 already argued, and moves out of the
sequencer's header into a chip of its own.

**It can be a list.** Entries are a scale plus a length in bars and beats, and the list
loops -- so the same figure four bars in each of three modes is three entries of 4 bars, 0
beats. Bars because that is almost always the boundary wanted; beats so it is not the only
one. The beat is not subdivided. A one-entry list is simply a fixed scale, so there is one
mechanism rather than a static mode and a progression mode.

- **Which entry is current comes from the transport position**, modulo the list's length in
  beats, never from a counter. Starting, stopping or editing mid-play lands somewhere
  deterministic, for the same reason clock divisions cannot drift.
- **The switch lands on its exact sample.** A note starting on the switch beat uses the new
  scale; one starting a sample earlier uses the old. The engine holds every table in the
  list in advance, which means a fixed cap on degrees per scale.
- **Held notes keep their pitch.** A voice resolves its degree once, at note-on. Retuning a
  sounding note was considered and rejected: a held major third dropping to a minor third
  mid-note is a step with no ramp, the transient every crossfade in this engine exists to
  prevent.
- **Degrees map by position.** Degree 6 of a seven-note scale becomes degree 1 of the next
  period in a five-note one -- the figure keeps its shape and spreads upward. Snapping to
  the nearest pitch instead is a key change rather than the same figure in another mode,
  and may be an option later.

The sequencer grid while the scale cycles is undecided: its rows are the scale, so it
either reflows every few bars or shows the scale being edited rather than the one playing.
The device should settle it.

### Chips that float

**Transport and scale are small chips, always on screen, that expand on tap** into a
larger card over whatever is showing -- graph or open panel -- without being modal. The
undo buttons are the precedent: screen space, hit-tested by one function both gesture
loops share. An expanded card takes touches inside its own bounds and nowhere else, and
closes only from its own chip, because a tap outside it is doing something else.

The transport chip is built, top-left: it shows the tempo, and its card holds the tempo
bar, beats per bar, the position as bar and beat, and reset. The position is polled only
while the card is open, so a closed chip costs no repainting. The card covers the In rail
and the top of an open panel while it is open, which is the price of not being modal.

The scale chip is built beside it. It names the scale sounding and, while a list plays,
which entry of how many. Its card is the list: a row per entry with its scale and
steppers for bars and beats, a page of tiles to choose each entry's scale from, and
scrolling past the rows that fit -- about five on the reference device. The card hangs
from the scale chip rather than the corner, which keeps it to the right of the undo
buttons and lets it use the height down to the gesture bar.

### Modulation onto controls -- adopted, deferred

**Bespoke's model is the one wanted.** A modulator has one output, and you drop it onto
*any slider on any module*. The slider then moves between a low and a high value stored on
the slider itself, in its own units -- "sweep the cutoff from 400Hz to 2kHz" -- and animates
to show where it is.

That dissolves three problems at once. Every parameter becomes a target without a jack,
where `kMaxPorts` is 4 and a jack costs 44dp. Depth lives in the destination's units, which
answers the attenuverter problem rather than patching it. And polyphony sorts into three
tiers: a modulator on a control moves every voice together, per-note values ride on note
events, and per-sample signals stay on ports. FM into an oscillator is a signal it
processes, not a knob being turned, so ports and advisory typing stay for exactly that.

The engine side is cheap, and SuperCollider shows how: `/n_map` makes a control read from a
bus by swapping one pointer (`Graph_MapControl` in scsynth). Our inputs are already
pointers the graph rewires; parameters would be the same, pointing at their own value or at
a modulator's output, with the usual crossfade on a change. SuperCollider's mapping has no
range, and touching a mapped control unmaps it -- Bespoke's range on the target is the
better shape for a finger.

**Deferred because of the gesture, not the engine.** Targets live in the open panel, and
the panel owns the screen. How a modulator's output reaches a slider inside it is the
unsolved part (open question 7), and nothing is built until it is.

**Answered in Phase 7, by reversing one premise of this section.** The argument above was
that a parameter becomes a target *without* a jack, since `kMaxPorts` is four and a jack
costs 44dp. Both halves were true of the edges a module had when this was written. A new
edge and a separate index space make the jack affordable -- and the jack turns out to be
what lets a modulator reach a control it cannot see, which the jackless version could
never do across a scope boundary.

### Names

**Not Bespoke's, and not Eurorack's by default.** Bespoke calls a note source an
"instrument" and the thing that sounds it a "synth"; nobody would call a MIDI controller
that makes no sound an instrument. The current module names come from Eurorack -- `VCA` most
obviously, in an engine with no voltage in it.

To be settled in a deliberate pass, with one principle proposed: **name by what flows in
and out**, since that is what a finger at the picker needs and it is unambiguous. The
catalogue already knows every port's kind, so picker categories could be derived rather
than filed by hand. One collision to watch: "voice" is the obvious word for notes-to-audio
and also the word for one of the copies inside it. The eight-character limit belongs to
the phone, not to Eurorack, and stays.

The app's own name is part of the same question and equally open. Changing the name shown
on the launcher costs nothing; changing the `applicationId` makes it a different app to
Android, so an installed copy cannot update into it.

## Phase 7 -- Subpatches, and the end of CV

**Designed, not built.** The screen-space reasoning below predates it; everything from
*Opening a module is going inside it* onward was settled in discussion on 2026-09-14,
before any code. It answers open question 7, retires an invariant CLAUDE.md marked
do-not-touch, and changes most of the module catalogue. Four layouts were drawn against
the real 986x443dp landscape frame first, and the drawings are what settled it -- two of
the four turned out to be too small rather than merely worse, which is not something the
argument had reached on its own.

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

**Polyphony no longer comes from copying.** This phase was written when a voice meant Osc,
Env, VCA and Filter patched together, and three voices meant three copies. Phase 6 moves
polyphony onto note events and voice pools, so grouping is about screen space again, and
abstraction's stamped-out instances are one candidate for what fills a pool rather than
the only route to a chord.

### Opening a module is going inside it

**One gesture, and what you find depends on what the module is.** Open a composite and
there is a graph; open a primitive and there are its controls. The breadcrumb is the same
either way, so how deep you are is one thing to read rather than two modes to tell apart --
and the open panel stops being the gesture loop's exception by becoming an ordinary scope.

The panel is reinterpreted rather than deleted. Its generous controls are what a scope
offers when the thing inside is a primitive, and that number is what decided the layout: a
slider gets 380dp of travel inside a scope against the 68dp a 116dp module face could give
it. 68dp is 166 physical pixels at this density -- enough to set a filter, tight for an
attack time.

The sequencer decided it as well. A 16-step grid on a 116dp face is 7dp per step, and 13dp
even at double width, against the 24dp the panel gives it. A layout that cannot hold the
module the instrument is built around is not a layout.

Three alternatives were drawn and rejected. **Controls on the module face**, which is
Bespoke's own layout and Audulus's, on those two measurements. **Semantic zoom**, with
controls drawn only above a threshold -- it keeps the density and dissolves the same
problems, but it puts patching and tweaking at different zooms, and open question 2 is
already watching how much navigation this interface costs. **Keeping the panel modal and
giving descent its own affordance**, which is the smallest change and leaves both of the
problems it was meant to solve exactly where they were.

### What a cable carries

**CV and gate go.** They are Eurorack's answer to having one kind of wire, and this stopped
being Eurorack in Phase 6. Four kinds, which are Bespoke's:

| | |
| --- | --- |
| **audio** | samples, per frame -- unchanged |
| **note** | events with pitch, velocity and an id -- unchanged, built in Phase 6 |
| **pulse** | events without pitch: reset, retrigger, sample-start |
| **modulation** | a value driving a control between a low and a high stored on the control, in its units |

Pulse and modulation keep the colours of the gate and CV they replace, which is most of the
argument that they are the same idea said properly. `Node::tick` already anticipates the
first: "one entry point on purpose: a pulse cable, if one is ever built, calls the same
thing with a count of its own".

**Typing stops being advisory.** It was advisory because in hardware it is all voltage, and
because audio-rate modulation lives in exactly the connections enforcement would forbid.
The second half is answered rather than abandoned -- a module that wants audio-rate
modulation declares an audio input, as `Osc`'s `fm` already does. The first half simply
stops being true, since not one of these four is a voltage. Note to pulse is the single
allowed conversion, because a note implies a trigger; the reverse is refused, because
nothing says what pitch it would be.

**The catalogue changes more than the engine does.** `Steps` drops pitch and gate and keeps
notes, ending the "same sequence, said twice" that Phase 6 left deliberately in place.
`Filter` drops its cutoff jack and `Osc` its pitch jack, both becoming modulatable knobs.
`Env` becomes a modulator rather than a CV source. `Vca` retires outright: its entire reason
was a CV input, and what remains is a gain with a modulatable level. Every surviving module
gets *shorter*, because ports drive height -- so retiring CV buys canvas back before the
subpatch work spends any of it.

**Blocked on the rest of this phase, in that order.** CV cannot be retired until a parameter
can be modulated, and a parameter cannot be modulated until the mechanic below exists.

---

**The type system landed 2026-09-15, catalogue untouched, as the first of two commits.**
Splitting it that way keeps the format bump and the module deletions off the same commit as
the enforcement rules, so a regression has one obvious cause. 94 graph checks, 90 node
checks, 208 JVM tests.

**`patchesTo` is like-to-like, and the one designed conversion is not built.** Note into a
pulse input was to be allowed. It is refused, because building it in the model alone would
have made a cable the UI accepts and the engine silently drops: `graph.cpp`'s Connect case
rejects note against non-note outright, and a pulse is still the *gate buffer* it was
renamed from rather than an event. Both halves of that have to move together. A test pins
the refusal and says why, so the next person to try it finds the reason rather than the
gap.

**The design's own example was wrong about the code.** "A module that wants audio-rate
modulation declares an audio input, as `Osc`'s `fm` already does" is stated twice above and
in `CLAUDE.md` -- but `fm` was declared `CV`, and audio reached it only because typing was
advisory. So enforcement removed audio-rate FM rather than preserving it, which is the
opposite of what the sentence promised. Recorded rather than quietly fixed: the argument
that enforcement costs nothing was leaning on a module that did not do what it was said to
do. FM is now deferred to a future FM voice, where the operators are internal and FM is
what the module *is* rather than a jack bolted to a subtractive one -- decided 2026-09-15.

**The emulator found what the desk had not: enforcement was quietly eating cables.** A file
written while typing was advisory can hold a cable that is now illegal -- audio into a gate
was the one tried -- and both of its ports still exist, so neither the missing-module check
nor the port-range one caught it. `connect` refused it, the loader discarded the result, and
three of four cables came back. The file on disk had already been rewritten without the
fourth before anything was touched: no edit, no warning, no way back.

So a cable refused *for its kind* now refuses the whole file, where a cable naming a port
that no longer exists still skips quietly -- the version 1 migration depends on that second
behaviour, and the two cases are genuinely different. One is a file this build cannot read
honestly; the other is a cable whose module went away and which is meant to disappear with
it.

**And refusing had to stop meaning destroying.** The caller's only answer to a refused file
is the demo patch, and the next autosave wrote that over the file it had just refused --
so every refusal path, including the format bump this phase is about to make, was quietly
a delete. A refused file is now moved to `patch.rejected.json` first. One slot, overwritten
each time: keeping every rejected file needs a policy for clearing them out, and the one
worth having back is the one just refused. This matters more than the bug that found it,
because Phase 7's format 5 refuses *every* format 4 file by design.

Verified on the emulator: the log names both ports, the file is refused whole, and all four
of its cables are still in `patch.rejected.json` afterwards. **Not on the phone.**
Enforcement can only refuse patches that were previously accepted, and which of those a
finger will miss is not something the desk can answer.

### Modulating a parameter

**Expose it from inside; patch it from outside.** A parameter has no jack until you say so.
Inside the module, each control row carries a `[ ]` chip -- `[` marks the low end and `]`
the high, which is what the icon is saying. Tapping it grows two range handles on the row at
the control's current value, and a port appears on the module's **bottom edge** in the same
moment, so the consequence shows up where the gesture was made and the label is learned
immediately. Tapping it again un-exposes. Back out, and the port is on the module's face
where you left it; an LFO or an envelope patches to it with the ordinary two taps.

**The virtue is that no armed state crosses a boundary.** The obvious alternative -- tap the
modulator's output, descend, tap the slider -- needs a half-finished cable to survive a
navigation, where the gesture loop's whole property is that it decides once, on the first
move. Making the port exist first keeps the connection an ordinary connection.

**Bottom, because a module can grow downward and not sideways.** `portIn` takes the ports'
band height as a parameter rather than deriving it from the rect, precisely so that an open
module does not move its jacks -- so a band added below the body costs nothing. Width is not
symmetrical with height here: the same function reads `rect.left` and `rect.right`, so a
wider module moves its outputs and every cable attached to them jumps. **A full band pages
into a second row, never wider.** Three fit across 116dp -- inset 13dp each side leaves 90dp,
and three at a 45dp pitch clears the 44dp `PORT_PITCH` used everywhere else. `kMaxParams` is
five, so one more row covers the worst a module can ask for.

**`kMaxPorts` is not involved.** A modulation port is addressed by parameter index rather
than port index, and a parameter takes at most one modulator, so it is an array of
`kMaxParams` beside `inputs_` in its own space; the four signal ports are untouched. The
engine side stays the pointer swap SuperCollider's `/n_map` suggested: a parameter reads
either its own float or a modulator's buffer, with the usual crossfade on a change, both
sides live.

**Ports are told apart by name and position, not by shape.** Every port on that edge is the
same kind, so a shape would have to carry identity -- arbitrary, where colour carrying kind
is not. The band runs left to right in the same order as the rows inside, so the bottom edge
is a map of what you just saw. The names are already short enough to be the labels: `cut`,
`res`, `A`, `D`, `S`, `R`, `wave`, `len`, `bias`, `gain`, `lvl`. A truncation rule, not a
naming scheme.

**A parameter can only be modulated from its immediate parent, for now.** A composite built
for a library should expose a sensible handful of things to modulate, which needs a way to
promote a port up through a second boundary. That will be added and is not designed here.
It is explicitly allowed to be fiddly: exposing controls is something done once while
authoring a reusable module, not while playing, so the cost falls in the right place.

**Three things to remember when this is built**, each of which has already gone wrong once
in this project or is one line from doing so. The `snapshotFlow` in `MainActivity` must read
the ranges *and* the modulation cables, or the feature is inert exactly as parameters were.
`toJson` must emit them, because marking a range is an edit and undo goes through the file
format. And `PortRef` survives with a third `PortDirection` whose index means a parameter,
but `PatchStore` hard-codes `OUTPUT`/`INPUT` on load, so it is a format bump.

---

**Engine and model built, 2026-09-14; the gesture is not.** 90 graph checks, 90 node checks,
197 JVM tests. Nothing has been on the device, and nothing a finger can reach has changed:
the `[ ]` chip, its brackets and the drawn band are the next step. Everything above the rule
was designed before any code; what follows is what building the rest changed.

**Every parameter became modulatable at once.** The graph applies a modulator through each
node's own `setParam`, once per block, so no node learned that modulation exists. The price is
a 1500Hz control rate at 48k -- which is why audio-rate modulation stays a declared audio
input, as the design already said. A block reads its modulator's *mean* over the block rather
than any one sample: picking a sample is pure aliasing, and averaging is at least a crude
lowpass.

**The crossfade needed no new mechanism.** A parameter's route is an `InputRef`, and `repatch`
runs on it unchanged. The one reinterpretation is that no source means the knob's own value
rather than silence -- so patching fades from the knob, replacing fades between two live
modulators, and unpatching fades back, all in value space. A knob moved while modulated is
remembered, and applied nowhere until the modulator lets go.

**Ports take fixed slots, not packed ones.** "Three across, then a second row" did not say
which parameter goes where, and the first reading -- pack the exposed ones left to right --
slides a port along whenever a parameter before it is exposed. So a parameter's slot is its
position among the rows, gaps included: a `Voice`'s release is always the middle of the second
row, and exposing it alone makes a two-row band with an empty row above it.

**A new range is not at the knob.** The design had both brackets start at the control's
current value. Taken literally, the first modulator patched to it would do nothing, which reads
as a cable that failed -- so a new range reaches a fifth of the knob's travel either side, and a
stepped parameter gets all of its options.

**Only CV modulates, for now.** Audio and gate outputs are refused at a parameter's jack, as
notes already were at a signal input. CV is what becomes modulation when CV retires, so this is
that rule arriving early rather than a new one. An `LFO` exists so there is something to patch:
free-running in hertz, and unipolar because the destination owns the range.

**The three things were remembered.** The `snapshotFlow` reads the ranges; `toJson` writes them
as format 4, with a modulation cable saved against its parameter's *name*, like the knobs; and
`PortRef` carries `PortDirection.MOD`. `ports(MOD)` is deliberately empty, because every loop
over `ports(dir)` was written for two sides and puts a port on the left or the right -- a site
that forgets modulation draws nothing rather than a jack in the wrong place.

**The mutation check found two tests that could not fail.** Deleting the crossfade outright
passed the first version, which measured the steepest sample: a bias that jumps steps a sine
only by as much as the sine is at that one sample, and near a zero crossing that is nothing.
The fade is now measured by how long it takes, in windows longer than a cycle. And a threshold
worked out on paper failed against the real output, because Out's limiter already reads a peak
of 0.6 as 0.543 -- so tests comparing two levels stay below 0.4, and the rest judge against what
was measured. Seventeen of twenty mutations now fail a test. The three that do not are the reap
and `Add` clearing parameter routes, which `retire()` and `record = Record{}` already guarantee
and which stay as the same belt and braces the signal inputs have; and a straight-line fade in
place of smoothstep, which windowed peaks cannot tell apart.

**The gesture, built the same night and checked on the emulator, not the phone.** 204 JVM
tests; the seven new ones pin the panel's geometry at the reference device's measurements. On
the Pixel 8 AVD, with a patch written for the purpose: the band drew with its fixed slots -- a
`Voice`'s attack mid-way along the first row and its release along the second with an empty
row between, a `Filter`'s `cut` and `res` side by side, and `cut` unmoved when `res` appeared
beside it. The `[ ]` chip exposed resonance at a fifth of its travel either side of the knob.
Dragging cutoff's `]` sent only the high end, frame by frame and geometrically, and the knob
under it never moved. An audio output tapped onto a parameter's jack sent nothing and stayed
armed; an LFO sent one `modulate`; undo sent one `unmodulate` and left the parameter exposed.
Every range reached the engine before any cable that used it, which is the one path no JVM
test can reach.

**What the gesture decided.** The chip sits in the panel's right gutter, level with its row's
control, so it costs the bar no travel and stays clear of the output jacks' labels. A bracket
within 22dp of the finger is taken before the knob is, so a drag that starts on `[` moves the
range and not the knob. On a row of buttons the brackets go around the options rather than
through them, so a range of a single option still shows two. On the open panel the jacks are
spread along its bottom edge in row order and labelled *below* the edge -- a panel of four or
five rows fills its body, and above the edge would be on the last bar. A cable into a
parameter's jack arrives from below, rather than crossing the module it feeds.

**Not done.** Not on the phone and not heard: the emulator runs shared AAudio at a 960-frame
burst, which says nothing about how a sweep sounds. The chip's `[ ]` renders with its space
squeezed to `[]`. The rails expose nothing -- a rail has no bottom edge to spare, and nothing
has yet wanted the output level modulated.

**The first use on the phone, 2026-09-15, changed four things.**

- **A range reads as a range.** Moving the brackets showed no numbers at all. An exposed bar
  now reads `[0.005s – 0.027s]` where its value was, following either end as it moves; an en
  dash, since a hyphen beside a negative number of cents reads as a sign.
- **An exposed knob is not the hand's.** The bar stayed draggable, and what it set was not
  clear -- it was the value the parameter returns to when unpatched, which nothing shows while
  a modulator runs. The bar now shows where the modulator has taken the parameter, live: the
  engine publishes each modulated value once per block, as it does a sequencer's step, and the
  open panel polls it per frame. "Modulation on" means the chip, patched or not; an exposed
  parameter with no cable in it simply shows its knob.
- **The whole row takes the nearer bracket**, which is what a row with no knob left to drag
  can mean, and a tap moves that bracket to where it landed, as a tap moves a knob.
- **A bracket at the end of its bar could barely be taken.** A new range parks `[` at the very
  end for any knob in the bottom fifth of its travel, and a bracket was found only within 22dp
  of it and never beyond the row -- so a finger aiming at it from outside, which is where a
  finger aiming at an edge lands, found nothing, and "stuck" was exactly right. Reproduced on
  the emulator before it was fixed: a drag starting 15dp left of the bracket sent no command,
  and the same drag starting on it moved it. The row now reaches a bracket's width past both
  ends. Mutation-checked, with the other three: restoring the old zone, leaving an exposed knob
  editable, and dropping either half of the published value each fail a test.

Noticed and left alone: a fifth of an exponential knob's travel near its bottom is not much,
so a new range on an attack of 5ms is 1ms to 27ms. The reading now says so.

**Cables are drawn over modules, at 60%. Settled 2026-09-15**, after playing the trial rather
than arguing it. Drawn under, a cable passing behind a box vanished there, and which of two
jacks it had left was a guess. Routing around the boxes was the other candidate and was not
tried first, for its costs: a route that flips sides as a module is dragged across it, and a
path search per cable per frame. Over the modules costs nothing and hides nothing, which is
what VCV Rack does.

The opacity was chosen on the phone from 80, 70, 60 and 50%. 80 still read as solid over a
module; 50 came too close to the 30% a cable from a switched-off In rail is drawn at, which is
what says the microphone is not listening. 60 lets a title read through a crossing cable and
keeps that difference visible. Each end gets a plug in the cable's colour, since the stroke
would otherwise cover the jack's own dot. Crossing a label is better than vanishing behind a
box -- that was the open half, and the phone answered it.

### CV is retired, and the catalogue is seven modules

**Built 2026-09-16**, as the second of the two commits the type system was split from.
94 graph checks, 115 node checks, 214 JVM tests.

`Voice` is `Osc` and keeps node id 10; the monophonic `Osc` is deleted and id 1 retired
unused, beside 7 (`Vca`) and 8 (`Clock`). `Vca` retires outright -- a `Mix` channel is
`in * level`, which is a VCA with its level on a knob, and the node test that proved a VCA
shut at zero and open at one now proves it of `Mix`. `Filter` drops its cutoff jack and
`Steps` its pitch output. Format 5, with every older file refused.

**Retiring things took their test fixtures with them**, which was most of the work and
none of the design. Three separate fixtures had to be rebuilt out of surviving modules:

- **A tone.** Fifteen graph tests measured against an oscillator that simply ran. A drone
  holding one note into an `Osc` with a flattened envelope is that tone, which is what
  `Drone` was added for a commit earlier.
- **A settable constant modulator.** The modulation tests used a stopped sequencer's pitch
  output, set by its transpose. An envelope held open by a drone sits at its sustain for as
  long as you like, so its sustain is the dial. It has to be floored just above zero:
  DaisySP's envelope decaying toward a sustain of exactly zero crosses below it and latches
  to idle, and idle is only left on a rising gate that a held note never gives -- so a
  modulator set to nothing once could never be raised again.
- **A gain to modulate.** `Mix`, for the reason above.

**A pitch output is a sample-and-hold, and several tests were about the holding.** "A note
held through a switch keeps its pitch" guarded against the held pitch being re-read against
a scale that arrived after the note started. Nothing holds a pitch now -- a note is two
events carrying the beat that chooses its scale -- so that test went with the output, while
its other half, that a rest keeps the degree it remembers, stayed. The scale tests that read
pitches now read the note and resolve it through `octavesOf`, which is the same call the
oscillator makes.

**Two thresholds had to be re-measured rather than kept.** Silence after a disconnect now
takes 400 blocks rather than 64: the 30ms fade was never what took the time, and Out's DC
blocker, charged by cutting the waveform wherever it was, decays as a clean exponential that
crosses the 0.03 threshold around block 250. The assertion is put well clear of it rather
than just past it. And ASan caught a dangling buffer in a test written the same hour --
`constantBuffer(0).data()` keeps a pointer into a temporary that dies at the end of the
statement.

**Not on the phone.** Verified on the emulator: the demo patch is Steps into Osc into
Filter into the rails and plays an articulated sequence; the palette is seven tiles; a
format 4 file is refused with its reason logged and kept at `patch.rejected.json`.

### The envelope takes notes, and the gate goes

**Decided and built 2026-09-16.** The design had `Env` become a modulator opened by a
*pulse*, and building it showed the hole in that: a pulse is an event without duration, so
it can say start and never say stop, while an ADSR's whole shape is a sustain between the
two. A note already carries an on, an off and an id to match them by, which is exactly what
an envelope wants. So `Env` takes notes.

The consequences ran further than the module. `Steps`' gate output had no consumer left, so
it went -- ending half of the "same sequence, said twice" a commit earlier than planned.
And **no port in the catalogue carries a pulse any more.** The kind stays, for a module
that wants a bare trigger, and the rule is now pinned against the kinds themselves rather
than against a pair of ports.

**Legato, not retriggered**, because re-attacking under a held note turns a chord into a
stutter. An Off is matched against the source that sent it as well as its id, for the same
reason `VoiceNode` does it: ids are each source's own and start again at 1 whenever a node
is rebuilt, so two sequencers on one envelope are both holding a note called 1 almost at
once.

**The mutation check caught itself being wrong**, which is worth recording. Two mutations
appeared to fail and were in fact failing to *compile* -- removing the `source` half of the
match leaves the parameter unused, and the suite builds with warnings as errors. Silenced,
both mutations passed every test. The tests were then genuinely unable to fail for a second
reason: they measured the envelope over the 21ms after the gate should have shut, and a
release of 250ms is still near its sustain level at that point. Measured after the release
has actually run, four mutations now fail the right checks. **A mutation that fails the
build is not a mutation that fails a test**, and the difference is invisible unless the
output is read rather than the exit code.

### Drone, and what a test tone is made of

**Added 2026-09-16, to unblock the catalogue change above.** Retiring the monophonic `Osc`
takes away the engine's only free-running audio-rate source, and about fifteen graph tests
are built on one -- `patchingDoesNotStep` measures a click against the saw's own worst step
precisely so that no fixed threshold has to be invented, and the roadmap already records
what happens when a threshold is invented instead. The LFO caps at 20Hz and is unipolar;
driving the polyphonic voice needs a sequencer and a running transport, and gives an
envelope-shaped signal whose baseline means something different.

Three ways out were weighed: an `Osc` that drones when nothing is patched to it, tests that
build a tone from `Steps` into `Osc`, and a source node compiled only into the test binary.
The answer taken was none of them -- **a `Drone` module**, which is a real module rather than
a fixture, and which the instrument wanted anyway. Every note source here was clocked, so
there was nothing that simply sounds.

**A cell is a degree, and the engine never learns there were rows.** The grid puts the
scale's degrees up the rows and octaves across the columns, which works because
`ScaleTable::octavesOf` already treats a degree as an unbounded integer that runs into the
next period past the end of the table -- so cell (row, column) is degree `column * size + row`
and the grid is a two-dimensional view of one axis. `setStep(index, degree, gate)` already
had the right shape for a toggled cell, so the command, the JSON and the `GraphSync` diff
all carried over untouched. The columns are bounded by the cells there are, so a scale with
many degrees to a period trades columns for rows rather than running off the end.

It is ticked at a quarter note and uses that for nothing but knowing the beat, because a
note must name the beat it starts on or the wrong scale resolves it -- while a drone has to
sound with the transport stopped, which is the property that makes it a tone source at all.

**Two things only the emulator found.** A module's panel opened on
`type.params.isNotEmpty()`, from when knobs were the only thing a panel held; a drone has a
grid and no knobs, so it took the tap and did nothing, with the grid it exists for
unreachable. And a grid with no knobs under it was still being given two thirds of the
body, leaving a third of the screen saying nothing. Both are the sort of thing the suite
was never going to notice.

**Still to look at on the phone.** Ten of a twelve-degree scale's rows fit, so the top two
want a scroll. Whether that reads as ordinary or as the grid being cut off is a question for
a finger.

### Choosing from a library

A flat grid of 5 columns by 6 rows is about 404x279dp on the reference device -- a
quarter of the screen, holding **thirty modules with no navigation at all**. Eight rows
covers 93% of the height and stops being a menu.

So submenus are a cost paid before it is needed, and they are the wrong cost. Depth
doubles every selection, and worse, it makes the user answer a question they should not
have to: is a wavefolder an Effect or a Synthesis module? Is a resonator a Filter? The
person who filed it knows; the person hunting does not, and every miss is a
back-navigation from a transient menu that has no obvious back.

**Macros decide this.** Once a saved subpatch is a node, user-made modules will
outnumber built-ins, and nobody is going to file their own patches into a taxonomy
chosen here. Whatever the picker is, it has to treat a built-in `Osc` and a saved
`BassVoice` identically -- which a flat browsable surface does naturally and a fixed
two-level menu does not.

The order, then: keep the flat grid while it fits; when it outgrows thirty, **filter in
place** -- category chips along the top of the same menu, narrowing the tiles below, no
second layer and no back, with macros getting a chip of their own. Type-to-filter only
if it gets genuinely large, and reluctantly: a keyboard covers the canvas, and it is
useless when you do not already know the name.

**Nothing to build early.** A composite is a `ModuleType` carrying an inner patch
definition; `PatchModule`, `PortRef` and the connection model are unchanged, because
each scope is just another flat graph. The one item with a deadline is the file
format -- definitions and instances must be separable, and that wants deciding before
Phase 5 hardens the schema. The loader already refuses unknown versions, so a bump is
clean.

A cheaper partial win, available any time: collapsing a module to a title-only strip
buys back a good deal of the same screen space for far less work.

## Phase 8 -- App-ness

- Patch library: name, save, load, duplicate, browse.
- Undo/redo. Falls out of Phase 3's command structs nearly free if they are designed to
  be invertible -- worth spending ten minutes on then rather than a refactor here.
- Foreground service so audio survives backgrounding and screen-off. An instrument that
  stops when the screen times out is not one.
- **Always recording**, as Bespoke is, so that something found while exploring can be
  saved rather than reconstructed. A rolling ten-minute window -- Bespoke defaults to
  thirty, held in memory -- kept **on disk** instead. Ten minutes of float stereo at 48kHz
  is about 230MB; a backgrounded process that size is the first thing Android's
  low-memory killer takes, which is exactly when you have gone to do something else, and
  a file also survives a crash. The audio thread copies each block into a lock-free ring
  and a writer thread drains it into a circular file, so the callback still never touches
  I/O. Stored as the stream received it, with bit depth chosen at save; saving does not
  clear the window. It grows out of the debug capture.

  An idea rather than a decision: undo snapshots timestamped against the window would let
  a saved recording carry the patch that made it.
- In-app open-source licenses screen. MIT requires the notice ship with the binary;
  DaisySP alone brings three (DaisySP, Plaits, Soundpipe) and Oboe brings Apache-2.0.
- Turn `isMinifyEnabled` on for release and confirm nothing reflective breaks.
- MIDI in over USB/BLE via `android.media.midi`, translated at the edge into Phase 6's
  note events, if it still seems worth it by then.

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
   is more headroom than feared -- but Phase 7 exists because a patch worth playing
   will exceed it. A minimap or collapsed modules are the cheaper interim answers.
3. **Does the unified gesture loop survive?** It already needs long-press (Phase 1) and
   may need knob-drag (Phase 5). At some point a single `awaitEachGesture` becomes the
   tangle it was written to avoid. Watch for it.
4. **What was the tap glitch?** For several versions a tap anywhere -- canvas or open
   panel -- produced a small click. Established at the time: taps send zero commands
   (the `PatchSync` log is empty through one), captures were clean, xruns were zero, and
   the canvas uses raw `pointerInput`, which never asks Android to play a touch sound.
   It disappeared on its own across a later build and nobody fixed it deliberately. An
   unexplained fix is not the same as a fixed bug, so it is recorded here rather than
   deleted: if it returns, start from what was already ruled out.
5. **Is single-source input the right call?** Replacing an occupied input keeps a patch
   readable and avoids hidden summing, but it makes a mult mandatory for things hardware
   modular does implicitly. It may prove to be one tap too many in practice.
6. **Can a touchscreen be played live?** Monitoring the mic feels late, but the mic is the
   harshest case -- the acoustic sound arrives instantly and the processed copy is heard
   against it as an echo -- and the reference device listens over Bluetooth A2DP, which
   adds 100ms or more before anything here. The estimated wired path is up to one display
   frame for touch delivery (16.7ms at 60Hz, 8.3ms at 120Hz, less with
   `requestUnbufferedDispatch`), up to 2ms to the next callback, and 4.2-5.9ms measured
   out. Measure it before designing for it: tap a fingernail on the glass beside a laptop
   mic while the speaker plays what the tap triggers, and read the gap off the recording.
   Two uses survive latency regardless -- continuous gestures on notes already sounding,
   and input the transport quantises, where the finger chooses what and the next step
   chooses when.
7. **How does a modulator reach a knob?** **Answered by design on 2026-09-14**, which is
   not how this list is meant to work -- recorded as answered rather than deleted, because
   the answer is still untested by a finger. "Tap the output, open the target, tap a
   slider" was the guess, and it was wrong in an instructive way: it needs a half-finished
   cable to survive a navigation. Phase 7 inverts it -- expose the parameter from inside,
   where a `[ ]` chip on its row gives it a jack on the module's bottom edge, then patch
   that jack from outside like any other. What remains untested is whether a port that
   only exists because you asked for it reads as a feature or as a thing you have to know
   about.
