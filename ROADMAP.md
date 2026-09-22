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

**Phase 10 took half of Eurorack's answer back.** Notes are still events and something
still allocates -- but what they are allocated *to* is a copy of a patch, stamped out per
note, which is the copying Eurorack was said to be stuck with. The difference that mattered
was never copying; it was who copies. Here you build one voice and the engine makes the
copies, so the patch on screen stays the size of one.

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
"does this actually feel right" judgment happen here. Others are expected to be on
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

**Signal typing is advisory, not enforced.** Audio, CV and gate color the cable and the
port, and any output may still patch to any input.

This roadmap originally said types would gate connection validity. That was wrong. In
hardware modular it is all just voltage, and patching audio into a CV input is a
technique rather than a mistake -- audio-rate modulation lives there, and refusing it
would make this less modular than the thing it models. The color says what to expect;
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

Upstream DaisySP holds far more than the eight modules above, all MIT -- the *vendored*
copy is only what is used, so adding one of these means copying it in (see
`vendor/daisysp/README.md`); this sentence said "the vendored tree" until 2026-09-18:
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
catalog (name, ports, signal kinds) and hand it to Kotlin at startup, with the UI
supplying only color and category. Then a module is one declaration, and a whole class
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
  "square" out of four unlabeled positions asks you to know the order by heart.

  The waveforms are drawn rather than named -- the shape is the name, and reading it
  needs no translation from the word "saw". All four glyphs are sampled from a function
  rather than hand-drawn as paths, so they stay consistent with each other and the
  near-vertical edges of the saw and square read as vertical at this size.

  **Two cycles, phased the way these glyphs are conventionally read.** One cycle was
  wrong: a single descending ramp is a slope, not a sawtooth, since the reset is the part
  that names it. Phasing them all to start and end at zero was wrong for the same kind of
  reason -- it is right for sine and triangle, but it put the saw's reset in the middle of
  the glyph and the square's edges at the quarter and three-quarter points, which reads as
  an off-center pulse. The saw starts at the top of a ramp and the square starts high, so
  both switch on the cycle boundary.

  **The saw glyph descends**, because that is what comes out: DaisySP's polyblep saw
  computes the rising ramp and multiplies by -1, confirmed both in `oscillator.cpp` and
  in a capture of the real output. The conventional rising glyph would be prettier and
  wrong.

  The math behind the buttons matters more than it looks. `valueAt` floors rather than
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
  act on -- correct modular behavior, and worth knowing that the grid gives no hint of
  it.

  A silenced step used to draw a gray box at the pitch it remembered. That was a lie
  about what you would hear -- a rest is the absence of a note, not a note in another
  color -- so it draws nothing now. The degree is still remembered underneath, which is
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
  its neighbor.

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

**One stack of whole patches, not a log of inverted commands.** A patch serializes to a
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
the In rail is centered on the left edge and the gesture bar is already excluded by the
inset. Rejected: a two-finger tap (fights the pinch), a three-finger swipe
(undiscoverable), and a long-press menu entry (two gestures deep for the control you
reach for when the last thing you did was wrong).

Hidden rather than grayed, because a disabled control promises that something could
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
  candidate and lost: every scale-aware note operation (up a step, quantize to the key)
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
that reads as a refusal or as a bug is a judgment for the phone.**

**Voices come from a curated module.** `Voice` is eight voices of oscillator and envelope,
allocated by note id and source, summed like Mix -- a chord is louder than a note, which is
true of every instrument. The other candidate, a patched voice from Phase 7 stamped out N
times, is not ruled out and can coexist; this one can exist now.

> **Reversed in Phase 10.** The other candidate is what shipped, and the two did not
> coexist: a curated module's voices all share its envelope, so nothing inside one can be
> patched per note, and an `Env` on FM's modulation index turned out to be inexpressible.
> Every synth is monophonic now and the copies are a poly subpatch's. The rules below --
> free voice, then oldest released, then steal -- were the right rules and moved intact to
> `PolyIn`. A note takes a free voice,
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
  prevent. *Revised 2026-09-16 for drones, which glide instead -- see "A drone follows the
  scale".*
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
catalog already knows every port's kind, so picker categories could be derived rather
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
do-not-touch, and changes most of the module catalog. Four layouts were drawn against
the real 986x443dp landscape frame first, and the drawings are what settled it -- two of
the four turned out to be too small rather than merely worse, which is not something the
argument had reached on its own.

A phone screen holds about 17 modules at zoom 1.0. A patch worth playing will exceed
that, and panning around a flat sheet of forty nodes is a worse problem than the one
tap-to-connect set out to solve. Conceptually coherent groupings are the answer:
build a voice out of Osc, Env, VCA and Filter, then treat it as one node.

**The rails already generalize, and that is the whole design.** `In` and `Out` mean
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

> **Reversed again in Phase 10, back to what this phase first said.** Polyphony comes from
> copying after all: a poly subpatch is a voice built once and stamped out per note, which
> is "a three-voice patch is twelve nodes" with the twelve nodes made for you. What Phase 6
> got right and kept is that notes are events and something allocates them; what it got
> wrong is where the copies live. Grouping was never only about screen space.

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

Pulse and modulation keep the colors of the gate and CV they replace, which is most of the
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

**The catalog changes more than the engine does.** `Steps` drops pitch and gate and keeps
notes, ending the "same sequence, said twice" that Phase 6 left deliberately in place.
`Filter` drops its cutoff jack and `Osc` its pitch jack, both becoming modulatable knobs.
`Env` becomes a modulator rather than a CV source. `Vca` retires outright: its entire reason
was a CV input, and what remains is a gain with a modulatable level. Every surviving module
gets *shorter*, because ports drive height -- so retiring CV buys canvas back before the
subpatch work spends any of it.

**Blocked on the rest of this phase, in that order.** CV cannot be retired until a parameter
can be modulated, and a parameter cannot be modulated until the mechanic below exists.

---

**The type system landed 2026-09-15, catalog untouched, as the first of two commits.**
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
behavior, and the two cases are genuinely different. One is a file this build cannot read
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
same kind, so a shape would have to carry identity -- arbitrary, where color carrying kind
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
spread along its bottom edge in row order and labeled *below* the edge -- a panel of four or
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
keeps that difference visible. Each end gets a plug in the cable's color, since the stroke
would otherwise cover the jack's own dot. Crossing a label is better than vanishing behind a
box -- that was the open half, and the phone answered it.

### Groups: the model and the engine

**Built 2026-09-17, overnight, from decisions taken the evening before:** grouping first
(abstraction later), a group's ports taken from the cables that crossed the selection's
edge, and format 5 still readable.

**One flat list, not a tree.** Every module says which group it is in (`parent`), and a
group owns nothing but its ports. The engine's view, saving, undo and every loop over the
patch stay one level deep; entering a group is a filter. A tree was the other shape and
would have put a recursion into each of those.

**A group's inside rails are real pinned modules.** `GroupIn` and `GroupOut` share the
group's `GroupPorts`, turned around: the box's inputs are the left rail's outputs. So a
cable inside a group is an ordinary cable to a rail, and the rails' drawing, hit testing and
cable code apply without a single new port direction. Two new directions were the first
design, and would have touched every place a cable is normalized.

**Ports are stored, never derived.** Derived from the cables, a port would vanish when its
last cable was unplugged, leaving nothing to plug back into, and would re-sort as cables
came and went -- and ports must never move. So grouping creates them and they stay. One
input port per outside *source*, fanning out inside to everything that source reached; one
output port per inside source, fanning out outside. Grouping names an input for what it
feeds when that is one thing ("cut"), since that says more than the source's "out".

**The engine never sees a group.** `engineConnections()` follows each cable's source back
through any chain of group ports to the real output, so the flattened cables are identical
before and after grouping, and `GraphSync` -- which now reads only the flattened view --
sends a playing patch nothing when it is grouped, nested or ungrouped. That is the test
that matters, because any command there is an audible crossfade.

**Format 6, reading 5.** Groups are additive -- a `parent` on a module, and a group's ports
and rail ids -- so a format 5 file is a patch with no groups. A parent that is not a group,
or a loop of groups inside each other, puts the module at the top rather than somewhere
nothing can reach.

**Two things only mutation checks found.** Dropping every parent on save went unnoticed by
a byte-for-byte round trip, because the flat patch it reloads to writes the same JSON again
and sounds the same; the test now compares where each module is. And a group's ports are a
snapshot list, which compares by identity -- a test comparing the box's ports with a rail's
passed only because they were the same list, and failed the first time two equal lists were
different ones.

### Groups: on screen

**Built the same night, checked on the emulator, not yet on the phone.**

**Choosing is a mode.** Long-pressing empty canvas offers "Group..." after the palette;
it turns taps into choosing -- each chosen module outlined -- with Group and Cancel
centered along the bottom. The Group button counts what it will take ("Group 2") and says
"Tap modules" while it would take nothing. A long press while choosing is ignored, and a
drag pans rather than moving a module. A lasso was the alternative and was passed over,
being one more thing for the gesture loop to decide on the first move.

**Opening a group is tapping it**, as opening a module shows its controls. Inside, the
group's ports are its rails, and a breadcrumb beside the scale chip ("Patch > Group") is
the way back out, a chip per level. A group's long-press menu adds Ungroup to Duplicate
and Delete. Adding a module from the menu inside a group puts it inside that group.

**Only what is in this scope is drawn, hit or tapped.** A cable into a group is drawn out
here to the box and inside from the rail, never both; the top level's two rails switch only
at the top level, since a group's right rail occupies the same place as Out.

**Checked on the emulator, end to end:** grouping Osc and Filter out of the demo patch
sent the engine no commands at all (the PatchSync log stayed empty), as did ungrouping,
undoing the ungroup, and nesting that group with Steps inside another. Restarted, the
engine was given exactly the flat patch -- Steps, Osc and Filter cabled straight through,
no node for either group. An LFO added inside a group was saved inside it; a module's panel
opens inside a group; the breadcrumb reached three levels.

**Two things the emulator changed.** The breadcrumb was drawn over an open panel and hid its
title, and a panel's taps go to its own loop, so it was a picture of a control that did
nothing there: it is hidden while a panel is open. And a group's rails wore the outline
that on In and Out means "switched on", about something that has no off: rails that are not
switches no longer get it.

**Left for the phone and for later:**
- ~~**Groups have no names.**~~ **Built, and checked on the emulator.** See below.
- ~~**A port can only come from grouping.**~~ **Built, then fixed on the phone.** Inside a
  group, an armed jack taken to the matching rail gains the group a port of that jack's name
  and kind. "Tap the rail" could not be made to happen at all by its owner, and the reason
  was geometry: a rail is 64dp wide and its jacks answer to a 22dp touch radius, so nearly
  every tap on a rail lands on a jack already there. There is now a **slot** -- an empty ringed
  port with a plus, in the cable's color -- drawn at the end of the matching rail whenever a
  jack inside is armed, a whole port pitch clear of the last jack, and tapping it adds the
  port. The rest of the rail keeps the jack armed rather than disarming.
- **A group's rails are centered, like In and Out**, at their owner's request. They briefly
  hung from a fixed top because a centered rail re-centers as it grows, sliding the jacks
  already on it; that shift now happens, but only at the moment a port is added, and the
  slot is measured against the rail *as it will be* so it marks where the port truly lands.
- **A new group lands where the chosen modules' top-left corner was,** which can put its box
  under the top row of chips, as grouping near the top of the demo patch did.
- ~~**A parameter cannot yet be exposed through a group's boundary**~~ **Built. See
  *A knob can be promoted* below.**
- Whether choosing modules by tapping reads as a mode, and whether the rails inside a group
  read as its ports.

### A module has a name

**2026-09-17, asked for after a day with groups on the phone.** Every box and every crumb
said "Group", so three levels read "Patch > Group > Group". A new group is now named
"Group 1", "Group 2", ... and any module can be renamed from its long-press menu.

**The number is one past the highest in use, counted over the whole patch** rather than per
scope, because a breadcrumb shows groups from several scopes at once and two "Group 2"s
there would read worse than a gap in the numbering. Renaming a group takes its number back
out of use, so names do not drift upwards through a session of grouping and ungrouping.

**The name lives on the module, not on the group**, and falls back to the type's name when
it is null -- which is what makes it free for everything else: `title` is what the box, the
panel header and the crumb all draw, and an older file with no name in it still reads
"Group". Duplicating a group numbers the copy afresh while the groups nested inside it keep
their names, since those are only ever read from within it.

**Text entry is the first composable over the canvas.** Everything else in this app is
drawn, and a drawn text field would mean owning a cursor, a selection and an IME
connection. `PatchCanvas`'s `Canvas` is now wrapped in a `Box`, and renaming puts a
`BasicTextField` on a scrim above it -- the second exception to the one-gesture-loop rule,
after the panel. The text starts selected, so the default is replaced by typing and kept by
tapping past it; an empty name is not an error but the way back, clearing it so the module
goes by its type again. The scrim commits rather than cancels, because undo is the cancel
this app has everywhere else -- and a rename is one step of it, as the undo button
appearing after one proves.

