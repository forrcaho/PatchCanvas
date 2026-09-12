package io.github.forrcaho.patchcanvas

import kotlin.math.floor

/**
 * A tuning, as a table of octave offsets.
 *
 * Pitch crosses into the engine as octaves -- `hz = root * 2^octaves` -- so a scale is
 * entirely a matter of what numbers the interface sends, and the engine never learns
 * what a semitone is. That is what makes an arbitrary tuning cost nothing: twelve-tone
 * equal temperament is one table among many rather than the assumption everything else
 * has to work around.
 *
 * [period] is how far a full turn of the scale travels, in octaves, and is 1.0 for
 * everything that repeats at the octave. Bohlen-Pierce repeats at the twelfth instead,
 * which is log2(3) -- the reason this is a field and not a constant.
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
        private fun equal(name: String, divisions: Int, period: Float = 1f) = Scale(
            name,
            (0 until divisions).map { it * period / divisions },
            period,
        )

        /** The default, and the only one that makes "semitone" mean anything. */
        val Chromatic = equal("12-TET", 12)

        /**
         * Deliberately more than a token: a scale mechanism with one scale in it is a
         * mechanism nobody can tell is working.
         */
        val all = listOf(
            Chromatic,
            Scale("Major", listOf(0f, 2f, 4f, 5f, 7f, 9f, 11f).map { it / 12f }),
            Scale("Minor pent", listOf(0f, 3f, 5f, 7f, 10f).map { it / 12f }),
            equal("19-TET", 19),
            equal("Bohlen-Pierce", 13, BOHLEN_PIERCE_PERIOD),
        )

        fun byName(name: String): Scale? = all.firstOrNull { it.name == name }
    }
}

/** log2(3): the tritave, which Bohlen-Pierce repeats at instead of the octave. */
const val BOHLEN_PIERCE_PERIOD = 1.5849625f
