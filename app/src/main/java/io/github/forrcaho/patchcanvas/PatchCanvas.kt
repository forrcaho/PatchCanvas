package io.github.forrcaho.patchcanvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * A touch-first patch canvas.
 *
 * Three departures from the ModSynth/Bespoke drag-a-cable model:
 *
 *  1. Connections are made by TAPPING an output, then TAPPING an input. No sustained
 *     drag across the screen, no finger covering the thing you're aiming at. Tapping
 *     empty space or the same port again cancels.
 *
 *  2. Hit targets are measured in SCREEN space, not world space. ModSynth divides the
 *     touch point by its zoom factor before testing, so ports get physically smaller as
 *     you zoom out — which is why it just disables connecting below 0.75 zoom. Here the
 *     target is a fixed amount of glass, so patching works zoomed out.
 *
 *  3. One unified gesture loop rather than competing detectors. Compose's
 *     detectTransformGestures / detectDragGestures / detectTapGestures all consume
 *     events, so stacking them fights over the same pointer. awaitEachGesture lets us
 *     decide once, on the first move, what this gesture actually is.
 *
 * ---------------------------------------------------------------------------
 * UNITS. World units are DP. This is load-bearing, and it used to be wrong: module
 * geometry was raw floats handed to DrawScope, which draws in PIXELS, while the touch
 * radius went through Dp.toPx(). The two only agreed at density 1.0 — which is where
 * @Preview renders, and nowhere else. On the reference device (density 2.4375) a module
 * came out 47.6x34.5dp with its ports 17.2dp apart inside a 24dp hit radius, so which
 * port you got was close to a coin flip.
 *
 * Everything below is therefore in dp, and Camera.worldToScreen folds density into the
 * one place that converts.
 *
 * TWO SPACES. Free modules live in world space and move with the camera. The I/O rails
 * are PINNED to the viewport edges and live in screen space, so they keep a constant
 * size and stay reachable at any zoom. Cables can therefore have one endpoint in each
 * space, which is why every cable is drawn in screen space after resolving both ends —
 * see portScreen(). The screen-space hit test needed no changes to cope with this,
 * which is a point in favor of thesis #2.
 */

// ---------------------------------------------------------------- model

/**
 * Which side of a module a port is on, and so what it takes.
 *
 * [MOD] is a parameter with a jack, on the module's bottom edge. Its index is the
 * parameter's rather than a position among ports, and it exists only while that parameter
 * is exposed. [PatchModule.ports] returns nothing for it, deliberately: every loop over
 * `ports(dir)` was written for two sides and puts a port on the left or the right, so a
 * site that forgets modulation draws no port rather than one in the wrong place.
 */
enum class PortDirection { INPUT, OUTPUT, MOD }

/** Which viewport edge a pinned module is welded to. */
enum class Edge { LEFT, RIGHT }

/**
 * What a port carries. Four kinds, and typing is enforced.
 *
 * It was advisory while these were Eurorack's signals, because in hardware it is all
 * voltage and patching audio into a CV input is a technique rather than a mistake. Not one
 * of these four is a voltage: [MODULATION] drives a control between a low and a high stored
 * on that control, in that control's own units, and [PULSE] and [NOTE] are events. Nothing
 * sensible happens when one is read as another, so a mismatch is refused rather than
 * colored -- see [Patch.connect].
 *
 * Audio-rate modulation does not need the loophole enforcement would close. A module that
 * wants it declares an audio input, where the rate is the whole point and the unit is a
 * sample; [MODULATION] is applied once per block and could not carry it anyway.
 *
 * [MODULATION] and [PULSE] keep the colors of the CV and gate they replace, which is most
 * of the argument that they are the same idea said properly.
 */
enum class SignalKind(val cable: Color, val idle: Color) {
    AUDIO(Color(0xFF8A93A3), Color(0xFF6E7684)),
    MODULATION(Color(0xFFB98FE0), Color(0xFF8A6FA8)),
    PULSE(Color(0xFFE0A24B), Color(0xFFA8793A)),
    NOTE(Color(0xFF7FD18A), Color(0xFF5E9A68));

    /**
     * Whether a cable may run from a port of this kind to one of [other]. Like to like,
     * and nothing else.
     *
     * The design allows exactly one conversion, [NOTE] into a [PULSE] input, because a
     * note implies a trigger -- and refuses the reverse, because nothing about a pulse
     * says what pitch it would be. **Designed, not built.** A pulse is still the gate
     * buffer it was renamed from rather than an event, and the engine refuses note against
     * non-note outright (see the Connect case in `graph.cpp`), so allowing it here would
     * make a cable the model accepts, the engine drops, and nothing on screen explains.
     * It arrives when a pulse carries events.
     */
    fun patchesTo(other: SignalKind): Boolean = this == other
}

data class Port(val name: String, val kind: SignalKind)

/**
 * How a knob's travel maps to its value.
 *
 * Frequencies and times are exponential because hearing is: the interesting half of a
 * 20Hz-18kHz sweep is all below 2kHz, and a linear knob would spend nine tenths of its
 * travel above it. Stepped values land on a choice rather than between two.
 */
enum class ParamCurve { LINEAR, EXPONENTIAL, STEPPED }

/**
 * A knob.
 *
 * Values cross to the engine in real units -- hertz, seconds, beats per minute -- rather
 * than normalized, so a node uses what it is given and the interface can say "440 Hz"
 * instead of "0.63". The range and the curve belong here, with the thing being
 * described.
 */
/**
 * What a stepped parameter's options look like on the panel.
 *
 * NUMBER covers anything counted, like a length. WAVE draws the waveform itself, which is
 * how every hardware oscillator labels this control and why: the shape is the name, and
 * reading it needs no translation from the word "saw". DIVISION is a note length from
 * [INTERVALS], and the one choice a panel shows in its header rather than as a row.
 */
enum class Choice { NUMBER, WAVE, DIVISION, PRESET, ARP, FILTER, SLOPE }

data class Param(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val curve: ParamCurve = ParamCurve.LINEAR,
    /** Only meaningful for STEPPED; ignored otherwise. */
    val choice: Choice = Choice.NUMBER,
    /**
     * Whether the slider is ticked at the degrees of the current scale.
     *
     * For the controls measured in cents. Cents are continuous and belong to no tuning,
     * which is what makes them right -- and is also what makes an unmarked slider a poor
     * way to land on a note. The marks say where the scale is without constraining the
     * knob to it.
     */
    val marks: Boolean = false,
    /**
     * Drawn as a chip in the panel's header rather than as a row of the panel's body.
     *
     * For the interval, which a sequencer panel has no row to spare for: the grid takes
     * two thirds of the body, and a third row in what is left overlaps the other two.
     */
    val header: Boolean = false,
    /**
     * What this parameter's modulation port is labeled, on a bottom edge where three share
     * 116dp. The name itself when it is that short already, which most are; otherwise its
     * first three letters, unless those would say something else.
     */
    val short: String = if (name.length <= 4) name else name.take(3),
    /**
     * A degree of the sounding scale, read with the note it is: "-12  C3". For a knob that
     * picks a note, where a bare number said nothing about which -- asked for on the phone
     * about Euclid, 2026-09-19.
     */
    val degree: Boolean = false,
) {
    /**
     * How many options a stepped parameter offers.
     *
     * Stepped ranges are integers one apart -- 0..3 waveforms, 1..8 lengths -- so the
     * count is the span plus one. Nothing else would make sense as a row of buttons.
     */
    val steps: Int get() = (max - min).toInt() + 1

    /**
     * Whether this row is drawn as buttons, one per option. A stepped parameter with more
     * options than fit a finger each is a bar with a whole-number reading instead: DotSeq's
     * length has 32, and as buttons they ran into each other and over the row's label --
     * found on the emulator, the first time one was drawn.
     */
    val buttons: Boolean get() = curve == ParamCurve.STEPPED && steps <= MAX_BUTTONS

    /** Which option a value is, 0-based. */
    fun indexOf(value: Float): Int =
        (value - min).roundToInt().coerceIn(0, steps - 1)

    /** Knob travel, 0..1, to a value. */
    fun valueAt(position: Float): Float {
        val t = position.coerceIn(0f, 1f)
        return when (curve) {
            ParamCurve.LINEAR -> min + t * (max - min)
            // Equal-width segments, deliberately not round(): rounding makes the first
            // and last options half as wide as the rest, so on a row of buttons the two
            // ends are half as easy to hit as their neighbors.
            ParamCurve.STEPPED ->
                min + floor(t * steps).coerceAtMost(steps - 1f)
            ParamCurve.EXPONENTIAL -> min * kotlin.math.exp(t * kotlin.math.ln(max / min))
        }
    }

    /** A value back to knob travel, so a restored patch shows its knobs where they are. */
    fun positionOf(value: Float): Float {
        val v = value.coerceIn(minOf(min, max), maxOf(min, max))
        return when (curve) {
            // The middle of its own segment, so the travel that produced a value maps
            // back into the same button rather than onto its edge.
            ParamCurve.STEPPED -> (indexOf(v) + 0.5f) / steps
            ParamCurve.LINEAR ->
                if (max == min) 0f else (v - min) / (max - min)
            ParamCurve.EXPONENTIAL ->
                if (max == min) 0f else (kotlin.math.ln(v / min) / kotlin.math.ln(max / min))
        }.coerceIn(0f, 1f)
    }

    /** For display: enough precision to be useful, not so much it is noise. */
    fun format(value: Float): String {
        val text = when {
            curve == ParamCurve.STEPPED -> value.toInt().toString()
            kotlin.math.abs(value) >= 100f -> value.toInt().toString()
            kotlin.math.abs(value) >= 10f -> "%.1f".format(value)
            else -> "%.3f".format(value).trimEnd('0').trimEnd('.')
        }
        return if (unit.isEmpty()) text else "$text$unit"
    }
}

/**
 * One step of a sequence: which degree of the scale, and whether it sounds.
 *
 * A rest is a step with [on] false rather than a missing entry, because the transport still
 * advances through it and the pitch still holds -- the note is withheld, the step is not.
 */
data class Step(val degree: Int, val on: Boolean = true)

/**
 * A note on a dot sequencer's grid: which step it starts on, which degree, how many
 * quarter steps it lasts, and how hard it is struck. Bespoke's DotSequencer is the shape,
 * and the reason notes are events with a start and an end rather than a gate a sequencer
 * holds for half a step.
 *
 * [velocity] is 0 to 1 and defaults to full, which is what every note in the app sounded
 * at before a dot could say otherwise -- so a file written without it comes back as the
 * music it was. What it reaches is already there: an `Osc` takes it as amplitude, a
 * `Pluck` as how hard the string is struck, and an `FM` as both its index and its output,
 * so on an FM velocity has always meant brightness. Only the sources never chose it.
 */
data class Dot(
    val step: Int,
    val degree: Int,
    val length: Int = DOT_SUBSTEPS,
    val velocity: Float = 1f,
)

/**
 * One leg of an envelope: reach [level] over [time] seconds, bent by [curve].
 *
 * A segment says where it is *going* and never where it starts, because it starts wherever
 * the envelope already is. That is not a shortcut -- it is what lets a note released during
 * the attack fall from the level it actually reached, which is the behaviour an ADSR gets
 * for free and a naive list of points loses.
 *
 * [curve] runs -1 to 1 with 0 straight. Positive leaves fast and arrives slow, which is the
 * shape of a natural decay; negative is the other way. It belongs to the segment rather
 * than to either end, which is Surge's split and the reason this is usable on a phone:
 * Bespoke hangs time, level and curvature on one draggable node, so the control that picks
 * between them is a mode, and a mode on a fingertip-sized target is a coin toss. Here a
 * node is time and level, and curvature is a drag on the line between two nodes --
 * different targets rather than different modes.
 *
 * [sustain] parks the envelope here while a note is held. At most one segment in a list has
 * it; [PatchModule.setSustain] is what enforces that. None of them having it is a perfectly
 * good envelope -- it runs to its end and stops, which is what a percussive patch wants,
 * and costs nothing on a synth besides, since a voice is freed the moment its gate ramp
 * reaches zero and a release shapes nothing anyway.
 */
data class EnvSegment(
    val time: Float,
    val level: Float,
    val curve: Float = 0f,
    val sustain: Boolean = false,
)

/**
 * What a new envelope is: the A/D/S/R every Env has had, said as segments.
 *
 * Deliberately the familiar shape rather than something the format could not have held
 * before. The point of the redesign is that this is now the *starting* point and not the
 * only one -- a node can be added, a curve bent, the sustain moved or dropped.
 */
/**
 * How far along its travel a segment is at [t], bent by [curve]. Mirrors EnvNode's shape.
 *
 * The same expression on both sides on purpose: this is what the editor draws and what the
 * engine plays, and an envelope that sounds unlike its own picture would be worse than one
 * with no picture at all. `(1 - e^-at)/(1 - e^-a)`, which is 0 at 0, 1 at 1 for every a,
 * and approaches a straight line as a does -- so there is no seam at the middle of the
 * knob, where a piecewise pair of curves would have one.
 */
internal fun envShape(t: Float, curve: Float): Float {
    val a = 6f * curve.coerceIn(-1f, 1f)
    // Straight enough that the exponential form is 0/0, so it is taken as straight.
    if (abs(a) < 1e-3f) return t
    return ((1f - exp(-a * t)) / (1f - exp(-a)))
}

internal val DEFAULT_ENVELOPE = listOf(
    EnvSegment(0.005f, 1f, 0.6f),
    EnvSegment(0.12f, 0.6f, 0.6f, sustain = true),
    EnvSegment(0.25f, 0f, 0.6f),
)

/**
 * What an exposed parameter sweeps between when something modulates it, in its own units.
 *
 * Stored on the parameter's module rather than on the cable, which is Bespoke's shape and
 * the reason for it: "sweep the cutoff from 400Hz to 2kHz" is a fact about the cutoff, and
 * it survives swapping one LFO for another. [low] above [high] is an inverted sweep, not an
 * error.
 */
data class ModRange(val low: Float, val high: Float)

/**
 * The range a parameter gets when it is first exposed: some travel either side of where
 * its knob sits, so the first modulator patched to it is heard at once.
 *
 * Both brackets on the knob, which is the literal reading of "at the control's current
 * value", would make that first cable do nothing -- and a cable that does nothing reads as a
 * cable that failed. A stepped parameter gets all of its options.
 */
internal fun initialModRange(param: Param, value: Float): ModRange {
    if (param.curve == ParamCurve.STEPPED) return ModRange(param.min, param.max)
    val at = param.positionOf(value)
    return ModRange(
        param.valueAt((at - MOD_SPREAD).coerceIn(0f, 1f)),
        param.valueAt((at + MOD_SPREAD).coerceIn(0f, 1f)),
    )
}

/** How far either side of the knob a new range reaches, in knob travel. */
internal const val MOD_SPREAD = 0.2f

/**
 * What a module's grid means, or that it has none.
 *
 * Both grids put the scale's degrees up the rows, because pitch ascending up the screen is
 * the one thing about a piano roll nobody has to be taught. They differ in what a column
 * is and in how many cells a column may sound.
 */
enum class GridKind {
    NONE,

    /**
     * Not a grid of cells at all, but it belongs here: the open panel is the view that
     * already owns the whole screen and already has drawing and hit testing for a shape
     * you edit with a finger, which is what an envelope needs. Columns are time and the
     * vertical is level, both continuous. See [EnvSegment].
     */
    ENVELOPE,

    /**
     * Columns are steps and rows are degrees, as in a sequence, but a cell holds a dot: a
     * note with its own length, as many to a column as make a chord. See [Dot].
     */
    DOTS,

    /**
     * Nothing to edit, only to see: a Euclid's pattern, one mark per step, filled where a
     * note falls, with the playhead on it. Its knobs are what change it, and watching the
     * pulses move as they turn is most of what makes the knobs make sense.
     */
    PATTERN,

    /** Columns are steps in time and each holds one degree: a melody. */
    SEQUENCE,

    /**
     * Columns are octaves and every cell is on or off by itself: a chord that holds.
     *
     * Rows are the scale's degrees within one period and a column is the next period up,
     * so cell (row, column) is degree `column * size + row`. That works because
     * `ScaleTable::octavesOf` already treats a degree as an unbounded integer that runs
     * into the next period past the end of the table -- the grid is a two-dimensional view
     * of that one axis, and the engine never learns there were rows.
     */
    DRONE,
}

data class ModuleType(
    val name: String,
    val inputs: List<Port>,
    val outputs: List<Port>,
    /**
     * A module's color says what kind of cable it sends: note sources are green like a
     * note cable, sound sources and processors are in audio's steel blues and grays, and
     * modulators are purple like a modulation cable. So a green cable leaves a green module.
     *
     * Each is a distinct shade of its family rather than the cable color itself, so a
     * module still reads as a module and neighbors in a family can be told apart.
     * `ModuleColorTest` pins the rule: the cable color nearest each accent is the kind
     * that module sends, no accent is a cable color, and any two modules' borders differ
     * visibly *as drawn* -- a first set of shades that measured apart in plain RGB looked
     * identical on the phone once the border's opacity had halved the difference. The
     * shades were then chosen by search, for the largest perceptual gap between any two
     * borders on screen with each accent still clearly nearest its own cable.
     * Widened 2026-09-16.
     *
     * There was no scheme before -- the accents were accidents of when each module was
     * added. Env was exactly the modulation cable's purple, Filter exactly the old gate's
     * orange, and Osc one shade off the note cable's green, so some colors meant "sends
     * this" and some "takes this". Chosen 2026-09-16. Out sends nothing into the patch and
     * stays a neutral near-white.
     */
    val accent: Color,
    val params: List<Param> = emptyList(),
    /**
     * Non-null for the I/O rails. A pinned type is unique, cannot be added or deleted,
     * has no stored position, and draws at constant size on a viewport edge.
     */
    val pinned: Edge? = null,
    /** How many cells the grid holds; mirrors StepsNode::kSteps or DroneNode::kCells. */
    val stepCount: Int = 0,
    /** What those cells mean, and so which grid the panel draws. */
    val grid: GridKind = GridKind.NONE,
    /**
     * Part of how a patch is organized rather than something that sounds: a subpatch and the
     * two rails inside it. What the engine gets is the patch flattened -- see
     * [Patch.engineGraph] -- so none of these is ever a node under its own name.
     */
    val structural: Boolean = false,
    /**
     * A subpatch's box, of either kind. Structural, but unlike the rails it is a box on the
     * canvas with ports of its own and something inside it, which is what nearly every
     * "is this a subpatch?" in the model actually means.
     */
    val box: Boolean = false,
    /**
     * Drawn as a pile of boxes: **several of this sound at once**.
     *
     * Exactly two types, and the rule is what keeps it to two. Every synth is monophonic --
     * a note at a time, and polyphony is a [Poly] around it -- so a stack tells you the two
     * places where that is not so and you do not need to wrap anything: a poly subpatch,
     * which is a voice the engine stamps out per note, and [Sf], whose voices are
     * TinySoundFont's and cannot be reduced to one because a single note can layer several
     * of them.
     *
     * It promises multiplicity, not a canvas behind it. Only a subpatch can be opened; a
     * stacked `SF` opens its panel like any other module, which is the one thing about this
     * that reads oddly and is worth less than the marker.
     */
    val stacked: Boolean = false,
) {
    /** Indices of the parameters drawn as rows of the panel; the rest live in its header. */
    val rowParams: List<Int> get() = params.indices.filter { !params[it].header }

    /**
     * Whether opening this module shows anything at all.
     *
     * Knobs were once the only thing a panel held, so "has parameters" stood in for this.
     * A drone had a grid and no parameters, and the old test made its panel unopenable --
     * the module took the tap and did nothing, with the grid it exists for unreachable.
     * Found by tapping it on a screen; nothing else would have.
     */
    val hasPanel: Boolean get() = params.isNotEmpty() || grid != GridKind.NONE

    /** The parameter choosing a clocked module's interval, or -1 for one the transport does not drive. */
    val intervalParam: Int get() = params.indexOfFirst { it.choice == Choice.DIVISION }
}

object Types {
    private val A = SignalKind.AUDIO
    private val M = SignalKind.MODULATION
    private val P = SignalKind.PULSE
    private val N = SignalKind.NOTE

    private val LIN = ParamCurve.LINEAR
    private val EXP = ParamCurve.EXPONENTIAL
    private val STEP = ParamCurve.STEPPED

    /**
     * One filter with four kinds and two slopes, rather than four modules.
     *
     * The knobs are the reason: `type` and `slope` are stepped, and a stepped row of
     * sixteen options or fewer draws as buttons, so all of it is four rows -- one short of
     * where a panel goes to two columns. The crowding that splitting would have avoided
     * does not happen, and splitting would have made changing a filter's character a
     * repatch rather than a tap, which is the wrong trade for the decision people change
     * most. Order mirrors FilterNode::setParam.
     */
    val Filter = ModuleType(
        // The notes port is second, so the audio jack every saved patch already names keeps
        // its place. It is also the first input of its kind on a module that takes audio:
        // a module that wants to follow the note declares a note input, beside the older
        // rule that one wanting audio-rate modulation declares an audio one.
        "Filter", listOf(Port("in", A), Port("notes", N)), listOf(Port("out", A)),
        Color(0xFF6D908B),
        params = listOf(
            // Hertz outright. This was once where a cable's zero sat, with a cutoff jack
            // moving it in octaves from there; with the jack gone it is an ordinary knob,
            // and a modulator sweeps it through the range exposed on the knob itself.
            Param("cutoff", 20f, 18000f, 1000f, "Hz", EXP),
            // To 1.0, where it stopped at 0.95: the SVF's damping reaches zero there and
            // the filter rings until something stops it. Not self-oscillation -- with no
            // input it stays silent -- and safe to reach only because FilterNode now gives
            // the filter a drive, without which the old maximum already had 39x of gain
            // sitting on the cutoff. See FilterNode::prepare.
            Param("res", 0f, 1f, 0.3f, "", LIN),
            Param("type", 0f, (FILTER_TYPES.size - 1).toFloat(), 0f, "", STEP, Choice.FILTER),
            Param("slope", 0f, (SLOPES.size - 1).toFloat(), 0f, "", STEP, Choice.SLOPE, short = "slp"),
            // How much of the note's pitch the cutoff follows. At 100 it keeps a fixed
            // ratio to the fundamental, and that ratio is the knob's own hertz against
            // middle C -- 785Hz is the third harmonic -- so "a saw filtered three above
            // itself" is two settings rather than a cable per note. Default 0, so every
            // filter that already exists is the filter it was. Last, because appending
            // leaves the other four at the indices the engine and the promoted knobs
            // already name.
            Param("track", 0f, 100f, 0f, "%", LIN, short = "trk"),
        ),
    )
    /**
     * Opened by notes rather than by a gate. A pulse is an event and has no duration, so
     * it could never say when to release; a note carries an on, an off and an id to match
     * them by, which is exactly what an envelope wants.
     */
    val Env = ModuleType(
        "Env", listOf(Port("notes", N)), listOf(Port("out", M)),
        Color(0xFF9A85D7),
        // No knobs: A, D, S and R were four fixed stages and the shape is segments now.
        // What replaced them is a grid, because an envelope is a shape and the one thing
        // four numbers cannot show you is what they add up to.
        grid = GridKind.ENVELOPE,
    )
    /**
     * A slow wave, for turning knobs. Patched to a parameter's modulation port it sweeps
     * that parameter across the range stored there, which is why it is unipolar -- see
     * LfoNode. Its output is modulation, which is now a kind in its own right.
     */
    val Lfo = ModuleType(
        "LFO", emptyList(), listOf(Port("out", M)),
        Color(0xFFEC7AEF),
        params = listOf(
            // Order mirrors LfoNode::setParam.
            Param("rate", 0.02f, 20f, 1f, "Hz", EXP),
            Param("wave", 0f, 3f, 3f, "", STEP, Choice.WAVE),
        ),
    )
    /**
     * No clock input: the transport steps it, at the interval chosen in its header. Order
     * mirrors StepsNode::setParam -- length, transpose, interval.
     */
    val Steps = ModuleType(
        "Steps", emptyList(), listOf(Port("notes", N)),
        Color(0xFF40CEA1),
        params = listOf(
            Param("len", 1f, STEP_COUNT.toFloat(), 8f, "", STEP),
            Param("transp", -TUNE_RANGE, TUNE_RANGE, 0f, "\u00A2", LIN, marks = true, short = "trn"),
            Param(
                "interval", 0f, (INTERVALS.size - 1).toFloat(), DEFAULT_INTERVAL.toFloat(),
                curve = STEP, choice = Choice.DIVISION, header = true,
            ),
        ),
        stepCount = STEP_COUNT,
        grid = GridKind.SEQUENCE,
    )
    /**
     * The sequencer: notes on a grid of steps by degrees, each its own length and a column
     * as many as a chord. Tap an empty cell for a one-step note, drag a note sideways to
     * lengthen or shorten it, tap one to remove it. The grid shows as many steps as the
     * sequence is long. Called DotSeq for a night, after Bespoke's DotSequencer.
     *
     * A dot's length is its duration, in quarter steps, so a gap between two notes is made
     * by shortening the first -- which is Bespoke's model and was this module's own design.
     * It had a `gate` knob for a day: taking Steps' place in the menu, it could not express
     * Steps' half step, because a length was whole steps and a dot could not be shorter than
     * one. A length in quarter steps says that per note instead. Order mirrors
     * SeqNode::setParam -- length, transpose, interval.
     */
    val Seq = ModuleType(
        "Seq", emptyList(), listOf(Port("notes", N)),
        Color(SEQ_ACCENT),
        params = listOf(
            Param("len", 1f, DOT_STEPS.toFloat(), 16f, "", STEP),
            Param("transp", -TUNE_RANGE, TUNE_RANGE, 0f, "\u00A2", LIN, marks = true, short = "trn"),
            Param(
                "interval", 0f, (INTERVALS.size - 1).toFloat(), DEFAULT_INTERVAL.toFloat(),
                curve = STEP, choice = Choice.DIVISION, header = true,
            ),
        ),
        grid = GridKind.DOTS,
    )
    /**
     * Passes each note with a probability and drops the rest: the same line with different
     * gaps each time round. Notes in, notes out.
     */
    val Chance = ModuleType(
        "Chance", listOf(Port("notes", N)), listOf(Port("notes", N)),
        Color(0xFF2C8864),
        params = listOf(Param("chance", 0f, 1f, 0.5f, "", LIN)),
    )
    /**
     * Each note becomes a chord: the note and up to three more, counted in degrees of the
     * scale sounding, so the same knobs are a triad in any scale that has one. 0 adds
     * nothing. The defaults are a major triad in twelve equal steps, the tuning a patch
     * starts in. Order mirrors ChordNode::setParam.
     */
    val Chord = ModuleType(
        "Chord", listOf(Port("notes", N)), listOf(Port("notes", N)),
        Color(0xFFA8F0D8),
        params = listOf(
            Param("note 2", -24f, 24f, 4f, "", STEP, short = "n2"),
            Param("note 3", -24f, 24f, 7f, "", STEP, short = "n3"),
            Param("note 4", -24f, 24f, 0f, "", STEP, short = "n4"),
        ),
    )
    /**
     * The notes held at its input, one at a time on the transport's ticks. Hold a chord on
     * a Drone and it plays it. Order mirrors ArpNode::setParam -- mode, octaves, interval.
     */
    val Arp = ModuleType(
        "Arp", listOf(Port("notes", N)), listOf(Port("notes", N)),
        Color(0xFF648438),
        params = listOf(
            Param("mode", 0f, (ARP_MODES.size - 1).toFloat(), 0f, "", STEP, Choice.ARP),
            Param("octaves", 1f, 4f, 1f, "", STEP, short = "oct"),
            Param(
                "interval", 0f, (INTERVALS.size - 1).toFloat(), DEFAULT_INTERVAL.toFloat(),
                curve = STEP, choice = Choice.DIVISION, header = true,
            ),
        ),
    )
    /**
     * A Euclidean rhythm: pulses spread as evenly as they go over steps, turned by rotate,
     * each note at one degree. 3 over 8 is the tresillo. Order mirrors EuclidNode::setParam.
     */
    val Euclid = ModuleType(
        "Euclid", emptyList(), listOf(Port("notes", N)),
        Color(0xFF009040),
        grid = GridKind.PATTERN,
        params = listOf(
            Param("steps", 1f, EUCLID_STEPS.toFloat(), 8f, "", STEP),
            Param("pulses", 0f, EUCLID_STEPS.toFloat(), 3f, "", STEP, short = "pul"),
            Param("rotate", 0f, (EUCLID_STEPS - 1).toFloat(), 0f, "", STEP, short = "rot"),
            Param("degree", -24f, 24f, 0f, "", STEP, short = "deg", degree = true),
            Param(
                "interval", 0f, (INTERVALS.size - 1).toFloat(), DEFAULT_INTERVAL.toFloat(),
                curve = STEP, choice = Choice.DIVISION, header = true,
            ),
        ),
    )
    /**
     * Notes that stay on until they are turned off, laid out as degrees by octaves.
     *
     * No transport, and one knob, a transpose: it is the plainest thing a note cable can carry, and
     * the only note source here that sounds with the transport stopped. Order mirrors
     * DroneNode, which knows only degrees.
     */
    val Drone = ModuleType(
        "Drone", emptyList(), listOf(Port("notes", N)),
        Color(0xFF91DA58),
        // Its one knob: the whole grid up or down, as Steps' and Seq's transpose -- asked for
        // on the phone, to move a drone's notes down an octave without redoing them.
        params = listOf(Param("transp", -TUNE_RANGE, TUNE_RANGE, 0f, "\u00A2", LIN, marks = true, short = "trn")),
        stepCount = DRONE_CELLS,
        grid = GridKind.DRONE,
    )
    /**
     * Notes in, sound out: one note at a time.
     *
     * Monophonic, which is the point of the poly subpatch rather than a limitation beside
     * it. It held eight voices and its own envelope, and that is exactly the design this
     * redesign replaced: nothing inside it could be reached per note. A voice is a patch
     * now -- this, an Env, an Amp -- inside a Poly the engine stamps out per note. Leaving
     * eight voices in here as well would be two allocators with the inner one never
     * choosing anything.
     *
     * It was called Voice until the monophonic oscillator was retired, and "voice" is free
     * again now that there are no slots inside this for the word to also mean.
     */
    val Osc = ModuleType(
        "Osc", listOf(Port("notes", N)), listOf(Port("out", A)),
        Color(0xFF6090C3),
        // One knob, where there were five. The envelope went to Env, which inside a poly
        // subpatch is one per note and can be patched anywhere -- an envelope built into a
        // synth was one envelope for every voice it had and reached nothing else, which is
        // the whole thing this redesign is about. What is left in OscNode is a 5ms gate
        // ramp, enough that a note does not click on and off and nothing more.
        params = listOf(Param("wave", 0f, 3f, 0f, "", STEP, Choice.WAVE)),
    )
    /**
     * A plucked string: Karplus-Strong, one delay line, one note at a time.
     *
     * A module rather than something patched from parts, because a string's pitch *is* the
     * length of its delay line -- so the line has to be retuned by the note, and nothing
     * patchable sets a delay's length from a note. Order mirrors PluckNode::setParam.
     */
    val Pluck = ModuleType(
        "Pluck", listOf(Port("notes", N)), listOf(Port("out", A)),
        Color(0xFF5CCCE0),
        params = listOf(
            // How long it rings: from a thud to, past 0.95, a string that never stops.
            Param("decay", 0f, 1f, 0.8f, "", LIN),
            // The burst that strikes it and the damping as it rings, together.
            Param("bright", 0f, 1f, 0.5f, "", LIN),
            // Below a quarter the bridge buzzes like a sitar's, above it the string
            // stiffens towards a bell, and in between it is a plain string.
            Param("stiff", 0f, 1f, 0.3f, "", LIN),
            // After the note ends: short is a finger muting it, long lets it ring on.
            Param("R", 0.01f, 10f, 1f, "s", EXP),
        ),
    )
    /**
     * Two-operator FM for every note: a sine whose phase another sine pushes around.
     *
     * The modulator runs at [ratio] times the note and pushes by [index] radians, and the
     * index falls on its own over [fall] -- brightness dying before loudness, which is the
     * bell and the electric piano.
     *
     * It had seven knobs, four of them an envelope, and those are gone with Osc's. What
     * started this redesign was wanting an Env on the index: FM's brightness is the thing
     * most worth shaping and the built-in envelope shaped only the loudness. Expose index,
     * patch an Env to it, and the two envelopes no longer have to be the same one. Order
     * mirrors FmNode::setParam.
     */
    val Fm = ModuleType(
        "FM", listOf(Port("notes", N)), listOf(Port("out", A)),
        Color(0xFF986C5C),
        params = listOf(
            // Whole numbers are harmonic and the rest clang; the keypad types them exactly.
            Param("ratio", 0.25f, 16f, 1f, "x", EXP, short = "rto"),
            Param("index", 0f, 10f, 2f, "", LIN, short = "idx"),
            // How fast the brightness dies away on its own, under the envelope's loudness.
            Param("fall", 0.01f, 20f, 1f, "s", EXP),
        ),
    )
    /**
     * A SoundFont player: notes in, one instrument of a bank out.
     *
     * The instrument is chosen from a page opened by the chip in the panel's header, as a
     * sequencer's interval is, and stored as bank * 128 + program -- a GM number, so a
     * patch moved to another bank asks it for the same instrument. Which bank is the
     * module's [PatchModule.font]. Order mirrors SfNode::setParam.
     */
    val Sf = ModuleType(
        "SF", listOf(Port("notes", N)), listOf(Port("out", A)),
        Color(0xFF00849C),
        // Drawn as a stack: the only module that sounds several notes by itself. See
        // ModuleType.stacked.
        stacked = true,
        params = listOf(
            Param(
                "preset", 0f, (129 * 128 - 1).toFloat(), 0f,
                curve = STEP, choice = Choice.PRESET, header = true,
            ),
            Param("level", 0f, 2f, 1f, "", LIN, short = "lvl"),
        ),
    )
    /**
     * A gain something else turns: audio in, modulation in, audio out.
     *
     * The VCA, back. It retired with CV on the argument that a Mix channel is `in * level`
     * and a level with a modulation jack is the same module -- true, and no longer the
     * point. With the envelopes out of the synths, the pair you reach for inside a poly
     * subpatch is Env and the thing Env opens, and that should be one cable rather than
     * opening a Mix, exposing a knob, setting its brackets and then patching.
     *
     * Its modulation input is a *port*, not a jack on a knob, which is the other half of
     * why it exists: a port is read per sample where a parameter is applied once a block,
     * and a 5ms attack through a 1500Hz control rate is a staircase. With nothing patched
     * there the port reads as fully open and this is a gain knob -- see Node::unityInputs
     * in node.h, which exists for exactly this port.
     */
    val Amp = ModuleType(
        "Amp", listOf(Port("in", A), Port("mod", M)), listOf(Port("out", A)),
        Color(0xFFA890A8),
        params = listOf(Param("gain", 0f, 2f, 1f, "", LIN)),
    )
    val Mix = ModuleType(
        "Mix",
        listOf(Port("a", A), Port("b", A), Port("c", A), Port("d", A)),
        listOf(Port("out", A)),
        Color(0xFFD2D4D8),
        params = listOf(
            Param("a", 0f, 2f, 1f, "", LIN),
            Param("b", 0f, 2f, 1f, "", LIN),
            Param("c", 0f, 2f, 1f, "", LIN),
            Param("d", 0f, 2f, 1f, "", LIN),
        ),
    )

    /** Signal flows left to right, so the sink is welded right and the source left. */
    val Out = ModuleType(
        "Out", listOf(Port("L", A), Port("R", A)), emptyList(),
        Color(0xFFE0E0E0),
        params = listOf(Param("level", 0f, 2f, 1f, "", LIN, short = "lvl")),
        pinned = Edge.RIGHT,
    )
    val In = ModuleType(
        "In", emptyList(), listOf(Port("L", A), Port("R", A)),
        Color(0xFF86A9CC),
        // Was a constant, and the right amount depends on the room.
        params = listOf(Param("gain", 0.25f, 64f, 8f, "x", EXP)),
        pinned = Edge.LEFT,
    )

    /**
     * Offered by the add menu. Pinned types are deliberately absent.
     *
     * There is no Mult, despite the roadmap listing one. A mult exists in hardware
     * because a physical jack takes one plug; here an output already fans out to as many
     * inputs as you like, since each input stores its own source. Only summing ever
     * needed a module, and that is Mix.
     */
    val palette =
        listOf(Osc, Pluck, Fm, Sf, Drone, Seq, Euclid, Arp, Chord, Chance, Filter, Env, Lfo, Amp, Mix)

    /**
     * Modules collapsed into one box. Its ports are its own rather than its type's -- they
     * come from the cables that crossed the selection's edge when it was made -- so the type
     * declares none. Opening one shows what is inside, with [SubpatchIn] and [SubpatchOut] as its
     * rails.
     */
    val Subpatch = ModuleType(
        "Subpatch", emptyList(), emptyList(), Color(0xFFB9C2CE), structural = true, box = true,
    )

    /**
     * A subpatch that is monophonic inside and polyphonic from outside.
     *
     * One of everything in there, and the engine is given [voices] copies of the lot. Notes
     * arriving at its note input are shared out one per instance -- idle first, then the
     * oldest released, then stealing; every other input is broadcast to all of them, and
     * their outputs are summed back into one.
     *
     * This is the answer to the thing that started the redesign, and the only place in the
     * app that chooses between voices. Polyphony used to live inside each synth, so an Osc's
     * eight voices shared one envelope and nothing could be patched per note -- an Env on
     * FM's modulation index was not expressible. Now a voice *is* a patch: an Osc, an Env,
     * an Amp and whatever else, one of each, stamped out per note. The synths are
     * monophonic to match, or there would be two allocators with the inner one never
     * choosing anything.
     *
     * A poly subpatch may not contain another. Instances would multiply, the id space that
     * stamps out the copies is one level deep on purpose, and nothing yet says what an
     * inner poly's stealing would mean against an outer one's.
     */
    val Poly = ModuleType(
        "Poly", emptyList(), emptyList(), Color(0xFF9FB4A8),
        structural = true, box = true, stacked = true,
        // Its one knob, and the only knob any subpatch has of its own. Not exposable: see
        // PatchModule.canExpose -- a modulator that adds and removes nodes is not a knob.
        params = listOf(Param("voices", 1f, MAX_PORTS.toFloat(), 4f, "", STEP, short = "vce")),
    )

    /**
     * Inside a subpatch, the left rail: each of the subpatch's inputs, as a source for what is
     * inside. Pinned like In, so the rails' drawing, hit testing and cables all apply.
     */
    val SubpatchIn = ModuleType(
        "Subpatch in", emptyList(), emptyList(), Color(0xFFB9C2CE), pinned = Edge.LEFT, structural = true,
    )

    /**
     * What the rails are called inside a poly subpatch, and drawn as a stack.
     *
     * The same two types either way -- a rail's type is in the file, and its ports, drawing
     * and hit testing are the boundary's whatever kind of box it belongs to. Only the name
     * changes, and it changes because what you are looking at inside a poly subpatch is one
     * instance of several: "Instance in" says the notes arriving here are this copy's share
     * rather than everything the box was sent.
     */
    fun railName(box: ModuleType, rail: ModuleType): String = when {
        box != Poly -> rail.name
        rail == SubpatchIn -> "Instance in"
        else -> "Instance out"
    }

    /** Inside a subpatch, the right rail: each of the subpatch's outputs, as a sink for what is inside. */
    val SubpatchOut = ModuleType(
        "Subpatch out", emptyList(), emptyList(), Color(0xFFB9C2CE), pinned = Edge.RIGHT, structural = true,
    )

    /**
     * Every type a file can name. Steps is here and not in the palette: Seq took its place in
     * the Add menu on 2026-09-19, and a patch that has one still loads and plays it -- retiring
     * it outright would have meant refusing every patch and saved subpatch made with it.
     */
    /** The two box types, by the name a file calls them. Not in [byName]: neither sounds. */
    val boxes: Map<String, ModuleType> = listOf(Subpatch, Poly).associateBy { it.name }

    val byName: Map<String, ModuleType> =
        (palette + listOf(Steps, Out, In)).associateBy { it.name } +
            // The name Seq had for its first night. Not a conversion: the same module, renamed.
            mapOf("DotSeq" to Seq)
}

/**
 * Pinned modules take fixed ids so a saved patch's connections still resolve against
 * the rails after a reload, rather than depending on allocation order.
 */
const val OUT_ID = 1L
const val IN_ID = 2L

/** The patch itself, as a module's parent: not inside any subpatch. */
const val TOP = 0L

/** What a patch is called until it is named. */
const val DEFAULT_PATCH_NAME = "Patch"
private const val FIRST_FREE_ID = 100L

/** Free modules live in world units, and one world unit is one dp. */
/**
 * A subpatch's ports, shared by the subpatch's box and the two rails inside it, so the box's
 * inputs are the left rail's outputs and the box's outputs the right rail's inputs by
 * construction rather than by keeping two lists in step.
 *
 * Stored, never recomputed from the cables. A port that only existed while a cable used it
 * would vanish the moment that cable was unplugged, leaving nothing to plug back into --
 * and ports must never move, which a derived list re-sorting itself would break.
 */
class SubpatchPorts(
    val inputs: SnapshotStateList<Port> = mutableStateListOf(),
    val outputs: SnapshotStateList<Port> = mutableStateListOf(),
    /**
     * The knobs sent out to this subpatch's edge, in the order they were promoted.
     *
     * References, not copies: the value stays on the module inside, and the subpatch's panel
     * turns that one. A copy would be a second place for the cutoff to live, which is two
     * numbers to keep in step and one of them wrong whenever they are not -- and the
     * engine already reads the module inside.
     */
    val promoted: SnapshotStateList<ParamRef> = mutableStateListOf(),
) {
    fun copy(): SubpatchPorts = SubpatchPorts(
        mutableStateListOf<Port>().apply { addAll(inputs) },
        mutableStateListOf<Port>().apply { addAll(outputs) },
        mutableStateListOf<ParamRef>().apply { addAll(promoted) },
    )
}

/** One module's parameter, named from outside it. */
data class ParamRef(val moduleId: Long, val index: Int)

/**
 * One row of an open panel: whose parameter it is, and which.
 *
 * A module's own rows name the module itself. A subpatch's rows name the modules inside it,
 * which is what lets one panel drawing serve both.
 */
data class ParamRow(val owner: PatchModule, val index: Int) {
    val param: Param get() = owner.type.params[index]
}


class PatchModule(
    val id: Long,
    val type: ModuleType,
    position: Offset,
    /** Non-null for a subpatch and its two rails, which share one set of ports. */
    val subpatchPorts: SubpatchPorts? = null,
) {
    var position by mutableStateOf(position)

    /**
     * The subpatch this module sits inside, or [TOP] for the patch itself.
     *
     * Every module lives in one flat list and says where it belongs, rather than subpatches
     * owning lists of their own. The engine's view, undo, saving and every loop over the
     * patch stay one level deep, and entering a subpatch is a filter on this.
     */
    var parent by mutableLongStateOf(TOP)

    /**
     * What this module is called, or null to go by its type's name.
     *
     * Subpatches need one most: every subpatch is the same type, so without a name the boxes,
     * the panel and the breadcrumb all say "Subpatch" and nothing tells two of them apart.
     * Any module can take one for the same reason -- a type says what a module is, a
     * name says what it is doing in this patch.
     */
    var name by mutableStateOf<String?>(null)

    /** The name to draw: the given one, or the type's. */
    val title: String get() = name ?: type.name

    /**
     * Which SoundFont an SF module plays, by the name the library lists it under; null until
     * one is chosen, and on everything else. A name rather than a handle, so the file says
     * what it was saved with and a patch opened where that font is missing says so rather
     * than guessing.
     */
    var font by mutableStateOf<String?>(null)

    /** Knob values in real units, one per declared parameter, starting at their defaults. */
    val params: SnapshotStateList<Float> =
        mutableStateListOf<Float>().apply { addAll(type.params.map { it.default }) }

    fun setParam(index: Int, value: Float) {
        if (index in params.indices) params[index] = value
    }

    /**
     * The sequence, empty for everything that is not a sequencer.
     *
     * Owned here rather than in C++ so the pattern is part of the patch: it saves, it
     * restores, and it undoes, all through machinery that already exists. The engine's
     * own default only matters for a node nothing has written to yet.
     */
    val steps: SnapshotStateList<Step> =
        mutableStateListOf<Step>().apply { addAll(defaultSteps(type)) }

    fun setStep(index: Int, step: Step) {
        if (index in steps.indices) steps[index] = step
    }

    /**
     * A dot sequencer's notes, in the order they were placed; empty on everything else.
     * Positional like steps as far as the engine is concerned -- it keeps a slot per index --
     * so removing one resends those after it, which for a grid's worth of dots is nothing.
     */
    val dots: SnapshotStateList<Dot> = mutableStateListOf()

    /**
     * An envelope's segments, in order; empty on everything else. Positional like dots, so
     * the engine keeps a slot per index and one edit is one command.
     */
    val segments: SnapshotStateList<EnvSegment> = mutableStateListOf<EnvSegment>().apply {
        if (type.grid == GridKind.ENVELOPE) addAll(DEFAULT_ENVELOPE)
    }

    /** Where each node sits in time: the running sum of the segments before it. */
    val segmentTimes: List<Float>
        get() {
            var at = 0f
            return segments.map { at += it.time; at }
        }

    /** The whole envelope's duration, which is what the editor's width stands for. */
    val envelopeSpan: Float get() = segments.sumOf { it.time.toDouble() }.toFloat()

    fun setSegment(index: Int, segment: EnvSegment) {
        if (index !in segments.indices) return
        segments[index] = segment.copy(
            time = segment.time.coerceIn(SEGMENT_MIN_TIME, SEGMENT_MAX_TIME),
            level = segment.level.coerceIn(0f, 1f),
            curve = segment.curve.coerceIn(-1f, 1f),
        )
    }

    /**
     * Splits segment [index] in two at [at] (0 to 1 along it), adding a node there.
     *
     * The new node lands on the line where it was tapped, so the envelope's shape does not
     * change the moment you add somewhere to bend it -- which it would if the new node took
     * a level of its own. Its two halves keep the curve they came from.
     */
    fun splitSegment(index: Int, at: Float): Boolean {
        if (segments.size >= MAX_SEGMENTS) return false
        val seg = segments.getOrNull(index) ?: return false
        val cut = at.coerceIn(0.05f, 0.95f)
        val from = if (index == 0) 0f else segments[index - 1].level
        val first = EnvSegment(
            time = (seg.time * cut).coerceAtLeast(SEGMENT_MIN_TIME),
            level = from + (seg.level - from) * envShape(cut, seg.curve),
            curve = seg.curve,
        )
        // The sustain stays on the second half: it marks where the envelope waits, and
        // that is the end of the leg that was split, not a new point in the middle of it.
        val second = seg.copy(time = (seg.time * (1f - cut)).coerceAtLeast(SEGMENT_MIN_TIME))
        segments[index] = first
        segments.add(index + 1, second)
        return true
    }

    /** Removes segment [index], unless it is the only one left. */
    fun removeSegment(index: Int): Boolean {
        if (segments.size <= 1 || index !in segments.indices) return false
        segments.removeAt(index)
        return true
    }

    /**
     * Puts the sustain on [index], or clears it when it is already there.
     *
     * Exactly one segment may hold it, which is enforced here rather than trusted: two
     * sustains would give the engine a second place to park that it can never reach, and
     * the drawing would show a wait that never happens.
     */
    fun setSustain(index: Int) {
        val already = segments.getOrNull(index)?.sustain ?: return
        for (i in segments.indices) {
            val want = i == index && !already
            if (segments[i].sustain != want) segments[i] = segments[i].copy(sustain = want)
        }
    }

    /**
     * Takes [from]'s grid state: its dots, or its envelope's segments.
     *
     * One function rather than the same two lines in three places. A module is copied by
     * undo, by duplicate and by adopting a saved subpatch, and when segments arrived all
     * three were copying dots and none of them knew about a second kind of grid -- so an
     * undone envelope, a duplicated one and one loaded from the library all came back as
     * the A/D/S/R default, silently and only for the module you had just been editing.
     * Cleared before it copies because an envelope is born with that default in it.
     */
    /**
     * Every slot-indexed list the engine is told about, for comparing a module against
     * itself a frame ago.
     *
     * One property so `MainActivity`'s sync flow has a single entry for all of them. That
     * flow is the seam CLAUDE.md records as having silently killed two whole features --
     * knobs and the step grid both updated the model, saved to disk and never reached the
     * engine -- and it fails by *omission*, which is the failure a list cannot have.
     */
    val slotLists: List<List<Any>>
        get() = listOf(steps.toList(), dots.toList(), segments.toList())

    fun copyGridFrom(from: PatchModule) {
        dots.clear()
        dots.addAll(from.dots)
        if (type.grid == GridKind.ENVELOPE) {
            segments.clear()
            segments.addAll(from.segments)
        }
    }

    /** Adds [dot] unless the sequencer is full. */
    fun addDot(dot: Dot): Boolean {
        if (dots.size >= MAX_DOTS) return false
        dots.add(dot)
        return true
    }

    fun removeDot(index: Int) {
        if (index in dots.indices) dots.removeAt(index)
    }

    fun setDotLength(index: Int, length: Int) {
        val dot = dots.getOrNull(index) ?: return
        if (dot.length != length) dots[index] = dot.copy(length = length.coerceIn(1, DOT_STEPS * DOT_SUBSTEPS))
    }

    fun setDotVelocity(index: Int, velocity: Float) {
        val dot = dots.getOrNull(index) ?: return
        val held = velocity.coerceIn(MIN_VELOCITY, 1f)
        // Never to silence: a dot dragged to nothing would still draw and still take its
        // step, and the only way to find out it was there would be to drag it back up.
        if (dot.velocity != held) dots[index] = dot.copy(velocity = held)
    }

    /**
     * Moves dot [index] to [step] and [degree], unless another dot is in the way.
     *
     * Refused rather than clamped when the target overlaps: the finger goes on moving and
     * the dot stays where it was until the way is clear, which reads as the dot declining
     * to pass rather than as a jump to somewhere nobody aimed at. Two dots at one degree
     * cannot overlap for the same reason [dotRoom] exists -- the second's start would be
     * heard as nothing.
     */
    fun moveDot(index: Int, step: Int, degree: Int): Boolean {
        val dot = dots.getOrNull(index) ?: return false
        val column = step.coerceIn(0, dotColumns(this) - 1)
        if (dot.step == column && dot.degree == degree) return true
        val blocked = dots.withIndex().any { (other, it) ->
            other != index && it.degree == degree &&
                column < it.step + it.stepsSpanned && it.step < column + dot.stepsSpanned
        }
        if (blocked) return false
        dots[index] = dot.copy(step = column, degree = degree)
        return true
    }

    /**
     * Whether this sequencer's dots are pinned where they are.
     *
     * The lock on the panel, and the whole of what it does: with it on, a vertical drag on a
     * dot has no position to change and sets the dot's velocity instead. It is stated as a
     * lock rather than as a velocity mode because that is the honest description of it --
     * "what a vertical drag means" is a fact about the tool, "whether a dot can move" is a
     * fact about the dots, and the second one is what a finger is asking about.
     *
     * View state, like [gridBottom] and which panel is open: not saved, not undone. A mode
     * you left on last week is not part of the instrument.
     */
    var dotsLocked by mutableStateOf(false)

    /**
     * The parameters given a jack, by index, and what each sweeps between.
     *
     * An immutable map replaced whole on every edit, like the patch's scale list, so two of
     * them compare by content. A snapshot map would compare by identity -- the trap that
     * made every module pulse on undo. Row parameters of free modules only: a header
     * parameter has no row to put brackets on, and a rail has no bottom edge to spare.
     */
    var modRanges by mutableStateOf(emptyMap<Int, ModRange>())

    /**
     * A knob may take a modulation jack unless it is a rail's or a box's. A Poly's voices
     * knob adds and removes nodes, which is not something a modulator can be allowed to do
     * once a block.
     */
    fun canExpose(index: Int): Boolean = !isPinned && !type.structural && index in type.rowParams

    /**
     * The degree shown on the grid's top row.
     *
     * View state, like the camera and like which panel is open: it is where you are
     * looking, not part of the patch, so it is neither saved nor undone. Starts at the
     * highest note of the default figure so a new sequencer opens with its pattern in
     * view rather than somewhere above it.
     */
    /**
     * The degree on the grid's *bottom* row.
     *
     * Anchored to the bottom rather than the top so the opening position needs no guess
     * about how many rows fit: the lowest note of the figure goes on the last row and
     * everything above follows. Anchoring to the top meant guessing the row count, and
     * being one out put the tonic exactly one row below the fold -- so the landmark the
     * tint exists to provide was the one thing never drawn.
     *
     * View state, like the camera and which panel is open: where you are looking, not
     * part of the patch, so it is neither saved nor undone.
     */
    var gridBottom by mutableStateOf(defaultSteps(type).minOfOrNull { it.degree } ?: 0)

    val isPinned: Boolean get() = type.pinned != null

    /**
     * Open on the panel.
     *
     * The open view is a screen-space panel covering nearly everything rather than the
     * module growing in place, so a module in the canvas never changes size -- its jacks
     * never move and no cable ever jumps. A view state, not part of the patch: a saved
     * file describes an instrument, not which panel you were looking at.
     */
    var expanded by mutableStateOf(false)

    /**
     * The type's height, plus a band for modulation ports when any are exposed. The band
     * grows downward, below the side jacks, which [portIn] places from the top -- so exposing
     * a parameter never moves a jack already on the module.
     */
    val height: Float get() = HEADER + portsBody + modBandFor(type, modRanges.keys)

    /**
     * The ports' band. Equal to the body, now that opening a module leaves the canvas.
     * Counted from this module's own ports, so a subpatch grows with the ports it was given.
     */
    val portsBody: Float
        get() = maxOf(MIN_BODY, maxOf(ports(PortDirection.INPUT).size, ports(PortDirection.OUTPUT).size, 1) * PORT_PITCH)

    val width: Float get() = if (isPinned) RAIL_WIDTH else WIDTH

    /** World-space bounds. Meaningless for pinned modules; use Frame.railRect instead. */
    val bounds: Rect get() = Rect(position, Size(width, height))

    /**
     * The side jacks. Empty for [PortDirection.MOD], whose ports are [modRanges].
     *
     * A subpatch's come from [subpatchPorts]: the box takes inputs and gives outputs, and inside,
     * the left rail gives the subpatch's inputs to what is there and the right rail takes its
     * outputs -- which is why the rails' directions are the box's turned around.
     */
    fun ports(dir: PortDirection): List<Port> {
        val shared = subpatchPorts
        return when {
            dir == PortDirection.MOD -> emptyList()
            shared == null -> if (dir == PortDirection.INPUT) type.inputs else type.outputs
            type == Types.SubpatchIn -> if (dir == PortDirection.OUTPUT) shared.inputs else emptyList()
            type == Types.SubpatchOut -> if (dir == PortDirection.INPUT) shared.outputs else emptyList()
            else -> if (dir == PortDirection.INPUT) shared.inputs else shared.outputs
        }
    }

    companion object {
        const val WIDTH = 116f
        /** Rails only need jacks and a name, so they cost far less canvas than a module. */
        const val RAIL_WIDTH = 64f
        /** Title band above the ports. */
        const val HEADER = 22f
        /** Center-to-center spacing of adjacent ports on one edge. */
        const val PORT_PITCH = 44f
        const val MIN_BODY = 44f
        const val CORNER = 8f
        const val PORT_RADIUS = 6f
        const val PORT_RADIUS_ARMED = 9f
        const val LABEL_INSET = 13f

        // The open panel, all in dp of screen. Sized generously because this view has
        // the screen to itself and a knob you cannot hit accurately is not a knob.
        const val PANEL_MARGIN = 22f
        const val PANEL_HEADER = 42f
        const val PANEL_SIDE = 108f
        const val PANEL_ROW_MAX = 76f
        const val PANEL_BAR = 16f
        const val PANEL_CHOICE = 34f
        const val GRID_ROW = 26f
        const val PANEL_STUB = 26f
        /** The [ ] chip beside each row of an open panel, and its distance from the row. */
        const val PANEL_MOD_CHIP_W = 40f
        const val PANEL_MOD_CHIP_H = 30f
        const val PANEL_MOD_GAP = 14f

        /**
         * The promote chip is narrower than the [ ] chip it mirrors: the left gutter has to
         * hold the panel's input labels as well, and 32dp is what is left clear of them.
         */
        const val PANEL_PROMOTE_W = 32f
        /** How far past either end of its bar a bracket can still be taken from. */
        const val BRACKET_REACH = 22f

        /**
         * The ports' share of the box. Height follows port count at a fixed pitch rather
         * than dividing a constant, so adjacent ports are never closer than PORT_PITCH no
         * matter how many a module has -- crowding is impossible by construction rather
         * than a case to disambiguate.
         */
        fun portsBodyFor(type: ModuleType): Float {
            val ports = maxOf(type.inputs.size, type.outputs.size, 1)
            return maxOf(MIN_BODY, ports * PORT_PITCH)
        }

        /** Closed height. Derived from the type alone, so the add menu can center one. */
        fun heightFor(type: ModuleType): Float = HEADER + portsBodyFor(type)

        /** Modulation ports per row of the bottom band, and the height of each row. */
        const val MOD_COLUMNS = 3
        const val MOD_ROW = PORT_PITCH

        /**
         * Which slot of the band a parameter's port takes: its position among the rows.
         *
         * Fixed per parameter rather than packed, because packing would slide a port along
         * whenever a parameter before it was exposed, and ports must never move. So the same
         * knob's jack is in the same place on every module of its type, gaps and all.
         */
        fun modSlot(type: ModuleType, index: Int): Int = type.rowParams.indexOf(index)

        /** The band's height: as many rows as its deepest exposed port needs, or none. */
        fun modBandFor(type: ModuleType, exposed: Set<Int>): Float {
            val deepest = exposed.maxOfOrNull { modSlot(type, it) / MOD_COLUMNS } ?: return 0f
            return (deepest + 1) * MOD_ROW
        }


    }
}

/**
 * Where a port sits inside a box, in whatever units the box is expressed in. Shared by
 * world modules (dp, inside the camera transform) and rails (px, screen space) so the
 * two cannot drift apart.
 */
internal fun portIn(
    rect: Rect,
    unit: Float,
    dir: PortDirection,
    index: Int,
    count: Int,
    /**
     * The ports' band, in the same units as the rect. Given rather than derived from the
     * rect's height, because a module that is open is taller and its jacks must not
     * move -- every cable attached to it would jump.
     */
    bodyHeight: Float,
): Offset {
    val x = if (dir == PortDirection.INPUT) rect.left else rect.right
    val bodyTop = rect.top + PatchModule.HEADER * unit
    val span = (count - 1) * PatchModule.PORT_PITCH * unit
    val first = bodyTop + (bodyHeight - span) / 2f
    return Offset(x, first + index * PatchModule.PORT_PITCH * unit)
}

/**
 * Where parameter [index]'s modulation port sits in a module's bottom band.
 *
 * Three across, inset like the labels, which puts them 45dp apart -- clear of
 * [PatchModule.PORT_PITCH] -- and each row [PatchModule.MOD_ROW] below the last, so the
 * deepest row lies on the module's bottom edge the way side jacks lie on its sides.
 * Measured down from the side jacks' band rather than up from the bottom, so a deeper row
 * appearing below never moves a port above it.
 */
internal fun modPortIn(rect: Rect, unit: Float, type: ModuleType, index: Int, bodyHeight: Float): Offset {
    val slot = PatchModule.modSlot(type, index).coerceAtLeast(0)
    val column = slot % PatchModule.MOD_COLUMNS
    val row = slot / PatchModule.MOD_COLUMNS
    val pitch = (PatchModule.WIDTH - 2f * PatchModule.LABEL_INSET) / (PatchModule.MOD_COLUMNS - 1)
    val bandTop = rect.top + PatchModule.HEADER * unit + bodyHeight
    return Offset(
        rect.left + (PatchModule.LABEL_INSET + column * pitch) * unit,
        bandTop + (row + 1) * PatchModule.MOD_ROW * unit,
    )
}

// ---------------------------------------------------------------- the open panel
//
// An opened module takes the screen, leaving a border through which the canvas is still
// visible. Its jacks sit on the edges with the cables running off past them, so you can
// see what is attached without the whole graph competing for attention -- to see where
// a cable goes, or to move one, you close the panel. All screen space, in px.

internal fun panelRect(frame: Frame): Rect {
    val m = PatchModule.PANEL_MARGIN * frame.density
    return Rect(
        frame.insetLeft + m,
        frame.insetTop + m,
        frame.canvas.width - frame.insetRight - m,
        frame.canvas.height - frame.insetBottom - m,
    )
}

private fun panelBody(panel: Rect, d: Float) =
    Rect(panel.left, panel.top + PatchModule.PANEL_HEADER * d, panel.right, panel.bottom)

/** Where a jack sits on the panel's edge, spread down the body. */
internal fun panelPort(panel: Rect, d: Float, dir: PortDirection, index: Int, count: Int): Offset {
    val body = panelBody(panel, d)
    val pitch = minOf(PatchModule.PANEL_ROW_MAX * d, body.height / (count + 1))
    val span = (count - 1) * pitch
    val first = body.top + (body.height - span) / 2f
    return Offset(if (dir == PortDirection.INPUT) panel.left else panel.right, first + index * pitch)
}

/**
 * A sequencer's panel is split: the grid takes the top, the knobs share what is left.
 *
 * Two thirds to the grid, because it is the thing being edited and the knobs are two
 * controls that were perfectly legible at half the height. A module with no sequence
 * gives its whole body to the knobs, which is what every panel did before.
 *
 * A grid with no knobs under it takes the whole body instead of leaving a third of the
 * panel empty. A drone had no parameters at all until 2026-09-19, and without this a third of the screen
 * said nothing while the thing being edited was squeezed above it.
 */
internal fun panelGrid(panel: Rect, d: Float, type: ModuleType? = null): Rect {
    val body = panelBody(panel, d)
    val side = PatchModule.PANEL_SIDE * d
    // The complement of the knobs, so the two can never overlap or leave a gap between them
    // however the split is decided. A grid-bearing module's rows are always its own, so the
    // count is its own -- only a subpatch's panel shows somebody else's, and it has no grid.
    val controls = if (type == null) body.height * (1f - GRID_SHARE)
    else panelSplit(panel, d, type, type.rowParams.size).controls
    return Rect(panel.left + side, body.top, panel.right - side, body.bottom - controls)
}

/**
 * The least a knob's row can be and still hold what it draws.
 *
 * A row is a label (14sp) and a value (16sp) on one line, then the bar: 4dp above the text,
 * the text itself, the 16dp bar and 10dp under it. At the reference device's font scale of
 * 1.5 the value is 24sp, whose line is about 28dp, so 60 is what the largest setting needs.
 *
 * A constant rather than read from `Frame.fontScale`, which is what a menu tile does,
 * because a panel cannot grow: it already has the whole screen. What it owes its knobs is a
 * floor at the worst case, which is a floor and not a scale.
 *
 * It was 46, arrived at by counting the font's *size* rather than its line, and the bar was
 * drawn through the bottom of every label. Seen on the phone.
 */
internal const val PANEL_ROW_MIN = 60f

/** What a grid took of the body before the knobs' own height decided it, and their floor now. */
private const val GRID_SHARE = 0.66f

/** A pattern's share, which is fixed: it is one row of marks and is only looked at. */
private const val PATTERN_GRID_SHARE = 0.2f

/** The grid never goes below this, whatever the knobs ask for. */
private const val GRID_FLOOR = 0.5f

/**
 * How a panel's body divides between the grid and the knobs, and into how many columns.
 *
 * One answer for both, because they are one decision: the columns depend on how much height
 * the knobs have, and how much height they take depends on the columns. Worked out against
 * the most the knobs could ever be given, so the two cannot disagree.
 */
private class PanelSplit(
    /** The knobs' band, measured up from the body's bottom. The grid has the rest. */
    val controls: Float,
    val columns: Int,
    /** Rows in the taller column, which is what the band has to hold. */
    val deepest: Int,
)

/**
 * It was a flat third to the knobs, decided when a sequencer had two of them and they were
 * legible at that. `Seq` arrived with three, and a third of the body split three ways is
 * 35dp a row where 60 is what a row draws. So the knobs ask for what they need, the grid
 * keeps the rest, and a panel too short for them all in one column goes to two.
 *
 * Two columns used to be a flat "more than five rows". That is exactly what this comes to
 * on a panel with no grid, where the knobs have the whole body; a panel with a grid has
 * half of it at most and so reaches two columns at three.
 */
private fun panelSplit(panel: Rect, d: Float, type: ModuleType, count: Int): PanelSplit {
    val body = panelBody(panel, d).height
    val rows = maxOf(count, 1)
    fun split(most: Float, least: Float): PanelSplit {
        val columns = if (rows * PANEL_ROW_MIN * d > most) 2 else 1
        val deepest = if (columns == 2) (rows + 1) / 2 else rows
        return PanelSplit((deepest * PANEL_ROW_MIN * d).coerceIn(least, most), columns, deepest)
    }
    return when {
        // No grid: the knobs have the whole body, as every panel did before grids.
        type.grid == GridKind.NONE -> split(body, body).let { PanelSplit(body, it.columns, it.deepest) }
        // A grid with no knobs under it takes the whole body rather than leaving a third of
        // the panel empty -- a drone had no parameters at all until 2026-09-19.
        count == 0 -> PanelSplit(0f, 1, 1)
        // A pattern is one row of marks that is only looked at, so it keeps its thin strip
        // and the knobs that change it get the rest, at the fixed share they always had.
        type.grid == GridKind.PATTERN ->
            split(body * (1f - PATTERN_GRID_SHARE), body * (1f - PATTERN_GRID_SHARE))
        // A sequence or a drone grid is the thing being edited: the knobs take what they
        // need between the third they always had and half, and the grid keeps the rest.
        else -> split(body * GRID_FLOOR, body * (1f - GRID_SHARE))
    }
}

private fun panelControls(panel: Rect, d: Float, type: ModuleType, count: Int): Rect {
    val body = panelBody(panel, d)
    return Rect(body.left, body.bottom - panelSplit(panel, d, type, count).controls, body.right, body.bottom)
}

/** A knob's row: label, value and the bar beneath them. */
internal fun panelRow(panel: Rect, d: Float, type: ModuleType, index: Int): Rect =
    // Placed by its position among the rows, not among the parameters: a parameter that
    // lives in the header takes no row, and must not leave a gap where one would be.
    panelRowAt(panel, d, type, type.rowParams.size, type.rowParams.indexOf(index).coerceAtLeast(0))

/**
 * The [slot]th of [count] rows.
 *
 * What a row's geometry actually depends on, and the form a subpatch's panel needs: its rows
 * are knobs promoted from the modules inside it, which have no place in any list of the
 * subpatch type's own parameters.
 */
internal fun panelRowAt(panel: Rect, d: Float, type: ModuleType, count: Int, slot: Int): Rect {
    val area = panelControls(panel, d, type, count)
    val side = PatchModule.PANEL_SIDE * d
    val split = panelSplit(panel, d, type, count)
    // The first half down the left and the rest down the right, so reading order is still
    // top to bottom and the parameters' order is kept. Split at half: a parameter could once
    // ask to start the second column, for FM, whose seven knobs split ratio/index/fall/A and
    // D/S/R and cut ADSR in two. FM has three knobs now and no module has more than four, so
    // the only panels that reach two columns are a subpatch's and a sequencer's -- and their
    // rows are other modules' knobs, which have no break to declare.
    val first = if (split.columns == 1) count else (count + 1) / 2
    val column = if (slot < first) 0 else 1
    val within = if (slot < first) slot else slot - first
    val rowHeight = minOf(PatchModule.PANEL_ROW_MAX * d, area.height / maxOf(split.deepest, 1))
    val block = rowHeight * split.deepest
    val top = area.top + (area.height - block) / 2f + within * rowHeight
    // The gap between the columns is a gutter as wide as a side one, because it has the
    // same work to do: the left column's [ ] chip and the right column's promote chip both
    // sit in it, each against its own row.
    val left = panel.left + side
    val right = panel.right - side
    val width = (right - left - (split.columns - 1) * side) / split.columns
    val x = left + column * (width + side)
    return Rect(x, top, x + width, top + rowHeight)
}

/**
 * The [ ] chip that gives a row's parameter a jack, in the panel's right-hand gutter.
 *
 * In the gutter rather than on the row, so it costs the bar none of its travel; and at the
 * gutter's inner edge, clear of the output jacks' labels on the panel's outer one. Level with
 * the row's control rather than its label, since that is what it is about.
 */
internal fun panelModChip(panel: Rect, d: Float, type: ModuleType, index: Int): Rect =
    panelModChipOn(panelRow(panel, d, type, index), d)

internal fun panelModChipOn(row: Rect, d: Float): Rect {
    val height = minOf(PatchModule.PANEL_MOD_CHIP_H * d, row.height - 4f * d)
    val center = minOf(row.bottom - 20f * d, row.bottom - height / 2f - 2f * d)
    return Rect(
        Offset(row.right + PatchModule.PANEL_MOD_GAP * d, center - height / 2f),
        Size(PatchModule.PANEL_MOD_CHIP_W * d, height),
    )
}

/**
 * The chip that sends a row's knob out to the subpatch's edge: the left gutter's mirror of the
 * [ ] chip, level with it.
 *
 * Stacking the two in the right-hand gutter was the first drawing and it failed on a
 * sequencer, whose grid leaves its rows about a third of the height the others get -- two
 * chips could not both be tall enough to hit. The left gutter is the same 108dp wide and
 * otherwise empty, and this chip keeps to its inner edge for the same reason the [ ] chip
 * keeps to the other one's: the panel's jack labels have the outer half.
 */
internal fun panelPromoteChipOn(row: Rect, d: Float): Rect {
    val jack = panelModChipOn(row, d)
    val width = PatchModule.PANEL_PROMOTE_W * d
    return Rect(
        Offset(row.left - PatchModule.PANEL_MOD_GAP * d - width, jack.top),
        Size(width, jack.height),
    )
}

/**
 * Where an exposed parameter's jack sits on the open panel: its bottom edge, spread in the
 * order of the rows, as the same jacks are spread along a closed module's bottom band.
 */
internal fun panelModPort(panel: Rect, d: Float, type: ModuleType, index: Int): Offset {
    val side = PatchModule.PANEL_SIDE * d
    val slots = maxOf(type.rowParams.size, 1)
    val slot = PatchModule.modSlot(type, index).coerceAtLeast(0)
    val span = panel.width - 2f * side
    return Offset(panel.left + side + (slot + 0.5f) * span / slots, panel.bottom)
}

/**
 * Where option [i] of a stepped row's buttons is drawn. Shared by the buttons and by the
 * brackets that sit around them, so the two cannot disagree about where an option is.
 */
internal fun choiceBox(row: Rect, d: Float, param: Param, i: Int): Rect {
    val n = param.steps
    val gap = 5f * d
    val height = PatchModule.PANEL_CHOICE * d
    val width = (row.width - gap * (n - 1)) / n
    val top = row.bottom - height - 6f * d
    return Rect(Offset(row.left + i * (width + gap), top), Size(width, height))
}

/**
 * Where a bracket sits on a row: `[` at the low end, `]` at the high.
 *
 * On a bar, exactly at the value. On a row of buttons, around them -- `[` against the left
 * of the low option and `]` against the right of the high one -- so a range of a single
 * option still reads as a range, not as two marks drawn over each other.
 */
internal fun panelBracketX(row: Rect, d: Float, param: Param, value: Float, closing: Boolean): Float =
    if (param.buttons) {
        val box = choiceBox(row, d, param, param.indexOf(value))
        if (closing) box.right else box.left
    } else {
        row.left + row.width * param.positionOf(value)
    }

/**
 * Which bracket of an exposed row a touch at [at] takes: false for the low `[`, true for the
 * high `]`, null for a touch that is not on this row.
 *
 * The nearer one, from anywhere on the row. An exposed row's knob belongs to its modulator, so
 * a bracket is the only thing a finger there can mean -- and the row reaches past both ends of
 * its bar, because a bracket parked at an end is aimed at from beyond it. A bracket was first
 * found only within 22dp of it and never outside the row, which left a `[` at the very left,
 * where a new range puts it for any knob in the bottom fifth of its travel, hard to take hold
 * of at all. Where the two brackets coincide, the side the finger landed on decides.
 */
internal fun panelBracketAt(
    panel: Rect, d: Float, module: PatchModule, rows: List<ParamRow>, at: Offset,
): Pair<ParamRow, Boolean>? {
    rows.forEachIndexed { slot, entry ->
        val range = entry.owner.modRanges[entry.index] ?: return@forEachIndexed
        val row = panelRowAt(panel, d, module.type, rows.size, slot)
        val reach = PatchModule.BRACKET_REACH * d
        val zone = Rect(row.left - reach, row.top - 6f * d, row.right + reach, row.bottom + 6f * d)
        if (!zone.contains(at)) return@forEachIndexed
        val param = entry.param
        val low = panelBracketX(row, d, param, range.low, closing = false)
        val high = panelBracketX(row, d, param, range.high, closing = true)
        val toLow = kotlin.math.abs(at.x - low)
        val toHigh = kotlin.math.abs(at.x - high)
        return entry to (if (toLow == toHigh) at.x > high else toHigh < toLow)
    }
    return null
}

/** What a tap on a row's reading is aimed at: the knob's value, or one end of its range. */
enum class ValueTarget { VALUE, LOW, HIGH }

/**
 * Which number a tap on the panel takes hold of, if any.
 *
 * The reading is the target, not the row: a row's bar is a knob you drag, and the number
 * above it is the same value written down, which is the thing to type over. [widthOf]
 * measures a string in the reading's own style, so the zone is the text as drawn rather
 * than a guessed rectangle -- the gesture loop has the measurer, and an exposed row reads
 * "[400Hz \u2013 2000Hz]", whose two numbers have to be told apart by where they actually sit.
 *
 * A stepped row has no reading: its lit button is the value, and there is nothing to type.
 */
internal fun panelValueAt(
    panel: Rect, d: Float, module: PatchModule, rows: List<ParamRow>, at: Offset,
    widthOf: (String) -> Float,
): Pair<ParamRow, ValueTarget>? {
    rows.forEachIndexed { slot, entry ->
        val param = entry.param
        if (param.buttons) return@forEachIndexed
        val row = panelRowAt(panel, d, module.type, rows.size, slot)
        val range = entry.owner.modRanges[entry.index]
        val text = if (range != null) rangeReading(param, range)
            else param.format(entry.owner.params.getOrElse(entry.index) { param.default })
        val width = widthOf(text)
        val zone = Rect(
            row.right - width - 8f * d, row.top - 4f * d,
            row.right + 8f * d, row.top + VALUE_ZONE_H * d,
        )
        if (!zone.contains(at)) return@forEachIndexed
        if (range == null) return entry to ValueTarget.VALUE
        // Split where the dash is: everything left of it is the low number as drawn.
        val dash = row.right - width + widthOf("[${param.format(range.low)} ") + widthOf("\u2013") / 2f
        return entry to (if (at.x < dash) ValueTarget.LOW else ValueTarget.HIGH)
    }
    return null
}

/**
 * How tall a reading's touch zone is, in dp, measured down from the top of its row.
 *
 * The text is 16sp and sits 2dp below the row's top; the rest is thumb. It stops well
 * short of the bar, which starts 26dp above the row's bottom and is a 76dp row away.
 */
internal const val VALUE_ZONE_H = 30f

/**
 * What an exposed bar reads in place of its value: its range, in the parameter's own units.
 * An en dash rather than a hyphen, which beside a negative number of cents would read as a sign.
 */
internal fun rangeReading(param: Param, range: ModRange): String =
    "[${param.format(range.low)} \u2013 ${param.format(range.high)}]"

/** Moves one end of a parameter's range to the knob value under [screenX]. */
internal fun Patch.moveBracket(
    row: ParamRow, bar: Rect, closing: Boolean, screenX: Float,
) {
    val range = row.owner.modRanges[row.index] ?: return
    val value = row.param.valueAt(panelKnobPosition(bar, screenX))
    expose(row.owner, row.index, if (closing) range.copy(high = value) else range.copy(low = value))
}

internal fun panelKnobAt(
    panel: Rect, d: Float, module: PatchModule, rows: List<ParamRow>, at: Offset,
): ParamRow? {
    rows.forEachIndexed { slot, entry ->
        // An exposed row's knob is not the hand's. It shows where the modulator has taken the
        // parameter, and dragging it would set a value nothing is listening to.
        if (entry.index in entry.owner.modRanges) return@forEachIndexed
        // Generous vertically: the rows are the only targets on the panel, so a near
        // miss should still land rather than do nothing.
        if (panelRowAt(panel, d, module.type, rows.size, slot).inflate(6f * d).contains(at)) return entry
    }
    return null
}

/**
 * Ticks where the scale's degrees fall on a slider measured in cents.
 *
 * Drawn under the bar rather than through it, so they read as a ruler the knob is
 * measured against rather than as part of its value -- and under rather than over,
 * because over is where the parameter's name and its reading already are. Nothing snaps
 * to them: cents are
 * continuous on purpose, and a knob that jumped to the nearest degree would make the
 * cent-sized adjustments the unit exists for impossible. The marks say where the notes
 * are; the hand decides whether to land on one.
 */
private fun DrawScope.drawScaleMarks(
    row: Rect,
    barTop: Float,
    barHeight: Float,
    d: Float,
    param: Param,
    scale: Scale,
) {
    for ((cents, tonic) in scaleMarks(param, scale)) {
        val x = row.left + row.width * param.positionOf(cents)
        val top = barTop + barHeight + 2f * d
        // Zero outranks the other tonics: on every slider these marks sit under, it is the
        // setting that changes nothing, and among a row of identical octave marks it was
        // the one you could not find at a glance.
        val zero = kotlin.math.abs(cents) < 0.01f
        drawLine(
            color = when {
                zero -> MarkZero
                tonic -> MarkTonic
                else -> MarkDegree
            },
            start = Offset(x, top),
            end = Offset(x, top + when {
                zero -> 10f * d
                tonic -> 7f * d
                else -> 4f * d
            }),
            strokeWidth = when {
                zero -> 3f * d
                tonic -> 2f * d
                else -> 1.5f * d
            },
        )
    }
}

private val MarkDegree = Color(0xFF5A6675)
private val MarkTonic = Color(0xFFAAB4C2)
private val MarkZero = Color(0xFFE4E7EC)

private val ChipFill = Color(0xFF1E232B)
private val ChipEdge = Color(0xFF3A424E)
private val PanelScrim = Color(0xE6161A20)
private val TileFill = Color(0xFF1A1F27)

/**
 * What a scale's tile says under its name: degrees per period, and the period itself when
 * it is not the octave. A tuning that does not repeat at the octave is the thing most
 * worth knowing before you pick it.
 */
private fun scaleDetail(scale: Scale): String {
    val period = if (kotlin.math.abs(scale.period - 1f) < 1e-4f) ""
    else "  ·  ${"%.3f".format(Math.pow(2.0, scale.period.toDouble()))}:1"
    return "${scale.size} degrees$period"
}

private val scaleAccent = Color(0xFF6FA8E5)

/**
 * The lock chip: a padlock, lit when the dots are pinned.
 *
 * Drawn rather than lettered, and the shackle is what says the state -- closed and centered
 * over the body when locked, lifted and hinged to one side when not. The fill says it too,
 * as every other chip's does, but the shackle is the part that reads at arm's length.
 */
private fun DrawScope.drawLockChip(rect: Rect, d: Float, locked: Boolean, accent: Color) {
    val corner = CornerRadius(7f * d, 7f * d)
    drawRoundRect(if (locked) accent else ChipFill, rect.topLeft, rect.size, corner)
    drawRoundRect(ChipEdge, rect.topLeft, rect.size, corner, style = Stroke(width = 1.5f * d))

    val ink = if (locked) Color(0xFF14171C) else Color(0xFFC3CBD6)
    val bodyW = 14f * d
    val bodyH = 10f * d
    // The body sits low in the chip, leaving the top third to the shackle.
    val body = Rect(
        Offset(rect.center.x - bodyW / 2f, rect.center.y - bodyH / 2f + 3f * d),
        Size(bodyW, bodyH),
    )
    drawRoundRect(ink, body.topLeft, body.size, CornerRadius(2f * d, 2f * d))
    val shackle = 7f * d
    // Unlocked, the shackle hangs from the body's right corner and is open on the left,
    // which is the difference a glance catches.
    val centerX = if (locked) body.center.x else body.right - 1.5f * d
    drawArc(
        color = ink,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(centerX - shackle / 2f, body.top - shackle - 1f * d),
        size = Size(shackle, shackle),
        style = Stroke(width = 2f * d, cap = StrokeCap.Round),
    )
    // Its legs, down to the body: two when closed, one when it is standing open.
    val legTop = body.top - shackle / 2f - 1f * d
    drawLine(ink, Offset(centerX + shackle / 2f, legTop), Offset(centerX + shackle / 2f, body.top),
        strokeWidth = 2f * d, cap = StrokeCap.Round)
    if (locked) {
        drawLine(ink, Offset(centerX - shackle / 2f, legTop), Offset(centerX - shackle / 2f, body.top),
            strokeWidth = 2f * d, cap = StrokeCap.Round)
    }
}

/** A chip: a label in a rounded box, lit while whatever it opens is open. */
private fun DrawScope.drawChip(
    rect: Rect,
    d: Float,
    label: String,
    open: Boolean,
    accent: Color,
    measurer: TextMeasurer,
    /** Drawn after the label and never shortened; the label gives way to it. */
    suffix: String = "",
) {
    val corner = CornerRadius(7f * d, 7f * d)
    drawRoundRect(
        color = if (open) accent else ChipFill,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = ChipEdge,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = corner,
        style = Stroke(width = 1.5f * d),
    )
    // A long label ends in an ellipsis rather than running off both ends of the chip,
    // which is what "Harmonic minor · 1 of 2" did on the device. The suffix keeps its
    // room: on the scale chip it is which entry is playing, the part nothing else shows.
    val style = if (open) PanelChipOnStyle else PanelChipStyle
    val room = (rect.width - 16f * d).toInt().coerceAtLeast(0)
    val tail = if (suffix.isEmpty()) null else measurer.measure(suffix, style, maxLines = 1)
    val head = measurer.measure(
        label,
        style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = (room - (tail?.size?.width ?: 0)).coerceAtLeast(0)),
    )
    val left = rect.center.x - (head.size.width + (tail?.size?.width ?: 0)) / 2f
    drawText(head, topLeft = Offset(left, rect.center.y - head.size.height / 2f))
    tail?.let {
        drawText(it, topLeft = Offset(left + head.size.width, rect.center.y - it.size.height / 2f))
    }
}

/** An SF panel's page: the fonts along the top when there is a choice, the instruments below. */
private fun DrawScope.drawPresetPage(panel: Rect, d: Float, sf: SfView, code: Int, measurer: TextMeasurer) {
    val body = panelBody(panel, d)
    drawRect(color = PanelScrim, topLeft = body.topLeft, size = body.size)
    val page = presetPage(panel, d, sf.fonts.size, sf.fontScale)
    page.fonts.forEachIndexed { i, rect ->
        drawChip(rect, d, sf.fonts[i], sf.fonts[i] == sf.fontName, scaleAccent, measurer)
    }
    val presets = sf.font?.presets.orEmpty()
    if (presets.isEmpty()) {
        // A heading, and under it the part that is long: a folder path is not a sentence and
        // has to wrap. Drawn as one line it ran off both edges of the phone -- the first
        // thing an SF panel said, and unreadable.
        val heading = when {
            sf.fonts.isEmpty() -> "No SoundFonts yet"
            sf.fontName == null -> "Choose a bank above"
            sf.failed -> "Not a SoundFont this can read"
            else -> "Loading ${sf.fontName}\u2026"
        }
        val detail = when {
            sf.fonts.isEmpty() -> "Put .sf2 files in ${sf.folder}"
            sf.failed -> sf.fontName
            else -> null
        }
        drawPageNote(page.area, d, heading, detail, measurer)
        return
    }
    val scroll = page.resolve(sf.scroll, presets, code)
    page.tiles(presets.size, scroll).forEach { (i, rect) ->
        drawTile(rect, d, presets[i].name, presetDetail(presets[i]), presets[i].code == code, measurer)
    }
    // How far down the list the page is, in the margin to its right: 274 instruments and no
    // sign of where you are among them would be a list you could only get lost in.
    val max = page.maxScroll(presets.size)
    if (max > 0) {
        val track = Rect(page.area.right + 2f * d, page.area.top, page.area.right + 6f * d, page.area.bottom)
        val shown = page.rows.toFloat() / (page.rows + max)
        val thumbH = maxOf(track.height * shown, 24f * d)
        val top = track.top + (track.height - thumbH) * scroll.coerceIn(0, max) / max
        drawRoundRect(ChipEdge, track.topLeft, track.size, CornerRadius(2f * d, 2f * d))
        drawRoundRect(scaleAccent, Offset(track.left, top), Size(track.width, thumbH), CornerRadius(2f * d, 2f * d))
    }
}

/**
 * What a page says when it has no tiles: a heading, and under it a line that may be long
 * enough to wrap -- a folder path, or the name of a file that would not load. Centered in
 * [area] and wrapped inside it, since neither is a sentence anyone can shorten.
 */
private fun DrawScope.drawPageNote(
    area: Rect, d: Float, heading: String, detail: String?, measurer: TextMeasurer,
) {
    val box = Constraints(maxWidth = (area.width - 24f * d).toInt().coerceAtLeast(1))
    val title = measurer.measure(
        heading,
        PanelParamStyle.copy(textAlign = TextAlign.Center),
        overflow = TextOverflow.Ellipsis,
        maxLines = 2,
        constraints = box,
    )
    val under = detail?.let {
        measurer.measure(
            it,
            GridLabelStyle.copy(textAlign = TextAlign.Center),
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
            constraints = box,
        )
    }
    val gap = if (under == null) 0f else 8f * d
    val height = title.size.height + gap + (under?.size?.height ?: 0)
    var y = area.center.y - height / 2f
    drawText(title, topLeft = Offset(area.center.x - title.size.width / 2f, y))
    y += title.size.height + gap
    under?.let { drawText(it, topLeft = Offset(area.center.x - it.size.width / 2f, y)) }
}

/** A tile in a chooser: a name, and a quieter line of detail beneath it. */
private fun DrawScope.drawTile(
    rect: Rect,
    d: Float,
    name: String,
    detail: String,
    chosen: Boolean,
    measurer: TextMeasurer,
) {
    drawRoundRect(
        color = if (chosen) scaleAccent else TileFill,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(7f * d, 7f * d),
    )
    if (!chosen) {
        drawRoundRect(
            color = ChipEdge,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(7f * d, 7f * d),
            style = Stroke(width = 1.5f * d),
        )
    }
    val title = measurer.measure(name, if (chosen) PanelChipOnStyle else PanelChipStyle)
    val titleTop = rect.top + 7f * d
    drawText(title, topLeft = Offset(rect.left + 10f * d, titleTop))
    drawText(
        measurer.measure(detail, GridLabelStyle),
        topLeft = Offset(rect.left + 10f * d, titleTop + title.size.height),
    )
}

/**
 * The interval chip, at the right of a clocked module's header.
 *
 * The right rather than the left, because the left of the header is where the floating
 * chips hang. The tuning chip that used to sit beside it is one of those now: the scale
 * belongs to the patch, and a control for it inside one sequencer changed all the others.
 */
internal fun panelIntervalChip(panel: Rect, d: Float): Rect {
    val height = 28f * d
    val width = 72f * d
    return Rect(
        Offset(panel.right - width - 14f * d, panel.top + (PatchModule.PANEL_HEADER * d - height) / 2f),
        Size(width, height),
    )
}

/**
 * The lock, immediately left of a dot sequencer's interval chip.
 *
 * Derived from that chip rather than measured from the panel's edge, so the two cannot
 * drift apart when either moves. Square and drawn as a glyph rather than a word, which is
 * also why it is the one chip that does not read [Frame.fontScale]: there is no label in
 * it to outgrow the box.
 */
internal fun panelLockChip(panel: Rect, d: Float): Rect {
    val interval = panelIntervalChip(panel, d)
    val width = 40f * d
    return Rect(
        Offset(interval.left - 10f * d - width, interval.top),
        Size(width, interval.height),
    )
}

/**
 * The chip in an SF panel's header that names its instrument and opens the page of them.
 * Where a sequencer's interval chip sits, and wider: it holds a name, not "1/8".
 */
internal fun panelPresetChip(panel: Rect, d: Float): Rect {
    val height = 28f * d
    val width = PRESET_CHIP_W * d
    return Rect(
        Offset(panel.right - width - 14f * d, panel.top + (PatchModule.PANEL_HEADER * d - height) / 2f),
        Size(width, height),
    )
}

internal const val PRESET_CHIP_W = 240f
/** A preset page's scroll that means "wherever the chosen preset is"; see [PresetPage.resolve]. */
internal const val SCROLL_TO_CHOSEN = -1
internal const val PRESET_TILE_W = 188f
internal const val PRESET_TILE_H = 44f
/** The strip of fonts above the presets: one line of text, so shorter than a tile. */
internal const val PRESET_STRIP_H = 34f

/**
 * An SF panel's page of instruments: a strip of fonts along the top when there is more
 * than one to choose, and the presets beneath, row after row, scrolled by dragging.
 *
 * A bank holds hundreds -- GeneralUser GS has 274 -- so unlike the interval's page this one
 * scrolls, in whole rows so a tile never sits half off the page.
 */
internal class PresetPage(
    /** One per font, left to right. Empty when there is only one font. */
    val fonts: List<Rect>,
    /** Where the preset tiles go. */
    val area: Rect,
    val columns: Int,
    /** How many rows are on the page at once. */
    val rows: Int,
    val tileW: Float,
    val tileH: Float,
    /** Between neighboring tiles, in px. */
    val gap: Float,
) {
    /** How far the page can scroll, in rows, for [count] presets. */
    fun maxScroll(count: Int): Int = maxOf(0, (count + columns - 1) / columns - rows)

    /** The presets on the page at [scroll], as their index in the list and where each is drawn. */
    fun tiles(count: Int, scroll: Int): List<Pair<Int, Rect>> {
        val first = scroll.coerceIn(0, maxScroll(count)) * columns
        return (first until minOf(count, first + rows * columns)).map { i ->
            val at = i - first
            i to Rect(
                Offset(area.left + (at % columns) * tileW, area.top + (at / columns) * tileH),
                Size(tileW - gap, tileH - gap),
            )
        }
    }

    /**
     * The scroll to draw at: [scroll] itself, or -- when it is [SCROLL_TO_CHOSEN] -- the
     * one that shows the chosen preset. That can only be worked out once a font's list has
     * loaded, which after switching banks is a moment after the switch.
     */
    fun resolve(scroll: Int, presets: List<SoundFontPreset>, code: Int): Int =
        if (scroll != SCROLL_TO_CHOSEN) scroll
        else presets.indexOfFirst { it.code == code }.let { if (it >= 0) scrollTo(it, presets.size) else 0 }

    /** The scroll that puts preset [index] on the page, as near the top as it can be. */
    fun scrollTo(index: Int, count: Int): Int = (index / columns).coerceIn(0, maxScroll(count))
}

internal fun presetPage(panel: Rect, d: Float, fontCount: Int, fontScale: Float = 1f): PresetPage {
    val body = panelBody(panel, d).deflate(10f * d)
    // Two lines of text to a tile, in sp, so the tile grows with the text setting -- the
    // reference device runs at 1.5 (see Frame.fontScale).
    val tileH = PRESET_TILE_H * d * maxOf(1f, fontScale)
    val strip = if (fontCount > 0) PRESET_STRIP_H * d * maxOf(1f, fontScale) + 6f * d else 0f
    val fonts = if (fontCount > 0) {
        val width = minOf(PRESET_TILE_W * d, body.width / fontCount)
        (0 until fontCount).map { i ->
            Rect(Offset(body.left + i * width, body.top), Size(width - 6f * d, strip - 6f * d))
        }
    } else {
        emptyList()
    }
    val area = Rect(body.left, body.top + strip, body.right - 10f * d, body.bottom)
    // As many columns as fit at the tile's width, then widened to fill the row: a gap at the
    // right end is width a long name could have had.
    val columns = maxOf(1, (area.width / (PRESET_TILE_W * d)).toInt())
    val tileW = area.width / columns
    return PresetPage(
        fonts, area,
        columns = columns,
        rows = maxOf(1, (area.height / tileH).toInt()),
        tileW = tileW, tileH = tileH, gap = 6f * d,
    )
}

/** The preset parameter's index on an SF module. */
internal const val SF_PRESET = 0

/** What an SF panel draws beyond its knobs: its font, and whether its page is open and where. */
internal class SfView(
    val menu: Boolean,
    val scroll: Int,
    /** The bank this module plays, or null until one is chosen. */
    val fontName: String?,
    /** Null until the font has loaded, and for good if it could not be read. */
    val font: LoadedFont?,
    val failed: Boolean,
    val fonts: List<String>,
    /** Where the user's `.sf2` files go, for a panel with none to offer. */
    val folder: String,
    /** The text setting, which the page's tiles grow with. */
    val fontScale: Float = 1f,
)

/** How a preset is described on its tile: which program, counted from 1 as GM charts are. */
internal fun presetDetail(preset: SoundFontPreset): String = when (preset.bank) {
    0 -> "${preset.program + 1}"
    128 -> "kit ${preset.program + 1}"
    else -> "${preset.program + 1} \u00b7 bank ${preset.bank}"
}

/** Where each of [count] chooser tiles lands inside a panel's body. */
internal fun panelTiles(panel: Rect, d: Float, count: Int): List<Rect> =
    tileGrid(panelBody(panel, d).deflate(10f * d), d, count)

/** Where each of [count] tiles lands in [area], row by row, stopping at one page. */
internal fun tileGrid(area: Rect, d: Float, count: Int): List<Rect> {
    val tileW = SCALE_TILE_W * d
    val tileH = SCALE_TILE_H * d
    val columns = maxOf(1, (area.width / tileW).toInt())
    val rows = maxOf(1, (area.height / tileH).toInt())
    val perPage = columns * rows
    return (0 until minOf(count, perPage)).map { i ->
        Rect(
            Offset(area.left + (i % columns) * tileW, area.top + (i / columns) * tileH),
            Size(tileW - 6f * d, tileH - 6f * d),
        )
    }
}

internal const val SCALE_TILE_W = 188f
internal const val SCALE_TILE_H = 56f

// ------------------------------------------------------------------- the step grid

/**
 * Which cell of the grid is under [at], as a step and a degree, or null if none is.
 *
 * Degrees ascend up the screen because pitch does, which is the one thing about a piano
 * roll nobody has to be taught.
 */
internal fun panelCellAt(
    panel: Rect, d: Float, module: PatchModule, at: Offset, scale: Scale = Scale.Chromatic,
): Pair<Int, Int>? {
    // An envelope has no cells: it is continuous in both axes, and its own hit testing is
    // envNodeAt and envSegmentAt. Excluded here so a tap on the shape cannot also read as a
    // cell somewhere behind it.
    if (module.type.grid == GridKind.NONE || module.type.grid == GridKind.PATTERN ||
        module.type.grid == GridKind.ENVELOPE
    ) {
        return null
    }
    val area = panelGrid(panel, d, module.type)
    if (!area.contains(at)) return null

    val window = gridWindow(module, area, d, scale)
    val row = ((at.y - area.top) / (area.height / window.rows)).toInt().coerceIn(0, window.rows - 1)

    if (module.type.grid == GridKind.DRONE) {
        val columns = droneColumns(scale)
        val column = ((at.x - area.left) / (area.width / columns)).toInt().coerceIn(0, columns - 1)
        val degree = droneDegree(window, row, column, scale)
        // A cell is its own index, because a drone's degrees are laid out in order and
        // run no further than its cells do.
        return degree to degree
    }

    if (module.type.grid == GridKind.DOTS) {
        return dotColumnAt(area, dotColumns(module), at.x) to window.degreeAt(row)
    }

    val cellWidth = area.width / module.type.stepCount
    val column = ((at.x - area.left) / cellWidth).toInt().coerceIn(0, module.type.stepCount - 1)
    return column to window.degreeAt(row)
}

/**
 * A dot sequencer's columns: as many as the sequence is long. Unlike Steps, which draws all
 * sixteen and dims those past the loop, because thirty-two at once is a 20dp cell -- a short
 * loop gets cells a finger can hit, and a long one is the choice to pay for detail.
 */
internal fun dotColumns(module: PatchModule): Int =
    module.params.getOrElse(0) { 16f }.roundToInt().coerceIn(1, DOT_STEPS)

/** The column under [x], clamped to the grid, so a drag past either end holds at it. */
internal fun dotColumnAt(area: Rect, columns: Int, x: Float): Int =
    ((x - area.left) / (area.width / columns)).toInt().coerceIn(0, columns - 1)

/** How many steps a dot reaches into, which is what it covers on the grid. */
internal val Dot.stepsSpanned: Int get() = (length + DOT_SUBSTEPS - 1) / DOT_SUBSTEPS

/** The dot covering [column] at [degree], or -1. A dot covers every step it reaches into. */
internal fun PatchModule.dotAt(column: Int, degree: Int): Int =
    dots.indexOfFirst { it.degree == degree && column >= it.step && column < it.step + it.stepsSpanned }

/**
 * How long dot [index] may grow, in quarter steps: to the end of the grid, or to the next
 * dot at its degree, whichever is first -- two notes at one pitch cannot overlap, since the
 * second's start would be heard as nothing.
 */
internal fun PatchModule.dotRoom(index: Int): Int {
    val dot = dots[index]
    val next = dots.filter { it !== dot && it.degree == dot.degree && it.step > dot.step }
        .minOfOrNull { it.step } ?: dotColumns(this)
    return ((minOf(next, dotColumns(this)) - dot.step) * DOT_SUBSTEPS).coerceAtLeast(1)
}

/**
 * The quarter step under [x], counted from the grid's left edge and clamped to it.
 *
 * What a stretch measures against. The column is not enough any more: a note may end partway
 * through a step, so where inside the cell the finger is decides the length.
 */
internal fun dotSubstepAt(area: Rect, columns: Int, x: Float): Int {
    val per = area.width / (columns * DOT_SUBSTEPS)
    return ((x - area.left) / per).toInt().coerceIn(0, columns * DOT_SUBSTEPS - 1)
}

/**
 * Which degrees a grid can scroll across, and which of them are on screen.
 *
 * The one place the answer is worked out, because three things need it and they
 * disagreed: the drawing, the hit test and the drag. The drag used to write the scroll
 * position with no limit at all and the drawing clamped it, against whichever scale was
 * sounding -- so a finger could scroll a drone past the top of its scale and keep
 * going, the stored position running on while the picture stood still. Dragging back then
 * did nothing until that overshoot was undone, and a change of scale re-clamped the
 * overshoot against a different size, which is the grid jumping. [bottom] is always
 * inside the range now, and the drag writes only values that are.
 *
 * [lowest] and [highest] are what a scroll bar measures against.
 */
internal data class GridWindow(val bottom: Int, val rows: Int, val lowest: Int, val highest: Int) {
    val top: Int get() = bottom + rows - 1

    /** Every degree the grid can show. */
    val span: Int get() = highest - lowest + 1

    /** Whether any of it is out of sight, which is when a scroll bar has something to say. */
    val scrolls: Boolean get() = span > rows

    /** The degree on screen row [row], counted from the top. Degrees ascend up the screen. */
    fun degreeAt(row: Int): Int = bottom + (rows - 1 - row)

    /** Where the bottom lands after moving it by [by] degrees, kept inside the range. */
    fun scrolledBy(by: Int): Int = (bottom + by).coerceIn(lowest, (highest - rows + 1).coerceAtLeast(lowest))
}

/**
 * How far a sequencer's grid scrolls: three octaves below the key and four above, in
 * whole periods of the scale. Middle C down three octaves is 33Hz and up four is 4.2kHz,
 * which is the span a melody lives in; a scroll bar needs a range to be a fraction of, and
 * the grid used to scroll through every integer there was. Widened, never narrowed, to
 * take in any step already written outside it -- a note must never be out of reach.
 */
private const val SEQUENCE_OCTAVES_BELOW = 3f
private const val SEQUENCE_OCTAVES_ABOVE = 4f

internal fun gridWindow(module: PatchModule, area: Rect, d: Float, scale: Scale): GridWindow {
    if (module.type.grid == GridKind.DRONE) return droneWindow(module, area, d, scale)
    val rows = gridRows(area, d)
    val written = if (module.type.grid == GridKind.DOTS) module.dots.map { it.degree }
        else module.steps.map { it.degree }
    val lowest = minOf(
        floor(-SEQUENCE_OCTAVES_BELOW / scale.period).toInt() * scale.size,
        written.minOrNull() ?: 0,
    )
    val highest = maxOf(
        ceil(SEQUENCE_OCTAVES_ABOVE / scale.period).toInt() * scale.size - 1,
        written.maxOrNull() ?: 0,
    )
    val bottom = module.gridBottom.coerceIn(lowest, (highest - rows + 1).coerceAtLeast(lowest))
    return GridWindow(bottom, rows, lowest, highest)
}


/**
 * Octave columns. Bounded by the cells there are, so a scale with many degrees to a period
 * trades columns for rows rather than running off the end of the grid.
 */
internal fun droneColumns(scale: Scale): Int =
    (DRONE_CELLS / scale.size).coerceIn(1, DRONE_OCTAVES)

/**
 * A drone's rows: always as many as fit, starting from a degree of the scale and running on
 * past the top of the period when the scale is shorter than the rows.
 *
 * The rows used to be the scale itself -- as many as it had degrees, when they fitted. So a
 * scale list alternating twelve degrees and seven redrew the open grid every few bars, from
 * eleven rows with the chosen degree at the bottom to seven taller ones with degree 0
 * there, and back. Now nothing about the layout depends on the scale's length except which
 * rows carry the tonic tint: the bottom row keeps its degree, and the rows only get
 * different names. The price is that when rows outnumber degrees, the top of one column
 * repeats the bottom of the next -- the same degrees, so the same cells, lit together and
 * toggled together. Chosen on the phone, 2026-09-16.
 *
 * The bottom may be any degree of the first period, so it survives a change to any scale
 * at least that long. It stops short of a top that would run the last column past the
 * cells there are, which only a scale with more degrees than the other columns leave room
 * for can reach.
 */
internal fun droneWindow(module: PatchModule, area: Rect, d: Float, scale: Scale): GridWindow {
    val rows = gridRows(area, d)
    val columns = droneColumns(scale)
    val lastColumnStart = (columns - 1) * scale.size
    val highest = minOf(scale.size - 1 + rows - 1, DRONE_CELLS - 1 - lastColumnStart)
    val bottom = module.gridBottom.coerceIn(0, (highest - rows + 1).coerceAtLeast(0))
    return GridWindow(bottom, rows, 0, highest)
}

/** The degree at a row and column, which is also its cell. Degrees ascend up the screen. */
internal fun droneDegree(window: GridWindow, row: Int, column: Int, scale: Scale): Int =
    column * scale.size + window.degreeAt(row)

/** How many degrees fit. Whole rows only -- a half-height row at the bottom is a lie. */
internal fun gridRows(area: Rect, d: Float): Int =
    (area.height / (PatchModule.GRID_ROW * d)).toInt().coerceAtLeast(1)

/**
 * Knob travel, 0..1, from a screen x along [bar] -- the row being dragged, not the panel.
 *
 * It was the panel once, which was the same thing while every row spanned it. Two columns
 * made it wrong in a way only a finger finds: a knob in either column took the whole screen
 * to cross, reported from the phone on the first night of two columns.
 */
internal fun panelKnobPosition(bar: Rect, screenX: Float): Float =
    ((screenX - bar.left) / bar.width).coerceIn(0f, 1f)

data class PortRef(val moduleId: Long, val dir: PortDirection, val index: Int)

/**
 * How a poly subpatch's copies are numbered, and the one place the engine's ids are not the
 * patch's.
 *
 * Instance 0 *is* the module's own id, so everything that asks the engine about a module by
 * id -- where a sequencer has got to, where a modulated knob is -- reads the first instance
 * without knowing there are others. Later instances carry their number in bits 48 and up,
 * where a patch's ids never reach: ids are handed out one at a time from 100 and a patch
 * would have to have had 2^48 modules in it to collide.
 */
internal const val INSTANCE_SHIFT = 48

internal fun cloneId(id: Long, instance: Int): Long =
    if (instance == 0) id else id or (instance.toLong() shl INSTANCE_SHIFT)

/**
 * The node that adds instance outputs [port] back into one signal, for poly subpatch [poly].
 *
 * Above the instance numbers, which stop at [MAX_PORTS], so a summing node can never be
 * mistaken for a copy of something.
 */
internal fun sumId(poly: Long, port: Int): Long = poly or ((0x80L + port) shl INSTANCE_SHIFT)

/** One node the engine runs, and where its knobs, sequence and ranges come from. */
class EngineNode(
    val id: Long,
    val type: NodeType,
    /** The module it is a copy of, or null for a poly subpatch's summing node. */
    val module: PatchModule?,
)

/** The patch as the engine holds it. See [Patch.engineGraph]. */
class EngineGraph(
    val nodes: List<EngineNode>,
    val cables: Set<Connection>,
    /**
     * Destinations that merge their sources rather than replacing them.
     *
     * Carried rather than worked out again from the patch, because half of these are ports
     * on nodes the patch has no module for -- a poly subpatch's note edge is not a module,
     * and asking the patch what kind of port it has would find nothing.
     */
    val noteInputs: Set<PortRef>,
)

data class Connection(val from: PortRef, val to: PortRef)

/** A default subpatch name, which is what a new subpatch is numbered against. */
/** "Subpatch 3", "Poly 1": a box's default name, for reading the number back out of it. */
private fun boxNumber(type: ModuleType) = Regex("""${Regex.escape(type.name)} (\d+)""")

/** How long a name may be, in characters: enough to be a label, short enough to fit a box. */
internal const val MAX_NAME = 16

class Patch {
    val modules = mutableStateListOf<PatchModule>()
    val connections = mutableStateListOf<Connection>()

    /**
     * Whether the microphone is listening.
     *
     * Runtime state, never serialized: it always starts false and is only true while an
     * input stream is actually open. Persisting it meant a crash or a force-stop with
     * the mic on came back showing a live In rail with nothing behind it.
     */
    var inputEnabled by mutableStateOf(false)

    /**
     * The tunings every sequencer degree is read against, in the order they loop.
     *
     * One list per patch rather than a scale per module: two sequencers in different
     * tunings is a thing somebody will eventually want and nobody wants by accident, and a
     * patch has a key in the same way it has a tempo. A plain immutable list replaced whole
     * on every edit, so comparing two of them compares their contents -- a snapshot list
     * would not. Never empty.
     */
    var scales by mutableStateOf(listOf(ScaleEntry(Scale.Chromatic)))

    /**
     * Beats per minute, for the transport every clocked module divides.
     *
     * Part of the patch like the scale, so it saves and undoes. Where the transport has
     * got to is not: that is a performance state, like the output switch, and lives only
     * in the engine.
     */
    var tempo by mutableFloatStateOf(TEMPO.default)

    /** How the transport's position reads as bars. Nothing divides by it yet. */
    var beatsPerBar by mutableIntStateOf(BEATS_PER_BAR.default.toInt())

    /**
     * What this patch is called, or null for the default. Shown on the breadcrumb's first
     * chip -- which is there at the top level now, so the patch has somewhere to be named --
     * and renamed by holding it, as a subpatch is.
     */
    var name by mutableStateOf<String?>(null)

    /** The name to draw, and what a saved file is offered as. */
    val title: String get() = name ?: DEFAULT_PATCH_NAME

    /**
     * Everything back to a fresh patch: no modules, no cables, the default tuning, tempo and
     * name, and the rails as they start. Undoable, like any edit, because it goes through the
     * model and the autosave records it.
     */
    fun reset() {
        Snapshot.withMutableSnapshot {
            scope = TOP
            modules.forEach { it.expanded = false }
            connections.clear()
            modules.removeAll { it.id != OUT_ID && it.id != IN_ID }
            pinned.forEach { rail ->
                rail.type.params.forEachIndexed { index, param -> rail.setParam(index, param.default) }
            }
            scales = listOf(ScaleEntry(Scale.Chromatic))
            tempo = TEMPO.default
            beatsPerBar = BEATS_PER_BAR.default.toInt()
            name = null
        }
    }

    /**
     * Modules to pulse, after an undo moved something you were not looking at.
     *
     * View state, like the camera and the open panel: never serialized, invisible to the
     * engine. At graph level a parameter is not drawn at all, so undoing a knob was pure
     * audio with no visible cause -- this answers "what did that?" without taking the
     * screen, which binding a whole panel to a repeatable button would.
     *
     * The serial makes the same set twice still fire; without it, undo and redo of one
     * knob would flash once and then look broken.
     */
    var flash by mutableStateOf(Flash.none)
        private set

    fun flash(ids: Set<Long>) {
        if (ids.isNotEmpty()) flash = Flash(ids, flash.serial + 1)
    }

    data class Flash(val ids: Set<Long>, val serial: Int) {
        companion object { val none = Flash(emptySet(), 0) }
    }

    private var nextId = FIRST_FREE_ID

    init {
        modules += PatchModule(OUT_ID, Types.Out, Offset.Zero)
        modules += PatchModule(IN_ID, Types.In, Offset.Zero)
    }

    val free: List<PatchModule> get() = modules.filter { !it.isPinned }
    val pinned: List<PatchModule> get() = modules.filter { it.isPinned }

    fun module(id: Long): PatchModule? = modules.firstOrNull { it.id == id }

    fun port(ref: PortRef): Port? {
        val module = module(ref.moduleId) ?: return null
        if (ref.dir != PortDirection.MOD) return module.ports(ref.dir).getOrNull(ref.index)
        // A parameter's jack, which exists only while the parameter is exposed. Modulation
        // by definition -- it is the one thing a knob knows how to be driven by.
        if (ref.index !in module.modRanges) return null
        val param = module.type.params.getOrNull(ref.index) ?: return null
        return Port(param.short, SignalKind.MODULATION)
    }

    /** What a cable leaving a port carries. Enforced: see [SignalKind.patchesTo]. */
    fun kindOf(ref: PortRef): SignalKind = port(ref)?.kind ?: SignalKind.AUDIO

    /**
     * The subpatch being looked at, or [TOP].
     *
     * View state, like the camera and the open panel: where you are, not what the patch
     * is, so it is neither saved nor undone. Anything that removes the subpatch falls back to
     * the top level through [scopeOrTop].
     */
    var scope by mutableLongStateOf(TOP)

    /** [scope], or [TOP] if the subpatch it names has gone -- undone, deleted or unpacked. */
    val scopeOrTop: Long
        get() = scope.takeIf { it == TOP || module(it)?.type?.box == true } ?: TOP

    /** The modules and rails on screen in [scopeOrTop]. */
    val shownFree: List<PatchModule> get() = scopeOrTop.let { at -> modules.filter { !it.isPinned && it.parent == at } }
    val shownRails: List<PatchModule> get() = scopeOrTop.let { at -> modules.filter { it.isPinned && it.parent == at } }

    /** A module in the scope being looked at, rail or free. */
    fun shown(id: Long): Boolean = module(id)?.parent == scopeOrTop

    /** Pinned types are never added; the rails exist for the life of the patch. */
    fun add(type: ModuleType, at: Offset): PatchModule? {
        if (type.box) return addBox(type, at)
        if (type.pinned != null || type.structural) return null
        return PatchModule(nextId++, type, at).also {
            it.parent = scopeOrTop
            modules.add(it)
        }
    }

    /**
     * An empty subpatch of either kind, with its two rails, where nothing is selected.
     *
     * The other way round from collapsing a selection, and the one that makes a subpatch the
     * thing you reach for first: drop the box, go inside, build there. It starts with no
     * ports at all and gains them the way any subpatch does after the fact -- by patching to
     * a rail's edge.
     */
    fun addBox(type: ModuleType, at: Offset): PatchModule? {
        if (!type.box) return null
        if (type == Types.Poly && insidePoly(scopeOrTop)) return null
        val ports = SubpatchPorts()
        val box = PatchModule(nextId++, type, at, ports).also {
            it.parent = scopeOrTop
            it.name = nextBoxName(type)
        }
        val railIn = PatchModule(nextId++, Types.SubpatchIn, Offset.Zero, ports).also { it.parent = box.id }
        val railOut = PatchModule(nextId++, Types.SubpatchOut, Offset.Zero, ports).also { it.parent = box.id }
        Snapshot.withMutableSnapshot {
            modules.add(box)
            modules.add(railIn)
            modules.add(railOut)
        }
        return box
    }

    /**
     * Re-adds a module with its stored id, for reload. The counter is advanced past
     * whatever came in so a restored patch cannot hand out an id it is already using;
     * that makes nextId derived state rather than another field to keep in the file.
     */
    internal fun adopt(module: PatchModule) {
        if (module.isPinned && module.type != Types.SubpatchIn && module.type != Types.SubpatchOut) return
        modules.add(module)
        if (module.id >= nextId) nextId = module.id + 1
    }

    /** Removes a module, and a subpatch together with everything inside it. */
    fun remove(module: PatchModule) {
        if (module.isPinned) return
        val gone = setOf(module.id) + descendants(module.id)
        // Every jack these modules had, so the subpatch around them can drop the ports that
        // reached only those -- worked out before the cables naming them are cleared.
        val jacks = connections.flatMap { listOf(it.from, it.to) }.filter { it.moduleId in gone }.toSet()
        val ports = subpatchPortsOn(module.parent, jacks)
        connections.removeAll { it.from.moduleId in gone || it.to.moduleId in gone }
        // A knob promoted to a subpatch's edge outlives the module it belongs to otherwise:
        // the panel would not draw it, but the file would keep carrying it.
        modules.forEach { it.subpatchPorts?.promoted?.removeAll { ref -> ref.moduleId in gone } }
        modules.removeAll { it.id in gone }
        dropOrphanedSubpatchPorts(module.parent, ports)
    }

    /** Everything inside subpatch [id], at any depth, its rails included. */
    fun descendants(id: Long): Set<Long> {
        val found = mutableSetOf<Long>()
        var frontier = setOf(id)
        while (frontier.isNotEmpty()) {
            val next = modules.filter { it.parent in frontier && it.id !in found && it.id != id }.map { it.id }.toSet()
            found += next
            frontier = next
        }
        return found
    }

    /**
     * The poly subpatch [id] is inside, at any depth, or null for one that is in none.
     *
     * At most one can be found, because a poly subpatch may not contain another -- see
     * [Types.Poly]. That is what lets an instance be a single number everywhere below.
     */
    fun polyOf(id: Long): PatchModule? {
        var at = module(id)?.parent ?: TOP
        var depth = 0
        while (at != TOP && depth++ < 64) {
            val box = module(at) ?: return null
            if (box.type == Types.Poly) return box
            at = box.parent
        }
        return null
    }

    /** How many copies of its contents a poly subpatch is worth. Its one knob. */
    fun voicesOf(poly: PatchModule): Int =
        poly.params.getOrElse(0) { 4f }.roundToInt().coerceIn(1, MAX_PORTS)

    /** Whether [scope] is a poly subpatch or sits inside one. */
    fun insidePoly(scope: Long): Boolean =
        scope != TOP && (module(scope)?.type == Types.Poly || polyOf(scope) != null)

    /** A subpatch's left rail or right rail. */
    fun subpatchRail(subpatch: Long, type: ModuleType): PatchModule? =
        modules.firstOrNull { it.parent == subpatch && it.type == type }

    /**
     * Sends a knob inside this subpatch out to its edge, or takes it back.
     *
     * Only from inside, and only a module directly in this subpatch: the chip that calls this
     * is on that module's panel, and a knob two levels down promotes to the subpatch it is in
     * and then, once a subpatch's own panel offers the chip, onward. A promoted knob is a
     * reference, so the value never moves and the engine is not told anything -- which is
     * why subpatching's promise holds here too: promoting changes no sound.
     */
    fun promote(module: PatchModule, index: Int): Boolean {
        val subpatch = module(scopeOrTop)?.takeIf { it.type.box } ?: return false
        if (module.parent != subpatch.id || module.isPinned) return false
        if (index !in module.type.rowParams) return false
        val ports = subpatch.subpatchPorts ?: return false
        val ref = ParamRef(module.id, index)
        if (ref in ports.promoted || ports.promoted.size >= roomToPromote(subpatch)) return false
        ports.promoted.add(ref)
        return true
    }

    fun unpromote(module: PatchModule, index: Int): Boolean {
        val ports = module(scopeOrTop)?.subpatchPorts ?: return false
        return ports.promoted.remove(ParamRef(module.id, index))
    }

    /**
     * Whether this knob could be sent out to the edge of the subpatch being looked at.
     *
     * The chip that does it is drawn only where this holds, so the panel says where
     * promotion is possible rather than offering it everywhere and refusing most taps.
     */
    fun canPromote(module: PatchModule, index: Int): Boolean {
        val subpatch = module(scopeOrTop)?.takeIf { it.type.box } ?: return false
        if (module.parent != subpatch.id || module.isPinned) return false
        if (index !in module.type.rowParams) return false
        val ports = subpatch.subpatchPorts ?: return false
        return ParamRef(module.id, index) in ports.promoted || ports.promoted.size < roomToPromote(subpatch)
    }

    /** Whether this knob is already out at the edge of the subpatch being looked at. */
    fun isPromoted(module: PatchModule, index: Int): Boolean =
        module(scopeOrTop)?.subpatchPorts?.promoted?.contains(ParamRef(module.id, index)) == true

    /**
     * The rows an open panel shows for [module].
     *
     * Its own row parameters, or -- for a subpatch, which has no knobs of its own -- the ones
     * promoted to its edge, resolved to the modules inside that really hold them. A
     * reference to a module that has since gone is dropped rather than drawn empty.
     */
    fun panelRows(module: PatchModule): List<ParamRow> =
        if (module.type.box) {
            // A Poly's own voices knob comes first, then the promoted ones. It is the only
            // knob a box has of its own, and it belongs at the top of the panel it opens
            // rather than behind a second gesture.
            module.type.rowParams.map { ParamRow(module, it) } +
                module.subpatchPorts?.promoted.orEmpty().mapNotNull { ref ->
                    module(ref.moduleId)?.takeIf { ref.index in it.type.params.indices }
                        ?.let { ParamRow(it, ref.index) }
                }
        } else {
            module.type.rowParams.map { ParamRow(module, it) }
        }

    /**
     * How many knobs may be promoted to [box]'s edge.
     *
     * One fewer on a Poly, whose voices knob takes a row of the same panel. The limit is
     * what a panel lays out at a finger's height, so it counts rows and not promotions.
     */
    private fun roomToPromote(box: PatchModule): Int = MAX_PROMOTED - box.type.rowParams.size

    /**
     * "Subpatch 1", "Subpatch 2", ... -- one past the highest number in use.
     *
     * Counted over every module in the patch rather than the scope being subpatched, since a
     * breadcrumb shows subpatches from several scopes side by side and two "Subpatch 2"s there
     * would be a worse answer than a gap in the numbering. A renamed subpatch simply drops
     * out of the count, and the number it held can come round again.
     */
    internal fun nextBoxName(type: ModuleType): String {
        val numbered = boxNumber(type)
        val taken = modules.mapNotNull { m ->
            numbered.matchEntire(m.name.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        }
        return "${type.name} ${(taken.maxOrNull() ?: 0) + 1}"
    }

    fun duplicate(module: PatchModule): PatchModule? = when {
        module.isPinned -> null
        module.type.box -> duplicateSubpatch(module)
        else -> add(module.type, module.position + Offset(28f, 28f))?.also {
            it.parent = module.parent
            it.name = module.name
            it.font = module.font
            it.copyGridFrom(module)
        }
    }

    /**
     * A subpatch copied whole: every module inside it at any depth, with its knobs, sequence
     * and exposed parameters, and every cable between them -- the rails' wiring included.
     * Cables to the outside are not copied, as duplicating a single module copies none.
     */
    private fun duplicateSubpatch(subpatch: PatchModule): PatchModule? =
        // The copy is a new subpatch and takes the next free number; the subpatches nested inside
        // it keep their names, since those are only ever read from within it.
        adoptSubpatch(this, subpatch, subpatch.position + Offset(28f, 28f), subpatch.parent, nextBoxName(subpatch.type))

    /**
     * Copies [makeSubpatch] and everything inside it out of [source] and into this patch, under
     * fresh ids, at [at] and inside [parent].
     *
     * One routine for duplicating a subpatch and for loading a saved one, because they are the
     * same operation: the only thing a saved subpatch adds is that [source] is a patch parsed
     * from a file rather than this one. Ids are allocated here, so a subpatch loaded twice is
     * two independent subpatches and a saved file can never collide with what is already in the
     * patch. Cables to the outside are not copied, exactly as duplicating copies none.
     */
    internal fun adoptSubpatch(
        source: Patch,
        subpatch: PatchModule,
        at: Offset,
        parent: Long,
        name: String? = subpatch.name,
    ): PatchModule? {
        val inside = source.descendants(subpatch.id)
        // A poly subpatch may not contain another, so a copy that would nest one is
        // refused rather than half made. Duplicating and loading both come through here.
        val carries = (inside + subpatch.id).any { source.module(it)?.type == Types.Poly }
        if (carries && (parent != TOP && insidePoly(parent))) return null
        // A snapshot before anything is added, since source may be this patch.
        val originals = source.modules.filter { it.id == subpatch.id || it.id in inside }.toList()
        val newId = originals.associate { it.id to nextId++ }
        val sharedCopies = originals.filter { it.type.box }
            .associate { it.id to (it.subpatchPorts ?: SubpatchPorts()).copy() }
        originals.forEach { from ->
            val ports = when (from.type) {
                Types.Subpatch, Types.Poly -> sharedCopies.getValue(from.id)
                Types.SubpatchIn, Types.SubpatchOut -> sharedCopies[from.parent]
                else -> null
            }
            val where = if (from.id == subpatch.id) at else from.position
            val copy = PatchModule(newId.getValue(from.id), from.type, where, ports)
            copy.name = if (from.id == subpatch.id) name else from.name
            copy.font = from.font
            copy.copyGridFrom(from)
            from.params.forEachIndexed { i, v -> copy.setParam(i, v) }
            from.steps.forEachIndexed { i, step -> copy.setStep(i, step) }
            copy.modRanges = from.modRanges
            copy.parent = if (from.id == subpatch.id) parent else newId.getValue(from.parent)
            modules.add(copy)
        }
        // The copies' promoted knobs must name the copies, not the originals they were
        // taken from -- otherwise a duplicated subpatch's panel turns the first subpatch's knobs.
        sharedCopies.forEach { (from, ports) ->
            val taken = originals.first { it.id == from }.subpatchPorts ?: return@forEach
            ports.promoted.clear()
            taken.promoted.forEach { ref ->
                newId[ref.moduleId]?.let { ports.promoted.add(ref.copy(moduleId = it)) }
            }
        }

        source.connections.filter { it.from.moduleId in newId && it.to.moduleId in newId }.forEach {
            connections.add(
                Connection(
                    it.from.copy(moduleId = newId.getValue(it.from.moduleId)),
                    it.to.copy(moduleId = newId.getValue(it.to.moduleId)),
                ),
            )
        }
        return modules.first { it.id == newId.getValue(subpatch.id) }
    }

    /**
     * Collapses [ids] into one subpatch, and returns it -- or null if they cannot be subpatched:
     * none, a rail among them, or not all in the same scope.
     *
     * The subpatch's ports come from the cables that crossed the selection's edge, so the
     * patch sounds exactly as it did: [engineConnections] is the same before and after, and
     * a synced engine is sent nothing at all. A cable coming in becomes an input port, one
     * per outside source, feeding every module inside it used to reach; a cable going out
     * becomes an output port, one per inside source, feeding everything outside it did.
     */
    fun makeSubpatch(ids: Set<Long>, type: ModuleType = Types.Subpatch): PatchModule? {
        if (!type.box) return null
        val chosen = modules.filter { it.id in ids }
        if (chosen.isEmpty() || chosen.size != ids.size || chosen.any { it.isPinned }) return null
        val at = chosen.first().parent
        if (chosen.any { it.parent != at }) return null
        // A poly subpatch may not contain another, from either direction: not around a
        // selection that already has one in it, and not inside one.
        if (type == Types.Poly) {
            if (insidePoly(at)) return null
            val within = ids + ids.flatMap { descendants(it) }
            if (within.any { module(it)?.type == Types.Poly }) return null
        }

        val ports = SubpatchPorts()
        val subpatch = PatchModule(
            nextId++, type,
            Offset(chosen.minOf { it.position.x }, chosen.minOf { it.position.y }),
            ports,
        ).also { it.parent = at; it.name = nextBoxName(it.type) }
        val railIn = PatchModule(nextId++, Types.SubpatchIn, Offset.Zero, ports).also { it.parent = subpatch.id }
        val railOut = PatchModule(nextId++, Types.SubpatchOut, Offset.Zero, ports).also { it.parent = subpatch.id }

        val inside = ids
        val coming = connections.filter { it.from.moduleId !in inside && it.to.moduleId in inside }
        val going = connections.filter { it.from.moduleId in inside && it.to.moduleId !in inside }
        val rewired = mutableListOf<Connection>()

        coming.groupBy { it.from }.forEach { (source, cables) ->
            val index = ports.inputs.size
            // Named for what it feeds when that is one thing, since "cutoff" says more
            // about a subpatch's input than "out" does; otherwise for what feeds it.
            val name = if (cables.size == 1) port(cables.single().to)?.name else port(source)?.name
            ports.inputs += Port(name ?: "in", kindOf(source))
            rewired += Connection(source, PortRef(subpatch.id, PortDirection.INPUT, index))
            cables.forEach { rewired += Connection(PortRef(railIn.id, PortDirection.OUTPUT, index), it.to) }
        }
        going.groupBy { it.from }.forEach { (source, cables) ->
            val index = ports.outputs.size
            ports.outputs += Port(port(source)?.name ?: "out", kindOf(source))
            rewired += Connection(source, PortRef(railOut.id, PortDirection.INPUT, index))
            cables.forEach { rewired += Connection(PortRef(subpatch.id, PortDirection.OUTPUT, index), it.to) }
        }

        Snapshot.withMutableSnapshot {
            connections.removeAll(coming + going)
            modules.add(subpatch)
            modules.add(railIn)
            modules.add(railOut)
            chosen.forEach { it.parent = subpatch.id }
            connections.addAll(rewired)
        }
        return subpatch
    }

    /**
     * Puts a subpatch's contents back where the subpatch was, wiring every cable straight
     * through its ports again. The inverse of [makeSubpatch]: unpacking what was just subpatched
     * gives back the same cables.
     */
    fun unpack(subpatch: PatchModule) {
        if (!subpatch.type.box) return
        val railIn = subpatchRail(subpatch.id, Types.SubpatchIn)
        val railOut = subpatchRail(subpatch.id, Types.SubpatchOut)
        val through = mutableListOf<Connection>()
        subpatch.ports(PortDirection.INPUT).indices.forEach { i ->
            val sources = connections.filter { it.to == PortRef(subpatch.id, PortDirection.INPUT, i) }.map { it.from }
            val sinks = connections.filter { railIn != null && it.from == PortRef(railIn.id, PortDirection.OUTPUT, i) }.map { it.to }
            sources.forEach { from -> sinks.forEach { to -> through += Connection(from, to) } }
        }
        subpatch.ports(PortDirection.OUTPUT).indices.forEach { i ->
            val sources = connections.filter { railOut != null && it.to == PortRef(railOut.id, PortDirection.INPUT, i) }.map { it.from }
            val sinks = connections.filter { it.from == PortRef(subpatch.id, PortDirection.OUTPUT, i) }.map { it.to }
            sources.forEach { from -> sinks.forEach { to -> through += Connection(from, to) } }
        }
        val structure = setOfNotNull(subpatch.id, railIn?.id, railOut?.id)
        Snapshot.withMutableSnapshot {
            connections.removeAll { it.from.moduleId in structure || it.to.moduleId in structure }
            // Everything inside except this subpatch's own two rails, which go with it. The
            // test used to be "not structural", which skipped nested subpatches as well and
            // left them pointing at a parent that no longer existed: still playing, drawn
            // in no scope at all, and only rescued by a reload.
            modules.filter { it.parent == subpatch.id && it.id !in structure }
                .forEach { it.parent = subpatch.parent }
            modules.removeAll { it.id in structure }
            through.forEach { if (it !in connections) connections.add(it) }
        }
    }

    /**
     * Gives subpatch [subpatchId] a new port, wired to [inside] -- a jack on a module inside it --
     * and says whether it did. An input jack, or a parameter's, gets a new input fed from the
     * left rail; an output gets a new output feeding the right rail. The port takes the jack's
     * name and kind, and is added after the others, so no port already there moves.
     *
     * This is the way a port is added after subpatching: patch to the rail's edge.
     */
    /**
     * Drops one of a subpatch's ports, closing the gap behind it.
     *
     * Ports are positional -- a cable names one by its index -- so every cable that pointed
     * past this one has to be renumbered, on the box outside and on the rail inside alike.
     * Cables on the port itself go, which costs the patch no sound: a port whose inside end
     * is gone already carried nothing, and [engineConnections] never saw it.
     */
    fun removeSubpatchPort(subpatch: PatchModule, dir: PortDirection, index: Int): Boolean {
        val ports = subpatch.subpatchPorts ?: return false
        val list = if (dir == PortDirection.INPUT) ports.inputs else ports.outputs
        if (index !in list.indices) return false
        val railType = if (dir == PortDirection.INPUT) Types.SubpatchIn else Types.SubpatchOut
        val railDir = if (dir == PortDirection.INPUT) PortDirection.OUTPUT else PortDirection.INPUT
        val rail = subpatchRail(subpatch.id, railType)

        fun names(ref: PortRef): Boolean =
            (ref.moduleId == subpatch.id && ref.dir == dir) ||
                (rail != null && ref.moduleId == rail.id && ref.dir == railDir)

        fun shifted(ref: PortRef): PortRef =
            if (names(ref) && ref.index > index) ref.copy(index = ref.index - 1) else ref

        Snapshot.withMutableSnapshot {
            connections.removeAll { c ->
                (names(c.from) && c.from.index == index) || (names(c.to) && c.to.index == index)
            }
            val renumbered = connections.map { Connection(shifted(it.from), shifted(it.to)) }
            connections.clear()
            connections.addAll(renumbered)
            list.removeAt(index)
        }
        return true
    }

    /**
     * Which of [subpatchId]'s ports reach one of [gone] inside it.
     *
     * Read *before* those jacks' cables are removed, since the cable is what says which
     * port reached them; [dropOrphanedSubpatchPorts] then decides, after the removal, which
     * of these are left reaching nothing.
     */
    private fun subpatchPortsOn(subpatchId: Long, gone: Set<PortRef>): List<Pair<PortDirection, Int>> {
        val found = mutableListOf<Pair<PortDirection, Int>>()
        forEachSubpatchRail(subpatchId) { dir, rail, railDir ->
            connections.forEach { c ->
                val railEnd = if (dir == PortDirection.INPUT) c.from else c.to
                val other = if (dir == PortDirection.INPUT) c.to else c.from
                if (railEnd.moduleId == rail.id && railEnd.dir == railDir && other in gone) {
                    found += dir to railEnd.index
                }
            }
        }
        return found.distinct()
    }

    /**
     * Drops those [candidates] that no longer reach anything inside the subpatch.
     *
     * Only ports an edit orphaned, never every port that happens to be unpatched: a port is
     * stored rather than derived precisely so that unplugging a cable to move it leaves the
     * jack there to plug back into. What is different about these is that the jack inside is
     * not coming back -- its parameter was unexposed, or its module deleted -- so the port is
     * one nothing could reach again, and a jack on the box that goes nowhere is exactly the
     * kind of thing this project refuses to draw.
     */
    private fun dropOrphanedSubpatchPorts(subpatchId: Long, candidates: List<Pair<PortDirection, Int>>) {
        val subpatch = module(subpatchId)?.takeIf { it.type.box } ?: return
        // Highest index first: removing a port renumbers the ones after it.
        candidates.sortedByDescending { it.second }.forEach { (dir, index) ->
            val rail = subpatchRail(subpatchId, if (dir == PortDirection.INPUT) Types.SubpatchIn else Types.SubpatchOut)
                ?: return@forEach
            val railDir = if (dir == PortDirection.INPUT) PortDirection.OUTPUT else PortDirection.INPUT
            val stillReaches = connections.any { c ->
                val railEnd = if (dir == PortDirection.INPUT) c.from else c.to
                railEnd.moduleId == rail.id && railEnd.dir == railDir && railEnd.index == index
            }
            if (!stillReaches) removeSubpatchPort(subpatch, dir, index)
        }
    }

/**
     * Drops every subpatch port that nothing is plugged into on either side.
     *
     * A subpatch's ports are stored rather than derived so that unplugging one leaves the jack
     * to plug back into. That is right for a port with a cable on its other side and wrong
     * for one with none: Forrest made a second output by mistake on 2026-09-19, dragged the
     * cable to the port he meant, and the empty one stayed -- nothing about it said it was
     * unused, and the only way out was a long press he had no reason to guess at. A port
     * that has nothing on either side is one nobody is in the middle of using.
     *
     * Swept after a cable is removed rather than checked when one is: a port is made and
     * then patched, and a sweep between those two would take it away again.
     */
    fun sweepUnusedSubpatchPorts() {
        modules.filter { it.type.box }.forEach { subpatch ->
            val ports = subpatch.subpatchPorts ?: return@forEach
            forEachSubpatchRail(subpatch.id) { dir, rail, railDir ->
                val list = if (dir == PortDirection.INPUT) ports.inputs else ports.outputs
                // Highest first: removing one renumbers those after it.
                for (index in list.indices.reversed()) {
                    val inside = connections.any { c ->
                        val end = if (dir == PortDirection.INPUT) c.from else c.to
                        end.moduleId == rail.id && end.dir == railDir && end.index == index
                    }
                    val outside = connections.any { c ->
                        val end = if (dir == PortDirection.INPUT) c.to else c.from
                        end.moduleId == subpatch.id && end.dir == dir && end.index == index
                    }
                    if (!inside && !outside) removeSubpatchPort(subpatch, dir, index)
                }
            }
        }
    }

    /** Each of a subpatch's two rails, with the direction of the box's ports it stands for. */
    private inline fun forEachSubpatchRail(
        subpatchId: Long,
        body: (dir: PortDirection, rail: PatchModule, railDir: PortDirection) -> Unit,
    ) {
        subpatchRail(subpatchId, Types.SubpatchIn)?.let { body(PortDirection.INPUT, it, PortDirection.OUTPUT) }
        subpatchRail(subpatchId, Types.SubpatchOut)?.let { body(PortDirection.OUTPUT, it, PortDirection.INPUT) }
    }

    fun addSubpatchPort(subpatchId: Long, inside: PortRef): Boolean {
        val subpatch = module(subpatchId)?.takeIf { it.type.box } ?: return false
        val ports = subpatch.subpatchPorts ?: return false
        val owner = module(inside.moduleId) ?: return false
        if (owner.parent != subpatchId || owner.isPinned) return false
        val jack = port(inside) ?: return false
        return if (inside.dir == PortDirection.OUTPUT) {
            val railOut = subpatchRail(subpatchId, Types.SubpatchOut) ?: return false
            val index = ports.outputs.size
            ports.outputs += Port(jack.name, jack.kind)
            connect(inside, PortRef(railOut.id, PortDirection.INPUT, index))
                .also { if (!it) ports.outputs.removeAt(index) }
        } else {
            val railIn = subpatchRail(subpatchId, Types.SubpatchIn) ?: return false
            val index = ports.inputs.size
            ports.inputs += Port(jack.name, jack.kind)
            connect(PortRef(railIn.id, PortDirection.OUTPUT, index), inside)
                .also { if (!it) ports.inputs.removeAt(index) }
        }
    }

    /** What the engine runs at the top level: every module that makes or shapes sound. */
    val engineModules: List<PatchModule> get() = modules.filter { !it.type.structural }

    /**
     * Every cable the engine should have, with subpatches flattened away.
     *
     * [engineGraph]'s cables, which is the whole of it: for a patch with no poly subpatch in
     * it every module is its own node, so this is the same set it always was.
     */
    fun engineConnections(): Set<Connection> = engineGraph().cables

    /**
     * The patch as the engine will actually hold it: a flat list of nodes and the cables
     * between them, with every subpatch resolved away.
     *
     * A plain subpatch resolves to nothing -- a cable through its ports arrives as the one
     * cable it stands for, which is why subpatching a playing patch sends the engine nothing.
     * A poly subpatch resolves to *copies*: [Patch.voicesOf] of everything inside it, plus a
     * PolyIn that shares the notes out and a PolySum per signal output that adds them back
     * up. The engine learns nothing about either; it is handed a flat graph, as it always
     * has been.
     *
     * Instance 0 keeps the module's own id, which is not a detail. Telemetry -- where a
     * sequencer has got to, where a modulated knob is -- is asked for by module id, and the
     * panel and the canvas both ask. Numbering from the original means every one of those
     * reads the first instance without knowing instances exist.
     */
    fun engineGraph(): EngineGraph {
        val byId = modules.associateBy { it.id }
        val into = connections.groupBy { it.to }
        val polys = modules.filter { it.type == Types.Poly }
        val railOut = modules.filter { it.type == Types.SubpatchOut }.associateBy { it.parent }

        /** The one note input a poly shares out. Every other input is broadcast whole. */
        fun shared(poly: PatchModule): Int =
            poly.ports(PortDirection.INPUT).indexOfFirst { it.kind == SignalKind.NOTE }

        /**
         * Every engine output feeding [ref], for instance [k] of whatever poly subpatch the
         * thing reading it is inside.
         */
        fun sources(ref: PortRef, k: Int, depth: Int): List<PortRef> {
            if (depth > 64) return emptyList() // only a corrupt file could nest this deep
            val module = byId[ref.moduleId] ?: return emptyList()
            return when (module.type) {
                // A subpatch's input, seen from inside: whatever feeds the box's input.
                Types.SubpatchIn -> {
                    val box = byId[module.parent] ?: return emptyList()
                    if (box.type == Types.Poly && ref.index == shared(box)) {
                        // Crossing out of a poly by its note input is the one edge that is
                        // a node: this instance's share of the notes, and nobody else's.
                        listOf(PortRef(box.id, PortDirection.OUTPUT, k))
                    } else {
                        // Everything else crosses whole, and lands on every instance. The
                        // instance number is this poly's, so outside it there is none.
                        val outer = if (box.type == Types.Poly) 0 else k
                        into[PortRef(box.id, PortDirection.INPUT, ref.index)].orEmpty()
                            .flatMap { sources(it.from, outer, depth + 1) }
                    }
                }
                // A subpatch's output, seen from outside: whatever feeds its right rail.
                Types.Subpatch -> {
                    val rail = railOut[module.id] ?: return emptyList()
                    into[PortRef(rail.id, PortDirection.INPUT, ref.index)].orEmpty()
                        .flatMap { sources(it.from, k, depth + 1) }
                }
                Types.Poly -> {
                    val rail = railOut[module.id] ?: return emptyList()
                    val inside = into[PortRef(rail.id, PortDirection.INPUT, ref.index)].orEmpty()
                    if (module.ports(PortDirection.OUTPUT).getOrNull(ref.index)?.kind == SignalKind.NOTE) {
                        // Notes merge at the destination rather than through a summing node,
                        // because merging event streams is what a note input already does --
                        // and each instance's events stay themselves, tagged by their slot.
                        (0 until voicesOf(module)).flatMap { j ->
                            inside.flatMap { sources(it.from, j, depth + 1) }
                        }
                    } else {
                        listOf(PortRef(sumId(module.id, ref.index), PortDirection.OUTPUT, 0))
                    }
                }
                else -> if (module.type.structural) emptyList()
                else listOf(ref.copy(moduleId = cloneId(module.id, k)))
            }
        }

        val nodes = mutableListOf<EngineNode>()
        modules.forEach { m ->
            if (m.type.structural) return@forEach
            val copies = polyOf(m.id)?.let { voicesOf(it) } ?: 1
            for (k in 0 until copies) nodes += EngineNode(cloneId(m.id, k), NodeType.of(m.type), m)
        }
        polys.forEach { poly ->
            // The note edge takes the poly's own id, so its voices knob reaches it as any
            // module's knob reaches its node. Only where there is a note port to share out:
            // a poly subpatch with no notes coming in is N copies running in lockstep, which
            // is a strange thing to build but not a reason to add a node that does nothing.
            if (shared(poly) >= 0) nodes += EngineNode(poly.id, NodeType.PolyIn, poly)
            poly.ports(PortDirection.OUTPUT).forEachIndexed { i, port ->
                if (port.kind != SignalKind.NOTE) {
                    nodes += EngineNode(sumId(poly.id, i), NodeType.PolySum, null)
                }
            }
        }

        val cables = mutableSetOf<Connection>()
        val noteInputs = mutableSetOf<PortRef>()
        connections.forEach { cable ->
            val sink = byId[cable.to.moduleId] ?: return@forEach
            if (sink.type.structural) return@forEach
            val notes = kindOf(cable.to) == SignalKind.NOTE
            val copies = polyOf(sink.id)?.let { voicesOf(it) } ?: 1
            for (k in 0 until copies) {
                val dest = cable.to.copy(moduleId = cloneId(sink.id, k))
                if (notes) noteInputs += dest
                sources(cable.from, k, 0).forEach { cables += Connection(it, dest) }
            }
        }
        polys.forEach { poly ->
            val note = shared(poly)
            if (note >= 0) {
                val dest = PortRef(poly.id, PortDirection.INPUT, 0)
                noteInputs += dest
                into[PortRef(poly.id, PortDirection.INPUT, note)].orEmpty().forEach { cable ->
                    sources(cable.from, 0, 0).forEach { cables += Connection(it, dest) }
                }
            }
            val rail = railOut[poly.id]
            poly.ports(PortDirection.OUTPUT).forEachIndexed { i, port ->
                if (port.kind == SignalKind.NOTE || rail == null) return@forEachIndexed
                val inside = into[PortRef(rail.id, PortDirection.INPUT, i)].orEmpty()
                for (k in 0 until voicesOf(poly)) {
                    val dest = PortRef(sumId(poly.id, i), PortDirection.INPUT, k)
                    inside.forEach { cable ->
                        sources(cable.from, k, 0).forEach { cables += Connection(it, dest) }
                    }
                }
            }
        }
        return EngineGraph(nodes, cables, noteInputs)
    }

    /**
     * Gives a parameter a jack sweeping [range], or moves the brackets of one that has one.
     * Refused for a parameter that cannot have one; see [PatchModule.canExpose].
     */
    fun expose(module: PatchModule, index: Int, range: ModRange): Boolean {
        if (!module.canExpose(index)) return false
        module.modRanges = module.modRanges + (index to range)
        return true
    }

    /** Takes a parameter's jack away, and whatever was patched into it. */
    fun unexpose(module: PatchModule, index: Int) {
        if (index !in module.modRanges) return
        val jack = PortRef(module.id, PortDirection.MOD, index)
        // Before the cable goes, since the cable is what says which port reached this jack.
        val ports = subpatchPortsOn(module.parent, setOf(jack))
        disconnect(jack)
        module.modRanges = module.modRanges - index
        // The jack is not coming back, so a subpatch port that reached only it has nothing
        // left to reach: it would be a jack on the box that quietly went nowhere.
        dropOrphanedSubpatchPorts(module.parent, ports)
    }

    /** A disabled input rail cannot be patched from, so it reads as present but inert. */
    fun portUsable(ref: PortRef): Boolean =
        !(ref.moduleId == IN_ID && !inputEnabled)

    /**
     * Patches two ports, and says whether it did.
     *
     * A signal input takes one source, so re-patching an occupied one replaces the cable
     * that was there. A note input takes several and merges them: a voice fed by two
     * sequencers is the obvious patch, and merging event streams hides nothing -- every
     * event stays itself and arrives when it arrived, which is not true of two signals
     * summing into one input. So a second note cable adds rather than replaces, and
     * patching a pair that is already patched removes that one cable, which is the only
     * way a finger has to take back one of several.
     *
     * Returns false for a patch that cannot be made -- notes to a signal input, or the
     * reverse -- so the caller can leave the port armed rather than silently dropping the
     * tap. This is the one place a cable is refused for what it carries.
     */
    fun connect(a: PortRef, b: PortRef): Boolean {
        val (out, inp) = when {
            a.dir == PortDirection.OUTPUT && b.dir != PortDirection.OUTPUT -> a to b
            b.dir == PortDirection.OUTPUT && a.dir != PortDirection.OUTPUT -> b to a
            else -> return false
        }
        if (out.moduleId == inp.moduleId) return false // no self-patching for now

        // A parameter's jack takes one modulator, as a signal input takes one source, and
        // only from a modulation output -- audio, notes or a pulse on a knob would pin it
        // somewhere with nothing on screen saying why.
        if (inp.dir == PortDirection.MOD) {
            if (port(inp) == null || kindOf(out) != SignalKind.MODULATION) return false
            connections.removeAll { it.to == inp }
            connections.add(Connection(out, inp))
            return true
        }
        if (!kindOf(out).patchesTo(kindOf(inp))) return false

        if (kindOf(inp) == SignalKind.NOTE) {
            val cable = Connection(out, inp)
            // Patching a pair already patched takes that cable back, which can leave a subpatch
            // port with nothing on either side.
            if (connections.remove(cable)) sweepUnusedSubpatchPorts() else connections.add(cable)
            return true
        }
        connections.removeAll { it.to == inp }
        connections.add(Connection(out, inp))
        return true
    }

    fun disconnect(ref: PortRef) {
        connections.removeAll { it.from == ref || it.to == ref }
        sweepUnusedSubpatchPorts()
    }
}

// ---------------------------------------------------------------- interaction state

sealed interface Interaction {
    data object Idle : Interaction
    /** A port is armed and waiting for its partner. */
    data class Connecting(val source: PortRef) : Interaction
    /**
     * A context menu is open at [anchor] (screen px). [targetId] is the module that was
     * long-pressed, or null for empty canvas, which decides what the menu offers.
     */
    data class Menu(
        val anchor: Offset,
        val targetId: Long?,
        /** Set when the press landed on a subpatch's port, which has its own one-item menu. */
        val port: PortRef? = null,
        /** The library's own menu, whose tiles are the saved subpatches. */
        val library: Boolean = false,
    ) : Interaction

    /**
     * Choosing modules to subpatch. A tap on a module adds or removes it; the Subpatch and Cancel
     * buttons at the bottom end it. A mode rather than a gesture, because every gesture the
     * canvas has is already spoken for -- and a lasso would be one more outcome for the
     * gesture loop to tell apart on the first move.
     */
    data class Selecting(val ids: Set<Long>, val type: ModuleType = Types.Subpatch) : Interaction

    /**
     * A module is being named, with the system keyboard up.
     *
     * The only interaction that is not drawn on the canvas: text entry needs a real
     * `BasicTextField` to get an IME, autocorrect and a cursor, so this one puts a
     * composable over the canvas rather than another shape inside it.
     */
    data class Renaming(val moduleId: Long) : Interaction

    /**
     * A number is being typed on the keypad, over the canvas like [Renaming].
     *
     * The keypad is drawn by this app rather than asked for from the system, unlike the
     * name field: a numeric IME resizes the window, and the panel whose value you are
     * typing would slide out from under the keypad as it opened.
     */
    data class Typing(val target: NumberTarget) : Interaction

    /**
     * Naming something on its way into the library: a subpatch, or the whole patch when
     * [moduleId] is null. Over the canvas like [Renaming], and for the same reason.
     */
    data class Saving(val moduleId: Long?) : Interaction
}

/** What a typed number is going to be written to. */
sealed interface NumberTarget {
    /** A knob on an open panel, or one end of the range it sweeps when modulated. */
    data class Knob(val moduleId: Long, val index: Int, val end: ValueTarget) : NumberTarget

    /** The transport's tempo, which is a knob in every way but where it lives. */
    data object Tempo : NumberTarget

    /** How long one envelope segment lasts. Typed in milliseconds; see [SEGMENT_TIME]. */
    data class SegmentTime(val moduleId: Long, val index: Int) : NumberTarget
}

sealed interface MenuItem {
    data class Add(val type: ModuleType) : MenuItem
    data class Duplicate(val moduleId: Long) : MenuItem
    data class Delete(val moduleId: Long) : MenuItem
    /** Starts choosing modules to collapse, into a subpatch of [type]. */
    data class StartSubpatch(val type: ModuleType) : MenuItem
    data class Unpack(val moduleId: Long) : MenuItem
    data class Rename(val moduleId: Long) : MenuItem

    /** A subpatch's promoted knobs, which is the only way its panel opens: a tap goes inside. */
    data class Knobs(val moduleId: Long) : MenuItem

    /** Takes a port off a subpatch, from the box outside or the rail inside. */
    data class RemovePort(val subpatchId: Long, val dir: PortDirection, val index: Int) : MenuItem

    /** Everything away, back to an empty patch. Undo puts it back, like any other edit. */
    data object NewPatch : MenuItem

    /** Writes a subpatch to the library. Null is the whole patch, saved as one subpatch. */
    data class Save(val moduleId: Long?) : MenuItem

    /** Opens the library, whose own tiles are the saved subpatches. */
    data object OpenLibrary : MenuItem

    /** One saved subpatch, placed where the menu that offered it was opened. */
    data class Load(val name: String) : MenuItem

    /** The library with nothing in it yet: a tile that says so and dismisses. */
    data object LibraryEmpty : MenuItem
}

/**
 * Which subpatch's port a jack belongs to, from either side of the boundary.
 *
 * The box's own jacks name the subpatch directly; a rail's name it the other way round, since
 * a subpatch's input is the left rail's output. Null for anything else -- the patch's own
 * rails are pinned too, and their ports are the audio device's, not a subpatch's.
 */
internal fun Patch.subpatchPortAt(ref: PortRef): Triple<PatchModule, PortDirection, Int>? {
    val module = module(ref.moduleId) ?: return null
    return when {
        module.type.box && ref.dir != PortDirection.MOD ->
            Triple(module, ref.dir, ref.index)
        module.type == Types.SubpatchIn && ref.dir == PortDirection.OUTPUT ->
            module(module.parent)?.let { Triple(it, PortDirection.INPUT, ref.index) }
        module.type == Types.SubpatchOut && ref.dir == PortDirection.INPUT ->
            module(module.parent)?.let { Triple(it, PortDirection.OUTPUT, ref.index) }
        else -> null
    }
}

private fun menuItems(
    patch: Patch,
    targetId: Long?,
    port: PortRef? = null,
    /** Non-null for the library's own menu: its tiles are what is saved. */
    saved: List<String>? = null,
): List<MenuItem> = when {
    saved != null ->
        saved.take(MAX_SAVED_TILES).map { MenuItem.Load(it) }.ifEmpty { listOf(MenuItem.LibraryEmpty) }
    port != null -> patch.subpatchPortAt(port)
        ?.let { (subpatch, dir, index) -> listOf(MenuItem.RemovePort(subpatch.id, dir, index)) }
        .orEmpty()
    targetId == null -> Types.palette.map { MenuItem.Add(it) } +
        // The boxes come first among what the menu offers after the modules: an empty one
        // you go inside, then the same thing made out of what is already on the canvas.
        // Both kinds of each, except that a poly subpatch cannot be made inside one.
        listOfNotNull(
            MenuItem.Add(Types.Subpatch),
            MenuItem.Add(Types.Poly).takeIf { !patch.insidePoly(patch.scopeOrTop) },
            MenuItem.StartSubpatch(Types.Subpatch),
            MenuItem.StartSubpatch(Types.Poly).takeIf { !patch.insidePoly(patch.scopeOrTop) },
        ) +
        MenuItem.OpenLibrary +
        // Saving the patch belongs here rather than on a module: it is about all of them,
        // and the empty canvas is the only thing that stands for the patch as a whole.
        listOfNotNull(
            MenuItem.Save(null).takeIf { patch.free.any { m -> m.parent == TOP } },
            // Next to Save, and only when there is something to clear.
            MenuItem.NewPatch.takeIf { patch.free.isNotEmpty() },
        )
    patch.module(targetId)?.type?.box == true -> listOfNotNull(
        MenuItem.Duplicate(targetId),
        // Only when it has any: an empty panel would be a door onto nothing, and the way
        // to put knobs there is inside the subpatch, where the chip is.
        MenuItem.Knobs(targetId).takeIf { patch.panelRows(patch.module(targetId)!!).isNotEmpty() },
        MenuItem.Rename(targetId),
        MenuItem.Save(targetId),
        MenuItem.Delete(targetId), MenuItem.Unpack(targetId),
    )
    else -> listOf(MenuItem.Duplicate(targetId), MenuItem.Rename(targetId), MenuItem.Delete(targetId))
}

/**
 * How many saved subpatches the library's menu shows.
 *
 * The menu wraps its tiles into rows and would run off the screen before it ran out of
 * names. A library bigger than this wants a list that scrolls, which is the next thing to
 * build here rather than a reason to hold this one back.
 */
internal const val MAX_SAVED_TILES = 12

/** The subpatches from the top down to the one being looked at, [TOP] first. */
internal fun Patch.scopePath(): List<Long> {
    val path = mutableListOf<Long>()
    var at = scopeOrTop
    while (at != TOP) {
        path += at
        at = module(at)?.parent ?: TOP
        if (path.size > 64) break
    }
    return listOf(TOP) + path.reversed()
}

/** Whether [screen] lands on the slot that would add a port for [source]. */
internal fun Patch.subpatchPortSlotHit(frame: Frame, source: PortRef, screen: Offset, touchPx: Float): Boolean {
    val at = scopeOrTop
    if (at == TOP) return false
    if (module(source.moduleId)?.parent != at) return false
    val rail = subpatchRail(at, railTypeFor(source)) ?: return false
    return (subpatchPortSlot(frame, rail, railDirFor(source)) - screen).getDistance() <= touchPx
}

/**
 * Which subpatch's crumb [screen] landed on, [TOP] for the patch itself, or null for none.
 *
 * One definition for the tap that goes there and the long press that renames it, so the
 * two cannot disagree about where a chip is. Null while a panel is open, since the
 * breadcrumb is not drawn over one -- the panel's own loop takes those touches anyway,
 * but a hit test that claims a control nobody can see is the kind of thing that is true
 * until it quietly is not.
 */
internal fun Patch.breadcrumbAt(frame: Frame, screen: Offset): Long? {
    // At the top level the bar is one chip, the patch's own: it is where the patch is named,
    // which it had nowhere to be before.
    if (modules.any { it.expanded }) return null
    scopePath().forEachIndexed { level, id ->
        if (frame.breadcrumbChip(level).contains(screen)) return id
    }
    return null
}

/** Looks inside a subpatch, or back out: closing any panel, which belongs to where you were. */
internal fun Patch.enterScope(id: Long) {
    modules.forEach { it.expanded = false }
    scope = id
}

/**
 * Camera. World is dp; screen is px. `screen = world * density * zoom + pan`.
 *
 * Density lives here rather than at every call site so there is exactly one place where
 * world units become pixels.
 */
class Camera(private val density: Float) {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /** Set once the user has moved the view, so the initial framing stops re-applying. */
    var userMoved by mutableStateOf(false)
        private set

    val worldToScreen: Float get() = density * zoom

    fun toWorld(screen: Offset) = (screen - pan) / worldToScreen
    fun toScreen(world: Offset) = world * worldToScreen + pan

    fun panBy(deltaScreen: Offset) {
        pan += deltaScreen
        userMoved = true
    }

    fun panTo(screen: Offset) {
        pan = screen
        userMoved = true
    }

    fun frameAt(screen: Offset) {
        pan = screen
    }

    fun zoomAround(pivotScreen: Offset, factor: Float) {
        val pivotWorld = toWorld(pivotScreen)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        pan = pivotScreen - pivotWorld * worldToScreen
        userMoved = true
    }

    companion object {
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 2.5f
        /** Below this the title is noise; below LABEL_ZOOM the port names are. */
        const val TITLE_ZOOM = 0.45f
        const val LABEL_ZOOM = 0.75f
    }
}

/**
 * Per-frame screen geometry: canvas size, density and safe-area insets, all in px.
 * Built identically by the gesture handler and the draw pass so the two agree about
 * where the rails are.
 */
internal class Frame(
    val canvas: Size,
    val density: Float,
    val insetLeft: Float,
    val insetTop: Float,
    val insetRight: Float,
    val insetBottom: Float,
    /**
     * The user's text size setting, 1.0 at the default. Labels are in sp and scale with it;
     * a box sized in dp does not, so anything sized to hold a label reads this.
     */
    val fontScale: Float = 1f,
) {
    /**
     * A rail's width, grown with the text size as a menu tile is.
     *
     * "Instance out" is the longest thing a rail's header says, and at font scale 1 it
     * fills 64dp exactly; at 1.5, which is what the reference device runs, it would spill
     * out of both sides of the box. Grow the box with the setting rather than shrinking the
     * text back against it. A rail is screen space and its rect is worked out every frame,
     * so nothing is stored against this width -- a change of text size moves the jacks and
     * the cables follow, because both are resolved through portScreen either way.
     */
    val railWidth: Float get() = PatchModule.RAIL_WIDTH * density * maxOf(1f, fontScale)

    fun railRect(module: PatchModule): Rect {
        val d = density
        val w = railWidth
        val h = module.height * d
        val top = insetTop + (canvas.height - insetTop - insetBottom - h) / 2f
        val left = when (module.type.pinned) {
            Edge.LEFT -> insetLeft + RAIL_MARGIN * d
            // Short of the margin by a stack's room; see RAIL_STACK_ROOM.
            else -> canvas.width - insetRight - (RAIL_MARGIN + RAIL_STACK_ROOM) * d - w
        }
        return Rect(Offset(left, top), Size(w, h))
    }

    /**
     * The undo and redo buttons, bottom-left.
     *
     * Screen space, like the rails: the canvas principle forbids chrome stacked above
     * the surface, not controls drawn inside it that stay put while the world moves.
     * Bottom-left is the one corner nothing else claims -- the In rail is centered on the
     * left edge, and the gesture bar is already excluded by the inset.
     */
    fun historyRect(redo: Boolean): Rect {
        val d = density
        val side = HISTORY_SIDE * d
        val step = side + HISTORY_GAP * d
        return Rect(
            Offset(
                insetLeft + RAIL_MARGIN * d + if (redo) step else 0f,
                canvas.height - insetBottom - RAIL_MARGIN * d - side,
            ),
            Size(side, side),
        )
    }

    /**
     * The transport's chip, top-left.
     *
     * Screen space and always present, floating over the graph and an open panel alike --
     * the undo buttons' arrangement, in the opposite corner. Top-left because it is free
     * on both: the In rail is centered on the left edge, and a panel keeps its own header
     * chips on the right.
     */
    fun transportChip(): Rect {
        val d = density
        return Rect(
            Offset(insetLeft + RAIL_MARGIN * d, insetTop + RAIL_MARGIN * d),
            Size(TRANSPORT_CHIP_W * d, TRANSPORT_CHIP_H * d),
        )
    }

    /** What the chip opens into, hanging beneath it over whatever is there. */
    fun transportCard(): Rect {
        val d = density
        val chip = transportChip()
        return Rect(
            Offset(chip.left, chip.bottom + 6f * d),
            Size(TRANSPORT_CARD_W * d, TRANSPORT_CARD_H * d),
        )
    }

    /** The scale chip, beside the transport's. */
    fun scaleChip(): Rect {
        val d = density
        val transport = transportChip()
        return Rect(
            Offset(transport.right + 8f * d, transport.top),
            Size(SCALE_CHIP_W * d, TRANSPORT_CHIP_H * d),
        )
    }

    /**
     * How many entry rows the scale card shows before it scrolls.
     *
     * The card hangs from the scale chip rather than the corner, which keeps it to the
     * right of the undo buttons, so it can use the height all the way down to the
     * gesture bar.
     */
    fun scaleRowsThatFit(): Int {
        val d = density
        val top = scaleChip().bottom + 6f * d
        val bottom = canvas.height - insetBottom - RAIL_MARGIN * d
        val room = bottom - top - (SCALE_CARD_PAD * 2 + SCALE_CARD_HEAD + SCALE_CARD_FOOT) * d
        return (room / (SCALE_ROW * d)).toInt().coerceIn(1, MAX_SCALE_ENTRIES)
    }

    /** The scale card, sized for [entries] rows up to as many as fit. */
    fun scaleCard(entries: Int): Rect {
        val d = density
        val chip = scaleChip()
        val rows = entries.coerceIn(1, scaleRowsThatFit())
        val height = (SCALE_CARD_PAD * 2 + SCALE_CARD_HEAD + SCALE_CARD_FOOT + rows * SCALE_ROW) * d
        return Rect(Offset(chip.left, chip.bottom + 6f * d), Size(SCALE_CARD_W * d, height))
    }

    /** The page of scales one entry chooses from, shown in place of the card while it does. */
    fun scalePicker(): Rect {
        val d = density
        val chip = scaleChip()
        return Rect(
            chip.left,
            chip.bottom + 6f * d,
            minOf(canvas.width - insetRight - RAIL_MARGIN * d, chip.left + SCALE_PICKER_W * d),
            canvas.height - insetBottom - RAIL_MARGIN * d,
        )
    }

    /** The page one entry's root is set on, in the same place as the picker. */
    fun scaleRootPage(): Rect = scalePicker()

    /**
     * The rail as it would be with [ports] jacks on it.
     *
     * Where a port about to be added will land, which is also where the slot marking it is
     * drawn. A rail is centered, like In and Out -- so it re-centers as it grows, and the
     * jacks already on it move half a pitch when one is added. That was the reason they
     * briefly hung from a fixed top instead; centered is what the patch's own rails do and
     * what was asked for, and the shift happens only at the moment a port is added.
     */
    fun railRectWith(module: PatchModule, ports: Int): Rect {
        val d = density
        val w = railWidth
        val body = maxOf(PatchModule.MIN_BODY, maxOf(ports, 1) * PatchModule.PORT_PITCH)
        val h = (PatchModule.HEADER + body) * d
        val top = insetTop + (canvas.height - insetTop - insetBottom - h) / 2f
        val left = when (module.type.pinned) {
            Edge.LEFT -> insetLeft + RAIL_MARGIN * d
            else -> canvas.width - insetRight - (RAIL_MARGIN + RAIL_STACK_ROOM) * d - w
        }
        return Rect(Offset(left, top), Size(w, h))
    }

    /**
     * One step of the breadcrumb, [level] 0 being the patch itself.
     *
     * In the top row beside the scale chip, because that row is where the patch-wide
     * controls already live and "where am I" is one of them. Shown only inside a subpatch:
     * at the top level there is nowhere else to be.
     */
    fun breadcrumbChip(level: Int): Rect {
        val d = density
        val start = scaleChip().right + 16f * d
        return Rect(
            Offset(start + level * (CRUMB_W + CRUMB_GAP) * d, transportChip().top),
            Size(CRUMB_W * d, TRANSPORT_CHIP_H * d),
        )
    }

    /** Subpatch (done) and Cancel, centered along the bottom while modules are being chosen. */
    fun selectionButton(done: Boolean): Rect {
        val d = density
        val w = SELECT_BUTTON_W * d
        val h = TRANSPORT_CHIP_H * d
        val gap = 12f * d
        val centerX = insetLeft + (canvas.width - insetLeft - insetRight) / 2f
        val top = canvas.height - insetBottom - RAIL_MARGIN * d - h
        val left = if (done) centerX - w - gap / 2f else centerX + gap / 2f
        return Rect(Offset(left, top), Size(w, h))
    }

    companion object {
        const val RAIL_MARGIN = 8f
        const val HISTORY_SIDE = 44f
        const val HISTORY_GAP = 8f
        const val TRANSPORT_CHIP_W = 96f
        const val TRANSPORT_CHIP_H = 36f
        const val TRANSPORT_CARD_W = 300f
        const val TRANSPORT_CARD_H = 204f
        const val SCALE_CHIP_W = 230f
        const val SCALE_CARD_W = 676f
        const val SCALE_CARD_PAD = 14f
        const val SCALE_CARD_HEAD = 26f
        const val SCALE_CARD_FOOT = 54f
        const val SCALE_ROW = 44f
        const val SCALE_PICKER_W = 830f
        const val CRUMB_W = 96f
        const val CRUMB_GAP = 6f
        const val SELECT_BUTTON_W = 120f
    }
}

/**
 * The canvas's own switches and buttons.
 *
 * Bundled because the gesture code hands them straight through to the tap logic, and
 * four more positional parameters on a function that already takes eight is where a
 * caller starts passing them in the wrong order.
 */
internal class CanvasControls(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val onToggleOutput: () -> Unit = {},
    val onToggleInput: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val onResetTransport: () -> Unit = {},
    /** The saved subpatches, newest listing first read when the picker opens. */
    val saved: List<String> = emptyList(),
    /** Loads a saved subpatch into [Patch] at a world position. The file read is the caller's. */
    val onLoadSubpatch: (String, Offset) -> Unit = { _, _ -> },
)

/**
 * Fires whichever history button is under [at], if one is there and enabled.
 *
 * Shared by both gesture loops -- the canvas one and the panel's -- because the buttons
 * are in the same screen position either way, and a control that moved depending on what
 * was open would be worse than one that is sometimes absent.
 */
internal fun CanvasControls.tapHistory(frame: Frame, at: Offset): Boolean {
    if (canUndo && frame.historyRect(false).contains(at)) {
        onUndo()
        return true
    }
    if (canRedo && frame.historyRect(true).contains(at)) {
        onRedo()
        return true
    }
    return false
}

/** Whether either enabled history button covers [at]. */
internal fun CanvasControls.overHistory(frame: Frame, at: Offset): Boolean =
    (canUndo && frame.historyRect(false).contains(at)) ||
        (canRedo && frame.historyRect(true).contains(at))

// ---------------------------------------------------------------- the transport card

private const val CARD_PAD = 14f

/** The tempo: a label, its reading and a bar beneath, like a panel's continuous knob. */
internal fun transportTempoRow(card: Rect, d: Float) =
    Rect(card.left + CARD_PAD * d, card.top + 12f * d, card.right - CARD_PAD * d, card.top + 66f * d)

/**
 * The tempo's reading, as a target for a finger: the top right of its row.
 *
 * Fixed rather than measured, unlike a panel's readings, because this one is a whole
 * number of bpm and never grows past "300 bpm".
 */
internal fun transportTempoValue(card: Rect, d: Float): Rect {
    val row = transportTempoRow(card, d)
    return Rect(row.right - 96f * d, row.top - 4f * d, row.right + 8f * d, row.top + VALUE_ZONE_H * d)
}

/**
 * Beats per bar: a label over a row of buttons. 68dp rather than the 60 it was, which
 * put the label's descenders under the buttons on the reference device -- a measured
 * text height includes its line spacing, so the label is taller than its point size.
 */
internal fun transportBeatsRow(card: Rect, d: Float) =
    Rect(card.left + CARD_PAD * d, card.top + 74f * d, card.right - CARD_PAD * d, card.top + 142f * d)

/** Back to bar one, bottom-right, with the position it would reset drawn beside it. */
internal fun transportReset(card: Rect, d: Float) =
    Rect(
        Offset(card.right - CARD_PAD * d - 88f * d, card.bottom - 12f * d - 40f * d),
        Size(88f * d, 40f * d),
    )

// ---------------------------------------------------------------- the scale card

/** Its entry rows, below the column headings and above the add button. */
internal fun scaleCardList(card: Rect, d: Float) = Rect(
    card.left + Frame.SCALE_CARD_PAD * d,
    card.top + (Frame.SCALE_CARD_PAD + Frame.SCALE_CARD_HEAD) * d,
    card.right - Frame.SCALE_CARD_PAD * d,
    card.bottom - (Frame.SCALE_CARD_PAD + Frame.SCALE_CARD_FOOT) * d,
)

/** The row at visible position [slot], counting from the top of what is shown. */
internal fun scaleCardRow(card: Rect, d: Float, slot: Int): Rect {
    val list = scaleCardList(card, d)
    val top = list.top + slot * Frame.SCALE_ROW * d
    return Rect(list.left, top, list.right, top + Frame.SCALE_ROW * d)
}

/** One entry's controls: its scale; bars, beats and root each with a pair of steppers; and remove. */
internal class ScaleRowParts(
    val name: Rect,
    val barsLess: Rect,
    val bars: Rect,
    val barsMore: Rect,
    val beatsLess: Rect,
    val beats: Rect,
    val beatsMore: Rect,
    val rootLess: Rect,
    val root: Rect,
    val rootMore: Rect,
    val remove: Rect,
) {
    val all: List<Rect>
        get() = listOf(
            name, barsLess, bars, barsMore, beatsLess, beats, beatsMore, rootLess, root, rootMore, remove,
        )
}

/**
 * Steppers rather than sliders, because a length is counted: one more bar is one tap,
 * and there is no way to land between two.
 */
internal fun scaleRowParts(row: Rect, d: Float): ScaleRowParts {
    val height = 36f * d
    val top = row.center.y - height / 2f
    fun box(from: Float, width: Float) = Rect(Offset(row.left + from * d, top), Size(width * d, height))
    return ScaleRowParts(
        name = box(0f, 196f),
        barsLess = box(208f, 36f),
        bars = box(244f, 40f),
        barsMore = box(284f, 36f),
        beatsLess = box(332f, 36f),
        beats = box(368f, 40f),
        beatsMore = box(408f, 36f),
        rootLess = box(456f, 36f),
        root = box(492f, 72f),
        rootMore = box(564f, 36f),
        remove = box(612f, 36f),
    )
}

// ---------------------------------------------------------------- the root page

/**
 * The key: where degree 0 sits, in cents above middle C. Two octaves either way, like the
 * transpose it resembles, which also reaches a full turn of a tritave scale.
 */
internal val ROOT = Param("root", -TUNE_RANGE, TUNE_RANGE, 0f, "¢", ParamCurve.LINEAR, marks = true)

/** Mirrors kMiddleC in nodes.h: the pitch a root of zero is. */
internal const val MIDDLE_C_HZ = 261.6256f

/** How close to a degree mark a tap has to land to be taken as meaning it, in dp. */
internal const val ROOT_SNAP = 8f

/** The slider, with room beneath its bar for the degree marks. */
internal fun rootSlider(page: Rect, d: Float) =
    Rect(page.left + 14f * d, page.top + 84f * d, page.right - 14f * d, page.top + 144f * d)

internal fun rootFineLess(page: Rect, d: Float) =
    Rect(Offset(page.left + 14f * d, page.top + 160f * d), Size(72f * d, 40f * d))

internal fun rootFineMore(page: Rect, d: Float) =
    Rect(Offset(page.left + 94f * d, page.top + 160f * d), Size(72f * d, 40f * d))

/**
 * Where a scale's degrees fall across [param]'s range, in cents, and whether each is a
 * tonic. Shared by the marks drawn under a slider and the snapping on the root page, so
 * what you see and what a tap lands on cannot disagree.
 */
internal fun scaleMarks(param: Param, scale: Scale): List<Pair<Float, Boolean>> {
    // Degrees far enough either side to cover the range whatever the period is. One
    // extra turn of the scale past it, then filtered by value, so a scale that repeats
    // at a tritave is not cut short.
    val turns = (param.max / (scale.period * 1200f)).toInt() + 2
    return (-turns * scale.size..turns * scale.size).mapNotNull { degree ->
        val cents = scale.octavesOf(degree) * 1200f
        if (cents < param.min || cents > param.max) null else cents to (degree.mod(scale.size) == 0)
    }
}

/**
 * The root a tap at [x] on the slider chooses: the degree mark under the finger when one
 * is within [ROOT_SNAP], and otherwise the whole cent where it landed.
 *
 * Only a tap snaps. A drag reads the slider continuously and never calls this, because
 * snapping while sliding would make every cent between the marks unreachable -- and those
 * cents are the reason the unit is cents at all.
 */
internal fun rootAtTap(x: Float, slider: Rect, d: Float, scale: Scale): Float {
    fun xOf(cents: Float) = slider.left + slider.width * ROOT.positionOf(cents)
    val free = ROOT.valueAt((x - slider.left) / slider.width).roundToInt().toFloat()
    val nearest = scaleMarks(ROOT, scale).minByOrNull { (cents, _) -> kotlin.math.abs(xOf(cents) - x) }
        ?: return free
    return if (kotlin.math.abs(xOf(nearest.first) - x) <= ROOT_SNAP * d) nearest.first else free
}

/** A root as it is read: signed whole cents, or a tenth where a degree is not whole. */
internal fun formatCents(cents: Float): String {
    val tenths = (cents * 10f).roundToInt()
    if (tenths == 0) return "0¢"
    val sign = if (tenths > 0) "+" else "−"
    val magnitude = kotlin.math.abs(tenths)
    return if (magnitude % 10 == 0) "$sign${magnitude / 10}¢" else "$sign${magnitude / 10}.${magnitude % 10}¢"
}

private val NOTE_NAMES = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

/**
 * The nearest twelve-tone note name, as a reading and never as the unit -- marked "≈" when
 * the root is not on that grid, so a 19-TET degree does not pretend to be a letter.
 */
/**
 * The nearest twelve-tone note and its octave, from cents above middle C: "C4" at 0, "C3"
 * an octave down. The same "≈" rule as [nearestNoteName] for a pitch off that grid.
 */
internal fun noteWithOctave(cents: Float): String {
    val semitones = (cents / 100f).roundToInt()
    val name = NOTE_NAMES[semitones.mod(12)] + (4 + Math.floorDiv(semitones, 12))
    return if (kotlin.math.abs(cents - semitones * 100f) < 0.5f) name else "≈$name"
}

/** What degree [degree] sounds as, in the scale and key given: a note name to read beside a number. */
internal fun degreeName(degree: Int, scale: Scale, rootCents: Float): String =
    noteWithOctave(scale.octavesOf(degree) * 1200f + rootCents)

internal fun nearestNoteName(cents: Float): String {
    val semitones = (cents / 100f).roundToInt()
    val name = NOTE_NAMES[semitones.mod(12)]
    return if (kotlin.math.abs(cents - semitones * 100f) < 0.5f) name else "≈$name"
}

internal fun scaleCardAdd(card: Rect, d: Float) = Rect(
    Offset(card.left + Frame.SCALE_CARD_PAD * d, card.bottom - Frame.SCALE_CARD_PAD * d - 40f * d),
    Size(120f * d, 40f * d),
)

/** The picker's tiles, below its title. */
internal fun scalePickerTiles(picker: Rect, d: Float, count: Int): List<Rect> =
    tileGrid(Rect(picker.left + 10f * d, picker.top + 40f * d, picker.right - 10f * d, picker.bottom - 10f * d), d, count)

/** Which floating card is open. One at a time, because both hang from the top-left corner. */
internal enum class FloatingCard { None, Transport, Scales }

/** The scale card's view state: which entry is choosing a scale or a root, and how far the list is scrolled. */
internal class ScaleCardView {
    var pickingFor by mutableIntStateOf(-1)
    var rootFor by mutableIntStateOf(-1)
    var scroll by mutableIntStateOf(0)

    /** Whether a page -- the picker or the root -- is showing in place of the list. */
    val onPage: Boolean get() = pickingFor >= 0 || rootFor >= 0
}

/** Which of a subpatch's rails a jack inside it would get its port on. */
internal fun railTypeFor(source: PortRef): ModuleType =
    if (source.dir == PortDirection.OUTPUT) Types.SubpatchOut else Types.SubpatchIn

/** The side of the rail that jack's port appears on: the rails mirror the subpatch's box. */
internal fun railDirFor(source: PortRef): PortDirection =
    if (source.dir == PortDirection.OUTPUT) PortDirection.INPUT else PortDirection.OUTPUT

/**
 * Where a subpatch's next port would land on [rail], and so where the slot for it is drawn.
 *
 * A rail is 64dp wide and its jacks answer to a 22dp touch radius, so nearly every tap on a
 * rail lands on a jack already there -- which is why "tap the rail to add a port" could not
 * be made to happen at all on the phone. The slot is a target of its own, a whole port pitch
 * from the last jack, so it can be hit.
 */
internal fun subpatchPortSlot(frame: Frame, rail: PatchModule, dir: PortDirection): Offset {
    val count = rail.ports(dir).size
    val body = maxOf(PatchModule.MIN_BODY, (count + 1) * PatchModule.PORT_PITCH)
    return portIn(frame.railRectWith(rail, count + 1), frame.density, dir, count, count + 1, body * frame.density)
}

/** Screen position of any port, whether its module is pinned or free. */
private fun portScreen(
    patch: Patch,
    ref: PortRef,
    camera: Camera,
    frame: Frame,
): Offset? {
    val module = patch.module(ref.moduleId) ?: return null
    if (ref.dir == PortDirection.MOD) {
        if (module.isPinned || ref.index !in module.modRanges) return null
        return camera.toScreen(
            modPortIn(module.bounds, 1f, module.type, ref.index, module.portsBody),
        )
    }
    val count = module.ports(ref.dir).size
    if (ref.index >= count) return null
    return if (module.isPinned) {
        portIn(frame.railRect(module), frame.density, ref.dir, ref.index, count,
               module.portsBody * frame.density)
    } else {
        camera.toScreen(portIn(module.bounds, 1f, ref.dir, ref.index, count, module.portsBody))
    }
}

// ---------------------------------------------------------------- the composable

@Composable
fun PatchCanvas(
    patch: Patch,
    modifier: Modifier = Modifier,
    safeArea: PaddingValues = PaddingValues(),
    portTouchRadius: Dp = 24.dp,
    /** Tapping a rail's body switches it. Out opens the master output, In the mic. */
    outputActive: Boolean = false,
    onToggleOutput: () -> Unit = {},
    onToggleInput: () -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    /** Sends the transport back to bar one. */
    onResetTransport: () -> Unit = {},
    /** Whatever `.scl` files were found. Never empty; at worst just the fallback. */
    scales: List<Scale> = listOf(Scale.Chromatic),
    /** Saved subpatches on disk. Null in previews and tests, where nothing is saved or loaded. */
    library: SubpatchLibrary? = null,
    /** The tuning a loaded subpatch's scale names are resolved against. */
    scaleLibrary: ScaleLibrary = ScaleLibrary.of(null),
    /** The SoundFonts an SF panel chooses from. Null in previews and tests. */
    soundFonts: SoundFontLibrary? = null,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Safe to capture in the gesture loop's closure: it delegates to the view, like a
    // callback, rather than being a value that goes stale -- see rememberUpdatedState below.
    val haptics = LocalHapticFeedback.current
    val camera = remember(density.density) { Camera(density.density) }
    var interaction by remember { mutableStateOf<Interaction>(Interaction.Idle) }

    // Text inside the world transform has to be laid out in world units. A density of 1
    // makes `11.sp` mean 11 world units — 11dp at zoom 1.0 — and keeps the measurer's
    // cache warm, since the style never changes with zoom. Screen-space text (rails,
    // menus) uses the ordinary measurer, where the same style means the same dp.
    val fontResolver = LocalFontFamilyResolver.current
    val worldMeasurer = remember(fontResolver, layoutDirection) {
        TextMeasurer(fontResolver, Density(1f, 1f), layoutDirection)
    }
    val screenMeasurer = rememberTextMeasurer()

    val insetLeft = with(density) { safeArea.calculateStartPadding(layoutDirection).toPx() }
    val insetRight = with(density) { safeArea.calculateEndPadding(layoutDirection).toPx() }
    val insetTop = with(density) { safeArea.calculateTopPadding().toPx() }
    val insetBottom = with(density) { safeArea.calculateBottomPadding().toPx() }

    fun frameFor(canvas: Size) =
        Frame(canvas, density.density, insetLeft, insetTop, insetRight, insetBottom, density.fontScale)

    // Through rememberUpdatedState, because the gesture loop below is keyed on Unit and
    // so captures its closure exactly once. A plain val would freeze canUndo at whatever
    // it was during the first composition -- which is false -- and the buttons would
    // draw correctly (that lambda is rebuilt every recomposition) while never being
    // hittable. They did exactly that on the device.
    // The library's names, read when its menu opens rather than kept live: a file dropped
    // into the folder over USB should be there the next time you look, and nothing needs
    // the list before then.
    var savedSubpatches by remember { mutableStateOf(emptyList<String>()) }
    val libraryOpen = (interaction as? Interaction.Menu)?.library == true
    LaunchedEffect(libraryOpen, library) {
        if (libraryOpen) savedSubpatches = withContext(Dispatchers.IO) { library?.names().orEmpty() }
    }
    val io = rememberCoroutineScope()

    val controls by rememberUpdatedState(
        CanvasControls(
            canUndo, canRedo, onToggleOutput, onToggleInput, onUndo, onRedo, onResetTransport,
            saved = savedSubpatches,
            onLoadSubpatch = { name, at ->
                io.launch {
                    val text = withContext(Dispatchers.IO) { library?.read(name) }
                    // Silently nothing if the file went away or will not parse: the refusal
                    // is logged where it happened, and a half-loaded subpatch is not a thing
                    // this can leave behind -- loadSubpatch either adopts all of it or none.
                    if (text != null) patch.loadSubpatch(text, at, scaleLibrary)
                }
            },
        ),
    )

    // What the sequencer is playing, polled per frame and only while its panel is open.
    // A poll rather than a push because the audio thread cannot call into the JVM, and
    // the newest value is the only one a repaint wants -- a step missed between frames is
    // a step nobody could have seen.
    val openModule = patch.modules.firstOrNull { it.expanded }
    var playingStep by remember { mutableIntStateOf(-1) }

    // Unkeyed, then reset by an effect. A keyed remember hands back a *different*
    // MutableState when the key changes, and the gesture loop is keyed on Unit -- so it
    // would go on writing to the state object from the first composition while the draw
    // read the newest one, and the chooser would never open. Same trap as the controls
    // above, wearing a different hat.
    var intervalMenu by remember { mutableStateOf(false) }
    LaunchedEffect(openModule?.id) { intervalMenu = false }

    // An SF panel's page of instruments: whether it is open, how far it is scrolled in rows,
    // and the fonts there are to switch between -- read when the page opens, like the subpatch
    // library's names, so a file dropped in over USB is there the next time you look.
    var presetMenu by remember { mutableStateOf(false) }
    var presetScroll by remember { mutableIntStateOf(0) }
    var fontNames by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(openModule?.id) { presetMenu = false }
    LaunchedEffect(presetMenu, soundFonts) {
        if (presetMenu) fontNames = withContext(Dispatchers.IO) { soundFonts?.names().orEmpty() }
    }

    // The floating cards. Unkeyed, for the same reason as the chooser above, and left alone
    // when a panel opens or closes: they float over both, so neither of them owns them.
    var card by remember { mutableStateOf(FloatingCard.None) }
    val scaleView = remember { ScaleCardView() }
    var transportBeat by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(card) {
        if (card != FloatingCard.Transport) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            transportBeat = AudioEngine.transportBeat()
        }
    }

    // Which entry of the scale list is sounding, from the engine, so the grid cannot
    // disagree with the sound about when a switch happened. Polled only while there is a
    // list to move through; writing back an unchanged value recomposes nothing.
    var playingEntry by remember { mutableIntStateOf(0) }
    val cycling = patch.scales.size > 1
    LaunchedEffect(cycling) {
        if (!cycling) {
            playingEntry = 0
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            playingEntry = AudioEngine.scaleEntry()
        }
    }
    // What the grid's rows and the tuning marks show: the scale sounding now.
    val playing = patch.scales.getOrElse(playingEntry) { patch.scales.first() }.scale

    // Through rememberUpdatedState for the same reason `controls` is: a drone's grid is
    // shaped by the scale -- how many rows it has and how many octaves fit beside them --
    // so the hit test needs the scale sounding now, and the gesture loop below is keyed on
    // Unit. A plain read would pin every drone tap to whichever scale the patch opened in.
    val gridScale by rememberUpdatedState(playing)
    LaunchedEffect(openModule?.id, openModule?.type?.grid) {
        // A sequencer's only, of either kind. A drone has no position to report, and polling one every
        // frame for a -1 is a frame's work for nothing.
        val id = openModule?.takeIf {
            it.type.grid == GridKind.SEQUENCE || it.type.grid == GridKind.DOTS || it.type.grid == GridKind.PATTERN
        }?.id
        if (id == null) {
            playingStep = -1
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            playingStep = AudioEngine.stepOf(id)
        }
    }

    // Where each modulated parameter of the open module has got to, polled per frame for the
    // same reasons as the playing step. Only the parameters with a cable in them: an exposed
    // one with nothing patched is simply its knob, and needs nothing from the engine.
    val modulated = openModule?.let { m ->
        patch.connections
            .filter { it.to.moduleId == m.id && it.to.dir == PortDirection.MOD }
            .map { it.to.index }
            .sorted()
    }.orEmpty()
    var liveParams by remember { mutableStateOf(emptyMap<Int, Float>()) }
    LaunchedEffect(openModule?.id, modulated) {
        val id = openModule?.id
        if (id == null || modulated.isEmpty()) {
            liveParams = emptyMap()
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            liveParams = modulated.mapNotNull { i -> AudioEngine.paramOf(id, i)?.let { i to it } }.toMap()
        }
    }

    // The pulse that says what an undo just touched. Snapped to full and faded out
    // rather than eased both ways: the onset should be simultaneous with the sound
    // changing, and an attack ramp would put it late.
    val flash = patch.flash
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(flash.serial) {
        if (flash.ids.isEmpty()) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0f, tween(FLASH_MS, easing = LinearEasing))
    }

    // Start the view clear of the cutout, the gesture bar and the left rail. Re-applies
    // while the user has not moved the camera, so a rotation still lands well.
    LaunchedEffect(insetLeft, insetTop, camera.userMoved) {
        if (!camera.userMoved) {
            val gutter = (PatchModule.RAIL_WIDTH + Frame.RAIL_MARGIN * 2f) * density.density
            camera.frameAt(Offset(insetLeft + gutter, insetTop))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    val longPressMs =
                        (viewConfiguration.longPressTimeoutMillis * LONG_PRESS_SCALE).toLong()
                    val touchPx = portTouchRadius.toPx()

                    awaitEachGesture {
                        val frame = frameFor(Size(size.width.toFloat(), size.height.toFloat()))
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // The transport floats over the graph and the panel alike, so it is
                        // asked before either. Only its own chip and, while open, its own
                        // card: a touch anywhere else carries on to whatever is underneath,
                        // which is what keeps it from being modal. An open context menu keeps
                        // its tiles, since it is the thing that was just asked for.
                        if (interaction !is Interaction.Menu) {
                            if (frame.transportChip().contains(down.position)) {
                                waitForUpRelease()
                                card = if (card == FloatingCard.Transport) FloatingCard.None
                                    else FloatingCard.Transport
                                return@awaitEachGesture
                            }
                            if (frame.scaleChip().contains(down.position)) {
                                waitForUpRelease()
                                card = if (card == FloatingCard.Scales) FloatingCard.None
                                    else FloatingCard.Scales
                                scaleView.pickingFor = -1
                                scaleView.rootFor = -1
                                return@awaitEachGesture
                            }
                            if (card == FloatingCard.Transport &&
                                frame.transportCard().contains(down.position)
                            ) {
                                transportCardGesture(
                                    frame, down.position, patch, controls.onResetTransport,
                                ) { interaction = Interaction.Typing(NumberTarget.Tempo) }
                                return@awaitEachGesture
                            }
                            val scaleArea = if (scaleView.onPage) frame.scalePicker()
                                else frame.scaleCard(patch.scales.size)
                            if (card == FloatingCard.Scales && scaleArea.contains(down.position)) {
                                scaleCardGesture(frame, down.position, patch, scales, scaleView, slop)
                                return@awaitEachGesture
                            }
                        }

                        // An open panel owns the screen. Pan, zoom and patching all belong to
                        // the canvas behind it, so this is a separate and much simpler loop
                        // rather than another outcome bolted into the one below.
                        val open = patch.modules.firstOrNull { it.expanded }
                        if (open != null) {
                            val panel = panelRect(frame)
                            // Checked before the knobs, because the buttons float over the
                            // panel and overhang its bottom edge -- where a tap would
                            // otherwise be read as tapping away to close.
                            val onHistory = controls.overHistory(frame, down.position)

                            // An SF's page of instruments owns the panel while it is open, as
                            // the interval chooser does below -- but it scrolls, so a drag is
                            // read before anything is chosen, and only a tap chooses.
                            if (open.type == Types.Sf && !onHistory) {
                                val d = frame.density
                                val fontName = open.font
                                val loadedFont = fontName?.let { soundFonts?.loaded?.get(it) }
                                val presets = loadedFont?.presets.orEmpty()
                                val page = presetPage(panel, d, fontNames.size, frame.fontScale)
                                if (presetMenu) {
                                    val code = open.params[SF_PRESET].roundToInt()
                                    val scrollFrom = page.resolve(presetScroll, presets, code)
                                    var moved = false
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.pressed } ?: break
                                        if ((change.position - down.position).getDistance() > slop) moved = true
                                        if (moved) {
                                            // Up the page is on through the list, like any list.
                                            val rows = ((change.position.y - down.position.y) / page.tileH).roundToInt()
                                            presetScroll = (scrollFrom - rows).coerceIn(0, page.maxScroll(presets.size))
                                        }
                                        change.consume()
                                    }
                                    if (!moved) {
                                        val font = page.fonts.indexOfFirst { it.contains(down.position) }
                                        if (font >= 0) {
                                            // Another bank, and the page stays open on it: the
                                            // instrument is chosen next, from its list.
                                            open.font = fontNames[font]
                                            // Where the chosen instrument is in this bank,
                                            // once its list has loaded.
                                            presetScroll = SCROLL_TO_CHOSEN
                                        } else {
                                            page.tiles(presets.size, scrollFrom)
                                                .firstOrNull { it.second.contains(down.position) }
                                                ?.let { (i, _) -> open.setParam(SF_PRESET, presets[i].code.toFloat()) }
                                            presetMenu = false
                                        }
                                    }
                                    return@awaitEachGesture
                                }
                                if (panelPresetChip(panel, d).contains(down.position)) {
                                    waitForUpRelease()
                                    presetScroll = SCROLL_TO_CHOSEN
                                    presetMenu = true
                                    return@awaitEachGesture
                                }
                            }

                            // The interval chooser owns the panel while it is open: nothing
                            // behind it is reachable, so a stray tap picks nothing and changes
                            // no knob. Anywhere dismisses it, including the chip itself.
                            val intervalParam = open.type.intervalParam
                            if (intervalParam >= 0 && intervalMenu) {
                                val tiles = panelTiles(panel, frame.density, INTERVALS.size)
                                waitForUpRelease()
                                val hit = tiles.indexOfFirst { it.contains(down.position) }
                                if (hit >= 0) open.setParam(intervalParam, hit.toFloat())
                                intervalMenu = false
                                return@awaitEachGesture
                            }

                            if (intervalParam >= 0 &&
                                panelIntervalChip(panel, frame.density).contains(down.position)
                            ) {
                                waitForUpRelease()
                                intervalMenu = true
                                return@awaitEachGesture
                            }

                            // The lock, beside it. A tap-only target like the chips, and the
                            // one thing on this panel that changes what a drag means rather
                            // than changing the patch -- so it sends the engine nothing.
                            if (open.type.grid == GridKind.DOTS &&
                                panelLockChip(panel, frame.density).contains(down.position)
                            ) {
                                waitForUpRelease()
                                open.dotsLocked = !open.dotsLocked
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                return@awaitEachGesture
                            }
                            // The reading before anything under it. It is a tap-only target,
                            // like the chips: a number is typed, never dragged, and the bar
                            // for dragging is in the same row a finger's width below.
                            // The rows this panel shows: its own knobs, or -- for a subpatch --
                            // the ones promoted to its edge, which belong to modules inside it.
                            val rows = patch.panelRows(open)
                            val typed = if (onHistory) null else panelValueAt(
                                panel, frame.density, open, rows, down.position,
                            ) { screenMeasurer.measure(it, PanelValueStyle).size.width.toFloat() }
                            if (typed != null) {
                                waitForUpRelease()
                                interaction = Interaction.Typing(
                                    NumberTarget.Knob(typed.first.owner.id, typed.first.index, typed.second),
                                )
                                return@awaitEachGesture
                            }

                            // The chips before the rows beside them. Only a chip itself counts,
                            // so a near miss on a knob never gives anything a jack, and the one
                            // that promotes is reached generously: five rows leave it 21dp.
                            val chipRow = if (onHistory) null else rows.withIndex().firstOrNull { (slot, r) ->
                                r.owner.id == open.id && open.canExpose(r.index) &&
                                    panelModChipOn(
                                        panelRowAt(panel, frame.density, open.type, rows.size, slot),
                                        frame.density,
                                    ).contains(down.position)
                            }
                            if (chipRow != null) {
                                waitForUpRelease()
                                val index = chipRow.value.index
                                if (index in open.modRanges) {
                                    patch.unexpose(open, index)
                                } else {
                                    val param = open.type.params[index]
                                    patch.expose(open, index, initialModRange(param, open.params[index]))
                                }
                                return@awaitEachGesture
                            }

                            val promoteRow = if (onHistory) null else rows.withIndex().firstOrNull { (slot, r) ->
                                patch.canPromote(r.owner, r.index) &&
                                    panelPromoteChipOn(
                                        panelRowAt(panel, frame.density, open.type, rows.size, slot),
                                        frame.density,
                                    ).inflate(6f * frame.density).contains(down.position)
                            }
                            if (promoteRow != null) {
                                waitForUpRelease()
                                val row = promoteRow.value
                                if (!patch.unpromote(row.owner, row.index)) patch.promote(row.owner, row.index)
                                return@awaitEachGesture
                            }

                            // A bracket before the knob it sits on: dragging `[` or `]` moves that
                            // end of the range, and leaves the knob where it is.
                            val bracket =
                                if (onHistory) null
                                else panelBracketAt(panel, frame.density, open, rows, down.position)
                            val knob =
                                if (onHistory || bracket != null) null
                                else panelKnobAt(panel, frame.density, open, rows, down.position)
                            // The bar each is measured along: its own row, which with two columns
                            // is half the panel.
                            val barOf = { entry: ParamRow ->
                                panelRowAt(panel, frame.density, open.type, rows.size, rows.indexOf(entry))
                            }
                            val knobBar = knob?.let(barOf)
                            val bracketBar = bracket?.first?.let(barOf)
                            val cell =
                                if (onHistory || knob != null) null
                                else panelCellAt(panel, frame.density, open, down.position, gridScale)

                            // Scrolling the grid is measured from where the drag began and
                            // in whole rows, so a slow drag moves the same distance as a fast
                            // one and never lands between two degrees.
                            // The height a row actually got, not GRID_ROW: rows divide the
                            // area evenly once their count is fixed, so the two differ by
                            // the remainder and a drag measured against the nominal value
                            // slides against the grid it is supposed to be moving.
                            val gridArea = panelGrid(panel, frame.density, open.type)

                            // Where this panel's editor -- whichever kind it is -- is allowed to
                            // claim a touch.
                            //
                            // One gate for all of them, and it is not tidiness. Each editor below
                            // ends in an unconditional `return@awaitEachGesture`, so an editor
                            // reached without a bounds check claims the entire screen: the
                            // envelope's did, for one build, and swallowed the tap *outside* the
                            // panel that is the only way to close one. The breadcrumb is hidden
                            // while a panel is open, so there was no other door. **An editor that
                            // returns unconditionally has to earn the gesture first**, and having
                            // one name for "earned it" is what stops the next editor forgetting.
                            //
                            // The tap-only targets above do not need this: each is a chip with a
                            // rect of its own and none of them return without hitting it. What is
                            // dangerous is a loop that claims.
                            val inEditor = open.type.grid != GridKind.NONE &&
                                gridArea.contains(down.position)

                            // An envelope's editor, which is not a grid of cells and has its
                            // own loop for that reason -- as the dot grid does below.
                            //
                            // Three targets, never a mode: the shape in the middle, a sustain
                            // rail above it and a rail of times below. A node carries a time
                            // and a level and a drag moves both; hanging the sustain and the
                            // keypad on that same node as well is what makes an envelope
                            // editor unusable with a finger, so they are elsewhere entirely.
                            if (open.type.grid == GridKind.ENVELOPE && !onHistory && knob == null &&
                                inEditor
                            ) {
                                val d = frame.density
                                val count = open.segments.size

                                val sustainCell =
                                    envCellAt(envSustainRail(gridArea, d), count, down.position)
                                if (sustainCell >= 0) {
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("PatchGesture", "sustain rail cell $sustainCell")
                                    }
                                    waitForUpRelease()
                                    open.setSustain(sustainCell)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    return@awaitEachGesture
                                }
                                val timeCell =
                                    envCellAt(envTimeRail(gridArea, d), count, down.position)
                                if (timeCell >= 0) {
                                    if (BuildConfig.DEBUG) {
                                        android.util.Log.d("PatchGesture", "time rail cell $timeCell")
                                    }
                                    waitForUpRelease()
                                    interaction = Interaction.Typing(
                                        NumberTarget.SegmentTime(open.id, timeCell),
                                    )
                                    return@awaitEachGesture
                                }

                                val geo = envGeometry(gridArea, open, d)
                                val node = envNodeAt(geo, open, down.position, d)
                                // A node wins over the line it sits on, so the two never
                                // compete for the same finger.
                                val segment =
                                    if (node >= 0) -1 else envSegmentAt(geo, open, down.position)
                                val grabbed = open.segments.getOrNull(if (node >= 0) node else segment)
                                if (BuildConfig.DEBUG) {
                                    android.util.Log.d(
                                        "PatchGesture",
                                        traceEnvTouch(open, geo, down.position, d, node, segment),
                                    )
                                }
                                // Which way this segment travels, read once: only its curve
                                // changes under the drag, so where it starts from cannot move.
                                val segmentRises = grabbed != null && segment >= 0 &&
                                    grabbed.level >= envFrom(open, segment)
                                var envMoved = false
                                var lifted = false
                                var removed = false

                                // The decide phase, under a long-press timer, as the canvas
                                // loop does it -- the timer wraps this phase rather than
                                // sitting beside it as a second detector.
                                //
                                // Removing a node was a *tap* for one build and it made the
                                // whole editor feel unreliable: a tap is what a finger does
                                // when it means to grab something, so segments vanished while
                                // people were trying to drag them, and a removed node costs
                                // its time and its curve where a removed dot costs one tap to
                                // put back. Deliberate gesture, deliberate loss.
                                try {
                                    withTimeout(longPressMs) {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.pressed }
                                            if (change == null) {
                                                lifted = true
                                                break
                                            }
                                            if ((change.position - down.position).getDistance() > slop) {
                                                envMoved = true
                                                break
                                            }
                                            change.consume()
                                        }
                                    }
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    // Compose's own, not kotlinx's TimeoutCancellationException:
                                    // AwaitPointerEventScope overrides withTimeout and throws
                                    // PointerEventTimeoutCancellationException. Catching the
                                    // wrong one compiles, never matches, and lets the exception
                                    // end the gesture -- so the long press did nothing at all
                                    // and said nothing about why. The canvas loop above has
                                    // always caught the right one; this is why.
                                    //
                                    // Held still on a node: take it away. Held still anywhere
                                    // else falls through, so a slow drag on a line still bends it.
                                    if (node >= 0 && open.removeSegment(node)) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        removed = true
                                    }
                                }
                                if (removed) {
                                    waitForUpRelease()
                                    return@awaitEachGesture
                                }
                                while (!lifted) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.pressed } ?: break
                                    val travel = change.position - down.position
                                    if (!envMoved && travel.getDistance() > slop) envMoved = true
                                    if (envMoved && grabbed != null && node >= 0) {
                                        // Both axes at once, unlike a dot, which locks to one:
                                        // a dot is a cell in a grid and a node is a point, and
                                        // carrying a point to a new time almost always wants a
                                        // new level with it.
                                        open.setSegment(
                                            node,
                                            grabbed.copy(
                                                time = grabbed.time + geo.timeDelta(travel.x),
                                                level = grabbed.level + geo.levelDelta(travel.y),
                                            ),
                                        )
                                    } else if (envMoved && grabbed != null && segment >= 0) {
                                        open.setSegment(
                                            segment,
                                            grabbed.copy(
                                                curve = envCurveAfterDrag(
                                                    grabbed.curve, travel.y, segmentRises, d,
                                                ),
                                            ),
                                        )
                                    }
                                    change.consume()
                                }
                                if (BuildConfig.DEBUG) {
                                    val what = when {
                                        !envMoved -> "TAP"
                                        node >= 0 -> "moved NODE $node"
                                        segment >= 0 ->
                                            "bent SEGMENT $segment to " +
                                                "${open.segments.getOrNull(segment)?.curve}"
                                        else -> "drag hit NOTHING"
                                    }
                                    android.util.Log.d("PatchGesture", "  ...$what")
                                }
                                // A tap adds a node, and unlike a drag it has to be pointing
                                // at the line: a drag owns the whole column because bending is
                                // an adjustment, where adding a node changes what the envelope
                                // is made of. A tap on a node itself does nothing at all --
                                // the only destructive thing in here costs a deliberate hold.
                                if (!envMoved && node < 0 && segment >= 0 &&
                                    envOnCurve(geo, open, segment, down.position, d)
                                ) {
                                    val times = open.segmentTimes
                                    val left = geo.x(if (segment == 0) 0f else times[segment - 1])
                                    val right = geo.x(times[segment])
                                    val at = if (right > left) {
                                        (down.position.x - left) / (right - left)
                                    } else {
                                        0.5f
                                    }
                                    open.splitSegment(segment, at)
                                }
                                return@awaitEachGesture
                            }

                            // Taken from the window rather than worked out again here: a drone
                            // shows fewer rows than fit when its scale is short, and a drag
                            // measured against the rows that would fit slid against the grid.
                            val window = gridWindow(open, gridArea, frame.density, gridScale)
                            val rowHeight = gridArea.height / window.rows
                            var moved = false

                            // A dot sequencer's grid: a drag that starts on a dot either
                            // stretches it or takes it somewhere, one that starts on an empty
                            // cell scrolls, and a tap adds a dot or takes one away. Its own
                            // loop, since none of it is a Steps cell's toggle.
                            //
                            // Which of the two a drag on a dot is gets decided once, on the
                            // first move, from the direction it went -- the same way the
                            // canvas loop decides what a gesture is, and for the same reason:
                            // a drag that keeps changing its mind halfway is unusable. Across
                            // is the length, since a length is a distance along the grid;
                            // down the grid is the degree, since that is what the rows are.
                            // With the dots locked there is no position to change, so a
                            // vertical drag sets how hard the note is struck instead.
                            if (cell != null && inEditor && open.type.grid == GridKind.DOTS) {
                                val (column, degree) = cell
                                val hit = open.dotAt(column, degree)
                                val columns = dotColumns(open)
                                val startVelocity = open.dots.getOrNull(hit)?.velocity ?: 1f
                                // Where in the dot the finger landed, so a long one carried
                                // by its third step does not jump to put its start under the
                                // finger. It is held by the part that was grabbed.
                                val grabbed = column - (open.dots.getOrNull(hit)?.step ?: column)
                                var lengthwise = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.pressed } ?: break
                                    val travel = change.position - down.position
                                    if (!moved && travel.getDistance() > slop) {
                                        moved = true
                                        lengthwise = abs(travel.x) >= abs(travel.y)
                                    }
                                    if (moved && hit >= 0 && lengthwise) {
                                        // In quarter steps, so a drag can end a note partway
                                        // through a cell -- which is the whole of what the
                                        // retired gate knob did, said per note.
                                        val under = dotSubstepAt(gridArea, columns, change.position.x)
                                        val dot = open.dots[hit]
                                        val from = dot.step * DOT_SUBSTEPS
                                        open.setDotLength(hit, (under - from + 1).coerceIn(1, open.dotRoom(hit)))
                                    } else if (moved && hit >= 0 && open.dotsLocked) {
                                        // Relative to where the dot already was, so a pass
                                        // over a phrase never jumps to wherever the finger
                                        // happens to have landed. Up is louder.
                                        open.setDotVelocity(
                                            hit,
                                            startVelocity - travel.y / (VELOCITY_TRAVEL * frame.density),
                                        )
                                    } else if (moved && hit >= 0) {
                                        // Both axes once it is moving: the drag was vertical
                                        // to begin with, but a dot being carried to another
                                        // degree usually wants a different step too.
                                        panelCellAt(
                                            panel, frame.density, open, change.position, gridScale,
                                        )?.let { (toColumn, toDegree) ->
                                            open.moveDot(hit, toColumn - grabbed, toDegree)
                                        }
                                    } else if (moved) {
                                        val rows = (change.position.y - down.position.y) / rowHeight
                                        open.gridBottom = window.scrolledBy(rows.roundToInt())
                                    }
                                    change.consume()
                                }
                                if (!moved) {
                                    if (hit >= 0) open.removeDot(hit) else open.addDot(Dot(column, degree))
                                }
                                return@awaitEachGesture
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                val change = pressed.first()
                                if ((change.position - down.position).getDistance() > slop) {
                                    moved = true
                                }
                                if (bracket != null) {
                                    patch.moveBracket(bracket.first, bracketBar!!, bracket.second, change.position.x)
                                } else if (knob != null) {
                                    knob.owner.setParam(
                                        knob.index,
                                        knob.param.valueAt(
                                            panelKnobPosition(knobBar!!, change.position.x),
                                        ),
                                    )
                                } else if (cell != null) {
                                    // Down the screen is down in pitch, so dragging the grid
                                    // downward brings higher degrees into view.
                                    val rows = ((change.position.y - down.position.y) / rowHeight)
                                    // From where the view stood, never from a stored overshoot,
                                    // and never past either end: dragging back must move the
                                    // grid at once, and a scale change must not re-clamp a
                                    // position nobody could see.
                                    open.gridBottom = window.scrolledBy(rows.roundToInt())
                                }
                                change.consume()
                            }

                            if (!moved) {
                                if (onHistory) {
                                    controls.tapHistory(frame, down.position)
                                } else if (bracket != null) {
                                    patch.moveBracket(bracket.first, bracketBar!!, bracket.second, down.position.x)
                                } else if (knob != null) {
                                    // A tap on a knob jumps there, which is faster than
                                    // dragging when you already know where you want it.
                                    knob.owner.setParam(
                                        knob.index,
                                        knob.param.valueAt(
                                            panelKnobPosition(knobBar!!, down.position.x),
                                        ),
                                    )
                                } else if (cell != null) {
                                    val (column, degree) = cell
                                    val step = open.steps[column]
                                    // Tapping the note that is already there mutes it rather
                                    // than clearing the cell: a rest still holds its pitch,
                                    // and tapping again brings it back without having to
                                    // remember what it was.
                                    open.setStep(
                                        column,
                                        if (step.degree == degree && step.on) step.copy(on = false)
                                        else Step(degree, on = true),
                                    )
                                } else if (!panel.contains(down.position)) {
                                    // The border is the way out. Tapping the panel itself does
                                    // nothing, so a missed knob never closes what you are
                                    // working on.
                                    open.expanded = false
                                }
                            }
                            return@awaitEachGesture
                        }

                        var kind = GestureKind.Undecided
                        var draggedModule: PatchModule? = null
                        var grabOffset = Offset.Zero
                        val startPan = camera.pan
                        var lastTwoFinger: TwoFinger? = null

                        // Only a free module can be dragged; rails are welded to the edge.
                        val hitModule = patch.hitModule(camera, frame, down.position)
                        val draggable = hitModule?.takeIf { !it.isPinned }

                        // ---- decide phase, under a long-press timer.
                        //
                        // The timer wraps only this phase rather than sitting alongside as a
                        // second detector: stacking detectors is the thing thesis #3 exists
                        // to avoid, and two of them would both consume this pointer.
                        try {
                            withTimeout(longPressMs) {
                                while (kind == GestureKind.Undecided) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }

                                    if (pressed.isEmpty()) {
                                        kind = GestureKind.Tap
                                    } else if (pressed.size >= 2) {
                                        val a = pressed[0].position
                                        val b = pressed[1].position
                                        lastTwoFinger = TwoFinger(
                                            centroid = (a + b) / 2f,
                                            spread = (a - b).getDistance(),
                                        )
                                        pressed.forEach { it.consume() }
                                        kind = GestureKind.Transform
                                    } else {
                                        val change = pressed.first()
                                        if ((change.position - down.position).getDistance() > slop) {
                                            kind = if (draggable != null && interaction is Interaction.Idle) {
                                                draggedModule = draggable
                                                grabOffset =
                                                    camera.toWorld(down.position) - draggable.position
                                                GestureKind.MoveModule
                                            } else {
                                                GestureKind.Pan
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            kind = GestureKind.LongPress
                        }

                        when (kind) {
                            GestureKind.Tap -> {
                                interaction = handleTap(
                                    patch, camera, frame, interaction, down.position, touchPx,
                                    controls,
                                )
                                return@awaitEachGesture
                            }
                            GestureKind.LongPress -> {
                                // Holding a history button is not a request for the add menu;
                                // it is a finger resting on a button. Nothing happens.
                                val onButton = controls.overHistory(frame, down.position)
                                val crumb = patch.breadcrumbAt(frame, down.position)
                                val heldPort = patch.hitPort(camera, frame, down.position, touchPx)
                                // A rail offers nothing to delete, so it opens no menu -- but
                                // it does have knobs, and tapping it is already its switch, so
                                // holding is the way in to its panel.
                                interaction = if (onButton) {
                                    Interaction.Idle
                                } else if (interaction is Interaction.Selecting) {
                                    // Choosing is taps; a finger that rests does not end it.
                                    interaction
                                } else if (heldPort != null && patch.subpatchPortAt(heldPort) != null) {
                                    // A subpatch's jack, from either side. Precise rather than
                                    // anywhere on the box, since the box's own menu is what
                                    // a press anywhere else on it means.
                                    Interaction.Menu(down.position, hitModule?.id, heldPort)
                                } else if (crumb != null) {
                                    // Holding a crumb renames what it names -- the subpatch you are
                                    // inside, whose box is a level up and not on screen, or at
                                    // the top of the path the patch itself.
                                    Interaction.Renaming(crumb)
                                } else if (hitModule != null && hitModule.isPinned) {
                                    if (hitModule.type.hasPanel) {
                                        patch.modules.forEach { it.expanded = false }
                                        hitModule.expanded = true
                                    }
                                    Interaction.Idle
                                } else {
                                    Interaction.Menu(down.position, hitModule?.id)
                                }
                                // Swallow the rest of the gesture so the release is not a tap.
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.none { it.pressed }) break
                                }
                                return@awaitEachGesture
                            }
                            else -> Unit
                        }

                        // ---- committed to a drag, a pan or a pinch
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            if (pressed.size >= 2) {
                                kind = GestureKind.Transform
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val now = TwoFinger(
                                    centroid = (a + b) / 2f,
                                    spread = (a - b).getDistance(),
                                )
                                lastTwoFinger?.let { prev ->
                                    if (prev.spread > 0f) {
                                        camera.zoomAround(now.centroid, now.spread / prev.spread)
                                    }
                                    camera.panBy(now.centroid - prev.centroid)
                                }
                                lastTwoFinger = now
                                pressed.forEach { it.consume() }
                                continue
                            }

                            lastTwoFinger = null
                            val change = pressed.first()
                            when (kind) {
                                GestureKind.MoveModule -> {
                                    draggedModule?.position =
                                        camera.toWorld(change.position) - grabOffset
                                    change.consume()
                                }
                                GestureKind.Pan -> {
                                    camera.panTo(startPan + (change.position - down.position))
                                    change.consume()
                                }
                                else -> Unit
                            }
                        }
                    }
                }
        ) {
            val frame = frameFor(size)
            val d = density.density
            val touchPx = portTouchRadius.toPx()

            drawRect(Color(0xFF14171C))

            withTransform({
                translate(camera.pan.x, camera.pan.y)
                scale(camera.worldToScreen, camera.worldToScreen, pivot = Offset.Zero)
            }) {
                val selecting = (interaction as? Interaction.Selecting)?.ids.orEmpty()
                patch.shownFree.forEach { module ->
                    drawModuleBox(
                        module = module,
                        rect = module.bounds,
                        unit = 1f,
                        // Dividing by zoom alone leaves a constant width in dp, which is
                        // what "1.5dp of line" should mean at any zoom.
                        strokeWidth = 1.5f / camera.zoom,
                        armed = (interaction as? Interaction.Connecting)?.source,
                        measurer = worldMeasurer,
                        showTitle = camera.zoom >= Camera.TITLE_ZOOM,
                        showLabels = camera.zoom >= Camera.LABEL_ZOOM,
                        alpha = 1f,
                        stacked = module.type.stacked,
                    )
                    if (module.id in flash.ids && pulse.value > 0f) {
                        drawFlash(module.bounds, 1f, pulse.value, 3f / camera.zoom)
                    }
                    if (module.id in selecting) {
                        drawRoundRect(
                            color = SelectedColor,
                            topLeft = module.bounds.topLeft,
                            size = module.bounds.size,
                            cornerRadius = CornerRadius(PatchModule.CORNER, PatchModule.CORNER),
                            style = Stroke(width = 3f / camera.zoom),
                        )
                    }
                }
            }

            patch.shownRails.forEach { rail ->
                // Both rails dim when they are not passing anything, so "this is a switch and
                // it is off" reads the same way on each. A correctly patched canvas that made
                // no sound, with nothing on screen saying why, was the single most confusing
                // thing about using this.
                val live = when (rail.id) {
                    IN_ID -> patch.inputEnabled
                    OUT_ID -> outputActive
                    else -> true
                }
                // A subpatch's rails are not switches, so they get neither the dimming nor the
                // switched-on outline -- the outline would say "this is live, tap to turn it
                // off" about something with no off.
                val switch = rail.id == IN_ID || rail.id == OUT_ID
                drawModuleBox(
                    module = rail,
                    rect = frame.railRect(rail),
                    unit = d,
                    strokeWidth = 1.5f * d,
                    armed = (interaction as? Interaction.Connecting)?.source,
                    measurer = screenMeasurer,
                    showTitle = true,
                    showLabels = true,
                    alpha = if (live) 1f else 0.38f,
                    // "Instance in" inside a poly subpatch, where what you are looking at is
                    // one copy of several and the notes on this rail are this copy's share.
                    title = patch.module(rail.parent)
                        ?.let { Types.railName(it.type, rail.type) } ?: rail.title,
                    // A poly subpatch's rails stack with it: what you are looking at in
                    // there is one instance of several.
                    stacked = patch.module(rail.parent)?.type?.stacked == true,
                )
                // After the box, not before: drawModuleBox fills opaquely, so a highlight
                // drawn underneath is painted straight over and never appears.
                //
                // Both rails get the same weight of outline when switched on, because they
                // are the same kind of control and reading as different ones was confusing.
                // In is red: a live microphone is a record light everywhere else, and the
                // one rail that can embarrass you should be the one that looks urgent.
                if (live && switch) {
                    val r = frame.railRect(rail)
                    drawRoundRect(
                        color = if (rail.id == IN_ID) RecordRed else rail.type.accent,
                        topLeft = r.topLeft,
                        size = r.size,
                        cornerRadius = CornerRadius(PatchModule.CORNER * d, PatchModule.CORNER * d),
                        style = Stroke(width = 2.5f * d),
                    )
                }
                if (rail.id in flash.ids && pulse.value > 0f) {
                    drawFlash(frame.railRect(rail), d, pulse.value, 3f * d)
                }
            }

            // Cables over the modules rather than under them, and a little translucent, so a cable
            // crossing a module stays visible and the module still shows through it. Drawn under,
            // a cable passing behind a box simply vanished there, and which of two jacks it came
            // out at was a guess. Routing around the boxes was the other candidate and was not
            // tried first: a route flips sides as a module is dragged across it, and it costs a
            // path search per cable per frame.
            //
            // In screen space, because a cable can run from a world module to a rail and so have
            // one endpoint in each space. Resolving both through portScreen() keeps that a non-case.
            patch.connections.forEach { conn ->
                // Only a cable with both ends in this scope. A cable into a subpatch ends at the
                // subpatch's box out here and starts again at its rail inside; either half alone is
                // the whole of what can be seen from where you are.
                if (!patch.shown(conn.from.moduleId) || !patch.shown(conn.to.moduleId)) return@forEach
                val a = portScreen(patch, conn.from, camera, frame) ?: return@forEach
                val b = portScreen(patch, conn.to, camera, frame) ?: return@forEach
                val dim = !patch.portUsable(conn.from) || !patch.portUsable(conn.to)
                // Colored by what the source emits, not what the destination expects --
                // the two may legitimately differ, and the cable should say what is actually
                // traveling down it.
                val color = patch.kindOf(conn.from).cable.copy(alpha = if (dim) 0.3f else CABLE_ALPHA)
                drawCable(a, b, color, 2.5f * d, intoBottom = conn.to.dir == PortDirection.MOD)
                // A plug at each end, in the cable's color. Drawn over, the stroke would cover the
                // jack's own dot; this puts one back, and says the jack is taken, as a patched jack
                // on the open panel already does.
                for ((ref, at) in listOf(conn.from to a, conn.to to b)) {
                    val pinned = patch.module(ref.moduleId)?.isPinned == true
                    val scale = if (pinned) d else camera.worldToScreen
                    drawCircle(color.copy(alpha = if (dim) 0.3f else 1f), PatchModule.PORT_RADIUS * scale, at)
                }
            }

            // Halo on the armed port, drawn unscaled so it always reads as a real target.
            (interaction as? Interaction.Connecting)?.let { state ->
                portScreen(patch, state.source, camera, frame)?.let { at ->
                    drawCircle(
                        color = Color(0xFF7FD1C1).copy(alpha = 0.28f),
                        radius = effectiveTouchRadius(camera, touchPx),
                        center = at,
                    )
                }
                // The empty slot after a subpatch rail's last jack: where a port for the armed
                // jack would go, and the only way to ask for one. Drawn only while something is
                // armed, so a rail at rest is what it has and no more.
                if (patch.scopeOrTop != TOP && patch.module(state.source.moduleId)?.parent == patch.scopeOrTop) {
                    patch.subpatchRail(patch.scopeOrTop, railTypeFor(state.source))?.let { rail ->
                        val slot = subpatchPortSlot(frame, rail, railDirFor(state.source))
                        val kind = patch.kindOf(state.source)
                        drawCircle(kind.cable.copy(alpha = 0.22f), touchPx, slot)
                        drawCircle(
                            color = kind.cable,
                            radius = PatchModule.PORT_RADIUS * d,
                            center = slot,
                            style = Stroke(width = 2f * d),
                        )
                        val arm = PatchModule.PORT_RADIUS * 0.6f * d
                        drawLine(kind.cable, Offset(slot.x - arm, slot.y), Offset(slot.x + arm, slot.y), 2f * d)
                        drawLine(kind.cable, Offset(slot.x, slot.y - arm), Offset(slot.x, slot.y + arm), 2f * d)
                    }
                }
            }

            patch.modules.firstOrNull { it.expanded }?.let { open ->
                val sfView = if (open.type == Types.Sf) {
                    val name = open.font
                    SfView(
                        menu = presetMenu, scroll = presetScroll, fontName = name,
                        font = name?.let { soundFonts?.loaded?.get(it) },
                        failed = name != null && soundFonts?.failed(name) == true,
                        fonts = fontNames, folder = soundFonts?.folder().orEmpty(),
                        fontScale = frame.fontScale,
                    )
                } else {
                    null
                }
                drawPanel(
                    open, patch, panelRect(frame), d, screenMeasurer, playing, playingStep,
                    intervalMenu, liveParams, sfView,
                    patch.scales.getOrElse(playingEntry) { patch.scales.first() }.rootCents,
                )
            }

            // After the panel, so they float over it rather than being buried by it. Undo is
            // most wanted from inside a panel, where the knob you just moved is on screen
            // and can be watched moving back; having to close the panel, undo blind and
            // reopen to see what happened is the opposite of that.
            //
            // Hidden rather than grayed when there is nothing to undo: a disabled control
            // promises something could happen here, and at the start of a session nothing
            // could. The panel's knob rows are inset by PANEL_SIDE, so the corner these sit
            // in covers no control of the panel's own.
            if (canUndo) drawHistoryButton(frame.historyRect(false), d, redo = false)
            if (canRedo) drawHistoryButton(frame.historyRect(true), d, redo = true)

            // Over the panel for the same reason as the buttons, and before the context menu,
            // which is transient and should cover everything while it is up.
            drawTransport(frame, d, patch, card == FloatingCard.Transport, transportBeat, screenMeasurer)
            // Not over an open panel: its header is where the chips would land, the "Osc" of
            // an Osc panel was the thing they covered on the emulator, and a panel's taps go to
            // its own loop, so the breadcrumb would be a picture of a control that did nothing.
            if (patch.modules.none { it.expanded }) {
                val path = patch.scopePath()
                path.forEachIndexed { level, id ->
                    drawChip(
                        frame.breadcrumbChip(level), d,
                        if (id == TOP) patch.title else patch.module(id)?.title ?: "Subpatch",
                        open = id == path.last(),
                        accent = Types.Subpatch.accent,
                        measurer = screenMeasurer,
                    )
                }
            }
            (interaction as? Interaction.Selecting)?.let { choosing ->
                val count = choosing.ids.size
                drawChip(
                    frame.selectionButton(done = true), d,
                    // "Subpatch \u00d73", not "Subpatch 3": subpatches are named "Subpatch 1",
                    // "Subpatch 2", so a button reading "Subpatch 1" over a selection of one
                    // looked like the name of the subpatch it was about to make.
                    if (count == 0) "Tap modules" else "${choosing.type.name} \u00d7$count",
                    open = count > 0,
                    accent = choosing.type.accent,
                    measurer = screenMeasurer,
                )
                drawChip(frame.selectionButton(done = false), d, "Cancel", false, choosing.type.accent, screenMeasurer)
            }
            drawScales(
                frame, d, patch, scales, playingEntry, card == FloatingCard.Scales, scaleView,
                screenMeasurer,
            )

            (interaction as? Interaction.Menu)?.let { menu ->
                drawMenu(
                menuLayout(
                    menuItems(patch, menu.targetId, menu.port, savedSubpatches.takeIf { menu.library }),
                    menu.anchor, d, size, frame.fontScale,
                ),
                d, screenMeasurer,
            )
            }
        }

        // The one part of the UI that is not drawn: text entry needs a real text field to
        // get an IME, a cursor and autocorrect, so naming puts a composable over the canvas
        // instead of another shape inside it. Nothing else lives up here.
        (interaction as? Interaction.Renaming)?.let { renaming ->
            val module = patch.module(renaming.moduleId)
            if (renaming.moduleId == TOP) {
                // The patch itself, from the first crumb. A name equal to the default is no
                // name, as a module's own type name is no name.
                RenameOverlay(TOP, patch.title, Types.Subpatch.accent, {
                    patch.name = it?.takeIf { name -> name != DEFAULT_PATCH_NAME }
                }) { interaction = Interaction.Idle }
            } else if (module != null) {
                RenameOverlay(module.id, module.title, module.type.accent, {
                    module.name = it?.takeIf { name -> name != module.type.name }
                }) { interaction = Interaction.Idle }
            }
        }
        (interaction as? Interaction.Typing)?.let { typing ->
            NumberKeypad(patch, typing.target) { interaction = Interaction.Idle }
        }
        (interaction as? Interaction.Saving)?.let { saving ->
            val subpatch = saving.moduleId?.let { patch.module(it) }
            SaveOverlay(
                initial = subpatch?.title ?: patch.title,
                library = library,
                // Built when the name is known, since saving the whole patch names the subpatch
                // it makes on the way out.
                json = { name ->
                    if (subpatch != null) patch.subpatchToJson(subpatch, name)
                    else patch.patchToSubpatchJson(name)
                },
            ) { interaction = Interaction.Idle }
        }
    }
}

/**
 * Naming a module, with the system keyboard.
 *
 * The text starts selected, so the default "Subpatch 3" is replaced by typing and kept by
 * tapping past it -- the same bargain a file manager's rename makes. An empty name is not
 * an error but the way back: it clears the name and the module goes by its type again.
 * Committing writes to the model like any other edit, so autosave and undo carry it
 * without knowing this dialog exists.
 *
 * The scrim commits rather than cancels. There is no Cancel here because undo is the
 * cancel this app has everywhere else, and a name is one step of it.
 */
@Composable
private fun RenameOverlay(
    /** What is being renamed, so the field resets when it changes: a module's id, or the patch. */
    key: Any,
    initial: String,
    accent: Color,
    /** The trimmed name, or null for "use the default". */
    onName: (String?) -> Unit,
    onDone: () -> Unit,
) {
    var text by remember(key) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun commit() {
        onName(text.text.trim().takeIf { it.isNotEmpty() })
        keyboard?.hide()
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .pointerInput(key) { detectTapGestures { commit() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                // High enough that the keyboard cannot reach it on a phone held either way.
                .padding(top = 72.dp, start = 24.dp, end = 24.dp)
                .widthIn(max = 360.dp)
                .background(Color(0xFF1B1F26), RoundedCornerShape(12.dp))
                .border(2.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
                // Swallows taps on the card itself, which would otherwise reach the scrim
                // behind it and commit halfway through an edit.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            BasicTextField(
                value = text,
                onValueChange = { if (it.text.length <= MAX_NAME) text = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color(0xFFE6E9EF),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        }
    }

    LaunchedEffect(key) {
        focus.requestFocus()
        keyboard?.show()
    }
}

/**
 * Naming something on its way into the library, and asking before it replaces anything.
 *
 * Two steps in one overlay rather than two interactions: typing a name, and -- only when
 * that name is taken -- Replace or Keep both. Forrest chose the prompt on 2026-09-17 over
 * silent replacement, which is the only option here that can lose work, and over always
 * numbering, which fills the folder with versions nobody asked for.
 *
 * The write is done on the IO dispatcher and nothing waits for it. A save that fails says
 * so in the log and leaves the library as it was; there is no state here to get out of step
 * with the disk, because the list is read afresh every time the picker opens.
 */
@Composable
private fun SaveOverlay(
    initial: String,
    library: SubpatchLibrary?,
    json: (String) -> String?,
    onDone: () -> Unit,
) {
    var text by remember(initial) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    var clash by remember(initial) { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val io = rememberCoroutineScope()
    val accent = Types.Subpatch.accent

    fun write(name: String) {
        val body = json(name)
        keyboard?.hide()
        if (body != null) io.launch { withContext(Dispatchers.IO) { library?.write(name, body) } }
        onDone()
    }

    fun commit() {
        val name = text.text.trim().take(MAX_NAME)
        if (name.isEmpty() || SubpatchLibrary.safeName(name).isEmpty()) return
        keyboard?.hide()
        if (library?.exists(name) == true) clash = name else write(name)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // Tapping away abandons the save. Unlike a rename, nothing has happened yet.
            .pointerInput(initial) { detectTapGestures { keyboard?.hide(); onDone() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .padding(top = 72.dp, start = 24.dp, end = 24.dp)
                .widthIn(max = 420.dp)
                .background(Color(0xFF1B1F26), RoundedCornerShape(12.dp))
                .border(2.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            BasicText(
                clash?.let { "\u201c$it\u201d is already saved" } ?: "Save to the library",
                style = TextStyle(color = Color(0xFF98A0AD), fontSize = 14.sp),
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (clash == null) {
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.text.length <= MAX_NAME) text = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color(0xFFE6E9EF),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf("Replace" to true, "Keep both" to false).forEach { (label, replace) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .padding(horizontal = 3.dp)
                                .height(52.dp)
                                .background(
                                    if (replace) Color(0xFFE07A6B).copy(alpha = 0.85f)
                                    else accent.copy(alpha = 0.85f),
                                    RoundedCornerShape(10.dp),
                                )
                                .pointerInput(label) {
                                    detectTapGestures {
                                        val name = clash ?: return@detectTapGestures
                                        write(if (replace) name else library?.freeName(name) ?: name)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                label,
                                style = TextStyle(
                                    color = Color(0xFF12151A),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(initial) {
        focus.requestFocus()
        keyboard?.show()
    }
}

// ---------------------------------------------------------------- the keypad

/** The most digits a typed number may hold. Longer than any control's range needs. */
internal const val MAX_ENTRY = 9

/**
 * One key pressed, applied to what has been typed so far.
 *
 * Pure, and tested as such: the keypad's behavior is all here, and the composable below
 * is layout. An entry that is only a sign or a point is left as it is rather than fixed
 * up, because [keypadValue] refuses it and the OK key then does nothing -- which is what
 * a half-typed number should do.
 */
internal fun keypadEntry(entry: String, key: String): String = when (key) {
    KEY_BACK -> entry.dropLast(1)
    KEY_SIGN -> if (entry.startsWith("-")) entry.drop(1) else "-$entry"
    KEY_CLEAR -> ""
    "." -> when {
        entry.contains('.') -> entry
        entry.isEmpty() -> "0."
        else -> "$entry."
    }
    else -> if (entry.count { it.isDigit() } >= MAX_ENTRY) entry else entry + key
}

/**
 * What a typed entry means for [param], or null if it means nothing yet.
 *
 * Clamped rather than refused when it is out of range: a cutoff typed as 20000 on a knob
 * that stops at 12000 asks for as high as it goes, and refusing it would just leave the
 * knob where it was with nothing said. min and max are read either way round, since a
 * parameter is allowed to descend.
 */
internal fun keypadValue(entry: String, param: Param): Float? {
    val value = entry.toFloatOrNull() ?: return null
    if (!value.isFinite()) return null
    val clamped = value.coerceIn(minOf(param.min, param.max), maxOf(param.min, param.max))
    // A stepped parameter typed on its bar takes a whole option: "7.5" steps is not one.
    return if (param.curve == ParamCurve.STEPPED) clamped.roundToInt().toFloat() else clamped
}

internal const val KEY_BACK = "\u232b"
internal const val KEY_SIGN = "\u00b1"
internal const val KEY_CLEAR = "C"
internal const val KEY_OK = "OK"

/** The keys, in rows, as they are laid out. OK takes the width of two. */
internal val KEYPAD_ROWS = listOf(
    listOf("7", "8", "9", KEY_BACK),
    listOf("4", "5", "6", KEY_SIGN),
    listOf("1", "2", "3", "."),
    listOf(KEY_CLEAR, "0", KEY_OK),
)

/**
 * The keypad, over the canvas, for whichever number was tapped.
 *
 * Drawn as composables rather than into the canvas, like the rename field and for the
 * same reason -- but with its own keys rather than the system's numeric IME, which would
 * resize the window and slide the panel being edited out from under itself.
 *
 * The entry starts empty with the current value in its place, so the first digit replaces
 * rather than appends: typing a number is how you say "this value", not how you amend the
 * one there. Tapping away cancels, where the rename field commits: a name is whatever the
 * field holds, while a half-typed number is not a value anyone meant.
 */
@Composable
private fun NumberKeypad(patch: Patch, target: NumberTarget, onDone: () -> Unit) {
    val module = when (target) {
        is NumberTarget.Knob -> patch.module(target.moduleId)
        is NumberTarget.SegmentTime -> patch.module(target.moduleId)
        else -> null
    }
    val param = when (target) {
        is NumberTarget.Tempo -> TEMPO
        is NumberTarget.SegmentTime ->
            SEGMENT_TIME.takeIf { module?.segments?.indices?.contains(target.index) == true }
        is NumberTarget.Knob -> module?.type?.params?.getOrNull(target.index)
    } ?: run {
        // The module went away under the keypad, which only an undo could do.
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val range = (target as? NumberTarget.Knob)?.let { module?.modRanges?.get(it.index) }
    val current = when {
        target is NumberTarget.Tempo -> param.format(patch.tempo)
        target is NumberTarget.SegmentTime ->
            param.format((module?.segments?.get(target.index)?.time ?: 0f) * 1000f)
        target is NumberTarget.Knob && target.end == ValueTarget.LOW && range != null ->
            param.format(range.low)
        target is NumberTarget.Knob && target.end == ValueTarget.HIGH && range != null ->
            param.format(range.high)
        target is NumberTarget.Knob ->
            param.format(module?.params?.getOrElse(target.index) { param.default } ?: param.default)
        else -> ""
    }
    val label = when {
        target is NumberTarget.SegmentTime -> "segment ${target.index + 1}"
        target is NumberTarget.Knob && target.end == ValueTarget.LOW -> "${param.name} from"
        target is NumberTarget.Knob && target.end == ValueTarget.HIGH -> "${param.name} to"
        else -> param.name
    }
    val accent = when (target) {
        is NumberTarget.Tempo -> TransportAccent
        is NumberTarget.SegmentTime -> module?.type?.accent ?: TransportAccent
        is NumberTarget.Knob -> if (range != null) ModulationColor else module?.type?.accent ?: TransportAccent
    }

    var entry by remember(target) { mutableStateOf("") }

    fun commit() {
        val value = keypadValue(entry, param)
        if (value != null) {
            when (target) {
                is NumberTarget.Tempo -> patch.tempo = value.roundToInt().toFloat()
                is NumberTarget.SegmentTime -> {
                    val m = module ?: return
                    val seg = m.segments.getOrNull(target.index) ?: return
                    // Typed in milliseconds, stored in seconds; see SEGMENT_TIME.
                    m.setSegment(target.index, seg.copy(time = value / 1000f))
                }
                is NumberTarget.Knob -> {
                    val m = module ?: return
                    when {
                        range == null -> m.setParam(target.index, value)
                        target.end == ValueTarget.LOW ->
                            patch.expose(m, target.index, range.copy(low = value))
                        target.end == ValueTarget.HIGH ->
                            patch.expose(m, target.index, range.copy(high = value))
                        // A row being modulated has no plain value to type: its reading is
                        // its range, and the tap that got here landed on one end of it.
                        else -> Unit
                    }
                }
            }
        }
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .pointerInput(target) { detectTapGestures { onDone() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(300.dp)
                .background(Color(0xFF1B1F26), RoundedCornerShape(14.dp))
                .border(2.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                .padding(14.dp)
                // The card is not the scrim: a key that missed must not cancel.
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            BasicText(
                label,
                style = TextStyle(color = Color(0xFF98A0AD), fontSize = 14.sp),
            )
            BasicText(
                entry.ifEmpty { current },
                style = TextStyle(
                    color = if (entry.isEmpty()) Color(0xFF6C7482) else Color(0xFFE6E9EF),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            KEYPAD_ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    row.forEach { key ->
                        val ok = key == KEY_OK
                        Box(
                            Modifier
                                .weight(if (ok) 2f else 1f)
                                .padding(horizontal = 3.dp)
                                .height(52.dp)
                                .background(
                                    if (ok) accent.copy(alpha = 0.85f) else Color(0xFF262B33),
                                    RoundedCornerShape(10.dp),
                                )
                                .pointerInput(key) {
                                    detectTapGestures {
                                        if (ok) commit() else entry = keypadEntry(entry, key)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                key,
                                style = TextStyle(
                                    color = if (ok) Color(0xFF12151A) else Color(0xFFE6E9EF),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * How much longer than the platform's touch-and-hold delay this canvas waits.
 *
 * Scaled rather than replaced, so a user who has changed Touch & hold delay for
 * accessibility still gets their setting, proportionally.
 *
 * Longer suits this surface specifically. The long press only fires while the finger
 * has not passed touch slop, so a hesitant drag -- finger down on a module, a beat,
 * then move -- would otherwise open the menu instead of dragging the module. The cost
 * of waiting is small; the cost of a menu you did not ask for is losing your place.
 */
private const val LONG_PRESS_SCALE = 1.25f

/** Consumes the rest of a gesture, so a decision taken on the down is not taken twice. */
private suspend fun AwaitPointerEventScope.waitForUpRelease() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) return
    }
}

private enum class GestureKind { Undecided, Tap, LongPress, MoveModule, Pan, Transform }

private data class TwoFinger(val centroid: Offset, val spread: Float)

// ---------------------------------------------------------------- hit testing

/**
 * A port never grabs past the midpoint to its neighbor.
 *
 * The screen-space radius is the right idea — a port should be a fixed amount of glass —
 * but it cannot exceed half the on-screen port pitch, or zooming out would let one port's
 * grab area swallow the next and hand the tap to whichever happened to be marginally
 * nearer. Capping keeps the generous target wherever there is room for it, and degrades
 * to "you have to aim" only when the ports really are that close together on the glass.
 *
 * Rails are exempt: their pitch is fixed in screen space and never shrinks.
 */
private fun effectiveTouchRadius(camera: Camera, radiusPx: Float): Float =
    min(radiusPx, PatchModule.PORT_PITCH * camera.worldToScreen * 0.5f)

private fun Patch.hitPort(
    camera: Camera,
    frame: Frame,
    screen: Offset,
    radiusPx: Float,
): PortRef? {
    var best: PortRef? = null
    var bestDist = Float.MAX_VALUE
    val worldRadius = effectiveTouchRadius(camera, radiusPx)
    val railRadius = min(radiusPx, PatchModule.PORT_PITCH * frame.density * 0.5f)

    val at = scopeOrTop
    modules.filter { it.parent == at }.forEach { module ->
        val limit = if (module.isPinned) railRadius else worldRadius
        PortDirection.entries.forEach { dir ->
            module.ports(dir).indices.forEach { i ->
                val ref = PortRef(module.id, dir, i)
                if (!portUsable(ref)) return@forEach
                val at = portScreen(this, ref, camera, frame) ?: return@forEach
                val dist = (at - screen).getDistance()
                if (dist <= limit && dist < bestDist) {
                    bestDist = dist
                    best = ref
                }
            }
        }
        // The bottom band's jacks, which ports() does not list -- see PortDirection.MOD.
        module.modRanges.keys.forEach { index ->
            val ref = PortRef(module.id, PortDirection.MOD, index)
            val at = portScreen(this, ref, camera, frame) ?: return@forEach
            val dist = (at - screen).getDistance()
            if (dist <= limit && dist < bestDist) {
                bestDist = dist
                best = ref
            }
        }
    }
    return best
}

private fun Patch.hitModule(camera: Camera, frame: Frame, screen: Offset): PatchModule? {
    shownRails.firstOrNull { frame.railRect(it).contains(screen) }?.let { return it }
    val world = camera.toWorld(screen)
    return shownFree.lastOrNull { it.bounds.contains(world) }
}

/**
 * How long the pulse lasts. Long enough to catch out of the corner of an eye, short
 * enough that a second undo half a second later reads as a second event rather than one
 * continuous glow.
 */
private const val FLASH_MS = 450

/** Warm white rather than the module's accent: this means "changed", not "is a filter". */
private val FlashColor = Color(0xFFE8EEF5)

/** A module chosen for a subpatch. Bright and steady, where the flash fades. */
private val SelectedColor = Color(0xFFF2F5F9)

private fun DrawScope.drawFlash(rect: Rect, unit: Float, alpha: Float, strokeWidth: Float) {
    drawRoundRect(
        color = FlashColor.copy(alpha = alpha),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(PatchModule.CORNER * unit, PatchModule.CORNER * unit),
        style = Stroke(width = strokeWidth),
    )
}

// ------------------------------------------------------------------ the step grid

/** A module's body. Shared with ModuleColorTest, which judges accents as they land on it. */
internal val ModuleFill = Color(0xFF232830)

/**
 * How much of its accent a module's border shows. At 0.55 over [ModuleFill], two accents
 * lose close to half their difference on the way to the screen -- which is why accents that
 * measured apart still looked alike on the phone, and why the test measures after this.
 */
internal const val MODULE_BORDER_ALPHA = 0.55f

/** How a stacked box says it is several: how many outlines behind it, and how far apart. */
internal const val STACK_LAYERS = 2
internal const val STACK_STEP = 4f

/**
 * The room a stack needs beyond the box it is behind, up and to the right.
 *
 * Reserved on the right-hand rail whether or not it is drawn stacked, for two reasons. The
 * stack leans one way everywhere -- a pile that leaned inward on one rail and outward on
 * everything else read as two different ideas -- and the rail must not move when you step
 * into a poly subpatch, which it would if the room appeared only where it was used.
 */
internal const val RAIL_STACK_ROOM = STACK_LAYERS * STACK_STEP

private val GridLine = Color(0xFF232A33)
private val GridCell = Color(0xFF12151A)
private val GridTonic = Color(0xFF26333F)
private val GridDisabled = Color(0xFF0B0D10)
private val GridLoopEdge = Color(0xFF5A6675)
private val GridPlayhead = Color(0x26FFFFFF)
private val GridPlaying = Color(0xFFF2F6FB)

/**
 * Steps across, scale degrees down.
 *
 * Rows are degrees of the scale rather than semitones, which is what makes this work for
 * a diatonic scale at all: seven rows to the octave, every one of them a note you meant,
 * and no way to land between them. In an equal division it degenerates to a piano roll.
 *
 * The tonic of each period is tinted, because without a landmark a scale of seven or
 * nineteen or thirteen degrees is uncountable by eye -- and unlike a piano roll there are
 * no black keys to count against.
 */
/**
 * The track and thumb of a grid's scroll bar, or null when everything is on screen.
 *
 * Beside the grid on the right, because the left gutter is where a sequencer numbers its
 * rows and the right one is empty at the grid's height -- the jacks' labels are further
 * out and the modulation chips sit beside the rows below.
 *
 * Only an indicator. The grid itself is what scrolls, under a drag anywhere on it, and a
 * bar you had to aim at would be a second, smaller way to do what the whole grid already
 * does. What the grid could not say was that there was more of it, and how much.
 *
 * The thumb has a floor on its height so a sequencer's eighty-odd degrees do not shrink it
 * to a sliver, and travels over what is left of the track, which is the usual way a thumb
 * with a minimum size keeps its two ends meaning the two ends of the range.
 */
internal fun gridScrollBar(area: Rect, d: Float, window: GridWindow): Pair<Rect, Rect>? {
    if (!window.scrolls) return null
    val track = Rect(area.right + 10f * d, area.top, area.right + 14f * d, area.bottom)
    val height = maxOf(track.height * window.rows / window.span, 16f * d).coerceAtMost(track.height)
    val above = (window.highest - window.top).toFloat()
    val top = track.top + (track.height - height) * above / (window.span - window.rows)
    return track to Rect(track.left, top, track.right, top + height)
}

private fun DrawScope.drawGridScrollBar(area: Rect, d: Float, window: GridWindow, accent: Color) {
    val (track, thumb) = gridScrollBar(area, d, window) ?: return
    val radius = CornerRadius(2f * d, 2f * d)
    drawRoundRect(GridCell, track.topLeft, track.size, radius)
    drawRoundRect(accent.copy(alpha = 0.7f), thumb.topLeft, thumb.size, radius)
}

/**
 * Degrees up the rows, octaves across the columns, each cell held on its own.
 *
 * No playhead and no dimming: nothing here is reached in turn, so there is no step being
 * played and no cell outside a loop. A lit cell is a note that is sounding right now,
 * which is the whole of what this grid says.
 */
private fun DrawScope.drawDroneGrid(
    area: Rect,
    d: Float,
    module: PatchModule,
    scale: Scale,
    accent: Color,
) {
    val window = droneWindow(module, area, d, scale)
    val rows = window.rows
    val columns = droneColumns(scale)
    val cellW = area.width / columns
    val cellH = area.height / rows
    val inset = 1f * d
    val radius = CornerRadius(3f * d, 3f * d)

    repeat(rows) { row ->
        repeat(columns) { column ->
            val degree = droneDegree(window, row, column, scale)
            val on = module.steps.getOrNull(degree)?.on == true
            // The tonic of each column, so the octaves read as octaves rather than as
            // four columns of undifferentiated cells.
            val tonic = degree.mod(scale.size) == 0
            val cell = Rect(
                Offset(area.left + column * cellW + inset, area.top + row * cellH + inset),
                Size(cellW - inset * 2f, cellH - inset * 2f),
            )
            drawRoundRect(
                color = when {
                    on -> accent
                    tonic -> GridTonic
                    else -> GridCell
                },
                topLeft = cell.topLeft,
                size = cell.size,
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Whether step [index] of a Euclid's pattern sounds. The engine's rule, EuclidNode::hit,
 * written again here for the drawing; a test holds the two to the same patterns.
 */
internal fun euclidHit(index: Int, steps: Int, pulses: Int, rotate: Int): Boolean {
    if (steps <= 0 || pulses <= 0) return false
    if (pulses >= steps) return true
    val at = (index + rotate).mod(steps)
    return (at * pulses) % steps < pulses
}

/** A Euclid's pattern: a mark per step, filled where a note falls, ringed where it is. */
/**
 * A segment's time, for reading rather than for the file: milliseconds under a second.
 *
 * An attack is 5ms and a release is 2s, and one unit across that range either reads as
 * 0.005 or as 2000 -- neither of which is the number anyone says out loud.
 */
internal fun envTime(seconds: Float): String = when {
    seconds < 1f -> "${(seconds * 1000f).roundToInt()}ms"
    seconds < 10f -> "${"%.2f".format(seconds).trimEnd('0').trimEnd('.')}s"
    else -> "${"%.1f".format(seconds).trimEnd('0').trimEnd('.')}s"
}

/** A node's drawn radius, in dp. */
internal const val ENV_NODE_RADIUS = 7f

/** How near a finger must land to take a node or bend a segment, in dp. */
internal const val ENV_GRAB = 22f

/**
 * Dp of vertical drag that bend a segment from straight to fully curved.
 *
 * It was 90, which put the whole range from -1 to +1 inside 439px on the reference device --
 * against a curve area 631px tall. Any drag anyone actually makes slammed it to a limit and
 * stuck there, and a control pinned at its maximum looks exactly like a control that is
 * broken: the report was "the curvature won't move", from a patch whose every segment was
 * sitting at 1.0. The drag is *relative* to where the curve already was, so a slower rate
 * costs nothing -- a second drag carries on from the first.
 */
internal const val ENV_CURVE_TRAVEL = 200f

/**
 * The curvature a vertical drag of [dy] pixels leaves, starting from [start].
 *
 * **Up bends the line up, whichever way the segment travels**, and that needs [rising]
 * because the curvature's own sign is a fact about *shape* -- leaves fast, arrives slow --
 * not about the screen. That shape puts the middle of a rising segment high and the middle
 * of a falling one low, so mapping the finger straight onto the number moves the line the
 * wrong way on exactly half of all segments. Which is what happened: on a release falling to
 * zero, dragging down raised the line, and kept raising it until the number hit -1 and the
 * control went dead. Reported as two faults and it was one.
 *
 * The trap for anyone changing this: checking that the *number* moved is not checking that
 * the *line* followed the finger. The first was verified on hardware and the second was not.
 *
 * Relative to [start] rather than absolute, so a second drag carries on from the first and a
 * deliberately slow rate costs nothing.
 */
internal fun envCurveAfterDrag(start: Float, dy: Float, rising: Boolean, density: Float): Float {
    val towardsFinger = if (rising) -1f else 1f
    return (start + towardsFinger * dy / (ENV_CURVE_TRAVEL * density)).coerceIn(-1f, 1f)
}

/**
 * The time axis an envelope is drawn against: a round number at or above its own length,
 * never the length itself.
 *
 * If the axis were the sum, the right-hand node would sit on the edge and could not be
 * dragged any longer, and every other node would slide whenever any segment changed. A
 * ladder holds the picture still while an edit is in progress and steps when it has to,
 * which reads as a zoom rather than as the envelope squirming under the finger.
 */
internal fun envelopeAxis(span: Float): Float =
    floatArrayOf(0.1f, 0.25f, 0.5f, 1f, 2f, 5f, 10f, 20f, 40f, 80f).firstOrNull { it >= span }
        ?: (MAX_SEGMENTS * SEGMENT_MAX_TIME)

/**
 * Where an envelope's seconds and levels land in the panel, and back again.
 *
 * One object shared by the drawing and the hit testing on purpose: an editor whose picture
 * and whose targets are worked out separately is one where they drift, and a node you can
 * see but not grab is the worst of the two halves.
 */
internal class EnvGeometry(val area: Rect, val axis: Float, private val inset: Float) {
    private val usable get() = area.height - 2f * inset
    fun x(time: Float): Float = area.left + (time / axis) * area.width
    fun y(level: Float): Float = area.bottom - inset - level * usable
    fun timeAt(px: Float): Float = ((px - area.left) / area.width) * axis
    fun levelAt(py: Float): Float = ((area.bottom - inset - py) / usable).coerceIn(0f, 1f)

    /**
     * How far a drag of [dx]/[dy] pixels moves a node, in seconds and in level.
     *
     * Relative rather than absolute, like the dot grid's velocity drag and for the same
     * reason: a node taken by its edge should not jump to put its centre under the finger.
     */
    fun timeDelta(dx: Float): Float = (dx / area.width) * axis
    fun levelDelta(dy: Float): Float = -dy / usable
}

/**
 * The two strips that keep the envelope's other two decisions off the curve.
 *
 * A node already carries a time and a level, and a drag moves both. Hanging the sustain and
 * the keypad on it as well would make the control that picks between them a mode, and a mode
 * on a fingertip-sized target is a coin toss -- which is the exact failure the roadmap
 * copied Surge to avoid. So each gets its own target instead, in its own strip, and neither
 * can be hit by a finger aiming at the shape.
 *
 * Both are divided evenly by segment rather than laid out against the time axis, because a
 * 5ms attack is half a percent of a one-second axis and the attack is the first thing anyone
 * wants to type exactly. An even cell is always a target; the column order is what ties it
 * to the node above it.
 */
internal const val ENV_RAIL = 26f

internal fun envSustainRail(area: Rect, d: Float): Rect =
    Rect(area.left, area.top, area.right, area.top + ENV_RAIL * d)

internal fun envTimeRail(area: Rect, d: Float): Rect =
    Rect(area.left, area.bottom - ENV_RAIL * d, area.right, area.bottom)

/** What is left for the shape once both rails have taken theirs. */
internal fun envCurveArea(area: Rect, d: Float): Rect =
    Rect(area.left, area.top + ENV_RAIL * d, area.right, area.bottom - ENV_RAIL * d)

/** One rail cell per segment, evenly divided. */
internal fun envCell(rail: Rect, count: Int, index: Int): Rect {
    val w = rail.width / count.coerceAtLeast(1)
    return Rect(rail.left + index * w, rail.top, rail.left + (index + 1) * w, rail.bottom)
}

/** Which cell of [rail] holds [at], or -1 when it is outside. */
internal fun envCellAt(rail: Rect, count: Int, at: Offset): Int {
    if (count <= 0 || !rail.contains(at)) return -1
    return ((at.x - rail.left) / (rail.width / count)).toInt().coerceIn(0, count - 1)
}

/** [area] is the whole grid; the curve gets what the rails leave. */
internal fun envGeometry(area: Rect, module: PatchModule, d: Float): EnvGeometry =
    EnvGeometry(
        envCurveArea(area, d), envelopeAxis(module.envelopeSpan), ENV_NODE_RADIUS * d + 2f * d,
    )

/** Every node's position, one per segment; the envelope's own start is [envOrigin]. */
internal fun envNodes(geo: EnvGeometry, module: PatchModule): List<Offset> {
    val times = module.segmentTimes
    return module.segments.mapIndexed { i, seg -> Offset(geo.x(times[i]), geo.y(seg.level)) }
}

/** Where the envelope starts: time zero at level zero, which is not a node and does not move. */
internal fun envOrigin(geo: EnvGeometry): Offset = Offset(geo.x(0f), geo.y(0f))

/** The level a segment leaves from: the one before it, or zero for the first. */
internal fun envFrom(module: PatchModule, index: Int): Float =
    if (index <= 0) 0f else module.segments[index - 1].level

/** Which node [at] is grabbing, or -1. Nearest wins, so two close together are both reachable. */
/**
 * What the envelope editor made of a touch, for a debug build's logcat.
 *
 * `adb logcat -s PatchGesture:V`, the same habit as PatchSync tracing every command that
 * crosses to the engine: "what did my finger actually land on" is otherwise answered by
 * guessing, and this editor has now been wrong twice in ways that a report could describe
 * but not locate.
 */
internal fun traceEnvTouch(
    module: PatchModule, geo: EnvGeometry, at: Offset, d: Float, node: Int, segment: Int,
): String {
    val nodes = envNodes(geo, module)
    val near = nodes.mapIndexed { i, p -> i to (p - at).getDistance() }
        .sortedBy { it.second }
        .take(2)
        .joinToString(" ") { "n${it.first}@${it.second.toInt()}px" }
    val curveGap = if (segment >= 0) {
        val times = module.segmentTimes
        val left = geo.x(if (segment == 0) 0f else times[segment - 1])
        val right = geo.x(times[segment])
        val t = ((at.x - left) / (right - left)).coerceIn(0f, 1f)
        "${(at.y - envCurveY(geo, module, segment, t)).toInt()}px"
    } else {
        "-"
    }
    val got = when {
        node >= 0 -> "NODE $node"
        segment >= 0 -> "SEGMENT $segment"
        else -> "NOTHING"
    }
    return "down (${at.x.toInt()},${at.y.toInt()}) -> $got | nearest $near | " +
        "off-curve $curveGap | grab ${(ENV_GRAB * d).toInt()}px | segs ${module.segments.size}"
}

internal fun envNodeAt(geo: EnvGeometry, module: PatchModule, at: Offset, d: Float): Int {
    val grab = ENV_GRAB * d
    var best = -1
    var bestDistance = grab
    envNodes(geo, module).forEachIndexed { i, p ->
        val distance = (p - at).getDistance()
        if (distance <= bestDistance) {
            best = i
            bestDistance = distance
        }
    }
    return best
}

/** Where a segment's curve sits at [t] along it, 0 to 1, in pixels. */
internal fun envCurveY(geo: EnvGeometry, module: PatchModule, index: Int, t: Float): Float {
    val seg = module.segments[index]
    val from = envFrom(module, index)
    return geo.y(from + (seg.level - from) * envShape(t, seg.curve))
}

/**
 * Which segment [at] belongs to, or -1 past the end of the envelope.
 *
 * **A segment owns its whole column**, not a band around its line. That is the second thing
 * this editor got wrong by drawing one thing and targeting another: the shape is drawn as a
 * *filled* area under the curve, which is the part that looks like the segment and is the
 * part a finger goes for -- and for two builds only a 53px band around the stroke responded,
 * so touches landing in the middle of the fill did nothing at all. Traced from the phone:
 * three attempts in a row at (1140, 808)-(1140, 897), each 200 to 300px below a stroke that
 * was drawn at y=595, each reported as NOTHING.
 *
 * Every x inside the envelope belongs to exactly one segment, so a column is unambiguous, and
 * a node still wins near itself. Past the last node there is no envelope, and nothing there
 * is a target.
 */
/**
 * Whether [at] is near enough to segment [index]'s drawn line to be *pointing* at it.
 *
 * The column is what a drag owns, because bending is an adjustment and wants a big target.
 * Adding a node is structural, so it is pointed at -- the same line this editor already draws
 * between adjusting a thing and changing what things there are, which is why removing one
 * costs a long press. A tap in the middle of the fill does nothing rather than quietly
 * growing the envelope a node.
 *
 * Measured against the curve rather than a straight chord, so a bent segment is pointed at
 * where it is drawn and not where it would have been.
 */
internal fun envOnCurve(
    geo: EnvGeometry,
    module: PatchModule,
    index: Int,
    at: Offset,
    d: Float,
): Boolean {
    val times = module.segmentTimes
    val left = geo.x(if (index == 0) 0f else times[index - 1])
    val right = geo.x(times[index])
    if (right <= left) return false
    val t = ((at.x - left) / (right - left)).coerceIn(0f, 1f)
    return abs(at.y - envCurveY(geo, module, index, t)) <= ENV_GRAB * d
}

internal fun envSegmentAt(geo: EnvGeometry, module: PatchModule, at: Offset): Int {
    val times = module.segmentTimes
    for (i in module.segments.indices) {
        val left = geo.x(if (i == 0) 0f else times[i - 1])
        val right = geo.x(times[i])
        if (right > left && at.x >= left && at.x <= right) return i
    }
    return -1
}

/**
 * The envelope, as the thing you edit and as the thing the engine plays.
 *
 * Drawn from the same [envShape] the engine runs, because an envelope that sounds unlike
 * its own picture is worse than one with no picture: the picture is the whole reason the
 * four knobs went.
 */
private fun DrawScope.drawEnvelope(
    area: Rect,
    d: Float,
    module: PatchModule,
    accent: Color,
    measurer: TextMeasurer,
) {
    val geo = envGeometry(area, module, d)
    val radius = ENV_NODE_RADIUS * d
    val grid = accent.copy(alpha = 0.16f)

    // The floor and the ceiling, so a level can be read against something. Two lines rather
    // than a full grid: the vertical is continuous and ruling it would imply steps.
    listOf(0f, 1f).forEach { level ->
        drawLine(
            grid, Offset(geo.area.left, geo.y(level)), Offset(geo.area.right, geo.y(level)), 1f * d,
        )
    }

    // The shape itself, sampled along each segment's own curve.
    val path = Path()
    val origin = envOrigin(geo)
    path.moveTo(origin.x, origin.y)
    val times = module.segmentTimes
    module.segments.indices.forEach { i ->
        val left = if (i == 0) 0f else times[i - 1]
        val steps = 24
        for (k in 1..steps) {
            val t = k.toFloat() / steps
            path.lineTo(geo.x(left + (times[i] - left) * t), envCurveY(geo, module, i, t))
        }
    }
    drawPath(path, accent, style = Stroke(width = 2.5f * d))

    // Under the curve, faintly, which is what makes it read as a level over time rather
    // than as a line graph of four numbers.
    val filled = Path().apply {
        addPath(path)
        lineTo(geo.x(module.envelopeSpan), geo.y(0f))
        lineTo(origin.x, origin.y)
        close()
    }
    drawPath(filled, accent.copy(alpha = 0.12f))

    // The sustain, before the nodes so a node sits on top of its own marker: a dashed line
    // down through the shape, because what it marks is a wait and a wait is a place in time.
    val sustainAt = module.segments.indexOfFirst { it.sustain }
    if (sustainAt >= 0) {
        val x = geo.x(times[sustainAt])
        var y = geo.area.top
        while (y < geo.area.bottom) {
            drawLine(
                accent.copy(alpha = 0.55f),
                Offset(x, y), Offset(x, minOf(y + 6f * d, geo.area.bottom)), 1.5f * d,
            )
            y += 11f * d
        }
    }

    // The nodes. The one that sustains is drawn open, so which point the envelope waits at
    // is legible without reading the line under it.
    envNodes(geo, module).forEachIndexed { i, p ->
        if (module.segments[i].sustain) {
            drawCircle(Color.Black, radius, p)
            drawCircle(accent, radius, p, style = Stroke(width = 2.5f * d))
        } else {
            drawCircle(accent, radius, p)
        }
    }
    // The start, which is not a node: it never moves and cannot be taken away.
    drawCircle(accent.copy(alpha = 0.5f), radius * 0.5f, origin)

    // The rails. Drawn after the shape so neither can be hidden behind the fill.
    val count = module.segments.size
    val sustainRail = envSustainRail(area, d)
    val timeRail = envTimeRail(area, d)
    module.segments.forEachIndexed { i, seg ->
        val top = envCell(sustainRail, count, i).deflate(2f * d)
        val bottom = envCell(timeRail, count, i).deflate(2f * d)

        // The sustain cell: filled where the envelope waits, an outline everywhere else, so
        // the one that holds it is legible without reading the dashes in the shape.
        drawRoundRect(
            if (seg.sustain) accent.copy(alpha = 0.5f) else accent.copy(alpha = 0.10f),
            Offset(top.left, top.top), Size(top.width, top.height),
            CornerRadius(4f * d, 4f * d),
        )
        val hold = measurer.measure(if (seg.sustain) "hold" else "${i + 1}", PanelValueStyle)
        drawText(
            hold,
            color = accent.copy(alpha = if (seg.sustain) 0.95f else 0.45f),
            topLeft = Offset(
                top.center.x - hold.size.width / 2f,
                top.center.y - hold.size.height / 2f,
            ),
        )

        // The time cell: the reading, and the target the keypad opens from. A reading that
        // is tapped and never dragged, which is the rule every other number on a panel
        // already follows.
        drawRoundRect(
            accent.copy(alpha = 0.10f),
            Offset(bottom.left, bottom.top), Size(bottom.width, bottom.height),
            CornerRadius(4f * d, 4f * d),
        )
        val text = measurer.measure(envTime(seg.time), PanelValueStyle)
        drawText(
            text,
            color = accent.copy(alpha = 0.85f),
            topLeft = Offset(
                bottom.center.x - text.size.width / 2f,
                bottom.center.y - text.size.height / 2f,
            ),
        )
    }
}

private fun DrawScope.drawEuclidPattern(area: Rect, d: Float, module: PatchModule, accent: Color, playingStep: Int) {
    val steps = module.params.getOrElse(0) { 8f }.roundToInt().coerceIn(1, EUCLID_STEPS)
    val pulses = module.params.getOrElse(1) { 3f }.roundToInt()
    val rotate = module.params.getOrElse(2) { 0f }.roundToInt()
    val pitch = area.width / steps
    val radius = minOf(pitch * 0.36f, area.height * 0.3f, 14f * d)
    repeat(steps) { i ->
        val center = Offset(area.left + (i + 0.5f) * pitch, area.center.y)
        if (euclidHit(i, steps, pulses, rotate)) {
            drawCircle(accent, radius, center)
        } else {
            drawCircle(GridCell, radius, center)
            drawCircle(ChipEdge, radius, center, style = Stroke(width = 1.5f * d))
        }
        if (i == playingStep) drawCircle(GridPlaying, radius + 4f * d, center, style = Stroke(width = 2f * d))
    }
}

/**
 * A dot sequencer's grid: the same rows of degrees as a sequence, with each dot drawn as one
 * bar across the steps it lasts, so a long note looks long.
 */
private fun DrawScope.drawDotGrid(
    area: Rect,
    d: Float,
    module: PatchModule,
    scale: Scale,
    accent: Color,
    measurer: TextMeasurer,
    playingStep: Int,
) {
    val columns = dotColumns(module)
    val window = gridWindow(module, area, d, scale)
    val rows = window.rows
    val cellW = area.width / columns
    val cellH = area.height / rows
    val inset = 1f * d
    val radius = CornerRadius(3f * d, 3f * d)

    if (playingStep in 0 until columns) {
        drawRect(
            color = GridPlayhead,
            topLeft = Offset(area.left + playingStep * cellW, area.top),
            size = Size(cellW, area.height),
        )
    }

    repeat(rows) { row ->
        val degree = window.degreeAt(row)
        val tonic = degree.mod(scale.size) == 0
        val top = area.top + row * cellH
        repeat(columns) { column ->
            drawRoundRect(
                color = if (tonic) GridTonic else GridCell,
                topLeft = Offset(area.left + column * cellW + inset, top + inset),
                size = Size(cellW - inset * 2f, cellH - inset * 2f),
                cornerRadius = radius,
            )
        }
        if (tonic) {
            drawLine(GridLine, Offset(area.left, top), Offset(area.right, top), 1f * d)
        }
        val label = measurer.measure(degree.toString(), if (tonic) GridTonicLabelStyle else GridLabelStyle)
        drawText(label, topLeft = Offset(area.left - label.size.width - 8f * d, top + (cellH - label.size.height) / 2f))
    }

    val substep = cellW / DOT_SUBSTEPS
    module.dots.forEach { dot ->
        if (dot.step >= columns) return@forEach
        // Its own width, in quarter steps, clipped to the grid: a dot that ends partway
        // through a cell is drawn ending there, because that is when the note ends.
        val left = area.left + dot.step * cellW
        val right = minOf(left + dot.length * substep, area.right)
        val sounding = playingStep in dot.step until dot.step + dot.stepsSpanned
        if (dot.degree in window.bottom..window.top) {
            val row = window.top - dot.degree
            val rect = Rect(
                Offset(left + inset, area.top + row * cellH + inset),
                Size(maxOf(right - left - inset * 2f, substep / 2f), cellH - inset * 2f),
            )
            val corner = CornerRadius(cellH / 3f, cellH / 3f)
            // How hard it is struck, as how much of it is filled -- Bespoke's DotSequencer
            // shows velocity this way and it is the right answer here too: the dot keeps its
            // full outline, so a quiet note is still a note at that step rather than a
            // smaller thing that has to be aimed at. Filled from the bottom, because that is
            // the direction the drag that sets it goes.
            drawRoundRect(accent.copy(alpha = 0.3f), rect.topLeft, rect.size, corner)
            val fill = rect.height * dot.velocity.coerceIn(0f, 1f)
            clipRect(rect.left, rect.bottom - fill, rect.right, rect.bottom) {
                drawRoundRect(accent, rect.topLeft, rect.size, corner)
            }
            if (sounding) {
                drawRoundRect(
                    GridPlaying, rect.topLeft, rect.size, CornerRadius(cellH / 3f, cellH / 3f),
                    style = Stroke(width = 2f * d),
                )
            }
        } else {
            // Out of sight above or below: a mark on that edge across the steps it lasts, so
            // a stretch of grid is never silently empty -- as a sequence's scrolled notes.
            val above = dot.degree > window.top
            val y = if (above) area.top else area.bottom - 3f * d
            drawRect(
                accent.copy(alpha = if (sounding) 1f else 0.6f),
                Offset(left + inset, y),
                Size(maxOf(right - left - inset * 2f, substep / 2f), 3f * d),
            )
        }
    }
}

private fun DrawScope.drawStepGrid(
    area: Rect,
    d: Float,
    module: PatchModule,
    scale: Scale,
    accent: Color,
    measurer: TextMeasurer,
    playingStep: Int,
) {
    val columns = module.type.stepCount
    val window = gridWindow(module, area, d, scale)
    val rows = window.rows
    val cellW = area.width / columns
    val cellH = area.height / rows
    val inset = 1f * d
    val radius = CornerRadius(3f * d, 3f * d)

    // Steps past the loop length still exist and are still editable; they simply are not
    // reached. Dimming them says so without hiding the work already in them.
    val length = module.params.getOrNull(0)?.toInt() ?: columns

    val topDegree = window.top

    // The column being played, behind the cells so a lit note still reads as a note.
    // Only when it is inside the loop: a length change can leave the engine reporting a
    // step that is no longer reached until the next tick.
    if (playingStep in 0 until minOf(length, columns)) {
        drawRect(
            color = GridPlayhead,
            topLeft = Offset(area.left + playingStep * cellW, area.top),
            size = Size(cellW, area.height),
        )
    }

    repeat(rows) { row ->
        val degree = window.degreeAt(row)
        val tonic = degree.mod(scale.size) == 0
        val top = area.top + row * cellH

        repeat(columns) { column ->
            val step = module.steps.getOrNull(column) ?: return@repeat
            val live = column < length
            val here = step.degree == degree
            val cell = Rect(
                Offset(area.left + column * cellW + inset, top + inset),
                Size(cellW - inset * 2f, cellH - inset * 2f),
            )

            // A silenced step draws nothing at all. It still remembers its degree --
            // which is what lets tapping the same cell bring the note back -- but a rest
            // is the absence of a note, not a note in a different color, and drawing one
            // where nothing sounds was simply a lie about what you would hear.
            val sounds = here && step.on
            val fill = when {
                sounds -> if (live) accent else accent.copy(alpha = 0.22f)
                !live -> GridDisabled
                tonic -> GridTonic
                else -> GridCell
            }
            drawRoundRect(
                color = fill,
                topLeft = cell.topLeft,
                size = cell.size,
                cornerRadius = radius,
            )

            // The note actually sounding right now, ringed rather than recolored: the
            // accent already means "there is a note here", and a second color for
            // "and it is happening" would compete with it.
            if (sounds && column == playingStep) {
                drawRoundRect(
                    color = GridPlaying,
                    topLeft = cell.topLeft,
                    size = cell.size,
                    cornerRadius = radius,
                    style = Stroke(width = 2f * d),
                )
            }
        }

        if (tonic) {
            drawLine(
                color = GridLine,
                start = Offset(area.left, top),
                end = Offset(area.right, top),
                strokeWidth = 1f * d,
            )
        }

        // Every row numbered, in the gutter the port labels already reserve. The tint
        // alone stops orienting you the moment you scroll past it, which on a
        // nineteen-degree scale is most of the time; the number works anywhere and the
        // tint tells you which of them is home.
        val label = measurer.measure(
            degree.toString(),
            if (tonic) GridTonicLabelStyle else GridLabelStyle,
        )
        drawText(
            label,
            topLeft = Offset(
                area.left - label.size.width - 8f * d,
                top + (cellH - label.size.height) / 2f,
            ),
        )
    }

    // Where the loop turns over. The columns past it are already darker, but a boundary
    // is a position rather than a shade, and counting sixteen dim squares to find it is
    // exactly the work this saves.
    if (length in 1 until columns) {
        val x = area.left + length * cellW
        drawLine(
            color = GridLoopEdge,
            start = Offset(x, area.top),
            end = Offset(x, area.bottom),
            strokeWidth = 2f * d,
        )
    }

    // A note scrolled out of sight leaves a mark on the edge it went past, so a column
    // is never silently empty -- which was indistinguishable from a rest, and is the one
    // thing the grid should never be ambiguous about.
    repeat(columns) { column ->
        val step = module.steps.getOrNull(column) ?: return@repeat
        if (!step.on) return@repeat
        val above = step.degree > topDegree
        if (!above && step.degree >= window.bottom) return@repeat

        val live = column < length
        val centerX = area.left + (column + 0.5f) * cellW
        val edgeY = if (above) area.top else area.bottom
        val point = if (above) edgeY + 1f * d else edgeY - 1f * d
        val base = if (above) edgeY + 8f * d else edgeY - 8f * d
        val half = 6f * d

        // A marker sounds the same way a cell does, or a note you cannot see would be
        // the one note the playhead never acknowledges.
        val marker = Path().apply {
            moveTo(centerX, point)
            lineTo(centerX - half, base)
            lineTo(centerX + half, base)
            close()
        }
        drawPath(
            marker,
            color = if (column == playingStep) GridPlaying
            else accent.copy(alpha = if (live) 0.9f else 0.25f),
        )
    }
}

// ------------------------------------------------------------ stepped parameters

/**
 * A stepped parameter as a row of buttons rather than a bar.
 *
 * A bar cannot show what the options are, which is fine for a length and useless for a
 * waveform: dragging to pick "square" out of four unlabeled positions asks you to know
 * the order by heart. The buttons are equal width so no option is harder to hit than
 * another, which is also why valueAt floors rather than rounds.
 */
private fun DrawScope.drawChoices(
    row: Rect,
    d: Float,
    param: Param,
    value: Float,
    accent: Color,
    measurer: TextMeasurer,
) {
    val n = param.steps
    val selected = param.indexOf(value)
    val radius = CornerRadius(8f * d, 8f * d)

    repeat(n) { i ->
        val box = choiceBox(row, d, param, i)
        val on = i == selected
        drawRoundRect(
            color = if (on) accent else Color(0xFF12151A),
            topLeft = box.topLeft,
            size = box.size,
            cornerRadius = radius,
        )
        if (!on) {
            drawRoundRect(
                color = Color(0xFF2A313B),
                topLeft = box.topLeft,
                size = box.size,
                cornerRadius = radius,
                style = Stroke(width = 1.5f * d),
            )
        }

        // Dark ink on the lit button, light on the rest: the accent colors are bright
        // enough that a white glyph on top of one disappears.
        val ink = if (on) Color(0xFF14171C) else Color(0xFFB7C0CE)
        if (param.choice == Choice.WAVE) {
            drawWave(box, d, i, ink)
        } else {
            val word = when (param.choice) {
                Choice.DIVISION -> INTERVALS.getOrNull(i)?.label.orEmpty()
                Choice.ARP -> ARP_MODES.getOrNull(i).orEmpty()
                Choice.FILTER -> FILTER_TYPES.getOrNull(i).orEmpty()
                Choice.SLOPE -> SLOPES.getOrNull(i).orEmpty()
                Choice.NUMBER -> (param.min + i).toInt().toString()
                // Never a row: a preset is chosen from its own page, off the header, and a
                // waveform is drawn rather than named a few lines above.
                Choice.WAVE, Choice.PRESET -> ""
            }
            if (word.isNotEmpty()) {
                val text = measurer.measure(word, PanelValueStyle)
                drawText(
                    text,
                    color = ink,
                    topLeft = Offset(
                        box.center.x - text.size.width / 2f,
                        box.center.y - text.size.height / 2f,
                    ),
                )
            }
        }
    }
}

/**
 * Two cycles of waveform [index], sampled rather than hand-drawn as four paths.
 *
 * Sampling keeps all four glyphs one code path and consistent with each other, and the
 * near-vertical segments read as vertical at this size. Two cycles rather than one
 * because a single descending ramp is a slope, not a sawtooth: the reset is the part
 * that names it, and the same goes for the square's second edge.
 *
 * Sampled at segment midpoints so no sample ever lands exactly on a discontinuity, which
 * would otherwise pin a vertex to the top or bottom of the jump depending on rounding.
 */
private fun DrawScope.drawWave(box: Rect, d: Float, index: Int, color: Color) {
    val w = minOf(box.width * 0.62f, 54f * d)
    val h = minOf(box.height * 0.46f, 15f * d)
    val left = box.center.x - w / 2f
    val mid = box.center.y

    // Phases follow the convention these glyphs are read by: the saw starts at the top
    // of a ramp and the square starts high, so both switch on the cycle boundary rather
    // than partway through it. Sine and triangle start at zero and rise.
    fun sample(u: Float): Float {
        val t = u % 1f
        return when (index) {
            // Descending, matching DaisySP: it computes the rising ramp and negates it,
            // so the real output falls and resets upward.
            0 -> 1f - 2f * t
            1 -> if (t < 0.5f) 1f else -1f
            2 -> 1f - 4f * kotlin.math.abs((t + 0.25f) % 1f - 0.5f)
            else -> kotlin.math.sin(2f * PI.toFloat() * t)
        }
    }

    val cycles = 2f
    val steps = 96
    val path = Path()
    repeat(steps) { i ->
        val f = (i + 0.5f) / steps
        val x = left + w * f
        val y = mid - sample(f * cycles) * h
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = 2f * d, cap = StrokeCap.Round))
}

// ---------------------------------------------------------------- history buttons

private val HistoryFill = Color(0xFF1E232B)
private val HistoryEdge = Color(0xFF3A424E)
private val HistoryGlyph = Color(0xFFB7C0CE)

/**
 * One curved arrow, mirrored.
 *
 * Both buttons draw the same arc over the top and differ only in which end carries the
 * head -- which is what the gesture means, and reads at a glance without a label. The
 * head is oriented from the tangent rather than a fixed rotation, so it stays attached
 * to the arc if the sweep is ever adjusted.
 */
private fun DrawScope.drawHistoryButton(rect: Rect, d: Float, redo: Boolean) {
    drawRoundRect(
        color = HistoryFill,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(PatchModule.CORNER * d, PatchModule.CORNER * d),
    )
    drawRoundRect(
        color = HistoryEdge,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = CornerRadius(PatchModule.CORNER * d, PatchModule.CORNER * d),
        style = Stroke(width = 1.5f * d),
    )

    val center = rect.center
    val radius = rect.width * 0.24f
    val stroke = 2.2f * d

    // Drawn a touch high: the arrowheads hang below the arc, so centering the arc itself
    // would leave the glyph sitting low in the box.
    val arc = Offset(center.x, center.y - radius * 0.35f)
    drawArc(
        color = HistoryGlyph,
        startAngle = START_ANGLE,
        sweepAngle = SWEEP_ANGLE,
        useCenter = false,
        topLeft = Offset(arc.x - radius, arc.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )

    // The head sits at the end the gesture travels towards, pointing along the arc:
    // undo runs back to the left, redo on to the right.
    val theta = ((if (redo) START_ANGLE + SWEEP_ANGLE else START_ANGLE) * PI / 180f).toFloat()
    val tip = Offset(arc.x + radius * cos(theta), arc.y + radius * sin(theta))
    // d/dtheta of the arc, negated for undo because it runs the other way round.
    val along = Offset(-sin(theta), cos(theta)) * (if (redo) 1f else -1f)
    val across = Offset(-along.y, along.x)
    val head = rect.width * 0.115f

    drawPath(
        Path().apply {
            moveTo(tip.x + along.x * head, tip.y + along.y * head)
            val back = tip - along * (head * 0.35f)
            lineTo(back.x + across.x * head * 0.8f, back.y + across.y * head * 0.8f)
            lineTo(back.x - across.x * head * 0.8f, back.y - across.y * head * 0.8f)
            close()
        },
        color = HistoryGlyph,
    )
}

/** The arc spans the top, leaving both ends clear of the box's sides. */
private const val START_ANGLE = 200f
private const val SWEEP_ANGLE = 140f

// ---------------------------------------------------------------- transport

private val TransportAccent = Color(0xFFD9C46A)

/**
 * The transport chip, and its card while open.
 *
 * The chip shows the tempo, since that is the number you glance at it for. Where the
 * transport has got to is drawn only while the card is open, which is also the only time
 * it is polled: a readout ticking in the corner would keep the canvas repainting every
 * frame for nobody.
 */
private fun DrawScope.drawTransport(
    frame: Frame,
    d: Float,
    patch: Patch,
    open: Boolean,
    beat: Double,
    measurer: TextMeasurer,
) {
    val bpm = "${patch.tempo.roundToInt()} bpm"
    drawChip(frame.transportChip(), d, bpm, open, TransportAccent, measurer)
    if (!open) return

    val card = frame.transportCard()
    val corner = CornerRadius(10f * d, 10f * d)
    drawRoundRect(Color(0xFF1B1F26), card.topLeft, card.size, corner)
    drawRoundRect(ChipEdge, card.topLeft, card.size, corner, style = Stroke(width = 1.5f * d))

    val tempoRow = transportTempoRow(card, d)
    drawText(measurer.measure(TEMPO.name, PanelParamStyle), topLeft = tempoRow.topLeft)
    val reading = measurer.measure(bpm, PanelValueStyle)
    drawText(reading, topLeft = Offset(tempoRow.right - reading.size.width, tempoRow.top))
    val barHeight = PatchModule.PANEL_BAR * d
    val barTop = tempoRow.bottom - barHeight - 4f * d
    val radius = CornerRadius(barHeight / 2f, barHeight / 2f)
    drawRoundRect(
        Color(0xFF12151A), Offset(tempoRow.left, barTop), Size(tempoRow.width, barHeight), radius,
    )
    drawRoundRect(
        TransportAccent,
        Offset(tempoRow.left, barTop),
        Size((tempoRow.width * TEMPO.positionOf(patch.tempo)).coerceAtLeast(barHeight), barHeight),
        radius,
    )

    val beatsRow = transportBeatsRow(card, d)
    drawText(measurer.measure(BEATS_PER_BAR.name, PanelParamStyle), topLeft = beatsRow.topLeft)
    drawChoices(beatsRow, d, BEATS_PER_BAR, patch.beatsPerBar.toFloat(), TransportAccent, measurer)

    // Bar and beat, both counted from one, as they are said.
    val reset = transportReset(card, d)
    val perBar = patch.beatsPerBar.coerceAtLeast(1)
    val whole = floor(beat).toLong().coerceAtLeast(0L)
    val position = measurer.measure(
        "bar ${whole / perBar + 1}  ·  beat ${whole % perBar + 1}",
        PanelValueStyle,
    )
    drawText(position, topLeft = Offset(tempoRow.left, reset.center.y - position.size.height / 2f))

    val buttonCorner = CornerRadius(8f * d, 8f * d)
    drawRoundRect(ChipFill, reset.topLeft, reset.size, buttonCorner)
    drawRoundRect(ChipEdge, reset.topLeft, reset.size, buttonCorner, style = Stroke(width = 1.5f * d))
    val label = measurer.measure("reset", PanelChipStyle)
    drawText(
        label,
        topLeft = Offset(reset.center.x - label.size.width / 2f, reset.center.y - label.size.height / 2f),
    )
}

/**
 * A touch that landed on the open transport card.
 *
 * Its own short loop, like the panel's, because the card owns what lands on it: a drag
 * along the tempo bar must not also pan the canvas underneath.
 */
private suspend fun AwaitPointerEventScope.transportCardGesture(
    frame: Frame,
    down: Offset,
    patch: Patch,
    onReset: () -> Unit,
    /** Called when the tempo's reading was tapped, which opens the keypad on it. */
    onTypeTempo: () -> Unit,
) {
    val d = frame.density
    val card = frame.transportCard()
    val tempoRow = transportTempoRow(card, d)

    // Before the bar under it, and tap-only: the reading is where the tempo is written
    // down, and the bar an inch below is where it is dragged.
    if (transportTempoValue(card, d).contains(down)) {
        waitForUpRelease()
        onTypeTempo()
        return
    }
    // Generous vertically, like a panel's knobs: the bar is the row's only target.
    val onTempo = tempoRow.inflate(6f * d).contains(down)

    // Whole beats per minute. The bar is some 270dp for 280bpm, so a finger cannot hold a
    // fraction of one steady anyway, and the chip reads better without it.
    fun tempoAt(x: Float) =
        TEMPO.valueAt((x - tempoRow.left) / tempoRow.width).roundToInt().toFloat()

    if (onTempo) patch.tempo = tempoAt(down.x)
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        val change = pressed.first()
        if (onTempo) patch.tempo = tempoAt(change.position.x)
        change.consume()
    }
    if (onTempo) return

    val beatsRow = transportBeatsRow(card, d)
    if (beatsRow.contains(down)) {
        patch.beatsPerBar =
            BEATS_PER_BAR.valueAt((down.x - beatsRow.left) / beatsRow.width).toInt()
    } else if (transportReset(card, d).contains(down)) {
        onReset()
    }
}

// ---------------------------------------------------------------- scales

/** A small button: a box with a centered label, dimmed when it would do nothing. */
private fun DrawScope.drawKey(
    rect: Rect,
    d: Float,
    label: String,
    measurer: TextMeasurer,
    enabled: Boolean = true,
) {
    val corner = CornerRadius(7f * d, 7f * d)
    val alpha = if (enabled) 1f else 0.35f
    drawRoundRect(ChipFill, rect.topLeft, rect.size, corner, alpha = alpha)
    drawRoundRect(ChipEdge, rect.topLeft, rect.size, corner, style = Stroke(width = 1.5f * d), alpha = alpha)
    val text = measurer.measure(label, PanelValueStyle)
    drawText(
        text,
        topLeft = Offset(rect.center.x - text.size.width / 2f, rect.center.y - text.size.height / 2f),
        alpha = alpha,
    )
}

/**
 * The scale chip, and its card -- or its page of tiles -- while open.
 *
 * The chip names the scale sounding now and, while a list plays, which entry of how many:
 * the one thing about a cycling scale that the grid, which only ever shows the current
 * one, cannot tell you.
 */
private fun DrawScope.drawScales(
    frame: Frame,
    d: Float,
    patch: Patch,
    library: List<Scale>,
    playingEntry: Int,
    open: Boolean,
    view: ScaleCardView,
    measurer: TextMeasurer,
) {
    val entries = patch.scales
    val playing = entries.getOrElse(playingEntry) { entries.first() }.scale
    // The key before anything else: it moves every note in the patch, and until the chip
    // named it there was no telling what it was without opening the card. It used to say
    // how many degrees the scale has, which its name mostly says already.
    val root = entries.getOrElse(playingEntry) { entries.first() }.rootCents
    val suffix = if (entries.size == 1) "  ·  ${noteWithOctave(root)}"
        else "  ·  ${noteWithOctave(root)}  ·  ${playingEntry + 1} of ${entries.size}"
    drawChip(frame.scaleChip(), d, playing.name, open, scaleAccent, measurer, suffix)
    if (!open) return

    val corner = CornerRadius(10f * d, 10f * d)
    val picking = view.pickingFor
    if (picking in entries.indices) {
        val picker = frame.scalePicker()
        drawRoundRect(Color(0xFF1B1F26), picker.topLeft, picker.size, corner)
        drawRoundRect(ChipEdge, picker.topLeft, picker.size, corner, style = Stroke(width = 1.5f * d))
        drawText(
            measurer.measure("scale for entry ${picking + 1}", PanelParamStyle),
            topLeft = Offset(picker.left + 14f * d, picker.top + 10f * d),
        )
        val chosen = entries[picking].scale.name
        scalePickerTiles(picker, d, library.size).forEachIndexed { i, tile ->
            drawTile(tile, d, library[i].name, scaleDetail(library[i]), library[i].name == chosen, measurer)
        }
        return
    }

    val rooting = view.rootFor
    if (rooting in entries.indices) {
        val page = frame.scaleRootPage()
        drawRoundRect(Color(0xFF1B1F26), page.topLeft, page.size, corner)
        drawRoundRect(ChipEdge, page.topLeft, page.size, corner, style = Stroke(width = 1.5f * d))
        val entry = entries[rooting]
        drawText(
            measurer.measure("root for entry ${rooting + 1}  ·  ${entry.scale.name}", PanelParamStyle),
            topLeft = Offset(page.left + 14f * d, page.top + 10f * d),
        )
        // Cents are the unit; the frequency and the nearest letter are readings of it.
        val hz = MIDDLE_C_HZ * Math.pow(2.0, entry.rootCents / 1200.0).toFloat()
        drawText(
            measurer.measure(
                "${formatCents(entry.rootCents)}  ·  ${"%.1f".format(hz)} Hz  ·  ${nearestNoteName(entry.rootCents)}",
                PanelValueStyle,
            ),
            topLeft = Offset(page.left + 14f * d, page.top + 40f * d),
        )

        val slider = rootSlider(page, d)
        val barHeight = PatchModule.PANEL_BAR * d
        val barTop = slider.top + 16f * d
        drawRoundRect(
            Color(0xFF12151A), Offset(slider.left, barTop), Size(slider.width, barHeight),
            CornerRadius(barHeight / 2f, barHeight / 2f),
        )
        drawScaleMarks(slider, barTop, barHeight, d, ROOT, entry.scale)
        drawCircle(
            color = scaleAccent,
            radius = 11f * d,
            center = Offset(slider.left + slider.width * ROOT.positionOf(entry.rootCents), barTop + barHeight / 2f),
        )
        drawKey(rootFineLess(page, d), d, "−1¢", measurer, entry.rootCents > ROOT.min)
        drawKey(rootFineMore(page, d), d, "+1¢", measurer, entry.rootCents < ROOT.max)
        return
    }

    val card = frame.scaleCard(entries.size)
    drawRoundRect(Color(0xFF1B1F26), card.topLeft, card.size, corner)
    drawRoundRect(ChipEdge, card.topLeft, card.size, corner, style = Stroke(width = 1.5f * d))

    val columns = scaleRowParts(scaleCardRow(card, d, 0), d)
    val headTop = card.top + Frame.SCALE_CARD_PAD * d
    fun heading(text: String, left: Float, right: Float, centered: Boolean) {
        val t = measurer.measure(text, GridLabelStyle)
        val x = if (centered) (left + right) / 2f - t.size.width / 2f else left
        drawText(t, topLeft = Offset(x, headTop))
    }
    heading("scale", columns.name.left, columns.name.right, centered = false)
    heading("bars", columns.barsLess.left, columns.barsMore.right, centered = true)
    heading("beats", columns.beatsLess.left, columns.beatsMore.right, centered = true)
    heading("root", columns.rootLess.left, columns.rootMore.right, centered = true)

    fun value(rect: Rect, text: String) {
        val t = measurer.measure(text, PanelValueStyle)
        drawText(t, topLeft = Offset(rect.center.x - t.size.width / 2f, rect.center.y - t.size.height / 2f))
    }

    val visible = frame.scaleRowsThatFit()
    val scroll = view.scroll.coerceIn(0, maxOf(0, entries.size - visible))
    for (slot in 0 until minOf(visible, entries.size - scroll)) {
        val index = scroll + slot
        val entry = entries[index]
        val parts = scaleRowParts(scaleCardRow(card, d, slot), d)
        // Lit while it is the one sounding, and only when there is a list to be in.
        drawChip(
            parts.name, d, entry.scale.name, entries.size > 1 && index == playingEntry,
            scaleAccent, measurer,
        )
        drawKey(parts.barsLess, d, "−", measurer, entry.bars > 0)
        value(parts.bars, entry.bars.toString())
        drawKey(parts.barsMore, d, "+", measurer, entry.bars < MAX_ENTRY_BARS)
        drawKey(parts.beatsLess, d, "−", measurer, entry.beats > 0)
        value(parts.beats, entry.beats.toString())
        drawKey(parts.beatsMore, d, "+", measurer, entry.beats < MAX_ENTRY_BEATS)
        // A hundred cents a step, which reaches every key a twelve-note scale is written
        // in. The value is itself a button, into the page that reaches everything else.
        drawKey(parts.rootLess, d, "−", measurer, entry.rootCents > ROOT.min)
        drawKey(parts.root, d, formatCents(entry.rootCents), measurer)
        drawKey(parts.rootMore, d, "+", measurer, entry.rootCents < ROOT.max)
        if (entries.size > 1) drawKey(parts.remove, d, "×", measurer)
    }

    // A mark where the list runs on past what is shown, so a scrolled list never looks
    // like the whole of it.
    val list = scaleCardList(card, d)
    fun more(atTop: Boolean) {
        val y = if (atTop) list.top else list.bottom
        val point = if (atTop) y - 5f * d else y + 5f * d
        val x = list.center.x
        drawPath(
            Path().apply {
                moveTo(x, point)
                lineTo(x - 7f * d, y)
                lineTo(x + 7f * d, y)
                close()
            },
            color = MarkTonic,
        )
    }
    if (scroll > 0) more(atTop = true)
    if (scroll + visible < entries.size) more(atTop = false)

    drawKey(scaleCardAdd(card, d), d, "+ add", measurer, entries.size < MAX_SCALE_ENTRIES)
}

/**
 * A touch that landed on the open scale card, or on its picker while an entry is choosing.
 *
 * Its own short loop, like the transport card's. A drag down the list scrolls it by
 * whole rows; a tap does whatever is under it. Every edit replaces the list whole, so it
 * saves, undoes and reaches the engine through the paths any patch edit takes.
 */
private suspend fun AwaitPointerEventScope.scaleCardGesture(
    frame: Frame,
    down: Offset,
    patch: Patch,
    library: List<Scale>,
    view: ScaleCardView,
    slop: Float,
) {
    val d = frame.density

    if (view.pickingFor >= 0) {
        waitForUpRelease()
        val index = view.pickingFor
        val hit = scalePickerTiles(frame.scalePicker(), d, library.size).indexOfFirst { it.contains(down) }
        if (hit >= 0 && index in patch.scales.indices) {
            patch.scales = patch.scales.toMutableList().also { it[index] = it[index].copy(scale = library[hit]) }
        }
        // Back to the list whatever was tapped: the picker asks one question, and a tap
        // anywhere but a tile is declining to answer it.
        view.pickingFor = -1
        return
    }

    if (view.rootFor >= 0) {
        val index = view.rootFor
        val page = frame.scaleRootPage()
        val slider = rootSlider(page, d)
        val entry = patch.scales.getOrNull(index)
        if (entry == null) {
            view.rootFor = -1
            waitForUpRelease()
            return
        }
        fun setRoot(cents: Float) {
            patch.scales = patch.scales.toMutableList()
                .also { it[index] = it[index].copy(rootCents = cents.coerceIn(ROOT.min, ROOT.max)) }
        }

        if (slider.contains(down)) {
            var sliding = false
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                val change = pressed.first()
                if ((change.position - down).getDistance() > slop) sliding = true
                if (sliding) {
                    // Continuous, never snapping: see rootAtTap.
                    setRoot(ROOT.valueAt((change.position.x - slider.left) / slider.width).roundToInt().toFloat())
                }
                change.consume()
            }
            if (!sliding) setRoot(rootAtTap(down.x, slider, d, entry.scale))
            return
        }

        waitForUpRelease()
        when {
            rootFineLess(page, d).contains(down) -> setRoot(entry.rootCents - 1f)
            rootFineMore(page, d).contains(down) -> setRoot(entry.rootCents + 1f)
            // Anywhere else on the page goes back to the list, like the picker.
            else -> view.rootFor = -1
        }
        return
    }

    val card = frame.scaleCard(patch.scales.size)
    val visible = frame.scaleRowsThatFit()
    val list = scaleCardList(card, d)
    val scrollFrom = view.scroll
    var moved = false
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break
        val change = pressed.first()
        if ((change.position - down).getDistance() > slop) moved = true
        if (moved && list.contains(down)) {
            // Up the screen moves on down the list, as any list under a finger does.
            val rows = ((down.y - change.position.y) / (Frame.SCALE_ROW * d)).roundToInt()
            view.scroll = (scrollFrom + rows).coerceIn(0, maxOf(0, patch.scales.size - visible))
        }
        change.consume()
    }
    if (moved) return

    val entries = patch.scales
    val scroll = view.scroll.coerceIn(0, maxOf(0, entries.size - visible))
    fun replace(index: Int, entry: ScaleEntry) {
        patch.scales = entries.toMutableList().also { it[index] = entry }
    }

    for (slot in 0 until minOf(visible, entries.size - scroll)) {
        val index = scroll + slot
        val entry = entries[index]
        val parts = scaleRowParts(scaleCardRow(card, d, slot), d)
        when {
            parts.name.contains(down) -> view.pickingFor = index
            parts.barsLess.contains(down) -> replace(index, entry.copy(bars = (entry.bars - 1).coerceAtLeast(0)))
            parts.barsMore.contains(down) -> replace(index, entry.copy(bars = (entry.bars + 1).coerceAtMost(MAX_ENTRY_BARS)))
            parts.beatsLess.contains(down) -> replace(index, entry.copy(beats = (entry.beats - 1).coerceAtLeast(0)))
            parts.beatsMore.contains(down) -> replace(index, entry.copy(beats = (entry.beats + 1).coerceAtMost(MAX_ENTRY_BEATS)))
            parts.rootLess.contains(down) -> replace(index, entry.copy(rootCents = (entry.rootCents - 100f).coerceAtLeast(ROOT.min)))
            parts.rootMore.contains(down) -> replace(index, entry.copy(rootCents = (entry.rootCents + 100f).coerceAtMost(ROOT.max)))
            parts.root.contains(down) -> view.rootFor = index
            parts.remove.contains(down) && entries.size > 1 -> {
                patch.scales = entries.toMutableList().also { it.removeAt(index) }
                view.scroll = view.scroll.coerceIn(0, maxOf(0, patch.scales.size - visible))
            }
            else -> continue
        }
        return
    }

    if (scaleCardAdd(card, d).contains(down) && entries.size < MAX_SCALE_ENTRIES) {
        // A copy of the last entry, which is usually the next thing wanted -- the same
        // scale for as long, ready to be changed -- and never an empty row to fill in.
        patch.scales = entries + entries.last()
        view.scroll = maxOf(0, patch.scales.size - visible)
    }
}

// ---------------------------------------------------------------- tap logic

private fun handleTap(
    patch: Patch,
    camera: Camera,
    frame: Frame,
    current: Interaction,
    screen: Offset,
    touchPx: Float,
    controls: CanvasControls,
): Interaction {
    if (current is Interaction.Menu) {
        val layout = menuLayout(
            menuItems(patch, current.targetId, current.port, controls.saved.takeIf { current.library }),
            current.anchor, frame.density, frame.canvas, frame.fontScale,
        )
        val chosen = layout.tiles.firstOrNull { it.first.contains(screen) }?.second
            ?: return Interaction.Idle // tapped away: dismiss
        when (chosen) {
            is MenuItem.Add -> {
                // Place the new module centered on where the long press landed.
                val world = camera.toWorld(current.anchor)
                patch.add(
                    chosen.type,
                    world - Offset(
                        PatchModule.WIDTH / 2f,
                        PatchModule.heightFor(chosen.type) / 2f,
                    ),
                )
            }
            is MenuItem.Duplicate -> patch.module(chosen.moduleId)?.let { patch.duplicate(it) }
            is MenuItem.Delete -> patch.module(chosen.moduleId)?.let { patch.remove(it) }
            is MenuItem.StartSubpatch -> return Interaction.Selecting(emptySet(), chosen.type)
            is MenuItem.Unpack -> patch.module(chosen.moduleId)?.let { patch.unpack(it) }
            is MenuItem.Rename ->
                return if (patch.module(chosen.moduleId) == null) Interaction.Idle
                else Interaction.Renaming(chosen.moduleId)
            is MenuItem.Save -> return Interaction.Saving(chosen.moduleId)
            is MenuItem.OpenLibrary ->
                return Interaction.Menu(current.anchor, null, library = true)
            is MenuItem.Load -> {
                // Centered on the press that opened the menu, as a new module is.
                val world = camera.toWorld(current.anchor)
                controls.onLoadSubpatch(
                    chosen.name,
                    world - Offset(PatchModule.WIDTH / 2f, PatchModule.heightFor(Types.Subpatch) / 2f),
                )
            }
            is MenuItem.NewPatch -> patch.reset()
            is MenuItem.LibraryEmpty -> Unit
            is MenuItem.RemovePort -> patch.module(chosen.subpatchId)?.let {
                patch.removeSubpatchPort(it, chosen.dir, chosen.index)
            }
            is MenuItem.Knobs -> patch.module(chosen.moduleId)?.let { subpatch ->
                patch.modules.forEach { it.expanded = false }
                subpatch.expanded = true
            }
        }
        return Interaction.Idle
    }

    // Undo before anything else on the canvas, and regardless of what is armed. It is
    // the control you reach for when the last thing you did was wrong, and making it
    // wait its turn behind an armed connection would be exactly backwards.
    if (controls.tapHistory(frame, screen)) return Interaction.Idle

    // The breadcrumb, before anything under it: it is how you get back out, so it must
    // never lose a tap to whatever happens to be beneath it on the canvas.
    patch.breadcrumbAt(frame, screen)?.let { id ->
        if (id != patch.scopeOrTop) patch.enterScope(id)
        return Interaction.Idle
    }

    if (current is Interaction.Selecting) {
        if (frame.selectionButton(done = true).contains(screen)) {
            // Nothing chosen is not an error to announce; the mode simply stays until it is
            // given something or cancelled.
            if (current.ids.isEmpty()) return current
            patch.makeSubpatch(current.ids, current.type)
            return Interaction.Idle
        }
        if (frame.selectionButton(done = false).contains(screen)) return Interaction.Idle
        val module = patch.hitModule(camera, frame, screen)?.takeIf { !it.isPinned }
            ?: return current
        return Interaction.Selecting(
            if (module.id in current.ids) current.ids - module.id else current.ids + module.id,
            current.type,
        )
    }

    val port = patch.hitPort(camera, frame, screen, touchPx)

    // A rail's body is its switch. Only while idle, so it never eats the tap that
    // cancels an armed connection.
    if (port == null && current is Interaction.Idle && patch.scopeOrTop == TOP) {
        patch.module(OUT_ID)?.let { out ->
            if (frame.railRect(out).contains(screen)) {
                controls.onToggleOutput()
                return Interaction.Idle
            }
        }
        patch.module(IN_ID)?.let { input ->
            if (frame.railRect(input).contains(screen)) {
                controls.onToggleInput()
                return Interaction.Idle
            }
        }
    }

    if (port == null && current is Interaction.Idle) {
        patch.hitModule(camera, frame, screen)?.let { module ->
            // A subpatch's body is its way in. Opening a composite is going inside it, as
            // opening a primitive shows its controls.
            if (module.type.box) {
                patch.enterScope(module.id)
                return Interaction.Idle
            }
            // Tapping a module's body opens its panel. One at a time: the panel takes the
            // screen, so there is nowhere for a second one to go.
            if (!module.isPinned && module.type.hasPanel) {
                patch.modules.forEach { it.expanded = false }
                module.expanded = true
                return Interaction.Idle
            }
        }
    }

    return when (current) {
        is Interaction.Idle -> {
            if (port != null) Interaction.Connecting(port) else Interaction.Idle
        }
        is Interaction.Connecting -> when {
            // Inside a subpatch, an armed jack taken to the slot at the end of the matching
            // rail makes a new port for it: an output to the right rail, an input or a
            // knob's jack to the left.
            patch.subpatchPortSlotHit(frame, current.source, screen, touchPx) ->
                if (patch.addSubpatchPort(patch.scopeOrTop, current.source)) Interaction.Idle else current
            // The rest of that rail, or the other one, keeps the jack armed rather than
            // disarming: a tap that did nothing and dropped the jack reads as a missed tap.
            port == null && patch.scopeOrTop != TOP &&
                patch.shownRails.any { frame.railRect(it).contains(screen) } -> current
            port == null -> Interaction.Idle                      // tapped away: cancel
            port == current.source -> {                            // tapped self: unpatch
                patch.disconnect(port)
                Interaction.Idle
            }
            port.dir != current.source.dir ->                      // opposite side
                // Refused rather than made when the two do not patch -- notes and signals
                // do not. The port stays armed, because a tap that did nothing and
                // disarmed as well would look like the tap was never seen at all.
                if (patch.connect(current.source, port)) Interaction.Idle else current
            else -> Interaction.Connecting(port)                   // same side: re-arm
        }
        // A menu is always resolved first and choosing modules has its own branch, so
        // both are returned above. Renaming never arrives here at all: its scrim is a
        // composable over the canvas and takes every touch while it is up.
        is Interaction.Menu, is Interaction.Selecting,
        is Interaction.Renaming, is Interaction.Typing, is Interaction.Saving,
        -> Interaction.Idle
    }
}

// ---------------------------------------------------------------- context menu

/**
 * Mirrors kMaxPorts in node.h. A module with more ports than this would have its extra
 * cables silently dropped by the engine, so it is asserted in a test rather than trusted.
 *
 * Eight since poly subpatches, where it bounds the voices too: the engine's note edge hands
 * one output to each instance and its summing node takes one input back, so a port is an
 * instance at those two nodes. No module declares more than four, which is still as many
 * jacks as a 116dp face holds at a finger's height; a subpatch's box grows with the ports
 * it was given and was never bound by a declaration.
 */
internal const val MAX_PORTS = 8

/** Mirrors kMaxParams in node.h. A ninth knob would simply never reach the engine. */
internal const val MAX_PARAMS = 8

/**
 * The most knobs a subpatch can carry out to its edge.
 *
 * The same as [MAX_PARAMS], for the same reason a module stops there: the panel lays out at
 * most two columns of four, and a ninth row is thinner than a finger. Nothing in the engine cares
 * -- a promoted knob is a reference, and the engine only ever sees the module inside.
 */
internal const val MAX_PROMOTED = MAX_PARAMS

/**
 * Mirrors StepsNode::kSteps in nodes.h. A seventeenth step would be written here, saved
 * to the file, and silently dropped on the way to the engine.
 */
internal const val STEP_COUNT = 16

/** Cells in a drone's grid; mirrors DroneNode::kCells, which is capped by a scale's degrees. */
internal const val DRONE_CELLS = 64

/** Steps on a dot sequencer's grid. Mirrors SeqNode::kSteps. */
internal const val DOT_STEPS = 32

/**
 * Divisions of a step a dot's length is counted in. Mirrors SeqNode::kDotSubsteps.
 *
 * A dot's length *is* its duration -- that is what a dot sequencer is, and it is Bespoke's
 * model. It was whole steps once, which meant nothing could be shorter than a step, and a
 * `gate` knob was added to take a share off the last step of every note at once when Seq
 * took Steps' place in the menu. Quarter steps say the same thing per note and say more, so
 * the knob went: Steps' half step is a length of 2.
 *
 * Four, which is what a finger can place on a cell a finger can hit. At 32 columns a cell
 * is 20dp and a quarter of it is 5dp, past what a drag can aim at -- but 32 columns is the
 * longest loop there is, and a short one has room to spare.
 */
internal const val DOT_SUBSTEPS = 4

/**
 * The quietest a dot can be dragged to.
 *
 * Not zero: a silent dot draws and takes its step like any other, so the only way to learn
 * it was there would be to drag it back up. A dot you do not want is removed with a tap.
 */
internal const val MIN_VELOCITY = 0.05f

/**
 * How far a finger travels, in dp, to take a dot's velocity across its whole range.
 *
 * A fixed distance rather than a share of the grid, because the grid's height is however
 * many rows a scale happens to show and the drag should not get coarser on a long scale.
 * 120dp is about a comfortable thumb swing on the reference device, and the drag is
 * relative to where the dot already was, so a long pass over a phrase never jumps.
 */
internal const val VELOCITY_TRAVEL = 120f

/** An Arp's modes, as its buttons say them. Mirrors ArpNode::step. */
internal val ARP_MODES = listOf("up", "down", "up/dn", "rand")

/**
 * A Filter's kinds, in the order FilterNode::Type has them -- which is also the order the
 * file stores, so this list is appended to and never reordered.
 *
 * "notch" rather than "band reject", because the buttons are 60dp wide and everyone who
 * reaches for one calls it a notch.
 */
internal val FILTER_TYPES = listOf("low", "high", "band", "notch")

/** How steeply a Filter rolls off: one pole pair, or two of them in series. */
internal val SLOPES = listOf("12dB", "24dB")

/** A Euclid's longest pattern. Mirrors EuclidNode::kMaxSteps. */
internal const val EUCLID_STEPS = 32

/** The most options a stepped row draws as buttons; past it, a bar. See [Param.buttons]. */
internal const val MAX_BUTTONS = 16

/** Seq's accent, a green that clears the others; see ModuleColorTest. */
internal const val SEQ_ACCENT = 0xFFD8F0AC

/** Dots one sequencer holds. Mirrors SeqNode::kMaxDots. */
internal const val MAX_DOTS = 128

/**
 * Segments one envelope holds. Mirrors EnvNode::kMaxSegments.
 *
 * A cap rather than a scroll or a zoom, which were the other two answers. Eight nodes
 * across a phone in landscape stay a fingertip apart at every spacing; a scroll would need
 * a gesture that competes with dragging a node on the one surface where a drag already
 * means "move this", and a zoom-to-fit puts the nodes closest together exactly when the
 * envelope gets interesting. The number is the cheap part -- raise it once it has been
 * played and found short.
 */
internal const val MAX_SEGMENTS = 8

/** The longest one envelope segment may last, in seconds. */
internal const val SEGMENT_MAX_TIME = 10f

/** The shortest, which the keypad can still be used to ask for exactly. */
internal const val SEGMENT_MIN_TIME = 0.001f

/** The most octave columns a drone offers, before its cells run out. */
internal const val DRONE_OCTAVES = 4

/** Mirrors kTuneRange in nodes.cpp: how far a tuning control reaches, in cents. */
internal const val TUNE_RANGE = 2400f

/**
 * A note length a clocked module can step at: [num]/[den] quarter-note beats.
 *
 * Written as fractions of a whole note because that is how they are said -- "sixteenths",
 * "eighth-note triplets" -- rather than Bespoke's 16n and 8nt, which have to be learned.
 */
internal data class Interval(val label: String, val detail: String, val num: Int, val den: Int)

/** Mirrors kIntervals in nodes.h, and is indexed the same way. Append rather than reorder. */
internal val INTERVALS = listOf(
    Interval("1/1", "whole", 4, 1),
    Interval("1/2", "half", 2, 1),
    Interval("1/4", "quarter", 1, 1),
    Interval("1/8", "eighth", 1, 2),
    Interval("1/16", "sixteenth", 1, 4),
    Interval("1/32", "thirty-second", 1, 8),
    Interval("1/4T", "quarter triplet", 2, 3),
    Interval("1/8T", "eighth triplet", 1, 3),
    Interval("1/16T", "sixteenth triplet", 1, 6),
)

/** Mirrors kDefaultInterval: an eighth. */
internal const val DEFAULT_INTERVAL = 3

/** The transport's rate. The range mirrors kMinTempo and kMaxTempo in transport.h. */
internal val TEMPO = Param("tempo", 20f, 300f, 120f, " bpm")

/**
 * A segment's duration on the keypad, in **milliseconds**, not seconds.
 *
 * This is the whole reason the keypad is wired to an envelope: nobody can drag a node to
 * 12ms and everybody can tap it and type 12. In seconds that same intent is 0.012, which is
 * three keys and a decimal point to say a number you were already holding.
 */
internal val SEGMENT_TIME = Param(
    "time", SEGMENT_MIN_TIME * 1000f, SEGMENT_MAX_TIME * 1000f, 100f, "ms", ParamCurve.EXPONENTIAL,
)

internal val BEATS_PER_BAR = Param("beats per bar", 2f, 8f, 4f, curve = ParamCurve.STEPPED)


/**
 * What a new sequencer plays.
 *
 * The figure StepsNode used to have compiled in, now living on this side because the
 * pattern belongs to the patch. Degrees of the current scale rather than semitones, so
 * it is a shape rather than a set of intervals -- in 19-TET or Bohlen-Pierce it is the
 * same gesture through a different tuning.
 */
private val DEFAULT_PATTERN = listOf(0, 3, 7, 10, 12, 10, 7, 3)

internal fun defaultSteps(type: ModuleType): List<Step> = when (type.grid) {
    // A cell is its own degree, and a fresh drone sounds nothing: a module that started
    // holding a chord nobody asked for would be a module you have to switch off.
    GridKind.DRONE -> (0 until type.stepCount).map { Step(it, on = false) }
    else -> (0 until type.stepCount).map { Step(DEFAULT_PATTERN[it % DEFAULT_PATTERN.size]) }
}

/** Column cap for the context menu, visible to tests. */
internal const val MENU_COLS = 4

private object MenuMetrics {
    const val TILE_W = 74f
    const val TILE_H = 40f
    const val GAP = 5f
    const val PAD = 7f
    const val COLS = MENU_COLS
    /** Lifted clear of the fingertip that opened it. */
    const val LIFT = 20f
    const val SCREEN_MARGIN = 10f
}

internal class MenuLayout(val rect: Rect, val tiles: List<Pair<Rect, MenuItem>>)

internal fun menuLayout(
    items: List<MenuItem>,
    anchor: Offset,
    d: Float,
    canvas: Size,
    /**
     * The text size setting. A tile holds a label, and the label is in sp, so at a larger
     * text size a tile has to be larger too -- at 1.5, the reference device's own setting,
     * "Save patch..." was half as wide again as its tile. Grown rather than the label
     * shrunk, because shrinking it undoes the setting the user chose in order to read it.
     */
    textScale: Float = 1f,
): MenuLayout {
    val tileW = MenuMetrics.TILE_W * textScale.coerceAtLeast(1f)
    val tileH = MenuMetrics.TILE_H * textScale.coerceAtLeast(1f)
    // Use as few rows as the column cap allows, then spread the items evenly across
    // them, so four items are 2x2 rather than a row of three and a lonely orphan.
    val rows = ceil(items.size / MenuMetrics.COLS.toFloat()).toInt().coerceAtLeast(1)
    val cols = ceil(items.size / rows.toFloat()).toInt().coerceAtLeast(1)
    val w = (MenuMetrics.PAD * 2 + cols * tileW + (cols - 1) * MenuMetrics.GAP) * d
    val h = (MenuMetrics.PAD * 2 + rows * tileH + (rows - 1) * MenuMetrics.GAP) * d
    val margin = MenuMetrics.SCREEN_MARGIN * d

    val left = (anchor.x - w / 2f).coerceIn(margin, maxOf(margin, canvas.width - w - margin))
    val top = (anchor.y - h - MenuMetrics.LIFT * d)
        .coerceIn(margin, maxOf(margin, canvas.height - h - margin))

    val tiles = items.mapIndexed { i, item ->
        val c = i % cols
        val r = i / cols
        val x = left + (MenuMetrics.PAD + c * (tileW + MenuMetrics.GAP)) * d
        val y = top + (MenuMetrics.PAD + r * (tileH + MenuMetrics.GAP)) * d
        Rect(Offset(x, y), Size(tileW * d, tileH * d)) to item
    }
    return MenuLayout(Rect(Offset(left, top), Size(w, h)), tiles)
}

private fun MenuItem.label(): String = when (this) {
    is MenuItem.Add -> type.name
    is MenuItem.Duplicate -> "Duplicate"
    is MenuItem.Rename -> "Rename\u2026"
    is MenuItem.Knobs -> "Knobs\u2026"
    is MenuItem.RemovePort -> "Remove port"
    is MenuItem.NewPatch -> "New patch"
    is MenuItem.Save -> if (moduleId == null) "Save patch\u2026" else "Save\u2026"
    is MenuItem.OpenLibrary -> "Load\u2026"
    is MenuItem.Load -> name
    is MenuItem.LibraryEmpty -> "Nothing saved"
    is MenuItem.Delete -> "Delete"
    is MenuItem.StartSubpatch -> "${type.name}\u2026"
    is MenuItem.Unpack -> "Unpack"
}

private fun MenuItem.tint(): Color = when (this) {
    is MenuItem.Add -> type.accent
    is MenuItem.Duplicate, is MenuItem.Rename -> Color(0xFF8A93A3)
    is MenuItem.Knobs -> Types.Subpatch.accent
    is MenuItem.RemovePort -> Color(0xFFE07A6B)
    is MenuItem.NewPatch -> Color(0xFFE07A6B)
    is MenuItem.Save, is MenuItem.OpenLibrary, is MenuItem.Load -> Types.Subpatch.accent
    is MenuItem.LibraryEmpty -> Color(0xFF6C7482)
    is MenuItem.Delete -> Color(0xFFE07A6B)
    is MenuItem.StartSubpatch -> type.accent
    is MenuItem.Unpack -> Types.Subpatch.accent
}

private fun DrawScope.drawMenu(layout: MenuLayout, d: Float, measurer: TextMeasurer) {
    drawRoundRect(
        color = Color(0xFF1B1F26),
        topLeft = layout.rect.topLeft,
        size = layout.rect.size,
        cornerRadius = CornerRadius(10f * d, 10f * d),
    )
    drawRoundRect(
        color = Color(0xFF3A424E),
        topLeft = layout.rect.topLeft,
        size = layout.rect.size,
        cornerRadius = CornerRadius(10f * d, 10f * d),
        style = Stroke(width = 1f * d),
    )
    layout.tiles.forEach { (rect, item) ->
        val tint = item.tint()
        drawRoundRect(
            color = tint.copy(alpha = 0.16f),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(6f * d, 6f * d),
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.55f),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = CornerRadius(6f * d, 6f * d),
            style = Stroke(width = 1f * d),
        )
        val text = measurer.fitting(
            item.label(), MenuLabelStyle,
            rect.width - 2f * MENU_LABEL_PAD * d, rect.height - 2f * MENU_LABEL_PAD * d,
        )
        drawText(
            text,
            topLeft = Offset(
                rect.left + (rect.width - text.size.width) / 2f,
                rect.top + (rect.height - text.size.height) / 2f,
            ),
        )
    }
}

/** Clear space either side of a tile's label, in dp. */
private const val MENU_LABEL_PAD = 6f

/** The smallest a label is scaled to fit before it is cut short instead. */
private const val MIN_LABEL_SCALE = 0.72f

/**
 * A label that fits a [width] by [height] tile: on one line at its own size when it can, on
 * two when it breaks at a space, smaller when it must, and cut short only past all of that.
 *
 * Found on the phone, whose font scale is larger than the emulator's: "Save patch..."
 * spilled out of its tile and ran into "Load..." beside it. A tile's size is in dp and its
 * label in sp, so how they compare is up to a setting the app does not control -- which is
 * why this measures rather than assumes. Shrinking alone was tried first and read "Save
 * pat..." at the smallest size worth reading; wrapping keeps the words, and a saved subpatch's
 * name in the library is where it matters most, since those are names people type.
 */
private fun TextMeasurer.fitting(
    label: String, style: TextStyle, width: Float, height: Float,
): TextLayoutResult {
    val natural = measure(label, style)
    if (natural.size.width <= width) return natural
    val box = Constraints(maxWidth = width.toInt().coerceAtLeast(1))
    val centered = style.copy(textAlign = TextAlign.Center)
    val wrapped = measure(label, centered, overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = box)
    if (!wrapped.hasVisualOverflow && wrapped.size.height <= height) return wrapped
    val scale = (width / natural.size.width).coerceAtLeast(MIN_LABEL_SCALE)
    val smaller = centered.copy(fontSize = style.fontSize * scale)
    val shrunk = measure(label, smaller, overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = box)
    return if (shrunk.size.height <= height) shrunk
    else measure(label, smaller, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = box)
}

// ---------------------------------------------------------------- drawing

/** A live microphone reads as a record light, not as another accent color. */
private val RecordRed = Color(0xFFE03B2F)

private val TitleStyle = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    color = Color(0xFFC9D0DA),
)

private val PanelTitleStyle = TextStyle(
    fontSize = 20.sp,
    fontWeight = FontWeight.Medium,
    color = Color(0xFFE4E7EC),
)

private val PanelParamStyle = TextStyle(
    fontSize = 14.sp,
    color = Color(0xFF98A0AD),
)

private val PanelValueStyle = TextStyle(
    fontSize = 16.sp,
    fontWeight = FontWeight.Medium,
    color = Color(0xFFE4E7EC),
)

/** The degree number beside a tonic row. Quiet: a landmark, not a label to read. */
private val GridLabelStyle = TextStyle(
    fontSize = 10.sp,
    color = Color(0xFF7E8896),
)

/** The tonic's own number: same size, enough brighter to pick out at a glance. */
private val GridTonicLabelStyle = TextStyle(
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    color = Color(0xFFD3DAE4),
)

private val PanelChipStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    color = Color(0xFFC3CBD6),
)

private val PanelChipOnStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
    color = Color(0xFF14171C),
)

private val PortLabelStyle = TextStyle(
    fontSize = 9.sp,
    color = Color(0xFF98A0AD),
)

private val MenuLabelStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    color = Color(0xFFE4E7EC),
)

/**
 * How opaque a cable is. Less than solid because cables now cross the modules they pass,
 * and a module's title and labels should still read through one.
 */
private const val CABLE_ALPHA = 0.6f

private fun DrawScope.drawCable(
    a: Offset,
    b: Offset,
    color: Color,
    width: Float,
    /**
     * A parameter's jack is on the module's bottom edge, so its cable comes up into it from
     * below. Arriving from the left like any other would draw it across the module it feeds.
     */
    intoBottom: Boolean = false,
) {
    val slack = ((b.x - a.x) * 0.5f).coerceAtLeast(28f)
    val path = Path().apply {
        moveTo(a.x, a.y)
        if (intoBottom) {
            val rise = (kotlin.math.abs(b.y - a.y) * 0.5f).coerceAtLeast(48f)
            cubicTo(a.x + slack, a.y, b.x, b.y + rise, b.x, b.y)
        } else {
            cubicTo(a.x + slack, a.y, b.x - slack, b.y, b.x, b.y)
        }
    }
    drawPath(path, color, style = Stroke(width = width))
}

/**
 * Draws a module into [rect], with all dp constants scaled by [unit] — 1 inside the
 * world transform, the display density for a screen-space rail.
 */
private fun DrawScope.drawModuleBox(
    module: PatchModule,
    rect: Rect,
    unit: Float,
    strokeWidth: Float,
    armed: PortRef?,
    measurer: TextMeasurer,
    showTitle: Boolean,
    showLabels: Boolean,
    alpha: Float,
    /** What the header says, for a rail whose name depends on the box it belongs to. */
    title: String = module.title,
    /** Drawn as a pile of boxes: a poly subpatch, and the rails inside one. */
    stacked: Boolean = false,
) {
    val corner = CornerRadius(PatchModule.CORNER * unit, PatchModule.CORNER * unit)

    if (stacked) {
        // Two outlines up and to the right, behind the box. A poly subpatch is one thing
        // you build and several the engine runs, and the box is the only place to say so:
        // its ports, its panel and what is inside it are all singular.
        for (layer in STACK_LAYERS downTo 1) {
            val step = STACK_STEP * unit * layer
            drawRoundRect(
                color = ModuleFill.copy(alpha = alpha),
                topLeft = rect.topLeft + Offset(step, -step),
                size = rect.size,
                cornerRadius = corner,
            )
            drawRoundRect(
                color = module.type.accent.copy(
                    alpha = MODULE_BORDER_ALPHA * alpha / (layer + 1f),
                ),
                topLeft = rect.topLeft + Offset(step, -step),
                size = rect.size,
                cornerRadius = corner,
                style = Stroke(width = strokeWidth),
            )
        }
    }

    drawRoundRect(
        color = ModuleFill.copy(alpha = alpha),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = module.type.accent.copy(alpha = MODULE_BORDER_ALPHA * alpha),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = corner,
        style = Stroke(width = strokeWidth),
    )

    if (showTitle) {
        val header = measurer.measure(title, TitleStyle)
        drawText(
            header,
            alpha = alpha,
            topLeft = Offset(
                rect.left + (rect.width - header.size.width) / 2f,
                rect.top + (PatchModule.HEADER * unit - header.size.height) / 2f,
            ),
        )
    }

    PortDirection.entries.forEach { dir ->
        val ports = module.ports(dir)
        ports.forEachIndexed { i, port ->
            val ref = PortRef(module.id, dir, i)
            val at = portIn(rect, unit, dir, i, ports.size, module.portsBody * unit)
            val lit = ref == armed
            // Idle color comes from what the port carries, so the four kinds are
            // distinguishable at a glance without reading a label -- and since typing is
            // enforced, the color now says which cables will be accepted rather than
            // merely which were expected.
            drawCircle(
                color = (if (lit) module.type.accent else port.kind.idle).copy(alpha = alpha),
                radius = (if (lit) PatchModule.PORT_RADIUS_ARMED else PatchModule.PORT_RADIUS) * unit,
                center = at,
            )
            if (showLabels) {
                val label = measurer.measure(port.name, PortLabelStyle)
                val x = if (dir == PortDirection.INPUT) {
                    rect.left + PatchModule.LABEL_INSET * unit
                } else {
                    rect.right - PatchModule.LABEL_INSET * unit - label.size.width
                }
                drawText(
                    label,
                    alpha = alpha,
                    topLeft = Offset(x, at.y - label.size.height / 2f),
                )
            }
        }
    }

    // The bottom band: a jack for each exposed parameter, its short name above it, under a
    // hairline that says the band is part of this module rather than a module below it.
    if (module.modRanges.isNotEmpty() && !module.isPinned) {
        val bandTop = rect.top + (PatchModule.HEADER + module.portsBody) * unit
        drawLine(
            color = module.type.accent.copy(alpha = 0.3f * alpha),
            start = Offset(rect.left + PatchModule.CORNER * unit, bandTop),
            end = Offset(rect.right - PatchModule.CORNER * unit, bandTop),
            strokeWidth = strokeWidth,
        )
        module.modRanges.keys.sorted().forEach { index ->
            val ref = PortRef(module.id, PortDirection.MOD, index)
            val at = modPortIn(rect, unit, module.type, index, module.portsBody * unit)
            val lit = ref == armed
            drawCircle(
                color = (if (lit) module.type.accent else SignalKind.MODULATION.idle).copy(alpha = alpha),
                radius = (if (lit) PatchModule.PORT_RADIUS_ARMED else PatchModule.PORT_RADIUS) * unit,
                center = at,
            )
            if (showLabels) {
                val label = measurer.measure(module.type.params[index].short, PortLabelStyle)
                drawText(
                    label,
                    alpha = alpha,
                    topLeft = Offset(
                        at.x - label.size.width / 2f,
                        at.y - (PatchModule.PORT_RADIUS_ARMED + 2f) * unit - label.size.height,
                    ),
                )
            }
        }
    }
}

/** The color of modulation, which now has a kind of its own to take it from. */
private val ModulationColor = SignalKind.MODULATION.cable

/**
 * The two ends of a modulation range, drawn as the glyphs that name them: `[` at the low end
 * and `]` at the high, from [top] to [bottom]. Taller than the bar, so a finger can find them.
 */
private fun DrawScope.drawBrackets(
    row: Rect, d: Float, param: Param, range: ModRange, top: Float, bottom: Float,
) {
    val serif = 6f * d
    for (closing in listOf(false, true)) {
        val x = panelBracketX(row, d, param, if (closing) range.high else range.low, closing)
        val inward = if (closing) -serif else serif
        val path = Path().apply {
            moveTo(x + inward, top)
            lineTo(x, top)
            lineTo(x, bottom)
            lineTo(x + inward, bottom)
        }
        drawPath(path, ModulationColor, style = Stroke(width = 2.5f * d))
    }
}

/**
 * The open module, filling the screen.
 *
 * Its jacks sit on the panel edge with a stub of cable running off past it: enough to
 * say what is attached, not enough to pretend you can trace it. Following a cable, or
 * moving one, means closing the panel -- which is the trade that buys knobs this size.
 */
private fun DrawScope.drawPanel(
    module: PatchModule,
    patch: Patch,
    panel: Rect,
    d: Float,
    measurer: TextMeasurer,
    /** The scale sounding now: what the grid's rows and the tuning marks are read against. */
    scale: Scale,
    playingStep: Int,
    intervalMenu: Boolean,
    /** Where each modulated parameter has got to, from the engine. A missing one shows its knob. */
    live: Map<Int, Float> = emptyMap(),
    /** An SF panel's font and its page of instruments; null for every other module. */
    sf: SfView? = null,
    /** The key sounding now, in cents from middle C, for a degree read as a note. */
    rootCents: Float = 0f,
) {
    val corner = CornerRadius(14f * d, 14f * d)

    // Opaque, and slightly lifted from the canvas showing through the border.
    drawRoundRect(Color(0xE6000000), panel.topLeft, panel.size, corner)
    drawRoundRect(Color(0xFF1B1F26), panel.topLeft, panel.size, corner)
    drawRoundRect(
        module.type.accent.copy(alpha = 0.7f), panel.topLeft, panel.size, corner,
        style = Stroke(width = 2f * d),
    )

    val title = measurer.measure(module.title, PanelTitleStyle)
    drawText(
        title,
        topLeft = Offset(
            panel.left + (panel.width - title.size.width) / 2f,
            panel.top + (PatchModule.PANEL_HEADER * d - title.size.height) / 2f,
        ),
    )

    // Jacks, with a stub for the ones carrying something.
    PortDirection.entries.forEach { dir ->
        val ports = module.ports(dir)
        ports.forEachIndexed { index, port ->
            val ref = PortRef(module.id, dir, index)
            val at = panelPort(panel, d, dir, index, ports.size)
            val patched = patch.connections.any {
                if (dir == PortDirection.INPUT) it.to == ref else it.from == ref
            }

            if (patched) {
                val away = if (dir == PortDirection.INPUT) -1f else 1f
                drawLine(
                    color = port.kind.cable,
                    start = at,
                    end = Offset(at.x + away * PatchModule.PANEL_STUB * d, at.y),
                    strokeWidth = 3f * d,
                )
            }
            drawCircle(
                color = if (patched) port.kind.cable else port.kind.idle,
                radius = (if (patched) 8f else 6f) * d,
                center = at,
            )

            val label = measurer.measure(port.name, PortLabelStyle)
            val x = if (dir == PortDirection.INPUT) at.x + 16f * d
                    else at.x - 16f * d - label.size.width
            drawText(label, topLeft = Offset(x, at.y - label.size.height / 2f))
        }
    }

    // A jack for each exposed parameter on the bottom edge, in the order of the rows, with a
    // stub down past the edge when patched. Labeled below the edge rather than above it:
    // a panel with four or five rows fills its body, and above would be on the last bar.
    module.modRanges.keys.sorted().forEach { index ->
        val ref = PortRef(module.id, PortDirection.MOD, index)
        val at = panelModPort(panel, d, module.type, index)
        val patched = patch.connections.any { it.to == ref }
        if (patched) {
            drawLine(
                color = ModulationColor,
                start = at,
                end = Offset(at.x, at.y + PatchModule.PANEL_STUB * d),
                strokeWidth = 3f * d,
            )
        }
        drawCircle(
            color = if (patched) ModulationColor else SignalKind.MODULATION.idle,
            radius = (if (patched) 8f else 6f) * d,
            center = at,
        )
        val label = measurer.measure(module.type.params[index].short, PortLabelStyle)
        drawText(label, topLeft = Offset(at.x + 12f * d, at.y + 4f * d))
    }

    val intervalParam = module.type.intervalParam
    val chosenInterval = if (intervalParam < 0) -1
        else module.params.getOrElse(intervalParam) { DEFAULT_INTERVAL.toFloat() }.roundToInt()
    INTERVALS.getOrNull(chosenInterval)?.let {
        drawChip(panelIntervalChip(panel, d), d, it.label, intervalMenu, scaleAccent, measurer)
    }

    // Only where there are dots to pin: it is the lock on their position, not a panel
    // ornament, and no other grid has anything for it to mean.
    if (module.type.grid == GridKind.DOTS) {
        drawLockChip(panelLockChip(panel, d), d, module.dotsLocked, scaleAccent)
    }

    if (sf != null) {
        val code = module.params.getOrElse(SF_PRESET) { 0f }.roundToInt()
        val label = when {
            sf.fontName == null -> "Choose a bank\u2026"
            sf.font != null -> sf.font.presetFor(code)?.name ?: "Not in ${sf.fontName}"
            sf.failed -> "Can't read ${sf.fontName}"
            else -> "Loading ${sf.fontName}\u2026"
        }
        drawChip(panelPresetChip(panel, d), d, label, sf.menu, scaleAccent, measurer)
        if (sf.menu) {
            drawPresetPage(panel, d, sf, code, measurer)
            return
        }
    }

    if (intervalParam >= 0 && intervalMenu) {
        drawRect(
            color = PanelScrim,
            topLeft = panelBody(panel, d).topLeft,
            size = panelBody(panel, d).size,
        )
        panelTiles(panel, d, INTERVALS.size).forEachIndexed { i, tile ->
            drawTile(tile, d, INTERVALS[i].label, INTERVALS[i].detail, i == chosenInterval, measurer)
        }
        return
    }

    val gridArea = panelGrid(panel, d, module.type)
    when (module.type.grid) {
        GridKind.SEQUENCE -> drawStepGrid(
            gridArea, d, module, scale, module.type.accent, measurer, playingStep,
        )
        GridKind.DRONE -> drawDroneGrid(gridArea, d, module, scale, module.type.accent)
        GridKind.DOTS -> drawDotGrid(gridArea, d, module, scale, module.type.accent, measurer, playingStep)
        GridKind.PATTERN -> drawEuclidPattern(gridArea, d, module, module.type.accent, playingStep)
        GridKind.ENVELOPE -> drawEnvelope(gridArea, d, module, module.type.accent, measurer)
        GridKind.NONE -> {}
    }
    // Neither of these scrolls: a pattern is read-only and an envelope is capped to what
    // fits, which is the whole reason MAX_SEGMENTS is a cap.
    if (module.type.grid != GridKind.NONE && module.type.grid != GridKind.PATTERN &&
        module.type.grid != GridKind.ENVELOPE
    ) {
        drawGridScrollBar(gridArea, d, gridWindow(module, gridArea, d, scale), module.type.accent)
    }

    // Knobs -- the rows only. Walking every parameter drew the interval, which lives in the
    // header, as a row of buttons laid over the first real row: it showed intervals where
    // taps set the length.
    val rows = patch.panelRows(module)
    rows.forEachIndexed { slot, entry ->
        val owner = entry.owner
        val index = entry.index
        val param = entry.param
        // A subpatch's rows are other modules' knobs, which is the only reason any of this is
        // written against a row rather than against this module's own parameters.
        val own = owner.id == module.id
        val accent = if (own) module.type.accent else owner.type.accent
        val row = panelRowAt(panel, d, module.type, rows.size, slot)
        val range = owner.modRanges[index]
        // Where the modulator has taken it this frame, for a parameter being modulated; its
        // knob for anything else. Polled against the open module, so a promoted row shows
        // its knob rather than a value read off the wrong node.
        val value = (if (own) live[index] else null) ?: owner.params.getOrElse(index) { param.default }
        if (own && module.canExpose(index)) {
            drawChip(panelModChipOn(row, d), d, "[ ]", range != null, ModulationColor, measurer)
        }
        // Only inside the subpatch it would promote to, which is where the chip means anything.
        if (patch.canPromote(owner, index)) {
            drawChip(
                panelPromoteChipOn(row, d), d, "\u2191",
                patch.isPromoted(owner, index), Types.Subpatch.accent, measurer,
            )
        }

        // On a subpatch's panel the module is named too: "cutoff" alone says which knob but
        // not whose, and a subpatch is exactly where two of them can be side by side.
        val label = if (own) param.name else "${owner.title}  \u00b7  ${param.name}"
        val name = measurer.measure(label, PanelParamStyle)
        drawText(name, topLeft = Offset(row.left, row.top + 4f * d))

        // A stepped parameter shows no numeric readout: the lit button is the reading,
        // and "0" next to a picture of a sawtooth is noise.
        if (!param.buttons) {
            // An exposed parameter reads its range, not a value it is not going to hold.
            val text = when {
                range != null -> rangeReading(param, range)
                param.degree -> "${param.format(value)}  ${degreeName(value.roundToInt(), scale, rootCents)}"
                else -> param.format(value)
            }
            val reading = measurer.measure(text, PanelValueStyle)
            drawText(
                reading,
                topLeft = Offset(row.right - reading.size.width, row.top + 2f * d),
            )
        }

        if (param.buttons) {
            drawChoices(row, d, param, value, accent, measurer)
            if (range != null) {
                val box = choiceBox(row, d, param, 0)
                drawBrackets(row, d, param, range, box.top - 4f * d, box.bottom + 4f * d)
            }
            return@forEachIndexed
        }

        val barHeight = PatchModule.PANEL_BAR * d
        val barTop = row.bottom - barHeight - 10f * d
        val radius = CornerRadius(barHeight / 2f, barHeight / 2f)

        drawRoundRect(
            color = Color(0xFF12151A),
            topLeft = Offset(row.left, barTop),
            size = Size(row.width, barHeight),
            cornerRadius = radius,
        )
        val filled = row.width * param.positionOf(value)
        drawRoundRect(
            color = accent,
            topLeft = Offset(row.left, barTop),
            size = Size(filled.coerceAtLeast(barHeight), barHeight),
            cornerRadius = radius,
        )

        if (param.marks) {
            drawScaleMarks(row, barTop, barHeight, d, param, scale)
        }

        // The range, over the fill, and its brackets rising above the bar rather than below
        // it -- below a cents bar is where the scale's marks are.
        if (range != null) {
            val lowX = panelBracketX(row, d, param, range.low, closing = false)
            val highX = panelBracketX(row, d, param, range.high, closing = true)
            drawRect(
                color = ModulationColor.copy(alpha = 0.4f),
                topLeft = Offset(minOf(lowX, highX), barTop),
                size = Size(kotlin.math.abs(highX - lowX), barHeight),
            )
            drawBrackets(row, d, param, range, barTop - 8f * d, barTop + barHeight + 2f * d)
        }
    }
}

// ---------------------------------------------------------------- demo state

@Composable
fun rememberDemoPatch(): Patch = remember { demoPatch() }

/**
 * The patch a fresh install opens with: a complete instrument, so the first thing you hear
 * is music rather than a test tone.
 *
 * A poly subpatch, because that is the shape the whole app is now about. Seq sends notes to
 * a box called Voice; inside the box there is *one* of everything -- an oscillator, an
 * envelope and an amp the envelope opens -- and the engine is handed four copies of it.
 * What comes out is summed and filtered.
 *
 * It is also the demo doing the explaining. Opening the box is the first thing anyone will
 * try, and what they find is a monophonic patch they can read in one look: notes in on the
 * left, sound out on the right, and an envelope shaping it that is theirs to repatch. The
 * old demo had the envelope hidden inside Osc where nothing could reach it.
 */
fun demoPatch(): Patch =
    Patch().apply {
        val steps = add(Types.Steps, Offset(110f, 40f))!!
        val osc = add(Types.Osc, Offset(275f, 40f))!!
        val env = add(Types.Env, Offset(275f, 180f))!!
        val amp = add(Types.Amp, Offset(440f, 40f))!!
        val filter = add(Types.Filter, Offset(605f, 40f))!!

        fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
        fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)

        // Notes to both the oscillator and the envelope. One source, so collapsing these
        // into the box gives it one note input rather than two.
        connect(out(steps, 0), into(osc, 0))
        connect(out(steps, 0), into(env, 0))
        connect(out(osc, 0), into(amp, 0))
        connect(out(env, 0), into(amp, 1))
        connect(out(amp, 0), into(filter, 0))
        connect(out(filter, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        connect(out(filter, 0), PortRef(OUT_ID, PortDirection.INPUT, 1))

        makeSubpatch(setOf(osc.id, env.id, amp.id), Types.Poly)?.also {
            it.name = "Voice"
            it.position = Offset(275f, 40f)
        }
    }