**The confirm chip had to be reworded.** It counted its selection as "Group 2", which with
names in play read like the name of the group it was about to make; it says "Group ×2" now.

**Checked on the emulator:** rename from the menu, the keyboard up with the old name
selected, the new name on the box, in the file, and gone again after one undo; a new group
named "Group 1" beside an older unnamed one, and the breadcrumb reading "Patch > Group 1".
Not yet checked on the phone.

**Renaming from inside, too.** A group's box is a level up and off screen while you are
working inside it, so the only way to rename it was to leave first. Holding its crumb in
the breadcrumb does it instead, at its owner's suggestion -- the name is already there,
and a long press is what renames things everywhere else in this app. "Patch" is not a
group and does nothing. One `Patch.breadcrumbAt` now answers for both the tap that enters
a scope and the press that renames it, so the two cannot disagree about where a chip is.

**Still open:** whether 16 characters is the right ceiling for a name on a box that does
not grow.

### A number can be typed

**2026-09-17, asked for in the same breath as naming.** Tapping a number opens a keypad on
it: any knob's reading on an open panel, either end of an exposed parameter's range, and
the tempo on the transport card. Dragging the bar under it is untouched -- the reading is
where a value is written down, the bar is where it is swept, and they are a finger's width
apart in the same row.

**The keypad is this app's own, not the system's numeric IME.** The name field takes the
IME because a name is text; a numeric IME would resize the window, and the panel whose
value is being typed would slide out from under the keypad as it opened. So the keys are
composables on a scrim, in the control's own color -- the module's accent for a knob, the
modulation purple for a range, the transport's yellow for the tempo -- and the header says
what is being typed: "cutoff", "cutoff from", "cutoff to".

**The entry starts empty with the current value in its place**, so the first digit replaces
rather than appends: typing a number says "this value", it does not amend the one there.
Tapping away cancels, where the name field commits -- a half-typed number is not a value
anyone meant, and C, backspace and ± are on the pad for the rest. A number past the end of
a knob is clamped rather than refused: 20000 on a cutoff that stops at 12000 is asking for
as high as it goes, and refusing it would leave the knob where it was with nothing said.

**The hit test measures the text it is testing.** A row's reading is right-aligned and
"[200Hz \u2013 1715Hz]" has two numbers in it, so the zone is the string as drawn and the split
between its ends is where the dash actually falls. The gesture loop has the measurer
already, which is what makes that affordable. A stepped row offers nothing: its lit button
is its reading.

**Checked on the emulator:** the tempo typed to 96 and saved; a filter's cutoff typed to
440Hz; the low end of an exposed cutoff typed to 200Hz, the high end opening its own pad
and cancelling clean when tapped away. Not yet on the phone.

**Still open:** the sequencer's grid and the scale card have numbers that are not typeable
yet, and neither is a group's port count. Whether the pad wants an arrow to nudge by one is
a question for the phone.

### A knob can be promoted

**2026-09-17.** Phase 7 said it from the start -- "a Voice macro whose filter cutoff cannot
be reached from outside is half a feature" -- and it was the last piece of grouping
missing. A group with a filter in it had no controls at all from the outside. Inside a
group, the chip beside a row now sends that knob out to the group's edge, and the group's
panel draws it.

**What is stored is a reference, never a copy.** `GroupPorts` gains a list of `ParamRef`,
and the value stays on the module inside. A copy would be a second place for the cutoff to
live, two numbers to keep in step and one of them wrong whenever they are not -- and the
engine already reads the module inside, so a promoted knob is not a thing it can be told
about. `GraphSyncTest` pins that: promoting sends the engine nothing, and turning the knob
from the group's panel arrives as an ordinary parameter change on the module that holds it.

**One panel drawing, told what its rows are.** Every panel now draws and hit-tests
`Patch.panelRows(module)` -- a module's own row parameters, or a group's promoted ones --
so a group shows knobs its type never declared without a second drawing to drift out of
step with the first. That meant widening the row geometry from "which parameter" to "which
of how many rows", which is what it always depended on.

**The chip went to the left gutter.** The first drawing stacked it above the `[ ]` chip on
the right, and it failed on a sequencer: a grid takes two thirds of the panel, so `Steps`
rows are about a third the height the others get and two chips could not both be tall
enough to hit. The left gutter is the same 108dp and otherwise empty. It keeps to that
gutter's inner edge for the same reason the `[ ]` chip keeps to the other one's -- the
panel's jack labels have the outer half -- which leaves it 32dp of width, and
`ModulationPanelTest` holds both chips to that on every module in the catalog.

**A group's panel opens from its menu**, chosen over the alternatives on 2026-09-17: a tap
already goes inside a group, and having one gesture mean two things depending on whether
the group had knobs was the option that would surprise you later. The entry appears only
when there is something to show. Its rows are labelled "Steps \u00b7 transp", with the bar in the
owning module's color, because "transp" alone says which knob but not whose -- and a group
is exactly where two modules' knobs sit side by side.

**Format 7** carries the promoted knobs and reads 6 and 5 as they stand, taking nothing
away. Promoting is an ordinary edit: it saves, it undoes, and a duplicated group's knobs
name the copies rather than the originals, which a test pins because the bug would be
silent -- one group's panel turning another group's filter.

**Checked on the emulator:** promoting `transp` inside a group, the chip lighting, "Knobs\u2026"
appearing on the group's menu, its panel showing "Steps \u00b7 transp", turning it writing
1253 cents to the Steps module in the file, and both edits undoing clean. **Not yet checked
on the phone** -- it went off USB before the build got there.

**Still open:** a knob two levels down promotes to the group it is in, and no further; the
group's own panel would need the same chip for that, which is Phase 7's "promote a port up
through a second boundary". And a promoted knob cannot yet be given a jack from outside --
the `[ ]` chip is drawn only on the module's own panel, where the jack would land.

### A port outlived what it reached

**2026-09-17, from the phone.** Expose a knob inside a group, give the group a port for it
by arming the jack and tapping the slot, then unexpose the knob: the jack inside vanished
and the port stayed, on the rail and on the box, ready to be patched into and going
nowhere. Deleting a module inside a group left the same thing behind.

**Ports are stored rather than derived, and that stays.** A port that existed only while a
cable used it would vanish the moment the cable was unplugged, leaving nothing to plug back
into. So the rule is narrower than "drop unpatched ports": a port goes when **the jack it
reached inside stops existing** -- its parameter unexposed, its module deleted -- because
that is the case where nothing could ever reach it again. A port feeding two modules
survives losing one of them, which is the test that keeps the narrow rule honest.

**Removing a port renumbers the cables that named a later one.** Ports are positional, so
this is the part that fails silently: with stale indices *both ends stay wrong by the same
amount*, so the engine still hears the right thing while the jack is drawn off the end of
the box. The first test could not see it, and the mutation check is what said so -- the
assertion that actually bites is that every cable names a port that exists.

**And a port can now be taken off by hand**, since the prune only fires at the moment of
the edit and Forrest's patch already carried an orphan: a long press on the jack itself,
from the box outside or the rail inside, offers "Remove port". Precise rather than anywhere
on the box, which is what the group's own menu means. Confirmed on the phone against the
real orphan: the port went, the cables that stayed still named their own ports, and the
engine was sent nothing.

### A group can be saved and loaded

**2026-09-17, overnight, the four decisions taken beforehand.** A group's long-press menu
has "Save\u2026"; the empty-canvas menu has "Load\u2026" and "Save patch\u2026". Loading drops a fresh
copy where the press landed, unpatched, with everything inside it: knobs, sequences,
exposed parameters, promoted knobs, nested groups and the names of all of them.

**A copy, never a link**, at its owner's choice. Linked instances -- edit the definition and
every patch using it follows -- is the more powerful idea and the more confusing one, and
nothing here can yet show you where a definition is used. Ids are allocated by the patch
doing the loading, so the same file loaded twice is two groups that share nothing.

**Files live beside the scales**, in `getExternalFilesDir/groups`, for the same reason the
`.scl` files do: a saved group is something to copy off the phone or hand to someone. The
name typed is sanitised into a file name -- dots dropped as well, which makes ".."
impossible rather than handled -- while the name you see stays inside the file on the group
itself. Saving over a name that exists asks: **Replace or Keep both**, "Keep both" saving
as "Filt Osc 2". Silent replacement was the one option that could lose work.

**Saving the whole patch groups a copy of it**, read back from the patch's own file, so the
patch you are playing is never touched. The first version grouped the live patch and
ungrouped it again, and was wrong in a way only the phone showed -- see below.

**The library's own menu is the add menu's grid**, one tile per saved group, rather than a
card that scrolls. It is the same visual language and it cost nothing; a library past a
dozen wants a list, and `MAX_SAVED_TILES` is where that decision will surface.

**Building it found an older bug.** Ungrouping moved the modules inside back out *unless
they were groups themselves*: the filter skipped nested groups along with the two rails it
was written to skip, leaving an inner group pointing at a parent that had just been
deleted. Still in the patch, still playing, drawn in no scope at all, and only rescued by a
reload, because the file reader puts a module with an unknown parent back at the top. It
had been there since groups were built and no test had nested one and ungrouped the outer.
Saving a patch whose top level was two groups did exactly that, which is how it surfaced.

**And a loaded group sounds**, which is the claim none of the above actually made. The
saved demo patch was loaded back into an empty one, the original chain unpatched from Out
and the group's own output patched there instead, so the only thing reaching the device was
the copy. Ten seconds of the engine's own capture: peak 0.45, RMS 0.13, fundamental around
578Hz -- the sequence playing through the copied Osc and Filter. The first capture was
silence and the reason was the master output, which starts switched off; worth remembering
before reading a silent capture as a fault.

**The phone found two more things the emulator could not.** "Save patch" on Forrest's own
patch -- a group at its top level, with cables crossing into it -- came back with the same
cables in a different order. Ungrouping re-adds the boundary's cables at the end of the
list; the engine diffs sets and heard nothing, but the autosave saw a new file, so saving
became an undo step that did nothing. The demo patch the test used happened to re-add its
cables in their original order. It now groups a *copy* read back from the patch's own file,
which makes "the patch is untouched" true by construction instead of by care.

And the menu's labels spilled out of their tiles: the reference device runs at **font
scale 1.5**, where 12sp is 18dp in a tile sized for 12. Shrinking the label was tried first
and read "Save pat\u2026"; the tiles now grow with the text setting instead, since shrinking
undoes the very setting the user chose in order to read it, and a label that still does not
fit wraps to two lines before anything is cut -- which is what a saved group's typed name
needs most. `PatchTest` holds the largest menu to the screen at 1.5.

**Checked on the phone:** "Filter Osc" saved with its promoted knob remapped to the copy;
the whole patch saved with the patch byte-identical afterwards and nothing sent to the
engine; Replace over an existing name; the library listing both, the long name on two
lines; "Filter Osc" loaded back with the same ports as the original and three real modules
added to the engine; one undo restoring the patch exactly.

**Checked on the emulator:** a group saved, its file holding one top-level group with a
nested group inside it; loaded back as an independent copy; the same name saved again
offering Replace or Keep both, and "Keep both" writing "Test voice 2"; the whole patch
saved with the patch byte-identical afterwards, then loaded into itself as one group, with
the engine told to add exactly the three real modules and nothing for the group.

**Still open:** there is no way to delete a saved group from inside the app -- the folder is
visible over USB, which is the answer for now. Loading always lands in the scope being
looked at, which is right, but a group loaded into a group has no way back out except
ungrouping. And a library past `MAX_SAVED_TILES` needs the list that scrolls.

### Grids say how much of them there is

**2026-09-16, from the first use of Drone on the phone.** Both grids now carry a scroll bar
beside them: the track is every degree the grid can show, the thumb the ones on screen. It
is only an indicator -- the grid itself scrolls under a drag anywhere on it, and a bar you
had to aim at would be a second, smaller way to do the same thing. What the grid could not
say was that there was more of it.

A scroll bar needs a range to be a fraction of, and a sequencer's grid scrolled through
every integer there was. It now spans three octaves below the key and four above, in whole
periods of the scale, widened to take in any step already written outside that.

**Building it exposed why the drone grid jumped.** The drag wrote the scroll position with
no limit and only the drawing clamped it, against whichever scale was sounding. A finger
could scroll past the top of the scale and keep going while the picture stood still;
dragging back then did nothing until that overshoot was undone, and a scale of a different
size re-clamped it somewhere new. `GridWindow` is now the one place the range is worked
out, and drawing, hit test and drag all read it.

**That was not all of the jumping.** On the phone, with a scale list alternating 12-TET and
Harmonic minor, a drone scrolled to show degree 1 at the bottom redrew every four bars as
seven taller rows with degree 0 at the bottom, because a seven-degree scale fit whole. The
stored position survived the round trip, but the grid reshaped under the hand.

