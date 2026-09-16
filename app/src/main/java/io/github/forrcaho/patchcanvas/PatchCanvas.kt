package io.github.forrcaho.patchcanvas

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeout
import kotlin.math.PI
import kotlin.math.ceil
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
 * which is a point in favour of thesis #2.
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
 * coloured -- see [Patch.connect].
 *
 * Audio-rate modulation does not need the loophole enforcement would close. A module that
 * wants it declares an audio input, where the rate is the whole point and the unit is a
 * sample; [MODULATION] is applied once per block and could not carry it anyway.
 *
 * [MODULATION] and [PULSE] keep the colours of the CV and gate they replace, which is most
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
 * than normalised, so a node uses what it is given and the interface can say "440 Hz"
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
enum class Choice { NUMBER, WAVE, DIVISION }

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
     * What this parameter's modulation port is labelled, on a bottom edge where three share
     * 116dp. The name itself when it is that short already, which most are; otherwise its
     * first three letters, unless those would say something else.
     */
    val short: String = if (name.length <= 4) name else name.take(3),
) {
    /**
     * How many options a stepped parameter offers.
     *
     * Stepped ranges are integers one apart -- 0..3 waveforms, 1..8 lengths -- so the
     * count is the span plus one. Nothing else would make sense as a row of buttons.
     */
    val steps: Int get() = (max - min).toInt() + 1

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
            // ends are half as easy to hit as their neighbours.
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

data class ModuleType(
    val name: String,
    val inputs: List<Port>,
    val outputs: List<Port>,
    val accent: Color,
    val params: List<Param> = emptyList(),
    /**
     * Non-null for the I/O rails. A pinned type is unique, cannot be added or deleted,
     * has no stored position, and draws at constant size on a viewport edge.
     */
    val pinned: Edge? = null,
    /** Non-zero only for sequencers; mirrors StepsNode::kSteps. */
    val stepCount: Int = 0,
) {
    /** Indices of the parameters drawn as rows of the panel; the rest live in its header. */
    val rowParams: List<Int> get() = params.indices.filter { !params[it].header }

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

    val Filter = ModuleType(
        "Filter", listOf(Port("in", A)), listOf(Port("out", A)),
        Color(0xFFE0A24B),
        params = listOf(
            // Hertz outright. This used to be where a cable's zero sat, with a cutoff jack
            // moving it in octaves from there; with the jack gone it is an ordinary knob,
            // and a modulator sweeps it through the range exposed on the knob itself.
            Param("cutoff", 20f, 18000f, 1000f, "Hz", EXP),
            Param("res", 0f, 0.95f, 0.3f, "", LIN),
        ),
    )
    val Env = ModuleType(
        "Env", listOf(Port("gate", P)), listOf(Port("out", M)),
        Color(0xFFB98FE0),
        params = listOf(
            Param("A", 0.001f, 5f, 0.005f, "s", EXP),
            Param("D", 0.001f, 5f, 0.12f, "s", EXP),
            Param("S", 0f, 1f, 0.6f, "", LIN),
            Param("R", 0.001f, 10f, 0.25f, "s", EXP),
        ),
    )
    /**
     * A slow wave, for turning knobs. Patched to a parameter's modulation port it sweeps
     * that parameter across the range stored there, which is why it is unipolar -- see
     * LfoNode. Its output is modulation, which is now a kind in its own right.
     */
    val Lfo = ModuleType(
        "LFO", emptyList(), listOf(Port("out", M)),
        Color(0xFFC9A8EE),
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
        "Steps", emptyList(), listOf(Port("gate", P), Port("notes", N)),
        Color(0xFF6FA8E5),
        params = listOf(
            Param("len", 1f, STEP_COUNT.toFloat(), 8f, "", STEP),
            Param("transp", -TUNE_RANGE, TUNE_RANGE, 0f, "\u00A2", LIN, marks = true, short = "trn"),
            Param(
                "interval", 0f, (INTERVALS.size - 1).toFloat(), DEFAULT_INTERVAL.toFloat(),
                curve = STEP, choice = Choice.DIVISION, header = true,
            ),
        ),
        stepCount = STEP_COUNT,
    )
    /**
     * Notes in, sound out, with the voices inside it.
     *
     * Called Voice until the monophonic oscillator was retired, which settled the worst
     * naming collision in the project: "voice" was both this module and one of the eight
     * inside it. Every synth is polyphonic now, so there is no other oscillator for the
     * name to be ambiguous against, and "voice" is left meaning only the slot.
     */
    val Osc = ModuleType(
        "Osc", listOf(Port("notes", N)), listOf(Port("out", A)),
        Color(0xFF8FD48A),
        // Order mirrors OscNode::setParam. Five, where every other module has at most
        // four: an envelope needs all of A, D, S and R for a note to have a shape, and
        // the waveform is the fifth. The panel divides its body by the rows it has.
        params = listOf(
            Param("wave", 0f, 3f, 0f, "", STEP, Choice.WAVE),
            Param("A", 0.001f, 5f, 0.005f, "s", EXP),
            Param("D", 0.001f, 5f, 0.12f, "s", EXP),
            Param("S", 0f, 1f, 0.6f, "", LIN),
            Param("R", 0.001f, 10f, 0.25f, "s", EXP),
        ),
    )
    val Mix = ModuleType(
        "Mix",
        listOf(Port("a", A), Port("b", A), Port("c", A), Port("d", A)),
        listOf(Port("out", A)),
        Color(0xFF9AA6B5),
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
        Color(0xFF7FB0E5),
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
    val palette = listOf(Osc, Filter, Env, Lfo, Steps, Mix)

    val byName: Map<String, ModuleType> =
        (palette + listOf(Out, In)).associateBy { it.name }
}

/**
 * Pinned modules take fixed ids so a saved patch's connections still resolve against
 * the rails after a reload, rather than depending on allocation order.
 */
const val OUT_ID = 1L
const val IN_ID = 2L
private const val FIRST_FREE_ID = 100L

/** Free modules live in world units, and one world unit is one dp. */
class PatchModule(
    val id: Long,
    val type: ModuleType,
    position: Offset,
) {
    var position by mutableStateOf(position)

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
     * The parameters given a jack, by index, and what each sweeps between.
     *
     * An immutable map replaced whole on every edit, like the patch's scale list, so two of
     * them compare by content. A snapshot map would compare by identity -- the trap that
     * made every module pulse on undo. Row parameters of free modules only: a header
     * parameter has no row to put brackets on, and a rail has no bottom edge to spare.
     */
    var modRanges by mutableStateOf(emptyMap<Int, ModRange>())

    fun canExpose(index: Int): Boolean = !isPinned && index in type.rowParams

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
    val height: Float get() = heightFor(type) + modBandFor(type, modRanges.keys)

    /** The ports' band. Equal to the body, now that opening a module leaves the canvas. */
    val portsBody: Float get() = portsBodyFor(type)

    val width: Float get() = if (isPinned) RAIL_WIDTH else WIDTH

    /** World-space bounds. Meaningless for pinned modules; use Frame.railRect instead. */
    val bounds: Rect get() = Rect(position, Size(width, height))

    /** The side jacks. Empty for [PortDirection.MOD], whose ports are [modRanges]. */
    fun ports(dir: PortDirection): List<Port> = when (dir) {
        PortDirection.INPUT -> type.inputs
        PortDirection.OUTPUT -> type.outputs
        PortDirection.MOD -> emptyList()
    }

    companion object {
        const val WIDTH = 116f
        /** Rails only need jacks and a name, so they cost far less canvas than a module. */
        const val RAIL_WIDTH = 64f
        /** Title band above the ports. */
        const val HEADER = 22f
        /** Centre-to-centre spacing of adjacent ports on one edge. */
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

        /** Closed height. Derived from the type alone, so the add menu can centre one. */
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
 */
internal fun panelGrid(panel: Rect, d: Float): Rect {
    val body = panelBody(panel, d)
    val side = PatchModule.PANEL_SIDE * d
    return Rect(panel.left + side, body.top, panel.right - side, body.top + body.height * 0.66f)
}

private fun panelControls(panel: Rect, d: Float, type: ModuleType): Rect {
    val body = panelBody(panel, d)
    return if (type.stepCount > 0) {
        Rect(body.left, body.top + body.height * 0.66f, body.right, body.bottom)
    } else {
        body
    }
}

/** A knob's row: label, value and the bar beneath them. */
internal fun panelRow(panel: Rect, d: Float, type: ModuleType, index: Int): Rect {
    val area = panelControls(panel, d, type)
    val rows = type.rowParams
    val count = rows.size
    // Placed by its position among the rows, not among the parameters: a parameter that
    // lives in the header takes no row, and must not leave a gap where one would be.
    val slot = rows.indexOf(index).coerceAtLeast(0)
    val side = PatchModule.PANEL_SIDE * d
    val rowHeight = minOf(PatchModule.PANEL_ROW_MAX * d, area.height / maxOf(count, 1))
    val block = rowHeight * count
    val top = area.top + (area.height - block) / 2f + slot * rowHeight
    return Rect(panel.left + side, top, panel.right - side, top + rowHeight)
}

/**
 * The [ ] chip that gives a row's parameter a jack, in the panel's right-hand gutter.
 *
 * In the gutter rather than on the row, so it costs the bar none of its travel; and at the
 * gutter's inner edge, clear of the output jacks' labels on the panel's outer one. Level with
 * the row's control rather than its label, since that is what it is about.
 */
internal fun panelModChip(panel: Rect, d: Float, type: ModuleType, index: Int): Rect {
    val row = panelRow(panel, d, type, index)
    val height = minOf(PatchModule.PANEL_MOD_CHIP_H * d, row.height - 4f * d)
    val centre = minOf(row.bottom - 20f * d, row.bottom - height / 2f - 2f * d)
    return Rect(
        Offset(row.right + PatchModule.PANEL_MOD_GAP * d, centre - height / 2f),
        Size(PatchModule.PANEL_MOD_CHIP_W * d, height),
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
    if (param.curve == ParamCurve.STEPPED) {
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
internal fun panelBracketAt(panel: Rect, d: Float, module: PatchModule, index: Int, at: Offset): Boolean? {
    val range = module.modRanges[index] ?: return null
    val row = panelRow(panel, d, module.type, index)
    val reach = PatchModule.BRACKET_REACH * d
    val zone = Rect(row.left - reach, row.top - 6f * d, row.right + reach, row.bottom + 6f * d)
    if (!zone.contains(at)) return null
    val param = module.type.params[index]
    val low = panelBracketX(row, d, param, range.low, closing = false)
    val high = panelBracketX(row, d, param, range.high, closing = true)
    val toLow = kotlin.math.abs(at.x - low)
    val toHigh = kotlin.math.abs(at.x - high)
    return if (toLow == toHigh) at.x > high else toHigh < toLow
}

/**
 * What an exposed bar reads in place of its value: its range, in the parameter's own units.
 * An en dash rather than a hyphen, which beside a negative number of cents would read as a sign.
 */
internal fun rangeReading(param: Param, range: ModRange): String =
    "[${param.format(range.low)} \u2013 ${param.format(range.high)}]"

/** Moves one end of a parameter's range to the knob value under [screenX]. */
internal fun Patch.moveBracket(
    module: PatchModule, panel: Rect, d: Float, index: Int, closing: Boolean, screenX: Float,
) {
    val range = module.modRanges[index] ?: return
    val value = module.type.params[index].valueAt(panelKnobPosition(panel, d, screenX))
    expose(module, index, if (closing) range.copy(high = value) else range.copy(low = value))
}

internal fun panelKnobAt(panel: Rect, d: Float, module: PatchModule, at: Offset): Int? {
    module.type.rowParams.forEach { i ->
        // An exposed row's knob is not the hand's. It shows where the modulator has taken the
        // parameter, and dragging it would set a value nothing is listening to.
        if (i in module.modRanges) return@forEach
        // Generous vertically: the rows are the only targets on the panel, so a near
        // miss should still land rather than do nothing.
        if (panelRow(panel, d, module.type, i).inflate(6f * d).contains(at)) return i
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
internal fun panelCellAt(panel: Rect, d: Float, module: PatchModule, at: Offset): Pair<Int, Int>? {
    if (module.type.stepCount == 0) return null
    val area = panelGrid(panel, d)
    if (!area.contains(at)) return null

    val rows = gridRows(area, d)
    if (rows <= 0) return null
    val rowHeight = area.height / rows
    val cellWidth = area.width / module.type.stepCount

    val column = ((at.x - area.left) / cellWidth).toInt().coerceIn(0, module.type.stepCount - 1)
    val row = ((at.y - area.top) / rowHeight).toInt().coerceIn(0, rows - 1)
    return column to (module.gridBottom + (rows - 1 - row))
}

/** How many degrees fit. Whole rows only -- a half-height row at the bottom is a lie. */
internal fun gridRows(area: Rect, d: Float): Int =
    (area.height / (PatchModule.GRID_ROW * d)).toInt().coerceAtLeast(1)

/** Knob travel, 0..1, from a screen x on the panel. */
internal fun panelKnobPosition(panel: Rect, d: Float, screenX: Float): Float {
    val side = PatchModule.PANEL_SIDE * d
    val left = panel.left + side
    val right = panel.right - side
    return ((screenX - left) / (right - left)).coerceIn(0f, 1f)
}

data class PortRef(val moduleId: Long, val dir: PortDirection, val index: Int)

data class Connection(val from: PortRef, val to: PortRef)

class Patch {
    val modules = mutableStateListOf<PatchModule>()
    val connections = mutableStateListOf<Connection>()

    /**
     * Whether the microphone is listening.
     *
     * Runtime state, never serialised: it always starts false and is only true while an
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
     * Modules to pulse, after an undo moved something you were not looking at.
     *
     * View state, like the camera and the open panel: never serialised, invisible to the
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

    /** Pinned types are never added; the rails exist for the life of the patch. */
    fun add(type: ModuleType, at: Offset): PatchModule? {
        if (type.pinned != null) return null
        return PatchModule(nextId++, type, at).also { modules.add(it) }
    }

    /**
     * Re-adds a module with its stored id, for reload. The counter is advanced past
     * whatever came in so a restored patch cannot hand out an id it is already using;
     * that makes nextId derived state rather than another field to keep in the file.
     */
    internal fun adopt(module: PatchModule) {
        if (module.isPinned) return
        modules.add(module)
        if (module.id >= nextId) nextId = module.id + 1
    }

    fun remove(module: PatchModule) {
        if (module.isPinned) return
        connections.removeAll { it.from.moduleId == module.id || it.to.moduleId == module.id }
        modules.remove(module)
    }

    fun duplicate(module: PatchModule): PatchModule? =
        if (module.isPinned) null else add(module.type, module.position + Offset(28f, 28f))

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
        disconnect(PortRef(module.id, PortDirection.MOD, index))
        module.modRanges = module.modRanges - index
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
            if (!connections.remove(cable)) connections.add(cable)
            return true
        }
        connections.removeAll { it.to == inp }
        connections.add(Connection(out, inp))
        return true
    }

    fun disconnect(ref: PortRef) {
        connections.removeAll { it.from == ref || it.to == ref }
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
    data class Menu(val anchor: Offset, val targetId: Long?) : Interaction
}

sealed interface MenuItem {
    data class Add(val type: ModuleType) : MenuItem
    data class Duplicate(val moduleId: Long) : MenuItem
    data class Delete(val moduleId: Long) : MenuItem
}

private fun menuItems(targetId: Long?): List<MenuItem> =
    if (targetId == null) Types.palette.map { MenuItem.Add(it) }
    else listOf(MenuItem.Duplicate(targetId), MenuItem.Delete(targetId))

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
) {
    fun railRect(module: PatchModule): Rect {
        val d = density
        val w = PatchModule.RAIL_WIDTH * d
        val h = module.height * d
        val top = insetTop + (canvas.height - insetTop - insetBottom - h) / 2f
        val left = when (module.type.pinned) {
            Edge.LEFT -> insetLeft + RAIL_MARGIN * d
            else -> canvas.width - insetRight - RAIL_MARGIN * d - w
        }
        return Rect(Offset(left, top), Size(w, h))
    }

    /**
     * The undo and redo buttons, bottom-left.
     *
     * Screen space, like the rails: the canvas principle forbids chrome stacked above
     * the surface, not controls drawn inside it that stay put while the world moves.
     * Bottom-left is the one corner nothing else claims -- the In rail is centred on the
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
     * on both: the In rail is centred on the left edge, and a panel keeps its own header
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
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
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
        Frame(canvas, density.density, insetLeft, insetTop, insetRight, insetBottom)

    // Through rememberUpdatedState, because the gesture loop below is keyed on Unit and
    // so captures its closure exactly once. A plain val would freeze canUndo at whatever
    // it was during the first composition -- which is false -- and the buttons would
    // draw correctly (that lambda is rebuilt every recomposition) while never being
    // hittable. They did exactly that on the device.
    val controls by rememberUpdatedState(
        CanvasControls(
            canUndo, canRedo, onToggleOutput, onToggleInput, onUndo, onRedo, onResetTransport,
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
    LaunchedEffect(openModule?.id, openModule?.type?.stepCount) {
        val id = openModule?.takeIf { it.type.stepCount > 0 }?.id
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

    Canvas(
        modifier = modifier
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
                            transportCardGesture(frame, down.position, patch, controls.onResetTransport)
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
                        // The [ ] chips come before the rows beside them. Only the chip itself
                        // counts, so a near miss on a knob never gives anything a jack.
                        val chipFor = if (onHistory) null else open.type.rowParams.firstOrNull {
                            open.canExpose(it) &&
                                panelModChip(panel, frame.density, open.type, it).contains(down.position)
                        }
                        if (chipFor != null) {
                            waitForUpRelease()
                            if (chipFor in open.modRanges) {
                                patch.unexpose(open, chipFor)
                            } else {
                                val param = open.type.params[chipFor]
                                patch.expose(open, chipFor, initialModRange(param, open.params[chipFor]))
                            }
                            return@awaitEachGesture
                        }

                        // A bracket before the knob it sits on: dragging `[` or `]` moves that
                        // end of the range, and leaves the knob where it is.
                        val bracket: Pair<Int, Boolean>? =
                            if (onHistory) null
                            else open.modRanges.keys.firstNotNullOfOrNull { index ->
                                panelBracketAt(panel, frame.density, open, index, down.position)
                                    ?.let { index to it }
                            }
                        val knob =
                            if (onHistory || bracket != null) null
                            else panelKnobAt(panel, frame.density, open, down.position)
                        val cell =
                            if (onHistory || knob != null) null
                            else panelCellAt(panel, frame.density, open, down.position)

                        // Scrolling the grid is measured from where the drag began and
                        // in whole rows, so a slow drag moves the same distance as a fast
                        // one and never lands between two degrees.
                        // The height a row actually got, not GRID_ROW: rows divide the
                        // area evenly once their count is fixed, so the two differ by
                        // the remainder and a drag measured against the nominal value
                        // slides against the grid it is supposed to be moving.
                        val gridArea = panelGrid(panel, frame.density)
                        val rowHeight = gridArea.height / gridRows(gridArea, frame.density)
                        val scrollFrom = open.gridBottom
                        var moved = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val change = pressed.first()
                            if ((change.position - down.position).getDistance() > slop) {
                                moved = true
                            }
                            if (bracket != null) {
                                patch.moveBracket(
                                    open, panel, frame.density, bracket.first, bracket.second,
                                    change.position.x,
                                )
                            } else if (knob != null) {
                                val param = open.type.params[knob]
                                open.setParam(
                                    knob,
                                    param.valueAt(
                                        panelKnobPosition(panel, frame.density, change.position.x),
                                    ),
                                )
                            } else if (cell != null) {
                                // Down the screen is down in pitch, so dragging the grid
                                // downward brings higher degrees into view.
                                val rows = ((change.position.y - down.position.y) / rowHeight)
                                open.gridBottom = scrollFrom + rows.roundToInt()
                            }
                            change.consume()
                        }

                        if (!moved) {
                            if (onHistory) {
                                controls.tapHistory(frame, down.position)
                            } else if (bracket != null) {
                                patch.moveBracket(
                                    open, panel, frame.density, bracket.first, bracket.second,
                                    down.position.x,
                                )
                            } else if (knob != null) {
                                // A tap on a knob jumps there, which is faster than
                                // dragging when you already know where you want it.
                                val param = open.type.params[knob]
                                open.setParam(
                                    knob,
                                    param.valueAt(
                                        panelKnobPosition(panel, frame.density, down.position.x),
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
                            // A rail offers nothing to delete, so it opens no menu -- but
                            // it does have knobs, and tapping it is already its switch, so
                            // holding is the way in to its panel.
                            interaction = if (onButton) {
                                Interaction.Idle
                            } else if (hitModule != null && hitModule.isPinned) {
                                if (hitModule.type.params.isNotEmpty()) {
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
            patch.free.forEach { module ->
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
                )
                if (module.id in flash.ids && pulse.value > 0f) {
                    drawFlash(module.bounds, 1f, pulse.value, 3f / camera.zoom)
                }
            }
        }

        patch.pinned.forEach { rail ->
            // Both rails dim when they are not passing anything, so "this is a switch and
            // it is off" reads the same way on each. A correctly patched canvas that made
            // no sound, with nothing on screen saying why, was the single most confusing
            // thing about using this.
            val live = when (rail.id) {
                IN_ID -> patch.inputEnabled
                OUT_ID -> outputActive
                else -> true
            }
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
            )
            // After the box, not before: drawModuleBox fills opaquely, so a highlight
            // drawn underneath is painted straight over and never appears.
            //
            // Both rails get the same weight of outline when switched on, because they
            // are the same kind of control and reading as different ones was confusing.
            // In is red: a live microphone is a record light everywhere else, and the
            // one rail that can embarrass you should be the one that looks urgent.
            if (live) {
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
            val a = portScreen(patch, conn.from, camera, frame) ?: return@forEach
            val b = portScreen(patch, conn.to, camera, frame) ?: return@forEach
            val dim = !patch.portUsable(conn.from) || !patch.portUsable(conn.to)
            // Coloured by what the source emits, not what the destination expects --
            // the two may legitimately differ, and the cable should say what is actually
            // travelling down it.
            val color = patch.kindOf(conn.from).cable.copy(alpha = if (dim) 0.3f else CABLE_ALPHA)
            drawCable(a, b, color, 2.5f * d, intoBottom = conn.to.dir == PortDirection.MOD)
            // A plug at each end, in the cable's colour. Drawn over, the stroke would cover the
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
        }

        patch.modules.firstOrNull { it.expanded }?.let { open ->
            drawPanel(
                open, patch, panelRect(frame), d, screenMeasurer, playing, playingStep,
                intervalMenu, liveParams,
            )
        }

        // After the panel, so they float over it rather than being buried by it. Undo is
        // most wanted from inside a panel, where the knob you just moved is on screen
        // and can be watched moving back; having to close the panel, undo blind and
        // reopen to see what happened is the opposite of that.
        //
        // Hidden rather than greyed when there is nothing to undo: a disabled control
        // promises something could happen here, and at the start of a session nothing
        // could. The panel's knob rows are inset by PANEL_SIDE, so the corner these sit
        // in covers no control of the panel's own.
        if (canUndo) drawHistoryButton(frame.historyRect(false), d, redo = false)
        if (canRedo) drawHistoryButton(frame.historyRect(true), d, redo = true)

        // Over the panel for the same reason as the buttons, and before the context menu,
        // which is transient and should cover everything while it is up.
        drawTransport(frame, d, patch, card == FloatingCard.Transport, transportBeat, screenMeasurer)
        drawScales(
            frame, d, patch, scales, playingEntry, card == FloatingCard.Scales, scaleView,
            screenMeasurer,
        )

        (interaction as? Interaction.Menu)?.let { menu ->
            drawMenu(menuLayout(menuItems(menu.targetId), menu.anchor, d, size), d, screenMeasurer)
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
 * A port never grabs past the midpoint to its neighbour.
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

    modules.forEach { module ->
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
    pinned.firstOrNull { frame.railRect(it).contains(screen) }?.let { return it }
    val world = camera.toWorld(screen)
    return free.lastOrNull { it.bounds.contains(world) }
}

/**
 * How long the pulse lasts. Long enough to catch out of the corner of an eye, short
 * enough that a second undo half a second later reads as a second event rather than one
 * continuous glow.
 */
private const val FLASH_MS = 450

/** Warm white rather than the module's accent: this means "changed", not "is a filter". */
private val FlashColor = Color(0xFFE8EEF5)

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
    val rows = gridRows(area, d)
    val cellW = area.width / columns
    val cellH = area.height / rows
    val inset = 1f * d
    val radius = CornerRadius(3f * d, 3f * d)

    // Steps past the loop length still exist and are still editable; they simply are not
    // reached. Dimming them says so without hiding the work already in them.
    val length = module.params.getOrNull(0)?.toInt() ?: columns

    val topDegree = module.gridBottom + rows - 1

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
        val degree = module.gridBottom + (rows - 1 - row)
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
            // is the absence of a note, not a note in a different colour, and drawing one
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

            // The note actually sounding right now, ringed rather than recoloured: the
            // accent already means "there is a note here", and a second colour for
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
        if (!above && step.degree >= module.gridBottom) return@repeat

        val live = column < length
        val centreX = area.left + (column + 0.5f) * cellW
        val edgeY = if (above) area.top else area.bottom
        val point = if (above) edgeY + 1f * d else edgeY - 1f * d
        val base = if (above) edgeY + 8f * d else edgeY - 8f * d
        val half = 6f * d

        // A marker sounds the same way a cell does, or a note you cannot see would be
        // the one note the playhead never acknowledges.
        val marker = Path().apply {
            moveTo(centreX, point)
            lineTo(centreX - half, base)
            lineTo(centreX + half, base)
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
 * waveform: dragging to pick "square" out of four unlabelled positions asks you to know
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

        // Dark ink on the lit button, light on the rest: the accent colours are bright
        // enough that a white glyph on top of one disappears.
        val ink = if (on) Color(0xFF14171C) else Color(0xFFB7C0CE)
        when (param.choice) {
            Choice.WAVE -> drawWave(box, d, i, ink)
            Choice.DIVISION -> {
                val text = measurer.measure(INTERVALS.getOrNull(i)?.label.orEmpty(), PanelValueStyle)
                drawText(
                    text,
                    color = ink,
                    topLeft = Offset(
                        box.center.x - text.size.width / 2f,
                        box.center.y - text.size.height / 2f,
                    ),
                )
            }
            Choice.NUMBER -> {
                val text = measurer.measure((param.min + i).toInt().toString(), PanelValueStyle)
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

    val centre = rect.center
    val radius = rect.width * 0.24f
    val stroke = 2.2f * d

    // Drawn a touch high: the arrowheads hang below the arc, so centring the arc itself
    // would leave the glyph sitting low in the box.
    val arc = Offset(centre.x, centre.y - radius * 0.35f)
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
) {
    val d = frame.density
    val card = frame.transportCard()
    val tempoRow = transportTempoRow(card, d)
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

/** A small button: a box with a centred label, dimmed when it would do nothing. */
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
    val suffix = if (entries.size == 1) "  ·  ${playing.size}"
        else "  ·  ${playingEntry + 1} of ${entries.size}"
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
    fun heading(text: String, left: Float, right: Float, centred: Boolean) {
        val t = measurer.measure(text, GridLabelStyle)
        val x = if (centred) (left + right) / 2f - t.size.width / 2f else left
        drawText(t, topLeft = Offset(x, headTop))
    }
    heading("scale", columns.name.left, columns.name.right, centred = false)
    heading("bars", columns.barsLess.left, columns.barsMore.right, centred = true)
    heading("beats", columns.beatsLess.left, columns.beatsMore.right, centred = true)
    heading("root", columns.rootLess.left, columns.rootMore.right, centred = true)

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
        val layout = menuLayout(menuItems(current.targetId), current.anchor, frame.density, frame.canvas)
        val chosen = layout.tiles.firstOrNull { it.first.contains(screen) }?.second
            ?: return Interaction.Idle // tapped away: dismiss
        when (chosen) {
            is MenuItem.Add -> {
                // Place the new module centred on where the long press landed.
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
        }
        return Interaction.Idle
    }

    // Undo before anything else on the canvas, and regardless of what is armed. It is
    // the control you reach for when the last thing you did was wrong, and making it
    // wait its turn behind an armed connection would be exactly backwards.
    if (controls.tapHistory(frame, screen)) return Interaction.Idle

    val port = patch.hitPort(camera, frame, screen, touchPx)

    // A rail's body is its switch. Only while idle, so it never eats the tap that
    // cancels an armed connection.
    if (port == null && current is Interaction.Idle) {
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

        patch.hitModule(camera, frame, screen)?.let { module ->
            // Tapping a module's body opens its panel. One at a time: the panel takes the
            // screen, so there is nowhere for a second one to go.
            if (!module.isPinned && module.type.params.isNotEmpty()) {
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
    }
}

// ---------------------------------------------------------------- context menu

/**
 * Mirrors kMaxPorts in node.h. A module with more ports than this would have its extra
 * cables silently dropped by the engine, so it is asserted in a test rather than trusted.
 */
internal const val MAX_PORTS = 4

/** Mirrors kMaxParams in node.h. A sixth knob would simply never reach the engine. */
internal const val MAX_PARAMS = 5

/**
 * Mirrors StepsNode::kSteps in nodes.h. A seventeenth step would be written here, saved
 * to the file, and silently dropped on the way to the engine.
 */
internal const val STEP_COUNT = 16

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

internal fun defaultSteps(type: ModuleType): List<Step> =
    (0 until type.stepCount).map { Step(DEFAULT_PATTERN[it % DEFAULT_PATTERN.size]) }

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
): MenuLayout {
    // Use as few rows as the column cap allows, then spread the items evenly across
    // them, so four items are 2x2 rather than a row of three and a lonely orphan.
    val rows = ceil(items.size / MenuMetrics.COLS.toFloat()).toInt().coerceAtLeast(1)
    val cols = ceil(items.size / rows.toFloat()).toInt().coerceAtLeast(1)
    val w = (MenuMetrics.PAD * 2 + cols * MenuMetrics.TILE_W + (cols - 1) * MenuMetrics.GAP) * d
    val h = (MenuMetrics.PAD * 2 + rows * MenuMetrics.TILE_H + (rows - 1) * MenuMetrics.GAP) * d
    val margin = MenuMetrics.SCREEN_MARGIN * d

    val left = (anchor.x - w / 2f).coerceIn(margin, maxOf(margin, canvas.width - w - margin))
    val top = (anchor.y - h - MenuMetrics.LIFT * d)
        .coerceIn(margin, maxOf(margin, canvas.height - h - margin))

    val tiles = items.mapIndexed { i, item ->
        val c = i % cols
        val r = i / cols
        val x = left + (MenuMetrics.PAD + c * (MenuMetrics.TILE_W + MenuMetrics.GAP)) * d
        val y = top + (MenuMetrics.PAD + r * (MenuMetrics.TILE_H + MenuMetrics.GAP)) * d
        Rect(Offset(x, y), Size(MenuMetrics.TILE_W * d, MenuMetrics.TILE_H * d)) to item
    }
    return MenuLayout(Rect(Offset(left, top), Size(w, h)), tiles)
}

private fun MenuItem.label(): String = when (this) {
    is MenuItem.Add -> type.name
    is MenuItem.Duplicate -> "Duplicate"
    is MenuItem.Delete -> "Delete"
}

private fun MenuItem.tint(): Color = when (this) {
    is MenuItem.Add -> type.accent
    is MenuItem.Duplicate -> Color(0xFF8A93A3)
    is MenuItem.Delete -> Color(0xFFE07A6B)
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
        val text = measurer.measure(item.label(), MenuLabelStyle)
        drawText(
            text,
            topLeft = Offset(
                rect.left + (rect.width - text.size.width) / 2f,
                rect.top + (rect.height - text.size.height) / 2f,
            ),
        )
    }
}

// ---------------------------------------------------------------- drawing

/** A live microphone reads as a record light, not as another accent colour. */
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
) {
    val corner = CornerRadius(PatchModule.CORNER * unit, PatchModule.CORNER * unit)

    drawRoundRect(
        color = Color(0xFF232830).copy(alpha = alpha),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = corner,
    )
    drawRoundRect(
        color = module.type.accent.copy(alpha = 0.55f * alpha),
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = corner,
        style = Stroke(width = strokeWidth),
    )

    if (showTitle) {
        val title = measurer.measure(module.type.name, TitleStyle)
        drawText(
            title,
            alpha = alpha,
            topLeft = Offset(
                rect.left + (rect.width - title.size.width) / 2f,
                rect.top + (PatchModule.HEADER * unit - title.size.height) / 2f,
            ),
        )
    }

    PortDirection.entries.forEach { dir ->
        val ports = module.ports(dir)
        ports.forEachIndexed { i, port ->
            val ref = PortRef(module.id, dir, i)
            val at = portIn(rect, unit, dir, i, ports.size, module.portsBody * unit)
            val lit = ref == armed
            // Idle colour comes from what the port carries, so the four kinds are
            // distinguishable at a glance without reading a label -- and since typing is
            // enforced, the colour now says which cables will be accepted rather than
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

/** The colour of modulation, which now has a kind of its own to take it from. */
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
) {
    val corner = CornerRadius(14f * d, 14f * d)

    // Opaque, and slightly lifted from the canvas showing through the border.
    drawRoundRect(Color(0xE6000000), panel.topLeft, panel.size, corner)
    drawRoundRect(Color(0xFF1B1F26), panel.topLeft, panel.size, corner)
    drawRoundRect(
        module.type.accent.copy(alpha = 0.7f), panel.topLeft, panel.size, corner,
        style = Stroke(width = 2f * d),
    )

    val title = measurer.measure(module.type.name, PanelTitleStyle)
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
    // stub down past the edge when patched. Labelled below the edge rather than above it:
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

    if (module.type.stepCount > 0) {
        drawStepGrid(
            panelGrid(panel, d), d, module, scale, module.type.accent, measurer, playingStep,
        )
    }

    // Knobs -- the rows only. Walking every parameter drew the interval, which lives in the
    // header, as a row of buttons laid over the first real row: it showed intervals where
    // taps set the length.
    module.type.rowParams.forEach { index ->
        val param = module.type.params[index]
        val row = panelRow(panel, d, module.type, index)
        val range = module.modRanges[index]
        // Where the modulator has taken it this frame, for a parameter being modulated; its
        // knob for anything else.
        val value = live[index] ?: module.params.getOrElse(index) { param.default }
        if (module.canExpose(index)) {
            drawChip(panelModChip(panel, d, module.type, index), d, "[ ]", range != null, ModulationColor, measurer)
        }

        val name = measurer.measure(param.name, PanelParamStyle)
        drawText(name, topLeft = Offset(row.left, row.top + 4f * d))

        // A stepped parameter shows no numeric readout: the lit button is the reading,
        // and "0" next to a picture of a sawtooth is noise.
        if (param.curve != ParamCurve.STEPPED) {
            // An exposed parameter reads its range, not a value it is not going to hold.
            val text = if (range != null) rangeReading(param, range) else param.format(value)
            val reading = measurer.measure(text, PanelValueStyle)
            drawText(
                reading,
                topLeft = Offset(row.right - reading.size.width, row.top + 2f * d),
            )
        }

        if (param.curve == ParamCurve.STEPPED) {
            drawChoices(row, d, param, value, module.type.accent, measurer)
            if (range != null) {
                val box = choiceBox(row, d, param, 0)
                drawBrackets(row, d, param, range, box.top - 4f * d, box.bottom + 4f * d)
            }
            return@forEach
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
            color = module.type.accent,
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
 * The patch a fresh install opens with: a complete instrument, so the first thing you
 * hear is music rather than a test tone. The transport steps Steps, Steps sends notes to
 * Osc, and Osc feeds the filter and both output channels.
 *
 * Shorter than it was, by three cables and a module. The old demo needed a VCA that an
 * envelope opened, because a Eurorack oscillator drones until something shapes it; Osc is
 * polyphonic and carries its own envelope per voice, so a note already has a shape when it
 * arrives at the filter.
 */
fun demoPatch(): Patch =
    Patch().apply {
        val steps = add(Types.Steps, Offset(165f, 40f))!!
        val osc = add(Types.Osc, Offset(330f, 40f))!!
        val filter = add(Types.Filter, Offset(495f, 40f))!!

        fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
        fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)

        connect(out(steps, 1), into(osc, 0))   // notes
        connect(out(osc, 0), into(filter, 0))
        connect(out(filter, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        connect(out(filter, 0), PortRef(OUT_ID, PortDirection.INPUT, 1))
    }
