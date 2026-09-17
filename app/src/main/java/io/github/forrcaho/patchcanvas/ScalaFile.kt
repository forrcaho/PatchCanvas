package io.github.forrcaho.patchcanvas

import kotlin.math.abs
import kotlin.math.ln

/*
 * Scala (.scl) files, the interchange format for tunings.
 *
 * Chosen because it already exists and there are thousands of scales written in it --
 * inventing a format here would mean asking people to retype work that is already done.
 * Nothing writes it: editing a tuning on a phone is nobody's idea of a good time, so
 * files arrive from elsewhere and this only has to read them.
 *
 * The shape of the format:
 *
 *     ! meantone.scl          <- lines starting with ! are comments, anywhere
 *     !
 *     Quarter-comma meantone  <- description, and it may legitimately be blank
 *     7                       <- how many pitches follow
 *     !
 *      193.157                <- a '.' anywhere means cents
 *      5/4                    <- a '/' means a frequency ratio
 *      2                      <- a bare integer is that ratio over one
 *      ...
 *      2/1                    <- the last one is the period, not a degree
 *
 * Two things about it catch people out, and both are asserted below. The unison is
 * implicit and never listed, so a "7 note" scale lists seven lines of which the seventh
 * is the octave. And the last entry is therefore the interval of repetition rather than
 * a note you can play -- which is exactly the period this app already models, and the
 * reason a non-octave scale like Bohlen-Pierce needs no special case.
 */

/** Null for anything unreadable: a file on disk is untrusted, and a bad one is skipped. */
fun parseScala(name: String, text: String): Scale? {
    val lines = text.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("!") }
        .toList()
    if (lines.size < 2) return null

    // lines[0] is the description, which may be blank -- so it is consumed by position
    // rather than skipped as empty, or every blank-description file would read its note
    // count as its description and its first pitch as its count.
    val count = lines[1].takeWhile { !it.isWhitespace() }.toIntOrNull() ?: return null
    if (count < 1 || count > MAX_DEGREES) return null

    val values = lines.drop(2).filter { it.isNotEmpty() }.take(count)
    if (values.size < count) return null

    val octaves = values.map { parsePitch(it) ?: return null }

    // Ascending and above the unison, which the format requires and a hand-edited file
    // may not honor. Out of order, the grid's rows would not be in pitch order either.
    if (octaves.any { it <= 0f }) return null
    if (octaves.zipWithNext().any { (a, b) -> b <= a }) return null

    // The last entry is the period; everything before it, plus the implicit unison, are
    // the degrees.
    return Scale(
        name = name,
        degrees = listOf(0f) + octaves.dropLast(1),
        period = octaves.last(),
    )
}

/** One pitch line, in octaves. Cents if it has a point, otherwise a ratio. */
private fun parsePitch(line: String): Float? {
    // Anything after the value is commentary, per the format.
    val token = line.takeWhile { !it.isWhitespace() }
    if (token.isEmpty()) return null

    return if (token.contains('.')) {
        token.toDoubleOrNull()?.let { (it / 1200.0).toFloat() }
    } else {
        val parts = token.split('/')
        if (parts.size > 2) return null
        val num = parts[0].toLongOrNull() ?: return null
        val den = if (parts.size == 2) parts[1].toLongOrNull() ?: return null else 1L
        if (num <= 0L || den <= 0L) return null
        (ln(num.toDouble() / den) / LN_2).toFloat()
    }
}

/**
 * A ceiling on degrees per period, mirrored by kMaxDegrees in scales.h.
 *
 * Not a format limit -- Scala files can be far larger -- but a grid row has to be big
 * enough for a finger, and a scale with hundreds of degrees would be unusable rather
 * than merely cramped. It also stops a corrupt count line from allocating wildly.
 *
 * 64 rather than the 128 it was, because the engine now holds every scale as a table of
 * fixed size, so it can cross to the audio thread without allocating. One limit rather
 * than a parser limit and a smaller engine one: a scale the engine would cut short at the
 * top is a scale that should not load, and failing here is the one place already built to
 * refuse a file and say so.
 */
const val MAX_DEGREES = 64

private const val LN_2 = 0.6931471805599453

/** True if two scales agree to within a hundredth of a cent. */
internal fun Scale.soundsLike(other: Scale): Boolean =
    size == other.size &&
        abs(period - other.period) < 1e-5f &&
        degrees.zip(other.degrees).all { (a, b) -> abs(a - b) < 1e-5f }