**Decided: a drone always shows as many rows as fit**, starting from a degree of the first
period and running on past the top of the period when the scale is shorter than the rows.
Nothing about the layout now depends on the scale's length except which rows carry the
tonic tint; the bottom row keeps its degree and the rows only get different names. The
price, accepted on purpose, is that the top of one column can repeat the bottom of the
next -- the same degrees, so the same cells, lit and toggled together. Two alternatives
were offered and passed over: fixed-height rows leaving blank space above a short scale,
and holding the open panel in the scale it opened with, which would have let the grid
disagree with what a newly toggled cell sounds.

### A drone follows the scale

**Found on the phone, 2026-09-16:** a drone holding degree 10 through a list of 12-TET and
Harmonic minor never changed pitch. That was the Phase 6 rule working as written -- a note
resolves its scale once, at note-on -- but the rule was made for notes that end. A
sequencer's note lasts half a step and picks up the new scale almost at once. A drone's
never ends, so it sounded its 12-TET pitch forever while its grid showed the new scale.

**Decided: held drone notes glide to the new scale.** Re-striking them, gliding every held
note including a sequencer's, and keeping the drone as a fixed pedal were the other three
choices. Gliding answers the original objection directly: that objection was to a pitch
step with no ramp, so this ramps it -- over 30ms with a smoothstep, like every crossfade.

It uses `NoteKind::Change`, which had been reserved and ignored since notes were built.
The drone decides *when*: on every beat, and whenever the scale list itself is replaced
(which can happen with the transport stopped and no tick coming), it works out each held
note's pitch under the scale for that beat and sends a Change only where the pitch moved.
The oscillator decides *how*: it finds the voice by id and source, as an Off does, and
glides. A sequencer never sends Change, so its notes keep the old rule.

**The join is what the tests had to prove.** Each half passing alone says nothing about a
Change being ticked, merged and resolved against the same list, so a graph test drives a
drone into an oscillator across a real scale switch and counts cycles either side. Five
mutations each fail a test: an oscillator ignoring Change, a glide that steps, a tick
that never asks for a retune, a replaced list going unnoticed, and a retune sent where
nothing moved.

**Heard on the phone** through the debug capture of that same patch: degree 10 (index 9) is
E-flat at 622Hz in Harmonic minor and A at 440Hz in 12-TET, and the capture turns from one
to the other 3.1 seconds in, with the pitch moving over about 30ms. `find_clicks.py` finds no
discontinuity, and the largest sample step at the turn is the same as in the steady tone.

### A new cable hears notes already held

**Found on the phone, 2026-09-16.** A drone holding a chord, patched to an oscillator added
after its cells were turned on, stayed silent. Nothing downstream was wrong: a source says
each note's start exactly once, and a drone's notes never end, so a destination connected
later never heard any of them begin. Toggling the cells off and on again was the only cure,
which is not something anyone would guess.

So a note cable now arrives *fresh*, and on the first block after it is connected the graph
asks the source what it is holding (`Node::heldNotes`) and delivers those as starts at the
top of the block. A note the source is starting in that very block is already in its
buffer and is skipped, or it would take two voices. Held notes carry the beat whose scale
they are sounding in now, so a newcomer starts at the pitch the other destinations already
glided to. The same path covers an oscillator re-created by undo or reconnected by hand.

Only `Drone` reports held notes. A sequencer's note lasts half a step at most, so a cable
connected mid-note misses only its tail; whether it should start late instead is left for
the phone to say.

Two graph tests: a destination patched to a held drone sounds, and a note starting as its
cable connects is no louder than one arriving the ordinary way. Mutation-checked -- the
second only once `startingNow` was left compiling, since removing its one use failed the
build rather than a test, which is exactly the false positive the Env work already warned
about. **Heard on the phone** in the patch that found it: with Drone -> Osc disconnected and
reconnected by hand while the drone held its chord, the capture is silent until the
reconnect and then carries degrees 3, 7 and 9 for the rest of it.

### A module's color is the kind it sends

**Decided 2026-09-16.** There was no scheme; each accent was picked when its module was
added. By the time anyone looked, Env was exactly the modulation cable's purple, Filter
exactly the retired gate's orange, and Osc one shade off the note cable's green -- so one
color said "sends this", another "takes this", and a third named a kind that no longer
existed.

Now note sources (Steps, Drone) are greens, sound sources and processors (Osc, Filter, Mix,
the In rail) are audio's steel blues and grays, and modulators (Env, LFO) are purples, each
a distinct shade rather than the cable color itself. A green cable leaves a green module.
Out sends nothing into the patch and stays neutral. The other options were colors by role
kept deliberately clear of every cable color, and one neutral accent for all modules.
`ModuleColorTest` holds the rule: the nearest cable color to each accent is the kind that
module sends, no accent is a cable color, and no two palette modules look alike.

**Seen on the phone, 2026-09-16.** With the Drone scrolled three rows and the list
alternating 12-TET and Harmonic minor, every frame was one of exactly two pictures: eleven
rows of the same height with degree 3 on the bottom, the tonic tint near the top in 12-TET
and five rows up in Harmonic minor. The scroll bar's thumb does change size and place at
each switch, because how far a drone can scroll depends on the scale's length -- it tells
the truth, but it is the one part of the panel that still moves.

The color families read clearly on the device. The shades *within* a family did not: at
module size Osc and Filter were hard to tell apart, and so were Steps and Drone.

**Widened, and measured as drawn.** The test had judged accents by plain RGB distance, and
a border shows its accent at 55% over the module's fill, which throws away close to half of
any difference before it reaches the screen. Measured perceptually (CIE Lab delta E) on the
border as composited, the closest pair was 7.0 -- Filter and Mix -- with Osc and Filter at
11. New shades were chosen by search: the largest smallest gap between any two borders,
with each accent still at least 10 nearer its own cable than any other and every border
keeping 2.2:1 contrast against the fill. The closest pair is now 16.9 on screen, and the
test holds every pair to 15. Steps is teal-green and Drone lime; Osc a clear blue, Filter a
gray-teal and Mix a light gray; Env a muted violet and LFO magenta. On the phone the pairs
separate, Osc and Filter least of them.

### CV is retired, and the catalog is seven modules

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
And **no port in the catalog carries a pulse any more.** The kind stays, for a module
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

**Added 2026-09-16, to unblock the catalog change above.** Retiring the monophonic `Osc`
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

## Phase 9 -- Instruments and sequencers

**Decided 2026-09-18, and built ahead of Phase 8** at Forrest's call: more modules, after
Bespoke's but not copies of them. Three instruments -- **Pluck** (Karplus-Strong), **FM**
and **SF** (a SoundFont player, which Bespoke does not have) -- and then sequencers:
**DotSeq**, the one asked about most because a note's length is set per note, and four
note processors, **Arp**, **Chance**, **Chord** and **Euclid**. In that order: voices
first, then DotSeq.

**Neither FM nor a plucked string can be built from groups**, which was asked. Modulation
is applied once per 32-sample block, about 1.5kHz, and FM needs every sample; since
typing was enforced no `Osc` takes audio in anyway. And the harder reason: an `Osc` sends
the sum of its eight voices, so one feeding another would bend every note of a chord by
the same mixture rather than each note by its own partner. A string is the same problem --
its pitch is the length of its delay line, one per note.

> **Still true in Phase 10, for a simpler reason.** The chord half of the argument went
> when the synths went monophonic -- an `Osc` sends one voice now, so two of them inside a
> poly subpatch would be one note each. What holds regardless is that an `Osc`'s frequency
> comes from the notes it is sent and nothing patchable sets it, and that nothing patchable
> sets a delay line's length from a note. Both are still what the module *is*. Both are what the module *is*,
which is the roadmap's test for a fixed module ("Growing the library", above), and the
decision already taken for FM on 2026-09-15.

**A panel past five rows goes to two columns**, chosen over pages and over trimming FM to
fit. The landscape panel is about 980dp wide, so a half-width bar is still long enough to
set finely, and nothing is hidden behind a chip. `kMaxParams` rises from 5 to 8 with it.

**The app shipped GeneralUser GS for a day**, and stopped on 2026-09-19: 32MB of the
download for a file anyone who wants an SF module can fetch, and the README says where. An
SF module starts with no bank and asks for one; the tests carry a SoundFont they build
themselves, which is better for them than a real bank whose presets are each tuned however
their sampler felt. What follows is why that bank was the one, and holds if one is ever
bundled again.

**The bank was GeneralUser GS**, S. Christian Collins' GM bank, whole: 29.8MB, 259
presets and 11 kits, under a custom license that explicitly allows bundling in free
software, modified or not. Chosen 2026-09-18 over the alternatives that turned up: the
small GM sets are GPL (TimGM6mb) or not redistributable (the 3MB Roland/Microsoft set),
and MuseScore General is MIT but either SF3, which TinySoundFont cannot read, or 208MB.
TinySoundFont holds samples as floats, so a loaded bank costs about twice its file --
paid only while an SF module exists.

**Decided the same evening, before an overnight build:**

- **FM is two operators and seven knobs** -- ratio, index, index decay, A, D, S, R --
  rather than Bespoke's three stacked, which would want pages rather than two columns.
  The index's own decay is what makes FM a bell or an electric piano: brightness falling
  faster than loudness.
- **DotSeq: tap an empty cell for a one-step dot, drag from a dot's end to change its
  length, tap a dot to remove it.** Velocity waits.
- **The green family widens** for the note processors -- yellow-greens and teal-greens --
  and the 15-point border rule stays. Loosening the rule was the alternative, and it
  exists because pairs at 7 to 11 were indistinguishable on the phone.

### The voices share one engine

> **Superseded in Phase 10**, though not much of it was wasted. `PolySynth<Voice, N>` is
> `MonoSynth<Voice>` in `synth.h`, because polyphony moved out to the poly subpatch and a
> synth with eight voices inside a four-instance subpatch is two allocators with the inner
> one never choosing anything. Everything below that is about *one* note -- the Voice
> interface, Off by source and id, the glide, a voice saying when it is free -- is
> unchanged. Only slot selection moved, to `PolyIn`, which kept the rules exactly.

Everything `Osc` did that was not its sound -- choosing a voice, stealing the oldest
released one before a held one, matching an Off by source *and* id, gliding on a Change,
releasing what an unpatched source held -- moved into `PolySynth<Voice, N>` in `poly.h`.
Each of those rules was paid for by a bug; a second synth copying them would have been a
second place to fix the next. A synth is now a `Voice` with `init`, `strike`, `setFreq`
and `render(gate, finished)`, and `render` says when the voice is free, because only the
voice knows what its silence looks like. A template, not a virtual call: `render` runs per
voice per sample. The refactor changed no test, and all of `Osc`'s passed unchanged.

### Pluck

**Built 2026-09-18.** DaisySP's `String` -- Emilie Gillet's, from Rings -- with the
excitation written in the voice after DaisySP's `StringVoice` (Plaits' string voice): a
burst of noise one period long, low-passed at a cutoff that rises with pitch, brightness
and velocity. Knobs `decay`, `bright`, `stiff`, `R`. Velocity is the accent -- harder is
brighter and rings longer, as in Plaits.

- **`rand()` is a mutex on Android.** Bionic's `random()` locks, and DaisySP calls `rand()`
  on the audio thread in the string's dispersion and in `Dust`. The vendored `String` has
  its own generator instead -- the only edit to upstream code beyond flattened includes --
  and `StringVoice` is not vendored at all, since its only use of `Dust` is a sustain mode
  nothing here needs.
- **A string frees its voice while still held.** An `Osc` note holds at its sustain until
  released; a string has no sustain and simply stops, so a held note that has rung out
  gives its voice back. Otherwise eight long notes and every voice is spoken for,
  silently. `voicesInUse()` exists so a test can see it -- stealing would otherwise hide
  it, since a ninth note sounds either way. *(Phase 10: `inUse()`, and with one voice the
  cost of not freeing it is worse -- the module would be deaf until something let go. The
  sustain half went with the envelopes; what holds a note open now is an `Env` outside.)*
- **It costs about seven Osc voices a voice**, which in Phase 10 is seven Osc *instances*:
  the per-voice cost is the same, and what varies is a poly subpatch's knob rather than a
  constant in the node. DaisySP's string works out its damping
  filter with `powf` and `atanf` every sample; eight ringing voices measured 13.6ms per
  second of audio on the desk, against 2.0 for eight Osc voices and 4.9 for eight FM. Perhaps
  5% of a phone core -- fine, and the first place to look if a dense patch stutters.
- **A release is a finger muting the string**: after an Off the voice fades with time
  constant `R`, 1s by default, so a half-step note from `Steps` still rings rather than
  choking.
- Measured: 523.27Hz for a 523.25Hz note, by autocorrelation -- zero crossings, which the
  `Osc` tests count, read a harmonically rich string as 2753Hz. Peak 1.14 at full
  velocity, level with an `Osc`. Mutation-checked: ignoring the release, never freeing a
  rung-out voice, and a 1% pitch error each fail.
