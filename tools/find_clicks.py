#!/usr/bin/env python3
"""Find discontinuities in a rendered WAV and say whether they line up with anything.

audio_analyze.py reports level, pitch and spectrum. This answers a different question:
where are the clicks, and are they periodic?

A click is a step the signal could not have made on its own, so the threshold is derived
from the signal's own slope rather than picked -- a fixed number would miss a real glitch
on a quiet patch and flag an ordinary edge on a loud one.

That threshold does *not* excuse a waveform whose edges are the point. A sawtooth steps
full scale once a cycle by design, and a rare large step is exactly what an outlier test
reports: run this on a saw and it will say hundreds of discontinuities, every one of them
the waveform. This file used to claim otherwise in this docstring, which cost an afternoon
of chasing a filter that was innocent. So the periodicity check below exists to say which
kind of periodic a run of events is, and the verdicts are the part to read:

  * evenly spaced and landing on multiples of the inner block (32), the device burst (96)
    or the stream buffer (192) -- the engine's plumbing;
  * evenly spaced at an audio rate and *not* on those boundaries -- almost certainly the
    waveform's own edges, so pick a smoother source and measure again;
  * irregular -- the DSP, which is the case worth chasing.

    python3 tools/find_clicks.py capture.wav
    python3 tools/find_clicks.py capture.wav --sigma 8 --png /tmp/clicks.png
"""
import argparse
import subprocess
import sys

import numpy as np


def decode(path):
    """Return (samples as (n, channels) float32, sample_rate). Via ffmpeg, so any WAV works."""
    probe = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0",
         "-show_entries", "stream=sample_rate,channels", "-of", "csv=p=0", path],
        capture_output=True, text=True, check=True)
    fields = probe.stdout.strip().split(",")
    rate, channels = int(fields[0]), int(fields[1])
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", str(channels),
         "-ar", str(rate), "-"],
        capture_output=True, check=True).stdout
    data = np.frombuffer(raw, dtype="<f4").copy()
    return data.reshape(-1, channels), rate


def find(samples, rate, sigma):
    """Indices of the first sample *after* a step that is an outlier against its own
    distribution.

    After, not before, and that off-by-one was a real bug for the life of this file: a step
    arriving at sample n shows up in the difference array at n-1, so the boundary check
    below was asking whether n-1 was a multiple of 32 and getting 31 every time. It
    reported 0% aligned for a click placed exactly on a block boundary -- which is to say
    the alignment diagnostic, the one thing this script exists for, could never fire.
    """
    diff = np.abs(np.diff(samples))
    if diff.size == 0:
        return np.array([], dtype=int), 0.0
    # Median absolute deviation: robust to the very outliers being looked for, where a
    # standard deviation would be inflated by them and hide the smaller ones.
    median = np.median(diff)
    mad = np.median(np.abs(diff - median))
    scale = mad * 1.4826 if mad > 0 else diff.std()
    threshold = median + sigma * scale
    hits = np.nonzero(diff > threshold)[0]
    if hits.size == 0:
        return hits, threshold
    # Collapse runs: one click smeared over a few samples is one event.
    keep = np.concatenate(([True], np.diff(hits) > rate // 1000))
    # +1: the sample the signal jumped *to*, which is the one whose offset means something.
    return hits[keep] + 1, threshold


def periodicity(gaps, rate, on_boundary):
    """One line on what an evenly spaced run of events most likely is.

    Events at a steady period are either the plumbing, the waveform, or a repeating figure
    in the music. Which one is decided by the period itself and by the boundary check: the
    inner block repeats at 1500Hz on a 48k stream, squarely inside the range a note can
    sound at, so neither number settles it alone.
    """
    median = float(np.median(gaps))
    if median <= 0:
        return "verdict: too few events to say"
    # Even, as a share of the period. A waveform's edges are exact; a click chasing notes
    # drifts by however much the music does.
    spread = float(np.median(np.abs(gaps - median))) / median
    if spread > 0.05:
        return ("verdict: irregular spacing -- this implicates the DSP, "
                "which is the case worth chasing")
    hz = rate / median
    if on_boundary:
        return (f"verdict: evenly spaced at {hz:.0f}Hz and on a buffer boundary -- "
                "the plumbing, not the DSP")
    if 20.0 <= hz <= 5000.0:
        return (f"verdict: evenly spaced at {hz:.0f}Hz, in the range a note sounds at -- "
                "most likely the waveform's own edges rather than clicks. A saw or a "
                "square reads this way by design; measure again with a sine source")
    return (f"verdict: evenly spaced at {hz:.1f}Hz, too {'slow' if hz < 20 else 'fast'} "
            "for a waveform edge -- something in the patch repeats at that rate")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wav")
    ap.add_argument("--sigma", type=float, default=10.0,
                    help="outlier threshold in robust deviations (default 10)")
    ap.add_argument("--png", metavar="PATH", help="plot the waveform with clicks marked")
    args = ap.parse_args()

    audio, rate = decode(args.wav)
    n, channels = audio.shape
    print(f"{args.wav}: {n / rate:.2f}s, {rate}Hz, {channels}ch, "
          f"peak {np.abs(audio).max():.4f}, rms {np.sqrt((audio ** 2).mean()):.4f}")

    total = 0
    for ch in range(channels):
        hits, threshold = find(audio[:, ch], rate, args.sigma)
        total += len(hits)
        print(f"\nchannel {ch}: {len(hits)} discontinuities "
              f"(step threshold {threshold:.4f})")
        if len(hits) == 0:
            continue

        times = hits / rate
        for t, i in list(zip(times, hits))[:20]:
            step = abs(audio[i, ch] - audio[i - 1, ch])
            print(f"    {t:8.4f}s  sample {i:>8}  step {step:.4f}")
        if len(hits) > 20:
            print(f"    ... and {len(hits) - 20} more")

        if len(hits) > 2:
            gaps = np.diff(hits)
            print(f"  gaps between events: min {gaps.min()}, median {int(np.median(gaps))}, "
                  f"max {gaps.max()} samples "
                  f"({gaps.min() / rate * 1000:.1f}–{gaps.max() / rate * 1000:.1f} ms)")
            # The diagnostic that matters: does the timing implicate the plumbing?
            on_boundary = False
            for name, period in (("inner block", 32), ("device burst", 96),
                                 ("stream buffer", 192)):
                aligned = np.count_nonzero(hits % period == 0)
                share = aligned / len(hits)
                verdict = "  <-- aligned" if share > 0.5 else ""
                if share > 0.5:
                    on_boundary = True
                print(f"    on a {name} boundary ({period}): "
                      f"{aligned}/{len(hits)} ({share:.0%}){verdict}")
            print("  " + periodicity(gaps, rate, on_boundary))

    if args.png and total:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, axes = plt.subplots(channels, 1, figsize=(14, 3 * channels), squeeze=False)
        t = np.arange(n) / rate
        for ch in range(channels):
            ax = axes[ch][0]
            ax.plot(t, audio[:, ch], linewidth=0.4)
            hits, _ = find(audio[:, ch], rate, args.sigma)
            for i in hits:
                ax.axvline(i / rate, color="red", alpha=0.5, linewidth=0.8)
            ax.set_ylabel(f"ch {ch}")
            ax.set_xlim(0, t[-1])
        axes[-1][0].set_xlabel("seconds")
        fig.tight_layout()
        fig.savefig(args.png, dpi=110)
        print(f"\nwrote {args.png}")

    return 0 if total == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
