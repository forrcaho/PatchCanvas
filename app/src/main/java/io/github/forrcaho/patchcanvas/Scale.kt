package io.github.forrcaho.patchcanvas

import kotlin.math.floor
import kotlin.math.ln

/**
 * A tuning, as a table of offsets in octaves.
 *
 * Pitch crosses into the engine as octaves -- `hz = root * 2^octaves` -- so a scale is
 * entirely a matter of what numbers the interface sends, and the engine never learns
 * what a semitone is. That is what makes an arbitrary tuning cost nothing: twelve-tone
 * equal temperament is one table among many rather than the assumption everything else
 * has to work around.
 *
 * **The steps are not required to be equal, and that is the point.** An equal division
 * is the easy case and the least musically interesting one; what earns the mechanism is
 * the diatonic scale and its relatives, where the whole character comes from the pattern
 * of large and small steps. A scale here is therefore an arbitrary ascending list, and
 * the constructors below exist so each kind can be written the way it is actually
 * described -- as step sizes, as frequency ratios, or as a division.
 *
 * [period] is how far a full turn travels, in octaves, and is 1.0 for everything that
 * repeats at the octave. Bohlen-Pierce repeats at the twelfth instead, which is log2(3)
 * -- the reason this is a field and not a constant.
 */
data class Scale(
    val name: String,
    /** Offsets within one period, in octaves, ascending from zero. */
    val degrees: List<Float>,
    val period: Float = 1f,
) {
    val size: Int get() = degrees.size

    /**
     * Octaves from the root for a degree index, which may be negative or run past one
     * period: index [size] is the first degree of the next period up.
     */
    fun octavesOf(index: Int): Float {
        val turn = floor(index.toDouble() / size).toInt()
        val within = index - turn * size
        return turn * period + degrees[within]
    }

    companion object {
        /**
         * A scale written the way musicians write one: the sizes of its steps, in units
         * of some underlying division.
         *
         * Diatonic major is `2 2 1 2 2 2 1` out of twelve, and reading that line tells
         * you where the semitones fall -- which the equivalent list of offsets does not.
         * The steps must sum to the division, and a test asserts it, because a typo there
         * produces a scale that is subtly out of tune rather than obviously broken.
         */
        fun steps(name: String, divisions: Int, steps: List<Int>, period: Float = 1f): Scale {
            val offsets = ArrayList<Float>(steps.size)
            var at = 0
            steps.forEach { offsets += at * period / divisions; at += it }
            return Scale(name, offsets, period)
        }

        /** Every step of a division, which is [steps] with every step size one. */
        fun equal(name: String, divisions: Int, period: Float = 1f) =
            steps(name, divisions, List(divisions) { 1 }, period)

        /**
         * Just intonation, written as the frequency ratios it is defined by.
         *
         * These are the scales an equal division approximates, and their steps are
         * unequal by construction -- 9:8 and 10:9 are both "whole tones" and are not the
         * same size.
         */
        fun ratios(name: String, ratios: List<Pair<Int, Int>>, period: Float = 1f) = Scale(
            name,
            ratios.map { (num, den) -> (ln(num.toDouble() / den) / LN2).toFloat() },
            period,
        )

        /**
         * The fallback, and the only scale defined in code.
         *
         * Everything else is a `.scl` file that a user can add to or replace, but a
         * patch has to be able to name a tuning even when the storage is unreadable or
         * empty, so exactly one is guaranteed. Its name matches the shipped 12-TET.scl
         * so the two are the same scale by either route.
         */
        val Chromatic = equal("12-TET", 12)
    }
}

/** log2(3): the tritave, which Bohlen-Pierce repeats at instead of the octave. */
const val TRITAVE = 1.5849625f

private const val LN2 = 0.6931471805599453