- **Heard through the app on the emulator**: a `Drone` holding degree 12 into a `Pluck` at
  decay 0.97, loaded from a file, output switched on. The engine's capture has harmonics at
  524, 1047, 1570, 2094 and 2617Hz, peak 0.37 -- the whole path, from the file through
  `GraphSync` and the node factory to the stream. Not yet played on the phone.

### Two columns, and FM

**Built overnight 2026-09-18.** `kMaxParams` is 8. Past five rows a panel lays its rows
out in two columns -- the first half down the left, the rest down the right, so the
parameters are still read in order -- with a gutter between them as wide as a side one,
since it holds the same two chips: the left column's `[ ]` and the right column's promote.
All of it is inside `panelRowAt`, which every panel's drawing and hit test was already
placed by, so the chips, brackets, typed values and group panels followed without being
touched. On the reference device an eight-row panel's rows are 276dp wide and keep the full 76dp
height a row is capped at, since four to a column fit without shrinking. A
test reads `kMaxParams` and `kMaxPorts` out of `node.h` now, as it does the node ids.

**FM**: two sines per voice, the modulator at `ratio` times the note and pushing the
carrier's phase by `index` radians. The index follows the amplitude envelope (Chowning's
brass: brighter as louder) and velocity, and falls on its own with time constant `fall`
(the bell and the electric piano: brightness dying before loudness). A fresh voice starts
both phases at zero so every attack is the same shape; a stolen one runs on, like an
`Osc`'s. `ratio` is continuous from 0.25 to 16 -- whole numbers are harmonic and the rest
clang, and the keypad types them exactly, which is the answer to "a slider cannot land on
3" until snapping proves necessary.

Tested by where the energy is rather than by ear: index 0 is a sine at the note with
nothing at 2f; ratio 1 puts energy at 2f; ratio 2 puts it at 3f and none at 2f, which
catches a ratio ignored or applied to the wrong operator; and with `fall` at 0.1s the 2f
partial is a third of the fundamental at the strike and under 2% a second on, the note
still at full level. Each mutation-checked. On the emulator a held C-E-G through the app
came back with the three notes level and their sidebands gone after a few seconds.

**The audio family goes warm.** No blue passes `ModuleColorTest` any more, so FM went warm.
The first shade, a dusty red (`A46868`), read on the phone as one of the LFO's purples --
at 55% over the dark fill a red border loses its warmth -- so it is a brown now
(`986C5C`), well clear of both purples and still nearest the gray audio cable. The family is
now "steel blues, grays and warm grays".

**Found on the phone the next morning: two-column knobs travelled the whole panel.** A
knob's position was measured from the panel's edges rather than its row's -- the same thing
while every row spanned the panel -- so a knob in either column took the whole screen to
cross. The geometry test had checked where rows are drawn and not what a drag in one does;
it now checks each row's own ends and middle.

### SF, a SoundFont player

**Built overnight 2026-09-18.** TinySoundFont (MIT, one header, vendored with no edits) plays
GeneralUser GS, shipped in the APK's assets and read from there rather than copied out --
32MB, and the APK is 62MB now. Files the user drops into `soundfonts`, beside `scales` and
`groups`, are listed after it. The panel has one knob, `level`; the instrument is chosen
from a chip in the header that names it and opens a page of tiles -- the fonts along the
top when there is more than one, the presets below, scrolled by dragging in whole rows,
numbered from 1 as GM charts are. The preset is stored as `bank * 128 + program`, so a patch
moved to another bank asks it for the same GM instrument; one the bank lacks plays its first.
The module's font is a name on the module (`"font"` in the file, like `"name"`), never
null on an SF so a patch keeps the bank it was made with if the shipped one changes.

- **Pitch is ours, not MIDI's.** Each note gets a channel of its own and plays on the
  nearest key, and the channel's tuning -- fractional semitones -- carries the rest, so a
  19-TET degree sounds where it should and a Change glides the channel (a block at a time,
  over the usual 30ms). Sixteen channels, the one released longest ago reused first, since
  a releasing tail takes the next note's tuning.
- **Nothing it does on the audio thread allocates**, which TinySoundFont does lazily by
  default: its voices are preallocated and capped (64, since a note can layer regions), and
  every channel is touched once when the node's synth is built. Built where? Not in the
  node: a font loads in the background and seconds after the node exists. So the graph
  grew a **Resource** -- anything built off the audio thread for a node, handed across by
  pointer with `SetResource`, the replaced one returned through `collectGarbage` exactly as
  a scale list is. Each SF node's synth is a `tsf_copy` sharing the one loaded bank; copies
  are made and closed on the interface's thread, since TinySoundFont's reference count is a
  plain int.
- **A node keeps its notes while it has no font, and strikes them when one lands.** Found
  on the emulator: a drone's chord, sent in the sync that made the node, reached it a
  quarter of a second before its font, and a held chord is sent once -- so it was never
  heard. Switching fonts re-strikes a held chord the same way.
- **Fonts load when a module wants one**, not at launch, and stay loaded for the process.
  GeneralUser GS parses in 184ms on the emulator (287 presets); on the phone it is not yet
  measured. A second font costs its own ~60MB and is not freed when nothing plays it --
  worth revisiting if anyone keeps several.
- **The level is TinySoundFont's own**, measured rather than guessed: at 0dB one note peaks
  from 0.4 (an electric piano) to 1.3 (a square lead), an Osc's range. 12dB was the guess
  and peaked at 3.4.
- **The bank is not in tune with itself**, which the tests learned the hard way: GeneralUser
  GS's piano is stretched 12 cents sharp at C5 and its flute 6, and the organs sound an
  octave down by design. Pitch tests use GM 81, the saw lead, within half a cent at both Cs,
  so what they find wrong is the node's. TinySoundFont also skips SoundFont modulators,
  which GeneralUser uses heavily, so some presets will not sound as their author voiced them.
- Tested on the host against the shipped bank: it loads with its piano and drum kits; a
  note and a quarter tone land within 5 cents; a Change glides; an Off lets go; a note held
  before the font arrives sounds once it does; replacing a synth hands the old one back
  (ASan would call a dropped one a leak). GraphSync hands a node its font once both exist,
  again after a font change or an engine restart, and to nothing that is not an SF. Each
  mutation-checked. On the emulator: the page opened, scrolled, chose Nylon Guitar into the
  file, switched to a second bank and handed the node its new synth; a C-E-G through the
  piano was captured.
- UBSan reported a left shift of a negative number inside TinySoundFont's parser on every
  load. Defined behavior in C++20 and on every compiler here; the host build compiles
  vendored code with `-fno-sanitize=shift` rather than editing upstream.

After switching fonts the page shows the chosen instrument in the new bank: the scroll is
asked for as "wherever the chosen one is" and resolved once that bank's list has loaded,
which is a moment after the switch. The first version opened at the top.

### DotSeq

**Built overnight 2026-09-18.** A grid of steps by degrees, as `Steps`' is, holding dots: a
note with a start, a degree and a length in steps, any number to a column. Tap an empty cell
for a one-step dot; drag a dot sideways to stretch or shrink it, never past the next dot at
its degree (two notes at one pitch cannot overlap) or the end of the loop; tap a dot to
remove it. A drag from an empty cell scrolls, as on `Steps`. The grid shows as many columns
as the loop is long, up to 32 -- unlike `Steps`, which draws all sixteen and dims those past
the loop, because 32 at once is a 20dp cell: a short loop gets cells a finger can hit. Dots
scrolled out of sight leave a bar on the edge they went past, across the steps they last.

- **A dot lasts its length in ticks, not frames.** It starts on its step's tick and ends on
  the tick `length` steps later, so a two-step dot is two whole steps, as Bespoke's are, and a
  stopped transport holds it exactly as it holds a `Steps` note. Offs go before Ons on a tick,
  so a dot followed at once by another at its degree is two notes. A jump in the count -- a
  reset, a changed interval -- ends everything held, since the ticks it waited for may never
  come. A `Steps` note is still half a step; a gap between dots is made by shortening one.
- **Dots cross by slot** (`SetDot`), like steps by index: only what changed, a cleared slot
  for each that went, and all of them for a node just made. 128 dots, and 16 sounding at
  once; both mirrored and asserted against `nodes.h`.
- **Format 8**, for dots and for the other direction: a build before it would read a DotSeq,
  an SF, an FM or a Pluck as a type that no longer exists, skip it, and autosave the patch
  without it. Now that build refuses the file and moves it aside. 8 reads 7, 6 and 5 as they
  stand.
- **A stepped knob with more than sixteen options is a bar**, with a whole-number reading
  that can be typed (and is rounded). DotSeq's `len` has 32, and as buttons they ran into each
  other and over the row's label -- found on the emulator the first time one was drawn.
- Tested: a dot's length, a chord of three lengths, an Off before the next On, the loop, a
  cleared slot, the jump, and what a new cable is told is held (node tests); the hit test at
  4, 16 and 32 columns, how far a dot may grow, the cap, the file round trip and its clamping,
  format 7 still reading, a duplicate and an undo byte for byte, sync by slot (JVM). Each
  area mutation-checked. On the emulator: a loaded pattern drawn, a dot stretched to four
  steps and one added by a tap, both in the file; DotSeq into FM captured with its energy
  per step repeating every eight steps.

### Note processors: Chance, Chord, Arp, Euclid

**Built overnight 2026-09-18**, in `processors.{h,cpp}`. The first modules with notes in *and*
notes out, which needed nothing new from the graph -- a processor's note input is ordered
before it like any other, and its output merges downstream like a sequencer's. What each has
to do is keep a source's promises: every On it sends matched by an Off, the notes a source
started ended when that source is unpatched (downstream too: `notesCut` queues the Offs and
the next block sends them), and a held note reported to a cable patched in late. A processor
answers to its sources' ids and speaks with its own; `NoteLinks` is the table between them.

- **Chance** passes each note with a probability, decided at its On; its Off and Changes
  follow it, and a dropped note's Off says nothing. The dice are an xorshift, since `rand()`
  locks.
- **Chord** makes each note the note and up to three more, in **degrees** of the scale
  sounding -- so one setting is a triad in any scale that has one, and means something in any
  tuning. 0 adds nothing; the defaults, 4 and 7, are a major triad in twelve equal steps, the
  tuning a patch starts in. A held note keeps the chord it started with, and a Change moves it
  whole.
- **Arp** holds what arrives, in order of pitch, and plays one on each tick -- up, down, up and
  down (not repeating either end), or at random -- across one to four octaves, an octave being
  the sounding scale's size in degrees. Steps' half-step notes and header interval chip.
- **Euclid** is a generator: pulses spread over steps by Bresenham's line (the same patterns
  as Bjorklund's up to rotation; 3 over 8 is the tresillo), turned by rotate, at one degree.
  Its panel draws the pattern across the top -- a mark per step, filled where a note falls,
  the playing one ringed -- from a Kotlin copy of the engine's rule that a test holds to the
  same strings node_test checks the engine against.
- Tested per processor on the host, each mutation-checked: Chance at 0, 1 and about half of a
  thousand, a dropped Off silent, a cut source ended; Chord's three notes and their degrees,
  a Change and an Off taking the whole chord; Arp in each mode from notes held out of order,
  across two octaves, and falling silent when let go; Euclid's patterns and its playing of
  them. On the emulator a held C-E-G through Arp (up and down, two octaves) into FM came back
  as 264, 328, 392, 520, 656, 784 and down again, one pitch per sixteenth.

Five note modules pushed the greens past what `ModuleColorTest` passes in a strict green, as
expected; the family widened to olives, forest and teal-greens and mint, and the 15-point rule
held. The add menu has fifteen modules and fits, at the reference font scale, on one page.

### The morning after: Seq, the Drone's transpose, and naming notes

**2026-09-19, from Forrest's first session with the night's modules**, with his choices:

- **DotSeq is Seq, and takes Steps' place in the Add menu.** Its notes are not dots, and it
  does everything Steps did but one thing: a note lasted its whole length, so nothing was
  shorter than a step and neighbors were always legato. It has a **gate** now -- how much of
  its last step a note sounds, 0.5 by default, which is Steps' half step; 1 is legato. The
  gate is counted in frames from the last step's tick and holds still with the transport,
  as Steps' does. Steps is out of the menu and **not retired**: every patch and saved group
  that has one (the demo patch among them) still loads and plays it. Retiring it would have
  meant refusing those files or converting them, and the conversion is exact but still a
  conversion. What Steps still has that Seq does not: rests that remember their pitch, and a
  pattern already in it when added. A file from the one night the name was DotSeq opens as
  Seq -- the same module renamed, read by an alias in `Types.byName`, not converted.
- **A Drone has a transpose**, like Steps' and Seq's, in cents with the scale's marks. Asked
  for to move a drone's notes down an octave. It is folded into where a held note *is*, so
  turning it is a retune like a changed scale: every held note is sent a Change and glides.
  The Drone had no knobs until now, which two tests had written down as a fact about it; the
  rule they protect -- a grid with no knobs still opens, and takes the whole body -- is now
  tested against a made-up module that has only a grid.
