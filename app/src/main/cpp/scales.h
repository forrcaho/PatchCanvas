#pragma once

#include <cstdint>

/** Mirrored by MAX_DEGREES in Scale.kt. Fixed, so a scale crosses to the audio thread without allocating. */
constexpr int32_t kMaxDegrees = 64;
/** Mirrored by MAX_SCALE_ENTRIES in Scale.kt. */
constexpr int32_t kMaxScaleEntries = 16;

/** Division rounding towards negative infinity, which is what a degree below the root needs. */
inline int64_t floorDiv(int64_t a, int64_t b) {
    const int64_t q = a / b;
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
}

/**
 * One tuning, as the table Scale.kt builds from a .scl file: offsets within one period,
 * in octaves, ascending from the root.
 *
 * octavesOf is where a degree becomes a pitch, and the only place. The engine still never
 * learns what a semitone is -- a table is whatever numbers the file held -- but it does
 * now hold the tables, because which one applies depends on the beat a note starts on,
 * and only the audio thread knows that to the sample.
 */
struct ScaleTable {
    float octaves[kMaxDegrees] = {};
    int32_t size = 0;
    float period = 1.0f;

    /** Octaves from the root. Degrees past the end run into the next period, and below zero into the one beneath. */
    float octavesOf(int32_t degree) const {
        // A table nobody has sent is twelve equal steps, so a sequencer is never silent,
        // or dividing by zero, for want of one.
        if (size <= 0) return static_cast<float>(degree) / 12.0f;
        const int64_t turn = floorDiv(degree, size);
        return static_cast<float>(turn) * period + octaves[degree - turn * size];
    }
};

/**
 * The patch's scales, in the order they loop, each held for a whole number of beats.
 *
 * Built on the UI thread and handed across as a pointer, like a node, so a change to the
 * list arrives all at once rather than a degree at a time: a half-copied table would be
 * heard, as whatever note fell in the gap.
 */
struct ScaleList {
    ScaleTable tables[kMaxScaleEntries];
    int32_t beats[kMaxScaleEntries] = {};
    int32_t count = 0;
    int64_t totalBeats = 0;

    /** Call once filled: clamps what arrived and totals the loop. */
    void finish() {
        if (count < 0) count = 0;
        if (count > kMaxScaleEntries) count = kMaxScaleEntries;
        totalBeats = 0;
        for (int32_t i = 0; i < count; ++i) {
            if (beats[i] < 1) beats[i] = 1;
            totalBeats += beats[i];
        }
    }

    /**
     * Which entry sounds on [wholeBeat].
     *
     * Whole beats only, because a switch lands on a beat and never between two. Comparing
     * integers is what makes a note on the switch beat take the new scale, and one a
     * sample earlier the old, with no rounding anywhere to decide which.
     */
    int32_t entryAt(int64_t wholeBeat) const {
        if (count <= 1 || totalBeats <= 0) return 0;
        int64_t pos = wholeBeat % totalBeats;
        if (pos < 0) pos += totalBeats;
        for (int32_t i = 0; i < count; ++i) {
            if (pos < beats[i]) return i;
            pos -= beats[i];
        }
        return count - 1;
    }

    const ScaleTable &tableAt(int64_t wholeBeat) const { return tables[entryAt(wholeBeat)]; }
};
