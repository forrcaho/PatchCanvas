# Vendored TinySoundFont

Upstream: https://github.com/schellingb/TinySoundFont
Commit: `853a0a171759f1ddba0de1442133a75912bbeffa`
License: MIT — see [LICENSE](LICENSE).

`tsf.h` only; `tml.h` (the MIDI file reader) is not used. `tsf.cpp` is ours: it defines
`TSF_IMPLEMENTATION` so the implementation compiles once, in its own target, with
upstream's warnings.

## What the audio thread may call

TinySoundFont allocates lazily, which the audio thread must never do. Two things make
its note and render paths allocation-free, and the SF node relies on both:

- `tsf_set_max_voices` preallocates the voices and caps them. Without it `tsf_note_on`
  `realloc`s four more whenever it runs out; with it, a note that finds no free voice
  takes one in its release or is dropped.
- A channel is allocated the first time anything touches it (`tsf_channel_init`). Every
  channel the node uses is touched once when its synth is built, off the audio thread.

`tsf_copy` shares the loaded samples through a plain `int` reference count, so copies are
made and closed on one thread -- the interface's, where the graph also frees what the
audio thread retires.

No edits to upstream source.