- **Euclid stays one row**, and its degree is read as a note: "-12  C3", in the scale and key
  sounding. Four rows like Bespoke's were the alternative; a group of four Euclids does that,
  and now each says which note it is.
- **The scale chip says the key.** It showed how many degrees the scale has ("12-TET · 12"),
  which the name mostly says; it shows the root's note and octave now ("12-TET · C4"). The
  root moves the whole patch and could not be seen without opening the card.

### The second session: unused ports, a named patch, and no bundled bank

**2026-09-19, from Forrest's second session**, again with his choices:

- **A group port with nothing on either side goes by itself.** He made a second output by
  dragging a jack to the rail's `+`, moved the cable to the port he had meant, and the empty
  one stayed -- nothing about it said it was unused, and the only way out was a long press
  he had no reason to guess at (it does offer "Remove port", which he had not found). Ports
  are still *stored*, so unplugging one that is patched on its other side still leaves the
  jack to plug back into; a port with nothing on either side is one nobody is in the middle
  of using. Swept after a cable is removed rather than checked when one is added -- a port is
  made and then patched, and a sweep between those two would take it away again -- and on
  load, so a file written before the rule opens clean.
- **A panel can say where its second column starts.** FM's split fell ratio/index/fall/A and
  D/S/R, which cuts ADSR in two. `A` now declares the break. Any module whose knobs come in
  groups can do the same; without it the split is still at half.
- **A patch has a name, and the breadcrumb is there at the top level** to hold it -- one chip
  reading "Patch" until it is renamed, by holding it as a group's crumb is held. The name is
  in the file, undone like anything else, and absent from a file until it is given.
- **"New patch"** clears everything back to an empty canvas -- default tuning, tempo and
  name, rails as they start -- from the canvas menu, beside "Save patch". Undo puts it back,
  because it goes through the model like any edit.
- **The bundled SoundFont is gone**, at his call: 32MB of the download for a file anyone who
  wants the SF module can fetch. The README says where to get GeneralUser GS and what the
  folders are for. An SF module starts with **no bank** and asks for one; with the folder
  empty its page says where to put `.sf2` files. The node tests build a **SoundFont of their
  own** -- one looping sine, 250Hz at key 60 -- which is better than a real bank for them:
  GeneralUser's own presets are each tuned however their sampler felt, and what these tests
  measure is the node's tuning. Writing it found nothing in the engine; it is 80 lines of
  RIFF chunks in the test file.

**Found on the way:** CLAUDE.md said `NodeType` mirroring the C++ enum was asserted. It was
not -- only that Kotlin's ids were distinct. A test now reads the enum out of `nodes.h` and
compares, and fails on a wrong id; worth having before six more modules each edit two
enums in two languages.

**Color is running out in the audio family.** The blue-grays near the audio cable are
nearly taken by `Osc`, `Filter`, `Mix` and `In`, and Pluck took an icy cyan (`5CCCE0`) as
the last clearly blue shade that `ModuleColorTest` passes. Five note processors will not
all fit in the greens at a 15-point border distance; that wants deciding when they land,
not by picking whatever passes.

## Phase 10 -- PatchGarden: the poly subpatch

**Begun 2026-09-20, on its own branch, because it may not turn out better.** Forrest tried
to put an envelope on an FM's modulation index and found he could not, and the reason was
structural rather than a missing feature. Everything below follows from that one attempt.

### What was actually wrong

Phase 6 moved polyphony *inside* the synths: an `Osc` held eight voices, a chord arrived
down one cable, and the module allocated. That was the right call against the thing it
replaced -- "a three-voice patch is twelve nodes", which this file proposed in Phase 7 and
then retracted -- and it bought a chord for the price of one module.

What it cost was not visible until someone wanted it. A synth's envelope was *one envelope
for every voice it had*, and nothing outside the synth could reach a single note. So
"one Env per voice, patched wherever you like" was not expressible, and FM's brightness --
the thing most worth shaping on an FM, and the one its knobs make hardest to get right --
could only ever follow the loudness envelope sitting beside it.

The idea that answers it was raised when polyphony was first built and **rejected then**: a
subpatch that is monophonic on the inside and clones itself for polyphony. It was rejected
because voice-pool polyphony was cheaper and grouping was about screen space. Both were
true. Neither addressed per-note patching, because nothing had tried to do it yet.

### Everything is a subpatch now

The name settles on **subpatch**, replacing "group" throughout -- "group" said what the
feature did to a selection, and the thing itself is a patch inside a patch. **Superpatch**
is the parent, for prose; it has nowhere to appear on screen yet. The app is renamed:
"canvas" named the surface at a time when the surface was the idea, and the canvas is still
there and still the thing you touch, but it is no longer what distinguishes this from any
other patcher. The launcher label and the docs only -- changing the `applicationId` makes it
a different app to Android, with no update path from an installed copy.

**The name was PatchMatryoshka for two days and is now PatchGarden.** Matryoshka named the
nesting, which is the mechanism; it is recorded here because the rename is in the history
either way, and because it pinned down what a rename costs -- the launcher label,
`rootProject.name` and the docs, and nothing else. That the second one was as cheap as the
first is the useful part: keeping the `applicationId` and the package on the original name
is what makes the app's name a label rather than an identity.

The goal behind the rename is that a subpatch should be **the first thing anyone reaches
for**, not the thing you tidy up with afterwards. So both kinds can be made empty from the
add menu, and you go inside and build there; the selection flow that collapses what is
already on the canvas is the second way rather than the only one.

### The synths lose their envelopes

`Osc` keeps one knob and `FM` three. What is left is a 5ms smoothstepped gate ramp
(`GateRamp`), enough that a note does not start or stop with a step in it -- measured at
both edges, because a square is at full amplitude from its first sample and a note let go
is at whatever phase it had reached.

`FM` loses Chowning's brightness-follows-loudness with it, on purpose: with the envelope
gone the amplitude is the ramp and nothing else, so keeping the coupling would have dimmed
the first five milliseconds of every note and no more. Expose `index`, patch an `Env`, and
the two envelopes no longer have to be the same envelope -- which is the thing that could
not be done before and is strictly more than what was lost.

**`Amp` un-retires the VCA**, at id 21; id 7 stays dead, because that module took a control
voltage. The argument that retired it -- a `Mix` channel is `in * level` and a level with a
modulation jack is the same module -- is still true and stopped being the point. Env and the
thing Env opens is the pair you reach for inside a poly subpatch, and that should be one
cable, not opening a Mix, exposing a knob, setting its brackets and then patching. Its
modulation input is a **port**, not a jack on a knob: a port is read per sample where a
parameter is applied once a block, and a millisecond attack through a 1500Hz control rate is
a staircase.

That port needed one new mechanism, `Node::unityInputs`. An unpatched input reads as
silence, which is right for an input that is summed and wrong for one that multiplies: an
Amp with nothing on its mod jack would be silent, and on a phone with no meter that reads
as a broken module. Ports that multiply say so and the graph hands them ones -- including on
the *previous* side of a crossfade, or patching a modulator would fade up from zero rather
than down from unity. The test passed until it measured across the join.

### How a poly subpatch reaches the engine

`Patch.engineGraph()` is the flattening. A plain subpatch flattens to nothing, as it always
did. A poly subpatch flattens by **copying**: `voices` of every module inside it, a `PolyIn`
that shares the notes out one instance at a time, and a `PolySum` per signal output that
adds them back up. Nothing about nesting crosses into C++; the engine is handed a flat
graph, which is the invariant this phase was most careful not to break.

- **Instance 0 keeps the module's own id.** Telemetry is asked for by module id -- by the
  panel and the canvas both -- so numbering from the original means all of it reads the
  first instance without knowing instances exist. Later instances carry their number in bits
  48 and up, where a patch's ids cannot reach.
- **Only notes are shared out.** Audio and modulation are broadcast to every instance: the
  instances are copies of one voice, not separate patches, so one LFO outside sweeps all
  four filters inside. A poly subpatch's note *output* merges at its destination rather than
  through a node, which is what a note input already does.
- **`PolyIn` keeps `PolySynth`'s allocation rules** -- idle, oldest released, then steal --
  because every one of them was paid for by a bug on the phone. It adds one thing a synth
  does not need: a stolen instance is sent an Off first, since what is inside it is an
  ordinary `Env` holding an ordinary note and nothing else would ever end it.
- **A poly subpatch may not contain another.** Instances would multiply and the id space is
  one level deep by choice. Refused from every direction: added inside one, made from a
  selection inside one, wrapped around one, duplicated or loaded into one.
- `kMaxPorts` is 8, which makes 8 the voice limit, because a port is an instance at those
  two edge nodes. `kMaxNodes` is 256, because eight copies of a six-module subpatch is
  forty-eight nodes for one box on screen.

On the canvas a poly subpatch draws as a stack, and so do its rails, which read **"Instance
in"** and **"Instance out"**: what you are looking at in there is one copy of several, while
its ports, its panel and its contents are all singular. Its `voices` knob is the only knob
either box has of its own and heads the panel it opens.

### Format 9 reads nothing older

The run from 5 to 8 was additive every time, which is why the version check kept accepting
the earlier ones. This one is not. A format 8 file names a "Group", which this build reads
as a retired type and skips -- taking everything inside it, then autosaving the patch that
way -- and its `Osc` and `FM` carry an envelope's four knobs where this build has none, so
every index after the first would land on the wrong knob. `PatchStore.load` still moves a
refused file to `patch.rejected.json` first, which is what makes refusing affordable.

`Param.newColumn` went with FM's seven knobs: it existed so ADSR was not split across the
panel's two columns, and no module has more than four knobs now. The only panel that
reaches two columns is a subpatch's.

**The audio family is now out of room**, as the note at the end of Phase 9 warned. `Amp`
had to be colored by what it sends, which is audio, and a search over every shade that
`ModuleColorTest` accepts at a 15-point border distance came back with nothing but the
mauve-gray corner -- `A890A8`, at 16.1 from its nearest neighbor. A sixteenth module that
sends audio will not fit, and the answer then is not a narrower margin: it is that a module
should probably be told apart by its *shape* or its glyph rather than by one more shade,
with color kept for the family alone.

### Seq's gate goes, and a dot's length is its duration

**2026-09-20, from Forrest, and it is the roadmap correcting itself.** He asked whether Seq
needed a gate at all, since an envelope shapes the note now -- and then whether Bespoke's
DotSequencer, which Seq is modeled on, even has one.

It does not, and neither did this module as designed. The DotSeq entry in Phase 9 says so
in as many words: *"a gap between dots is made by shortening one."* The `gate` knob arrived
the next morning for one reason, recorded in the entry after it: Seq took Steps' place in
the Add menu and could not do the one thing Steps could, sound a note shorter than a step.
A dot's length was whole steps with a minimum of one, so a global knob was the cheap way to
get sub-step articulation into a model that already expressed duration per note.

The answer was to fix the resolution rather than keep the knob. **A dot's length is now in
quarter steps**, so duration is the dot's own extent again: Steps' half step is a length of
2, a gap is made by shortening a note, and the grid *draws* it -- a half-step note is half a
cell wide, where before the same music was a number hidden in a knob. `Seq` is back to two
knobs in one column, which is also what the panel wanted.

Worth writing down, because it was nearly missed: `gate` was not a control anyone had
reasoned about on its own merits. It was a compatibility patch for a module it replaced, and
it survived a day of use, a panel-layout fix built around accommodating it, and two rounds of
argument about whether it was redundant -- before anyone asked what the thing it was copied
from actually did. The check that settled it took one grep of this file.

Format 10, reading nothing older: a 9 stores lengths in whole steps and would come back a
quarter of its length, which still loads and still plays and is not the music that was
written. Forrest's live patch was converted explicitly, off the device, by a script that
multiplied each length by four and took the gate's share off -- a migration run by a person
who knew what it meant, which is the only kind this project allows.

### The synths go monophonic, which was the point all along

**2026-09-20, and a correction to the entry above as much as to the code.** Asked whether
`SF` should carry a stack, this assistant answered that it should not because Osc, Pluck and
FM were polyphonic too -- and offered "should the synths be monophonic?" as an open fork
worth deciding later. Forrest: *"the whole point of the Poly module is so the simple synths
can be made monophonic. Otherwise it's not a simplification at all."*

Which is right, and is what this phase was for. Polyphony inside each synth is the thing the
poly subpatch replaces, not a second way of doing it. Leaving eight voices in an `Osc` that
sits inside a four-instance subpatch is two allocators stacked with the inner one never
choosing anything, and the whole design surface the redesign set out to retire still sitting
there behind it.

So `PolySynth<Voice, 8>` is `MonoSynth<Voice>` in `synth.h` -- the file renamed with it,
since `poly.h` holding nothing polyphonic is a lie. What survives is everything one note
needs and `PolyIn` does not do: resolving a degree against the scale of its beat, matching
an Off by source as well as id, gliding on a Change, releasing on `notesCut`. What goes is
slot selection, about twenty-five lines, now living once in `PolyIn`.

