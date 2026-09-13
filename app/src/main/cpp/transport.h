#pragma once

#include <cmath>
#include <cstdint>

/**
 * A note length, as num/den quarter-note beats: 1/16 is a quarter of a beat.
 *
 * A ratio rather than a double so that divisions which ought to coincide do. A beat and
 * the triplet starting on it are the same instant; working out each one's boundary count
 * as floor(beat * den / num) keeps them on the same frame, because rounding cannot put
 * a larger product below a smaller one -- and a double could not hold a third anyway.
 */
struct Interval {
    int32_t num = 0;
    int32_t den = 1;

    bool none() const { return num <= 0 || den <= 0; }
};

/** A boundary crossed inside a block: the sample it lands on, and which one it was. */
struct Tick {
    int32_t offset = 0;
    int64_t count = 0;
};

/**
 * Where the patch is in musical time. One per graph, and every clocked node divides it.
 *
 * This is the frame counter the roadmap commits to, moved from a Clock node into the
 * graph. Position is a beat anchored at a frame count rather than a running sum, so it
 * accumulates no rounding: a tempo change re-anchors at the current beat, and everything
 * after it is one multiplication from the anchor.
 *
 * Two Clock nodes could drift apart, because each truncated its own period to whole
 * frames -- 127bpm is 22677.17 frames a beat, and a module losing 0.17 of a frame every
 * beat is a sixth of a second out after twenty minutes. Every division here is computed
 * from the same position, so there is nothing to drift.
 *
 * Audio thread only, apart from setSampleRate, which is called while no stream is running.
 */
class Transport {
public:
    static constexpr double kMinTempo = 20.0;
    static constexpr double kMaxTempo = 300.0;

    void setSampleRate(int32_t rate) {
        rebase();
        rate_ = rate > 0 ? rate : 48000;
        recompute();
    }

    /** Carries on from the current beat at the new rate, rather than jumping to where it would have got to. */
    void setTempo(double bpm) {
        rebase();
        bpm_ = bpm < kMinTempo ? kMinTempo : (bpm > kMaxTempo ? kMaxTempo : bpm);
        recompute();
    }

    /** Back to beat zero. The next block's first frame is the start of bar one. */
    void reset() {
        anchorBeat_ = 0.0;
        framesSinceAnchor_ = 0;
    }

    /** Stopped means time stops: the position holds, and nothing ticks until it runs again. */
    void setRunning(bool running) { running_ = running; }
    bool running() const { return running_; }

    double tempo() const { return bpm_; }
    double beatsPerFrame() const { return beatsPerFrame_; }

    /** The beat at [frame], counted from the start of the block about to be processed. */
    double beatAt(int64_t frame) const {
        return anchorBeat_ + static_cast<double>(framesSinceAnchor_ + frame) * beatsPerFrame_;
    }

    /**
     * The boundaries of [interval] that fall inside the next [frames], written to [out].
     *
     * A boundary ticks on the first frame at or after it. Compared against the frame just
     * before the block rather than against the block's own start, so a boundary landing
     * exactly between two blocks belongs to exactly one of them -- and after a pause the
     * frame before is the last one that played, so resuming neither repeats nor skips a
     * tick.
     */
    int32_t ticks(Interval interval, int32_t frames, Tick *out, int32_t max) const {
        if (!running_ || interval.none() || frames <= 0 || max <= 0) return 0;

        const double den = static_cast<double>(interval.den);
        const double num = static_cast<double>(interval.num);
        auto index = [&](int64_t frame) {
            return static_cast<int64_t>(std::floor(beatAt(frame) * den / num));
        };

        int64_t last = index(-1);
        // Almost every block crosses nothing, and two floors are enough to know that.
        if (index(frames - 1) == last) return 0;

        int32_t found = 0;
        for (int32_t f = 0; f < frames && found < max; ++f) {
            const int64_t k = index(f);
            if (k != last) {
                out[found].offset = f;
                out[found].count = k;
                ++found;
                last = k;
            }
        }
        return found;
    }

    /** Moves on by one block, if running. Called once per block, after every node has seen it. */
    void advance(int32_t frames) {
        if (running_) framesSinceAnchor_ += frames;
    }

private:
    void rebase() {
        anchorBeat_ = beatAt(0);
        framesSinceAnchor_ = 0;
    }

    void recompute() { beatsPerFrame_ = bpm_ / 60.0 / static_cast<double>(rate_); }

    double anchorBeat_ = 0.0;
    int64_t framesSinceAnchor_ = 0;
    double bpm_ = 120.0;
    int32_t rate_ = 48000;
    double beatsPerFrame_ = 120.0 / 60.0 / 48000.0;
    bool running_ = false;
};
