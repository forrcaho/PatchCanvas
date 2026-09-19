# Vendored DaisySP subset

Upstream: https://github.com/electro-smith/DaisySP
Commit: `599511b740f8f3a9b8db72a0642aa45b8a23c3a3`
License: MIT — see [LICENSE](LICENSE), which also carries the Plaits and Soundpipe
notices for the code DaisySP incorporates.

## Why vendored rather than a submodule

DaisySP's own repository carries `DaisySP-LGPL` as a submodule. Adding DaisySP as a
submodule here would mean anyone running `git clone --recursive` on PatchCanvas pulls
the LGPL half we deliberately exclude, and acquires a relinking obligation without ever
choosing to. Copying the files we use removes that hazard entirely, and keeps offline
and CI builds free of a configure-time download.

The cost is that updating is a manual re-copy. That is the right trade for a dozen files.

## What is here, and why only this

| File | Used by |
| --- | --- |
| `oscillator.*` | Osc — the PolyBLEP waveforms; naive saws alias audibly |
| `svf.*` | Filter — state-variable, the Mutable Instruments one |
| `adsr.*` | Env |
| `limiter.*` | Out — a feedback patch can reach full scale instantly |
| `dcblock.*` | Out — keeps offset out of the converter |
| `KarplusString.*` | Pluck — the string, from Rings |
| `delayline.h`, `onepole.h`, `crossfade.*` | what the string is built from |
| `dsp.h` | shared helpers the above need |

`Utility/dsp.h` includes `custom_dsp.h` behind `#ifdef DSY_CUSTOM_DSP`, which we do
not define, so that missing file is not a problem.

Edits to upstream source (the second is marked `PatchCanvas:` where it is made):

- Includes flattened (`"Utility/dsp.h"` to `"dsp.h"`, and so on), so one include
  directory suffices.
- `KarplusString.cpp` calls `rand()` on the audio thread for its dispersion noise, and
  Android's `rand()` takes a mutex. The string keeps its own linear congruential
  generator instead (`rng_`). Anything else vendored later that calls `rand()` --
  `Dust`, `WhiteNoise`, `StringVoice` -- needs the same change.