`SF` keeps its 64 voices, and they are not the same thing: they are TinySoundFont's, and one
note can take several of them at once for a layered preset, so capping it to one would
silence half of some instruments. That makes `SF` genuinely the only polyphonic source --
which is the question Forrest actually asked, arrived at from the other end.

**So `SF` is drawn as a stack, and the question answered itself.** Asked before the synths
went monophonic, the answer was no: Osc, Pluck and FM were polyphonic too, so marking one
of four would say nothing. Afterwards it is yes, and for the same reason -- it is the only
one left, so the stack tells you the one place you do not need a `Poly` around anything.
The cost Forrest named stands: opening it finds a panel rather than a canvas. That is worth
less than the marker, because the stack promises *several sound at once* and never promised
something to go inside. `ModuleType.stacked` declares it and a test pins the set at two, so
a third is a decision.

**What it cost the tests, which is the interesting part.** Four of them asserted things that
only a polyphonic synth can do: a chord into one Osc, a ninth note stealing, two sequencers
sounding at once, a long note surviving a short one's Offs. Every one of those properties is
still real; three of them just moved to the poly subpatch, where two notes actually sound at
once, and the graph tests now build the flattened rig by hand to say so. The fourth --
"unpatching a source ends its notes" -- became a cleaner statement monophonically: the
source that does not hold the voice takes nothing with it.

**Not heard yet, and here is what to listen for.** A monophonic synth retriggers on every
overlapping note, which never arose before because a second note took a second voice. The
voice is told it was stolen, so an Osc keeps its gate ramp open and an FM leaves its phases
running -- but there is no glide between the two pitches, so a legato line steps. Whether
that wants a portamento is a question for a finger, not a test.

### What is not known yet

> **Answered on 2026-09-21**, by listening to it. See below.

**None of this has been heard.** Every defect that mattered in this project was found by a
person playing it on hardware with a clean compile and a green suite, and the three things
most likely to be wrong here are all of that kind: whether the 5ms ramp is audible on
earbuds, where four voices summing sits against Out's limiter, and what stealing sounds like
when an `Env` rather than a built-in envelope is holding the note.

**The open question the whole branch is for:** does building an instrument inside a box, one
voice deep, actually read better than turning eight knobs on a module that hides its
polyphony? The argument says yes and the argument is why this exists. It is not the same as
playing it.

### Heard, on the phone

**2026-09-21. All four sound fine**, which is the sentence this phase was waiting for. A
patch of four boxes was built for the purpose -- one per question, each self-contained with
a single audio output, so one is plugged into `Out` at a time:

- **the 5ms ramp**, alone: a sine through a fixed gain of 0.4, half-step notes alternating
  low C and middle C, nothing else shaping the edge.
- **four voices against the limiter**: a four-note chord into a four-voice `Poly`, saws at
  full velocity. The capture peaked at 0.46, which past the 0.6 master gain is 0.76 out of a
  limiter fed about 2.8 -- roughly 11dB of reduction at the sustain, and it sounds fine.
- **stealing under an `Env`**: three notes an event into two instances, each chord held past
  the start of the next.
- **the legato line**, which steps: one monophonic `Osc`, every note overlapping the next by
  half a step, so the `Env` never closes and pitch is the only thing that moves. Heard, and
  not worth a portamento yet.

**An `Env`'s release is silent on a synth, and that is accepted.** Found while building the
stealing test the obvious way -- short notes, a three-second release, so a steal would land
on a tail -- and the capture showed the sound stopping dead within one 5ms window of each
note off, with 480ms of digital zero before the next note. `MonoSynth::process` frees the
voice the moment its `GateRamp` reaches zero and writes zeros from then on, so the `Amp` has
nothing left to multiply and `R` shapes nothing. Attack, decay and sustain are real; release
is not. `Pluck` is the exception, because its own `R` keeps the string rendering past the
note.

So "shaping is an `Env` inside a poly subpatch" is true with a caveat: an `Env` shapes a note
while it is *held*, and a tail needs a module that outlives the note. The alternatives were
an `R` knob on each synth -- an envelope creeping back into the synth, which is the thing
this phase removed -- and letting something downstream hold the voice open, which nothing in
the graph can currently say. Both were rejected on 2026-09-21 in favor of documenting it.
A delay or a reverb is the honest answer, and Phase 11 has both.

The stealing test as first built is worth remembering as a shape: it was a test whose
premise the engine could not satisfy, and it ran, and it made sound, and it was measuring
nothing.

---

## Phase 11 -- The catalog fills in

**Decided 2026-09-21**, from playing Phase 10 rather than from a plan. Nothing here is
structural: the poly subpatch answered the question it was built for, and what is left is
that the module set and the note model are thin in places a patch runs into at once. In
order, and the order is deliberate -- smallest first, then the cheap gap, then the one
that needs iterating on hardware:

### Velocity in Seq

**Built 2026-09-21, and smaller than it looked, because the engine has carried velocity all
along.** `OscVoice` sets its amplitude from it, `PluckVoice` uses it as the strike accent,
and `FmVoice` scales both the index and the output by it -- so on an FM, velocity already
meant brightness. What was missing was at the other end: every note source in `nodes.cpp`
wrote `on.velocity = 1.0f` and nothing ever chose otherwise. So the work was a fourth number
on `Dot`, one line in `SeqNode`, a field on the `SetDot` command, a gesture, and a format
bump.

**The engine needed no new field.** `Command` already carries a `value` that `SetDot` did
not use -- it is `SetParam`'s value and `SetModRange`'s low end -- so velocity rides in it
and the struct is the size it was.

**The gesture was the open part, and the lock is what settled it.** A drag on a dot already
set its length, from anywhere on the dot, and there was no way to move a dot at all -- you
removed it and placed another, losing its length. Three things now want a drag and a finger
has two axes. What was built:

- **A drag decides its axis once, on the first move**, the way the canvas loop decides what
  a gesture is, and for the same reason: a drag that changes its mind halfway is unusable.
  Across is the length, since a length is a distance along the grid; down the grid is the
  degree, since that is what the rows are. A tie goes to the length, which is the commoner
  edit and is what the gesture did before.
- **Once it is moving, both axes move it.** The drag was vertical to begin with, but a dot
  carried to another degree usually wants a different step too. It is held by the part that
  was grabbed, so a long dot taken by its third step does not jump to put its start under
  the finger, and a move into another dot at that degree is *refused* rather than clamped --
  the dot stays put until the way is clear, which reads as declining to pass rather than as
  a jump nobody aimed at.
- **The lock chip pins the dots**, and with nothing to move, a vertical drag sets velocity
  instead -- drawn as how much of the dot is filled, from the bottom, which is the direction
  the drag goes. Bespoke's DotSequencer shows velocity that way and it is right: the dot
  keeps its full outline, so a quiet note is still a note at that step rather than a smaller
  thing to aim at.

**Forrest's, and the reason it works:** it is stated as a *lock*, not as a velocity mode.
"Can a dot move" is a fact about the dots; "what does a vertical drag mean" is a fact about
the tool, and only the first is something a finger is already asking. The mode is the same
either way -- the name is what makes it legible, and the padlock says it without a word.

**A long-press per note was the first proposal and lost on arithmetic.** Press, wait,
buzz, then drag, per note -- sixteen notes is sixteen long-presses and about six seconds of
waiting before any of the drags, and setting accents across a phrase is exactly the pass
that makes velocity worth having. A chip costs one tap for the whole pass. The long-press
can still be added on top later for a single accent without taking anything away.

**Format 11, and it reads 10.** A dot gains a fourth number. That is additive in the strict
sense -- a dot that never said how hard it was struck was struck at full, so reading it at
full is a restatement rather than a conversion -- which is the distinction the refusal rule
has always drawn. The direction that needs no rule is the other one: a format 10 build
refuses an 11 file on the version alone, which is what stops it dropping every velocity and
autosaving the patch without them.

Velocity is written through the float's own `toString`, because widening `0.3f` to a double
puts `0.30000001192092896` in a file that people read with `cat`. Both come back as the
same float; only one of them is legible.

**Verified on the phone**: the lock draws its state, a locked vertical drag fills the dot
and the velocity reaches the engine (`PatchSync` logs `dot 128[2] = step 2 degree 6 for 6 at
0.45230705`), an unlocked one carries the dot to another degree, and a horizontal one still
sets the length. The patch it was tried on was a format 10 file, so that path was exercised
by accident and works.

**And then it clicked, which no test had asked about.** Forrest, playing it: clicking
between the notes of ear test 1 once their velocities differed, and gone the moment every
note was back at full. That last half is the whole diagnosis -- equal velocities have no
difference to step, so the click was in the *difference*, not in the velocity.

`OscVoice::strike` set the velocity as the oscillator's own amplitude, outright. For a note
starting from silence that is correct and inaudible. For a note landing on a voice that is
still sounding it is a hard step -- and two abutting notes are exactly that, because a dot a
whole step long ends on the tick the next one starts, `SeqNode` sends the Off and the On in
one block, and `MonoSynth` reopens the gate in the same sample it closed it. The ramp never
descends, by design: closing and reopening it is the step it exists to avoid. So the
waveform jumped by the whole difference between the two velocities. Reproduced in
`graph_test` on the real chain -- Seq into Osc into Amp into Out, dots a whole step long at
alternating velocities -- as jumps of up to **0.22** at every step boundary, against a
waveform whose own largest step is **0.014**. At equal velocities: no jump over 0.02
anywhere.

**So the level moved into `GateRamp`, where the gate already was.** It takes a level outright
while the gate is shut -- a silent voice has nothing to step, since whatever the level is it
is multiplied by zero, so a new note still gets its velocity exactly from its first sample --
and glides to it at the ramp's own rate otherwise. Only a note landing on a sounding voice
pays the 5ms. `OscVoice` no longer touches `SetAmp` and `FmVoice` no longer multiplies its
output by velocity; both hand it to the ramp. After: 0.0138 where it was 0.22, which is the
waveform's own slope and nothing else.

**What it says about the design**, and the reason it is written down rather than just fixed:
a gate that ramps and a level that does not is not a declicked voice. `GateRamp`'s job was
stated as "not sounding a step when a gate opens or closes", and velocity arrived as a second
thing that could step at exactly the same moments. Anything else a synth ever multiplies its
output by belongs on the same ramp.

The one discontinuity deliberately left: an `FM`'s index still steps at a strike, since
`brightness` resets to 1 there and `depth` is `index * velocity * brightness`. That is the
index envelope restarting, which is what it is for, and a step in timbre at a
phase-continuous point is not what a step in amplitude is. It has not been heard.

**Confirmed by ear on the phone, 2026-09-21**: the same patch that clicked does not. Which
is the pattern this file keeps recording -- the suite was green and the compile was clean
through every minute this bug existed, and the person playing it found it in one sentence.
The second half of that sentence, "gone when every note was at full", is what made it a
five-minute diagnosis instead of an afternoon.

### Filter: every type, and a slope

**Built 2026-09-21.** Four kinds -- low, high, band, notch -- and two slopes, in one module
rather than four. The knobs are the reason: `type` and `slope` are stepped with a handful of
options each, and a stepped row of sixteen or fewer draws as buttons (`Param.buttons`), so
the whole thing is four rows -- one short of where a panel goes to two columns. The
crowding that splitting would have avoided does not happen. Splitting would also have made
changing a filter's character a repatch rather than a tap, which is the wrong trade for the
decision people change most often.

Not the header: it holds one chip, at the panel's right, and both `panelIntervalChip` and
`panelPresetChip` are written to that one position. A second chip is machinery this did not
need when there were rows to spare.

**The kinds were nearly free and the slope was not.** `daisysp::Svf` computes `Low()`,
`High()`, `Band()` and `Notch()` every sample and `FilterNode` read one and discarded three,
so `type` is a switch over outputs that already existed. The slope is a second `Svf` in
series -- 24dB is two pole pairs -- and it runs *always*, even at 12dB where its output is
thrown away. A filter whose state had been frozen since the last slope change would resume
from a stale sample, which is a step at the one moment nothing is meant to happen; two
double-sampled biquads per filter is cheap enough not to think about it again.

**And the resonance turned out to be a hazard that had already shipped.** The SVF's only
limit on its resonance is a cubic term scaled by its drive, and `FilterNode::prepare` set
that drive to zero. Measured, with a sine sitting exactly on the cutoff:

| drive | gain at res 0.3 | at 0.9 | at 0.95 (the old maximum) | at 1.0 |
| --- | --- | --- | --- | --- |
| **0.00, as shipped** | 1.9x | 19x | **39x** | 1255x |
| 0.02 | 1.8x | 2.6x | 2.6x | 2.6x |
| 0.50 | 1.1x | 1.1x | 1.1x | 1.1x |

Nothing had caught it because nothing had ever pointed a tone at the cutoff and read the
number: every filter test until now used noise and asked whether the top came off. `Out`'s
limiter would have held the output, which is precisely the problem -- it would have held it
as a brick wall over whatever else was playing.

