package io.github.forrcaho.patchcanvas

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeout
import kotlin.math.ceil
import kotlin.math.min

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

enum class PortDirection { INPUT, OUTPUT }

/** Which viewport edge a pinned module is welded to. */
enum class Edge { LEFT, RIGHT }

/**
 * What a port carries.
 *
 * Advisory, not enforced: any output may patch to any input. In hardware modular it is
 * all just voltage, and patching audio into a CV input is a technique rather than a
 * mistake -- audio-rate modulation lives there. Blocking it would make this less modular
 * than the thing it is modelled on. The colour says what to expect; the cable decides
 * what happens.
 */
enum class SignalKind(val cable: Color, val idle: Color) {
    AUDIO(Color(0xFF8A93A3), Color(0xFF6E7684)),
    CV(Color(0xFFB98FE0), Color(0xFF8A6FA8)),
    GATE(Color(0xFFE0A24B), Color(0xFFA8793A)),
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
data class Param(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val curve: ParamCurve = ParamCurve.LINEAR,
) {
    /** Knob travel, 0..1, to a value. */
    fun valueAt(position: Float): Float {
        val t = position.coerceIn(0f, 1f)
        return when (curve) {
            ParamCurve.LINEAR -> min + t * (max - min)
            ParamCurve.STEPPED -> kotlin.math.round(min + t * (max - min))
            ParamCurve.EXPONENTIAL -> min * kotlin.math.exp(t * kotlin.math.ln(max / min))
        }
    }

    /** A value back to knob travel, so a restored patch shows its knobs where they are. */
    fun positionOf(value: Float): Float {
        val v = value.coerceIn(minOf(min, max), maxOf(min, max))
        return when (curve) {
            ParamCurve.LINEAR, ParamCurve.STEPPED ->
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
)

object Types {
    private val A = SignalKind.AUDIO
    private val C = SignalKind.CV
    private val G = SignalKind.GATE

    private val LIN = ParamCurve.LINEAR
    private val EXP = ParamCurve.EXPONENTIAL
    private val STEP = ParamCurve.STEPPED

    val Osc = ModuleType(
        "Osc", listOf(Port("pitch", C), Port("fm", C)), listOf(Port("out", A)),
        Color(0xFF7FD1C1),
        params = listOf(
            Param("tune", -24f, 24f, 0f, "st", LIN),
            Param("wave", 0f, 3f, 0f, "", STEP),
        ),
    )
    val Filter = ModuleType(
        "Filter", listOf(Port("in", A), Port("cutoff", C)), listOf(Port("out", A)),
        Color(0xFFE0A24B),
        params = listOf(
            // The knob sets where a cable's zero sits; the cable moves it in octaves
            // from there, which is how a cutoff input behaves on hardware.
            Param("cutoff", 20f, 18000f, 1000f, "Hz", EXP),
            Param("res", 0f, 0.95f, 0.3f, "", LIN),
        ),
    )
    val Env = ModuleType(
        "Env", listOf(Port("gate", G)), listOf(Port("out", C)),
        Color(0xFFB98FE0),
        params = listOf(
            Param("A", 0.001f, 5f, 0.005f, "s", EXP),
            Param("D", 0.001f, 5f, 0.12f, "s", EXP),
            Param("S", 0f, 1f, 0.6f, "", LIN),
            Param("R", 0.001f, 10f, 0.25f, "s", EXP),
        ),
    )
    val Vca = ModuleType(
        "VCA", listOf(Port("in", A), Port("cv", C)), listOf(Port("out", A)),
        Color(0xFFE07A9B),
        // Added to the control voltage, so a VCA with nothing patched can still open.
        params = listOf(Param("bias", 0f, 1f, 0f, "", LIN)),
    )
    val Clock = ModuleType(
        "Clock", emptyList(), listOf(Port("gate", G)),
        Color(0xFFD9C46A),
        params = listOf(Param("bpm", 20f, 300f, 120f, "", LIN)),
    )
    val Steps = ModuleType(
        "Steps", listOf(Port("clock", G)), listOf(Port("pitch", C), Port("gate", G)),
        Color(0xFF6FA8E5),
        params = listOf(
            Param("len", 1f, 8f, 8f, "", STEP),
            Param("transp", -24f, 24f, 0f, "st", LIN),
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
        params = listOf(Param("level", 0f, 2f, 1f, "", LIN)),
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
    val palette = listOf(Osc, Filter, Env, Vca, Clock, Steps, Mix)

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

    val height: Float get() = heightFor(type)

    /** The ports' band. Equal to the body, now that opening a module leaves the canvas. */
    val portsBody: Float get() = portsBodyFor(type)

    val width: Float get() = if (isPinned) RAIL_WIDTH else WIDTH

    /** World-space bounds. Meaningless for pinned modules; use Frame.railRect instead. */
    val bounds: Rect get() = Rect(position, Size(width, height))

    fun ports(dir: PortDirection): List<Port> =
        if (dir == PortDirection.INPUT) type.inputs else type.outputs

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
        const val PANEL_STUB = 26f

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

/** A knob's row: label, value and the bar beneath them. */
internal fun panelRow(panel: Rect, d: Float, index: Int, count: Int): Rect {
    val body = panelBody(panel, d)
    val side = PatchModule.PANEL_SIDE * d
    val rowHeight = minOf(PatchModule.PANEL_ROW_MAX * d, body.height / maxOf(count, 1))
    val block = rowHeight * count
    val top = body.top + (body.height - block) / 2f + index * rowHeight
    return Rect(panel.left + side, top, panel.right - side, top + rowHeight)
}

internal fun panelKnobAt(panel: Rect, d: Float, module: PatchModule, at: Offset): Int? {
    val count = module.type.params.size
    module.type.params.indices.forEach { i ->
        // Generous vertically: the rows are the only targets on the panel, so a near
        // miss should still land rather than do nothing.
        if (panelRow(panel, d, i, count).inflate(6f * d).contains(at)) return i
    }
    return null
}

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

    private var nextId = FIRST_FREE_ID

    init {
        modules += PatchModule(OUT_ID, Types.Out, Offset.Zero)
        modules += PatchModule(IN_ID, Types.In, Offset.Zero)
    }

    val free: List<PatchModule> get() = modules.filter { !it.isPinned }
    val pinned: List<PatchModule> get() = modules.filter { it.isPinned }

    fun module(id: Long): PatchModule? = modules.firstOrNull { it.id == id }

    fun port(ref: PortRef): Port? = module(ref.moduleId)?.ports(ref.dir)?.getOrNull(ref.index)

    /** What a cable leaving a port carries. Advisory: it colours, it does not gate. */
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

    /** A disabled input rail cannot be patched from, so it reads as present but inert. */
    fun portUsable(ref: PortRef): Boolean =
        !(ref.moduleId == IN_ID && !inputEnabled)

    /** Inputs take one source. Re-patching an occupied input replaces the old cable. */
    fun connect(a: PortRef, b: PortRef) {
        val (out, inp) = when {
            a.dir == PortDirection.OUTPUT && b.dir == PortDirection.INPUT -> a to b
            b.dir == PortDirection.OUTPUT && a.dir == PortDirection.INPUT -> b to a
            else -> return
        }
        if (out.moduleId == inp.moduleId) return // no self-patching for now
        connections.removeAll { it.to == inp }
        connections.add(Connection(out, inp))
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

    companion object {
        const val RAIL_MARGIN = 8f
    }
}

/** Screen position of any port, whether its module is pinned or free. */
private fun portScreen(
    patch: Patch,
    ref: PortRef,
    camera: Camera,
    frame: Frame,
): Offset? {
    val module = patch.module(ref.moduleId) ?: return null
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

                    // An open panel owns the screen. Pan, zoom and patching all belong to
                    // the canvas behind it, so this is a separate and much simpler loop
                    // rather than another outcome bolted into the one below.
                    val open = patch.modules.firstOrNull { it.expanded }
                    if (open != null) {
                        val panel = panelRect(frame)
                        val knob = panelKnobAt(panel, frame.density, open, down.position)
                        var moved = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val change = pressed.first()
                            if ((change.position - down.position).getDistance() > slop) {
                                moved = true
                            }
                            if (knob != null) {
                                val param = open.type.params[knob]
                                open.setParam(
                                    knob,
                                    param.valueAt(
                                        panelKnobPosition(panel, frame.density, change.position.x),
                                    ),
                                )
                            }
                            change.consume()
                        }

                        if (!moved) {
                            if (knob != null) {
                                // A tap on a knob jumps there, which is faster than
                                // dragging when you already know where you want it.
                                val param = open.type.params[knob]
                                open.setParam(
                                    knob,
                                    param.valueAt(
                                        panelKnobPosition(panel, frame.density, down.position.x),
                                    ),
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
                                onToggleOutput, onToggleInput,
                            )
                            return@awaitEachGesture
                        }
                        GestureKind.LongPress -> {
                            // A rail offers nothing to delete, so it opens no menu -- but
                            // it does have knobs, and tapping it is already its switch, so
                            // holding is the way in to its panel.
                            interaction = if (hitModule != null && hitModule.isPinned) {
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

        // Cables are drawn in screen space, because a cable can run from a world module
        // to a rail and so have one endpoint in each space. Resolving both through
        // portScreen() keeps that a non-case.
        patch.connections.forEach { conn ->
            val a = portScreen(patch, conn.from, camera, frame) ?: return@forEach
            val b = portScreen(patch, conn.to, camera, frame) ?: return@forEach
            val dim = !patch.portUsable(conn.from) || !patch.portUsable(conn.to)
            // Coloured by what the source emits, not what the destination expects --
            // the two may legitimately differ, and the cable should say what is actually
            // travelling down it.
            drawCable(
                a, b,
                patch.kindOf(conn.from).cable.copy(alpha = if (dim) 0.3f else 1f),
                2.5f * d,
            )
        }

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
            drawPanel(open, patch, panelRect(frame), d, screenMeasurer)
        }

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
    }
    return best
}

private fun Patch.hitModule(camera: Camera, frame: Frame, screen: Offset): PatchModule? {
    pinned.firstOrNull { frame.railRect(it).contains(screen) }?.let { return it }
    val world = camera.toWorld(screen)
    return free.lastOrNull { it.bounds.contains(world) }
}

// ---------------------------------------------------------------- tap logic

private fun handleTap(
    patch: Patch,
    camera: Camera,
    frame: Frame,
    current: Interaction,
    screen: Offset,
    touchPx: Float,
    onToggleOutput: () -> Unit,
    onToggleInput: () -> Unit,
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

    val port = patch.hitPort(camera, frame, screen, touchPx)

    // A rail's body is its switch. Only while idle, so it never eats the tap that
    // cancels an armed connection.
    if (port == null && current is Interaction.Idle) {
        patch.module(OUT_ID)?.let { out ->
            if (frame.railRect(out).contains(screen)) {
                onToggleOutput()
                return Interaction.Idle
            }
        }
        patch.module(IN_ID)?.let { input ->
            if (frame.railRect(input).contains(screen)) {
                onToggleInput()
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
            port.dir != current.source.dir -> {                    // valid partner
                patch.connect(current.source, port)
                Interaction.Idle
            }
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

/** Mirrors kMaxParams in node.h. A fifth knob would simply never reach the engine. */
internal const val MAX_PARAMS = 4

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

private val PortLabelStyle = TextStyle(
    fontSize = 9.sp,
    color = Color(0xFF98A0AD),
)

private val MenuLabelStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Medium,
    color = Color(0xFFE4E7EC),
)

private fun DrawScope.drawCable(a: Offset, b: Offset, color: Color, width: Float) {
    val slack = ((b.x - a.x) * 0.5f).coerceAtLeast(28f)
    val path = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(a.x + slack, a.y, b.x - slack, b.y, b.x, b.y)
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
            // Idle colour comes from what the port carries, so audio, CV and gate are
            // distinguishable at a glance without reading a label.
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

    // Knobs.
    val count = module.type.params.size
    module.type.params.forEachIndexed { index, param ->
        val row = panelRow(panel, d, index, count)
        val value = module.params.getOrElse(index) { param.default }

        val name = measurer.measure(param.name, PanelParamStyle)
        drawText(name, topLeft = Offset(row.left, row.top + 4f * d))

        val reading = measurer.measure(param.format(value), PanelValueStyle)
        drawText(
            reading,
            topLeft = Offset(row.right - reading.size.width, row.top + 2f * d),
        )

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
    }
}

// ---------------------------------------------------------------- demo state

@Composable
fun rememberDemoPatch(): Patch = remember { demoPatch() }

/**
 * The patch a fresh install opens with: a complete voice, so the first thing you hear is
 * an instrument rather than a test tone. Clock drives Steps, Steps plays Osc and fires
 * Env, Env opens the VCA, and the VCA feeds both output channels.
 */
fun demoPatch(): Patch =
    Patch().apply {
        val clock = add(Types.Clock, Offset(20f, 40f))!!
        val steps = add(Types.Steps, Offset(165f, 40f))!!
        val osc = add(Types.Osc, Offset(310f, 40f))!!
        val filter = add(Types.Filter, Offset(455f, 40f))!!
        val vca = add(Types.Vca, Offset(600f, 40f))!!
        val env = add(Types.Env, Offset(455f, 220f))!!

        fun out(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.OUTPUT, i)
        fun into(m: PatchModule, i: Int) = PortRef(m.id, PortDirection.INPUT, i)

        connect(out(clock, 0), into(steps, 0))
        connect(out(steps, 0), into(osc, 0))   // pitch
        connect(out(steps, 1), into(env, 0))   // gate
        connect(out(osc, 0), into(filter, 0))
        connect(out(filter, 0), into(vca, 0))
        connect(out(env, 0), into(vca, 1))     // envelope opens the VCA
        connect(out(vca, 0), PortRef(OUT_ID, PortDirection.INPUT, 0))
        connect(out(vca, 0), PortRef(OUT_ID, PortDirection.INPUT, 1))
    }