**What made the fix easy was that the drive costs nothing musically.** An impulse rings
12ms at res 0.5, 71ms at 0.9 and past three seconds at 1.0, and those numbers do not move
with the drive at all: it saturates the steady-state peak and leaves the ring, which is what
resonance actually sounds like. So 0.02, which keeps about 2.6x at the top -- a resonant
filter is supposed to have a peak, and 0.5 flattens it to 1.1 where the knob stops doing
anything audible at its own resonance.

**This changes how existing patches sound**, which is worth saying plainly because no
version check protects against it: a patch with its resonance up will be tamer than it was.
At the default 0.3 the cubic term is negligible and nothing moves. It is the right trade --
39x was not a filter setting, it was a fault waiting for a bass note -- but it is a change
to music that already existed, and this file is where that gets recorded rather than
discovered.

**The res knob now reaches 1.0**, where it stopped at 0.95, because the damping reaches zero
there and the filter rings until something stops it. That is the top of the knob and worth
having. It is **not** self-oscillation and the tests say so: nothing here can make the
damping negative, so with no input a filter at full resonance is silent.

**Format 12**, additive like 11 before it: a filter that names neither type nor slope was a
12dB lowpass, which is what it comes back as. The bump is for the other direction -- knobs
are keyed by name, so an 11 build would read a bandpass, ignore the two knobs it does not
know, and autosave it as a lowpass.

**On the phone**: the panel is four rows in one column as predicted, with the buttons
legible at font scale 1.5, and the four voices played through a filter at 400Hz peaked at
0.404 with the res knob at its new top against 0.407 at 0.3 -- more energy at the resonance,
as a resonant filter should give, and the same peak, with nothing near the limiter and
`find_clicks.py` reporting zero discontinuities on either channel. Which is the measurement
that matters: at the old drive of zero that capture would have arrived as a brick wall.

What is pinned on the desk is that each kind keeps what it is named for, that the steeper
slope is much quieter two octaves up and no quieter below the cutoff, that the resonance is
finite and bounded at every kind and slope, and that full resonance rings past a second
while silence in is silence out. All four mutation-checked; reverting the drive alone
reports 3137x. **What no test can say is whether the four kinds are musical**, which is a
knob and an ear.

### The cutoff follows the note

**Asked for on 2026-09-21, from playing the filter**: a sawtooth wants filtering at some
multiple of its own fundamental, not at a fixed frequency. Which turned out to name a gap
rather than a feature -- **nothing in the app could make anything follow pitch.** Notes
reach the things that sound them and nowhere else.

**The Filter takes notes.** It declares a `notes` port beside its audio one -- the first
node here to take both, which the graph needed nothing new for, since it already dispatches
per port on the note mask -- and a `track` knob says how much of the note's distance from
middle C the cutoff follows. At 100% the cutoff holds a fixed ratio to the fundamental, and
that ratio is the knob's own hertz against middle C: 785Hz is the third harmonic. The pitch
is resolved by `pitchOf`, the same one line a synth uses, so the filter learns no more about
a semitone than anything else does.

The rule this sets, beside the one it already had: **a module that wants audio-rate
modulation declares an audio input; a module that wants to follow the note declares a note
input.** The alternative was a general module turning notes into modulation -- one thing
filling the gap for every parameter at once, which is the more composable answer and is
still open. It lost here on precision: the tracking ratio would live in a bracket dragged on
the target, exact only when its octave span matched, so one imprecise drag gives 97%
tracking, which drifts across the keyboard and reads as a tuning bug.

**It works, and the measurement is the point of it.** A four-octave line through a fixed
filter comes out with its loudness varying 1.74x from note to note, because the low ones
keep their harmonics and the high ones lose their fundamental. Tracked, the same line varies
**1.06x** -- the filter sits the same distance above every note, which is the whole promise.

**Format 13**, additive like 12 and 11 before it: no `track` knob is no tracking, and a port
appended leaves every saved cable's index where it was, so 12, 11 and 10 all still read. The
bump is for the other direction again -- a 12 build would read a cable into a note port it
does not have, skip it quietly, and autosave the patch without it. Which makes the rule
broader than the one written down for a new module type: **a knob or a port added to an
existing module bumps the version too**, because knobs are keyed by name and port indices are
positional, and an older build drops what it cannot name either way.

**The cutoff slides rather than jumping**, and the first version did not. A modulator moves
a cutoff a little every block; a note moves it octaves in one, and the capture caught that
as five discontinuities in ten seconds where the untracked line had none. The fix is a rate
limit -- a fixed number of octaves per block, 0.05, so a leap of an octave takes about
13ms -- and not an exponential glide, which was tried first and measured worse: smoothing
moves a quarter of the distance in the *first* block, and a quarter of four octaves is a
whole octave of coefficient in one step. Offline, against a steady tone, the step out of
proportion to the waveform went 8.9x jumping, 2.15x smoothed, 1.17x rate-limited.

**What is left is resonance, and it may not be a defect at all.** With the rate limit in
place the capture still reports one event per pass of the sequence, and the controls say
what it is:

| | discontinuities in 10s |
| --- | --- |
| tracked, res 0.6 | 5 |
| tracked, res 0.0 | 0 |
| no filter at all | 0 |

So it is resonance plus a moving cutoff, not the tracking rate: a resonator holding energy
at one frequency and being retuned produces a transient, which is what a swept resonant
filter *is*. An analog one does the same, and the app has always had the same case in an LFO
on a resonant cutoff. Whether it reads as a click or as a filter sweeping is a question for
an ear, and `find_clicks.py` cannot answer it -- as this phase already learned twice over,
once when it flagged a sawtooth's own edges as five discontinuities a cycle, and once when
two clean captures were wrong about clicks that were real.

### The tool was lying, in two ways

**2026-09-21, found while chasing the tracked filter.** `find_clicks.py` is what this
project reaches for when something might be clicking, and it misled the whole investigation
twice before it was read properly.

**Its docstring claimed a robustness it did not have**: that deriving the threshold from the
signal's own slope meant a sawtooth's once-a-cycle edge would not be flagged. The opposite
is true -- a rare large step is exactly what an outlier test reports -- and a four-octave
saw line duly read as 39 discontinuities, every one of them the waveform, spaced 92 samples
apart at the note's own frequency. The gaps were printed all along; nothing drew the
conclusion. It now does, as a verdict: evenly spaced at an audible rate and off the buffer
boundaries means the waveform, and the line says to measure again with a sine.

**And the alignment check had never once fired.** Hits index the *difference* array, so a
step arriving at sample n is reported at n-1, and `hits % 32 == 0` was asking whether n-1
was a multiple of 32 -- which answers 31, always. A click placed deliberately on a block
boundary reported 0% aligned. That check is the reason the file exists, it is the thing
CLAUDE.md calls the useful part, and it has been answering a question about the wrong sample
since it was written. Caught by building a synthetic capture with clicks at known offsets
and finding the tool disagreed with the fixture.

Re-run against every capture from this phase, the fix changes no conclusion -- the tracked
filter's residual events are still 0/5 on a block boundary, so they are the resonance and
not the plumbing. Which is the outcome to want from a tool fix and not the one to assume
from it.

**The ladder is still to come**, and deliberately after this: a second filter *character*,
where resonance thins the bass and the saturation is part of the sound. It needs more of
DaisySP vendored -- with the LGPL half kept out and `rand()` audited -- and it is a
listening project rather than a switch, which is a better thing to start once this one has
been played.

### The Env, redone: segments, curvature, and a shape you can see

**Built 2026-09-22**, and it is the one this phase called "the one that may not work". The
hooks were where the plan said they were: an envelope editor is another `GridKind` in the
open panel, which already owns the whole screen and already has drawing and hit testing for
a shape you edit with a finger, and the number keypad was already there.

**A segment says where it is going, never where it starts.** That one decision is what the
whole node does differently from the ADSR it replaced, and it is not a shortcut: a segment
begins wherever the output already is, so a note let go during a long attack falls from the
level it actually *reached*. A naive list of points would start the release at the sustain
level -- a level that note never touched -- and step the output by the whole difference,
which is the same class of fault as the velocity click and audible for the same reason. A
mutation confirms it: starting from the previous segment's level instead reports 0.797 where
the note had got to 0.2.

**Which is also why the format could not read an older file.** DaisySP's A, D and R are
one-pole *time constants* toward targets they never reach -- the release from a sustain of
0.6 with `R` at 250ms really runs `R*ln(1 + 100S)`, about four times the knob, which is why
the old test had to measure 2048 blocks to watch it close. A segment covers a stated distance
in a stated time. The mapping between them exists and writing it would have been the silent
conversion 10 was drawn against, so **14 reads nothing but 14** and the additive run that
carried 11, 12 and 13 ends here. Forrest chose that over keeping the ADSR alive as a second
module; the catalog stays one envelope deep and every saved patch goes to
`patch.rejected.json`, which is a refusal and not a delete.

**The gesture is Surge's split, and the reason is arithmetic.** Bespoke hangs time, level and
curvature on one draggable node, so the control that picks between them is a mode, and a mode
on a fingertip-sized target is a coin toss. Here a node is time and level -- both axes at
once, unlike a dot, because a node is a point rather than a cell and carrying a point to a
new time almost always wants a new level with it -- and curvature is a drag on the line
*between* two nodes. Different targets rather than different modes.

The other two decisions got targets of their own rather than being stacked onto the node as
well: **a sustain rail above the shape and a rail of times below it**. Both divide evenly by
segment rather than against the time axis, which looks wrong until you do the sum -- a 5ms
attack is half a percent of a one-second axis, and the attack is the first thing anyone wants
to type exactly. An even cell is always a target; the column order is what ties it to the
node above. The keypad is in **milliseconds** for the same reason the roadmap gave when it
was only a plan: nobody can drag a node to 12ms and everybody can tap it and type 12.

**A cap, not a scroll or a zoom.** `MAX_SEGMENTS` is 8. A scroll needs a gesture that
competes with dragging a node on the one surface where a drag already means "move this", and
a zoom-to-fit puts the nodes closest together exactly when the envelope gets interesting. The
number is the cheap part and is one line to raise once it has been played and found short.
The time axis is a round number at or above the envelope's own length rather than the length
itself -- otherwise the last node sits on the edge and cannot be dragged any longer, and every
other node slides whenever any segment changes.

**The curve is one expression on both sides.** `(1 - e^-at)/(1 - e^-a)`, which is 0 at 0 and
1 at 1 for every `a` and approaches a straight line as `a` does, so there is no seam at the
middle of the control where a piecewise pair of curves would have one. `envShape` in Kotlin
and `EnvNode` in C++ are deliberately the same formula: an envelope that sounds unlike its own
picture would be worse than one with no picture, and the picture is the entire reason the four
knobs went.

**A release is optional and that is a real setting, not a short time.** With no segment marked
sustain the envelope ignores the note off completely and runs its whole shape -- so a
sequencer's staccato does not cut a percussive patch short. This costs nothing on a synth
either way, because of the Phase 10 finding: a voice is freed the moment its gate ramp reaches
zero, so a release shapes nothing there regardless.

**A parked envelope keeps following its level.** Found while fixing the `graph_test` fixture
that used to drive a modulator through Env's sustain knob. Dragging a sustain node is
something anyone does with a note held down, and an editor that is deaf exactly then cannot be
tuned by ear -- so holding tracks the segment's level over the gate ramp's own 5ms rather than
jumping, since this is a level feeding an `Amp`. It also removed a wart the ADSR had imposed
on that fixture: a sustain of exactly zero used to latch the envelope to idle forever, so a
modulator set to nothing once could never be raised again.

**Three copies of a module and none of them knew.** `replaceWith` copied dots and not
segments, so undo silently handed back a default envelope; `duplicate` and `adoptSubpatch` had
the same hole, so a duplicated Env and one loaded from the subpatch library did too. Caught by
the byte-identical round-trip test, which is the one this file already credits with catching
the tuning and the tempo. They share `PatchModule.copyGridFrom` now -- one function, so the
next kind of grid is added in one place rather than in three and a half.

### On the phone, 2026-09-22

**Every gesture works, the shape is exact, and one thing looks wrong.**

Driven on the Pixel 10 Pro XL with a purpose-built patch (`EnvTestPatchGen`): Seq into Osc
into Amp, the same Seq opening a five-segment Env on the Amp's modulation port, so what is
heard is the envelope and nothing else. All six of the editor's actions confirmed against
`PatchSync`: dragging a node moves time and level together and every frame reaches the engine;
dragging a segment bends it and clamps at 1.0; a tap on a segment splits it; a tap on a node
removes it and clears the trailing slot; a tap on a time cell opens the keypad, where **typing
12 gave `seg 102[0] = 0.012s`** -- the roadmap's own sentence, executed; and a tap on a
sustain cell moves the hold with exactly two commands, one gaining it and one losing it. Undo
restores the whole shape through the model. The autosave round-trips and reloads identically.
Engine throughout: MMAP exclusive, 96-frame burst, ~5ms, **zero xruns**.

**The refusal behaved.** The format-13 "Ear tests" patch on the device was refused on the
version alone and kept at `patch.rejected.json`, byte for byte. A refusal is still not a
delete.

**The shape is exact, and proving it needed the node rather than the capture.** Measured off
`EnvNode` on the host, the listening patch's five segments read 1.0000 / 0.3500 / 0.7499 /
0.6001 and then hold 0.6000 forever -- each within 0.0001 of what was asked. The phone capture
appeared to disagree, reading the dip at 0.43 where 0.35 was asked, and the capture was the
one that was wrong: `Out`'s limiter squashes the attack peak, and normalising against a
limited peak inflates everything measured below it. The giveaway was two captures side by
side -- a sustain of 0.6 read 0.3257 and an attack of 1.0 read 0.3813, a ratio of 1.17 where
1.67 was due. **Another entry for the list of times a capture accused the engine and was
wrong**, after the sawtooth's own edges and the Bluetooth packet loss. `find_clicks.py`
likewise reported 63 discontinuities, every one of them a step of 0.0006 against a peak of
0.38 -- the detection floor, not a click. The verdict line reads spacing and cannot read
magnitude.

**What looks wrong: the rails do not line up with the nodes.** Both are divided evenly by
segment, deliberately, so that a 5ms attack still has a tappable cell. On screen the cost is
larger than the argument made it sound: the `hold` chip sits nowhere near its own dashed line,
and the last cell floats over empty canvas past the end of the curve, because the time axis is
a round number above the envelope's length and the cells are not. The eye expects column N to
stand under node N and it does not. The tappability argument still holds -- variable-width
cells would make the attack's cell half a percent of the width -- so the fix is probably to
*tie* them rather than to move them: a faint leader from each cell to its node, or shading the
cell and its span of the curve together. Not yet decided, and it is a decision about a picture,
which is a decision for an eye.

**And it shipped with no way out.** Forrest, the moment he had it: the open Env could not be
closed. The breadcrumb is hidden while any panel is open -- by design, and true of every panel
-- so the only exit is a tap on the border *outside* the panel, and the envelope's gesture loop
was swallowing it. The loop ends in an unconditional `return@awaitEachGesture`, and nothing
scoped it to the editor, so it claimed every touch on the screen and the panel became a room
with no door. Scoped to `gridArea` now, and `EnvelopeTest` pins the geometry that makes the
exit reachable.

The general lesson is worth more than the fix: **a gesture loop that returns unconditionally
has to earn the gesture first.** The dot grid's loop looks identical and is safe only because
it is reached through a `cell != null` that is null everywhere outside the grid. The envelope's
had no such gate and nothing in the suite could see it, because closing a panel is a touch on
a composable and every test here is geometry. Found in one sentence by the person using it,
which is the pattern this file has now recorded for the invisible rail highlight, four
transients, the mic race, the knobs that never reached the engine, the velocity click, and now
this.

**Two smaller notes.** The grab radius is honest but the curve is thin: two deliberate attempts
to bend a segment missed by about 32dp and did nothing at all, with no feedback to say why.
And the release still cannot be *heard* on a synth -- the Seq capture shows the audio stop dead
at note-off, because the Osc is freed the moment its gate ramp closes, which is the Phase 10
finding exactly. Half of every envelope drawn here is currently unhearable in any patch that
does not outlive its own note. Delay and reverb are the answer and they are the next thing in
this phase.

### Played, and it was flaky, 2026-09-22

**"The controls on Env seem very flaky"**, and underneath it two separate faults, neither of
which the suite could have seen.

**The curvature would not move**, reported against the segment holding the sustain -- which
turned out to be a coincidence. Both segments in that patch were at curvature *exactly 1.0*,
the maximum, so dragging up did nothing and a control pinned at its limit looks exactly like a
control that is dead. The cause is that `ENV_CURVE_TRAVEL` was 90dp: the full range from -1 to
+1 was 439px against a curve area 631px tall, so **any real drag crossed most of the range**
and everything ended up saturated. 200dp now. The test pins the relationship rather than the
number -- one drag down the editor must not cross the whole range -- because the number is a
feel and will be tuned again, while the relationship is what was actually wrong.

**And the segments were disappearing.** The same patch had gone from six segments to two. A
tap on a node removed it, which mirrored the dot grid deliberately, and that mirror was wrong:
a tap is what a finger does when it means to *grab* something, and a removed dot costs one tap
to put back where a removed node costs its time and its curve. That asymmetry is the answer to
the question this section left open, and it was answered by use rather than by argument, which
is what the question was for. Removal is a long press; a tap on a node does nothing.

**The long press then did nothing, silently, and the reason is worth keeping.**
`AwaitPointerEventScope` overrides `withTimeout` and throws Compose's
`PointerEventTimeoutCancellationException`, not kotlinx's `TimeoutCancellationException`.
Catching the wrong one compiles, never matches, and lets the exception end the gesture -- so
the feature was simply inert. The canvas loop three hundred lines above has always caught the
right one. Found by logging the exception's class name, after the code read as correct twice.

**And then the same complaint again, from the other end of the same mistake.** The last
segment's curvature would not move, and before it stopped it moved *the wrong way*: dragged
down and to the left, the middle of the segment went up. That second sentence is the entire
diagnosis. Curvature's sign is a fact about shape -- leaves fast, arrives slow -- and that
shape puts a rising segment's middle high and a falling segment's middle **low**. The drag
mapped the finger onto the number, so on any falling segment the line went the wrong way, and
kept going until the number reached -1 and the control looked dead. One fault, reported as
two, twice: the saturation fixed in the previous pass was this same bug pushing the value into
a corner.

`envCurveAfterDrag` takes the segment's direction now. What is worth keeping is how it
survived a device pass: the earlier check watched `PatchSync` report the curvature changing
and concluded the control worked. **Checking that the number moved is not checking that the
line followed the finger.** The test asserts the drawn midpoint, uphill and down, and
mutation-checks against the old sign.

**Third round, and this time the phone was asked instead of the code.** Same complaint --
the last segment's curvature would not change -- after two fixes that were both real and
neither of which was it. Rather than theorise again, a `PatchGesture` trace went into the
debug build: every touch in the editor logs what it landed on, what the nearest nodes were and
how far off the curve it was. Three attempts came back identical:

```
down (1150,808) -> NOTHING | nearest n0@519px n1@541px | off-curve - | grab 53px
down (1131,874) -> NOTHING | nearest n1@555px n0@581px | off-curve - | grab 53px
down (1140,897) -> NOTHING | nearest n1@546px n0@604px | off-curve - | grab 53px
```

Every one of them 200 to 300px *below* a line drawn at y=595, and every one inside the shaded
fill. **The envelope is drawn as a filled area and only its outline was a target.** The fill
is the part that looks like the segment and it is the part a finger goes for; it did nothing,
silently, which is exactly what "the controls are flaky" describes.

A segment owns its whole column now. A tap still has to point at the line, because adding a
node changes what the envelope is made of where bending it is an adjustment -- the same line
that makes removal a long press, and worth holding because the previous round's complaint was
about changes nobody asked for.

**What this says about the last three sessions.** All three faults were the same mistake
wearing different clothes: drawing one thing and targeting another. The rails do not line up
with the nodes; the curvature followed the number rather than the line; the fill looks like
the segment and was not it. Each was found by a person's finger and none by the suite, which
is the pattern this file has recorded from the beginning -- but the trace is new, and it took
the third one from a guess to a measurement in a single reading. It stays in the debug build
for the next one.

### The rails line up, 2026-09-22

**Forrest's, and the right answer**: the cells should shrink and grow with their segments but
keep a minimum width for the text. That is both halves of the problem at once -- the even
division existed because a 5ms attack is half a percent of a one-second axis and its cell has
to stay tappable, and the cost was that nothing lined up with anything.

Allocated by water-filling: anything that would fall under the floor takes the floor and drops
out, and what is left is shared among the rest by duration, repeatedly, until nothing else
sinks. Where no floor binds the cells land exactly on the segment columns, so the `hold` chip's
edge falls on its own dashed line and the last cell ends at the last node. Where the floors
cannot all fit -- eight segments on a short envelope -- the row widens toward the panel edge,
and past even that they share equally, which is where this started. The floor reads
`Frame.fontScale`, like anything else sized to hold a label.

Confirmed on the phone both ways: a two-segment envelope's cells land on its two columns with
the dashed line exactly on the boundary, and setting the first segment to 5ms through the
keypad leaves a small, legible "5ms" cell beside a long "965ms" one, the short cell wider than
its own column by exactly the floor.

**That closes the editor's habit of drawing one thing and targeting another**, which was three
faults in three sessions: the rails beside the shape rather than above it, the curvature
following the number rather than the line, and the fill that looked like the segment and was
not a target. All three were found by a finger and none by the suite.

Was open, now answered by use: whether a tap on a node should remove it. It mirrors the dot grid, where a tap
toggles, but a dot costs one tap to put back and a node costs its curve and its time. It was
not hit by accident once while driving the editor, which is weak evidence and the only kind
available until it is played.

### The fm port comes back to Osc

**Now that an `Osc` is one voice again**, the objection that retired it is gone: it used to
send the sum of eight voices, so one feeding another bent every note of a chord by the same
mixture. One voice feeding one voice is what FM means.

**The old port had no index at all, which is why the relationship never made sense.** Phase
3's `OscNode` was `hz = baseHz_ * exp2(pitch[i]) + fm[i] * 100.0` -- a hardcoded hundred
hertz per unit of input, with the depth welded shut. There was nothing to understand.

**The `FM` module's index is a different quantity again.** `FmVoice::render` is phase
modulation -- `sin(2π·carrier + depth·sin(2π·modulator))`, with `depth = index · velocity ·
brightness` -- and its modulator is a *unit* sine, so in there the modulator's amplitude and
the index are the same number. They come apart only when the modulator arrives down a cable
carrying an amplitude of its own, which is exactly the port's case.

**So the port carries one knob, `index`, in the same radians as FM's, defined as what a
full-scale ±1.0 input means.** A modulator at half amplitude then gives half the index,
which is both what an ear expects and what an `Amp` in front of it reads. No separate trim:
a "0dB index" slider and an `index` knob are the same control named twice. The alternative
-- hertz per unit, as the old code did -- makes the timbre drift with pitch, because the
index is Δf over the modulator's frequency and a fixed deviation in hertz is a different
index at every note. Phase modulation is pitch-invariant for free, which is why `FM`
sounds consistent across the keyboard without doing anything about it.

**An `Osc` has no tune knob, and that is a separate gap this exposes.** Its only parameter
is `wave`, so there is no way to vibrato one at all: a slow modulator into a phase-modulation
port shifts phase rather than pitch, so it produces no vibrato for its trouble. A `tune` in
cents, exposable like any other parameter, is the other half of this and a separate feature.

### Noise, and then delay and reverb

White, pink and brown, from one module with a `type`. The DaisySP caveat applies before any
of it is vendored: anything calling `rand()` is edited first, because Bionic's takes a mutex
and the audio thread cannot.

Delay and reverb are last and least decided. `delayline.h` is already vendored --
`Pluck`'s string is built on it -- so a delay is mostly a question of what its controls
are and whether its time follows the transport. One constraint comes with it:
`DelayLine`'s length is a template parameter, so the longest delay there can be is chosen
at build time and a node carries that buffer whether it uses it or not. A reverb is the
only thing in this phase with a real choice of algorithm in it, and choosing it from a
listening test beats choosing it from a paper.

**Both of them also answer the release question from Phase 10**, which is the other reason
they are here: a tail that outlives its note is exactly a module that keeps sounding after
the note ends, and neither of these needs a synth to be told anything.

### What this costs the file format

**More than one bump, and all but one of them are cheap.** Velocity was a fourth number on a
dot (11); the Filter's type and slope were knobs keyed by name (12) and its note port was a
port appended (13). `Noise`, `Delay` and `Reverb` are new module types, and the rule is that
adding one bumps the version even though nothing needs converting -- an older build reads an
unknown type as retired, skips it, and autosaves the patch without it.

**The expensive one was the `Env`, as predicted, and it landed as predicted.** It changes what
an envelope *is* in the file, which was the only one of these that would have been tempting to
convert silently. 14 reads nothing but 14. What the plan did not know was the arithmetic that
makes the conversion impossible rather than merely unwise: A, D and R are one-pole time
constants toward targets they never reach, so there is no segment duration that is the same
thing as an `R` of 250ms -- the honest mapping is `R*ln(1 + 100S)`, which depends on the
sustain. An ADSR read as four segments is a patch that loads, plays, and is not the sound that
was saved.

---

## Testing

28 tests as of Phase 1, against a suite that previously had a `junit` dependency and
nothing else. Covered: the graph invariants, the port geometry, and every malformed-input
path through the loader.

Worth doing once rather than assuming -- check the suite can actually fail.
Reintroducing the original 17.2dp port spacing fails three tests. Doing that also exposed
a real gap, since spacing and centering are separate terms in `portIn` and only spacing
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
   and input the transport quantizes, where the finger chooses what and the next step
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
