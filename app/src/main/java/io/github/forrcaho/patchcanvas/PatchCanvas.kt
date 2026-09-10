package io.github.forrcaho.patchcanvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * one place that converts. Nothing in this file should call toPx() on module geometry.
 */

// ---------------------------------------------------------------- model

enum class PortDirection { INPUT, OUTPUT }

data class ModuleType(
    val name: String,
    val inputs: List<String>,
    val outputs: List<String>,
    val accent: Color,
)

object Types {
    val Osc = ModuleType("Osc", listOf("pitch", "fm"), listOf("out"), Color(0xFF7FD1C1))
    val Filter = ModuleType("Filter", listOf("in", "cutoff"), listOf("out"), Color(0xFFE0A24B))
    val Env = ModuleType("Env", listOf("gate"), listOf("out"), Color(0xFFB98FE0))
    val Steps = ModuleType("Steps", listOf("clock"), listOf("pitch", "gate"), Color(0xFF6FA8E5))
    val Out = ModuleType("Out", listOf("L", "R"), emptyList(), Color(0xFFE0E0E0))
}

/** Modules live in world units, and one world unit is one dp. */
class PatchModule(
    val id: Long,
    val type: ModuleType,
    position: Offset,
) {
    var position by mutableStateOf(position)

    /**
     * Height follows port count at a fixed pitch rather than dividing a constant, so
     * adjacent ports are never closer than PORT_PITCH no matter how many a module has.
     * That makes crowding impossible by construction instead of a case to disambiguate.
     */
    val height: Float
        get() {
            val ports = maxOf(type.inputs.size, type.outputs.size, 1)
            return HEADER + maxOf(MIN_BODY, ports * PORT_PITCH)
        }

    val size: Size get() = Size(WIDTH, height)
    val bounds: Rect get() = Rect(position, size)

    /** Ports run down the left (inputs) and right (outputs) edges, centred in the body. */
    fun portOffset(dir: PortDirection, index: Int): Offset {
        val names = if (dir == PortDirection.INPUT) type.inputs else type.outputs
        val x = if (dir == PortDirection.INPUT) position.x else position.x + WIDTH
        val bodyTop = position.y + HEADER
        val bodyHeight = height - HEADER
        val span = (names.size - 1) * PORT_PITCH
        val first = bodyTop + (bodyHeight - span) / 2f
        return Offset(x, first + index * PORT_PITCH)
    }

    companion object {
        const val WIDTH = 116f
        /** Title band above the ports. */
        const val HEADER = 22f
        /** Centre-to-centre spacing of adjacent ports on one edge. */
        const val PORT_PITCH = 44f
        const val MIN_BODY = 44f
        const val CORNER = 8f
        const val PORT_RADIUS = 6f
        const val PORT_RADIUS_ARMED = 9f
        const val LABEL_INSET = 13f
    }
}

data class PortRef(val moduleId: Long, val dir: PortDirection, val index: Int)

data class Connection(val from: PortRef, val to: PortRef)

class Patch {
    val modules = mutableStateListOf<PatchModule>()
    val connections = mutableStateListOf<Connection>()

    private var nextId = 1L

    fun add(type: ModuleType, at: Offset): PatchModule =
        PatchModule(nextId++, type, at).also { modules.add(it) }

    fun module(id: Long): PatchModule? = modules.firstOrNull { it.id == id }

    fun portPosition(ref: PortRef): Offset? =
        module(ref.moduleId)?.portOffset(ref.dir, ref.index)

    fun portName(ref: PortRef): String? = module(ref.moduleId)?.let {
        val names = if (ref.dir == PortDirection.INPUT) it.type.inputs else it.type.outputs
        names.getOrNull(ref.index)
    }

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

// ---------------------------------------------------------------- the composable

@Composable
fun PatchCanvas(
    patch: Patch,
    modifier: Modifier = Modifier,
    safeArea: PaddingValues = PaddingValues(),
    portTouchRadius: Dp = 24.dp,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val camera = remember(density.density) { Camera(density.density) }
    var interaction by remember { mutableStateOf<Interaction>(Interaction.Idle) }

    // Text is drawn inside the world transform, so it has to be laid out in world units.
    // A density of 1 makes `11.sp` mean 11 world units — i.e. 11dp at zoom 1.0 — and
    // keeps the measurer's cache warm, since the style never changes with zoom.
    val fontResolver = LocalFontFamilyResolver.current
    val textMeasurer = remember(fontResolver, layoutDirection) {
        TextMeasurer(fontResolver, Density(1f, 1f), layoutDirection)
    }

    // Start the view inside the safe area rather than under the cutout or gesture bar.
    // Re-applies while the user has not moved the camera, so a rotation still lands well.
    val insetLeft = with(density) { safeArea.calculateStartPadding(layoutDirection).toPx() }
    val insetTop = with(density) { safeArea.calculateTopPadding().toPx() }
    LaunchedEffect(insetLeft, insetTop, camera.userMoved) {
        if (!camera.userMoved) camera.frameAt(Offset(insetLeft, insetTop))
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val touchPx = portTouchRadius.toPx()

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var kind: GestureKind = GestureKind.Undecided
                    var draggedModule: PatchModule? = null
                    var grabOffset = Offset.Zero
                    val startPan = camera.pan
                    var lastTwoFinger: TwoFinger? = null

                    // What is under the finger decides what a drag would mean.
                    val hitModule = patch.hitModule(camera.toWorld(down.position))

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.isEmpty()) {
                            // ---- released
                            if (kind == GestureKind.Undecided) {
                                interaction = handleTap(
                                    patch, camera, interaction, down.position, touchPx
                                )
                            }
                            break
                        }

                        if (pressed.size >= 2) {
                            // ---- pinch: zoom + pan together, cancels any pending tap
                            kind = GestureKind.Transform
                            val a = pressed[0].position
                            val b = pressed[1].position
                            val now = TwoFinger(centroid = (a + b) / 2f, spread = (a - b).getDistance())
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

                        if (kind == GestureKind.Undecided) {
                            if ((change.position - down.position).getDistance() > slop) {
                                kind = if (hitModule != null && interaction is Interaction.Idle) {
                                    draggedModule = hitModule
                                    grabOffset = camera.toWorld(down.position) - hitModule.position
                                    GestureKind.MoveModule
                                } else {
                                    GestureKind.Pan
                                }
                            }
                        }

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
        val touchPx = portTouchRadius.toPx()

        drawRect(Color(0xFF14171C))

        withTransform({
            translate(camera.pan.x, camera.pan.y)
            scale(camera.worldToScreen, camera.worldToScreen, pivot = Offset.Zero)
        }) {
            // Stroke widths divide by zoom, not by worldToScreen: dividing by zoom alone
            // leaves a constant width in dp, which is what "1.5dp of line" should mean.
            patch.connections.forEach { conn ->
                val a = patch.portPosition(conn.from) ?: return@forEach
                val b = patch.portPosition(conn.to) ?: return@forEach
                drawCable(a, b, Color(0xFF8A93A3), 2.5f / camera.zoom)
            }

            patch.modules.forEach { module ->
                drawModule(
                    module = module,
                    armed = (interaction as? Interaction.Connecting)?.source,
                    zoom = camera.zoom,
                    measurer = textMeasurer,
                )
            }
        }

        // Halo on the armed port, drawn unscaled so it always reads as a real target.
        (interaction as? Interaction.Connecting)?.let { state ->
            patch.portPosition(state.source)?.let { world ->
                drawCircle(
                    color = Color(0xFF7FD1C1).copy(alpha = 0.28f),
                    radius = effectiveTouchRadius(camera, touchPx),
                    center = camera.toScreen(world),
                )
            }
        }
    }
}

private enum class GestureKind { Undecided, MoveModule, Pan, Transform }

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
 */
private fun effectiveTouchRadius(camera: Camera, radiusPx: Float): Float =
    min(radiusPx, PatchModule.PORT_PITCH * camera.worldToScreen * 0.5f)

private fun Patch.hitPort(camera: Camera, screen: Offset, radiusPx: Float): PortRef? {
    var best: PortRef? = null
    var bestDist = effectiveTouchRadius(camera, radiusPx)

    modules.forEach { module ->
        fun test(dir: PortDirection, count: Int) {
            repeat(count) { i ->
                val d = (camera.toScreen(module.portOffset(dir, i)) - screen).getDistance()
                if (d <= bestDist) {
                    bestDist = d
                    best = PortRef(module.id, dir, i)
                }
            }
        }
        test(PortDirection.INPUT, module.type.inputs.size)
        test(PortDirection.OUTPUT, module.type.outputs.size)
    }
    return best
}

private fun Patch.hitModule(world: Offset): PatchModule? =
    modules.lastOrNull { it.bounds.contains(world) }

// ---------------------------------------------------------------- tap logic

private fun handleTap(
    patch: Patch,
    camera: Camera,
    current: Interaction,
    screen: Offset,
    touchPx: Float,
): Interaction {
    val port = patch.hitPort(camera, screen, touchPx)

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

// ---------------------------------------------------------------- drawing

private val TitleStyle = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.Medium,
    color = Color(0xFFC9D0DA),
)

private val PortLabelStyle = TextStyle(
    fontSize = 9.sp,
    color = Color(0xFF98A0AD),
)

private fun DrawScope.drawCable(a: Offset, b: Offset, color: Color, width: Float) {
    val slack = ((b.x - a.x) * 0.5f).coerceAtLeast(28f)
    val path = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(a.x + slack, a.y, b.x - slack, b.y, b.x, b.y)
    }
    drawPath(path, color, style = Stroke(width = width))
}

private fun DrawScope.drawModule(
    module: PatchModule,
    armed: PortRef?,
    zoom: Float,
    measurer: TextMeasurer,
) {
    val r = module.bounds

    drawRoundRect(
        color = Color(0xFF232830),
        topLeft = r.topLeft,
        size = r.size,
        cornerRadius = CornerRadius(PatchModule.CORNER, PatchModule.CORNER),
    )
    drawRoundRect(
        color = module.type.accent.copy(alpha = 0.55f),
        topLeft = r.topLeft,
        size = r.size,
        cornerRadius = CornerRadius(PatchModule.CORNER, PatchModule.CORNER),
        style = Stroke(width = 1.5f / zoom),
    )

    if (zoom >= Camera.TITLE_ZOOM) {
        val title = measurer.measure(module.type.name, TitleStyle)
        drawText(
            title,
            topLeft = Offset(
                r.left + (PatchModule.WIDTH - title.size.width) / 2f,
                r.top + (PatchModule.HEADER - title.size.height) / 2f,
            ),
        )
    }

    fun ports(dir: PortDirection, names: List<String>) {
        names.forEachIndexed { i, name ->
            val ref = PortRef(module.id, dir, i)
            val at = module.portOffset(dir, i)
            val lit = ref == armed
            drawCircle(
                color = if (lit) module.type.accent else Color(0xFF6E7684),
                radius = if (lit) PatchModule.PORT_RADIUS_ARMED else PatchModule.PORT_RADIUS,
                center = at,
            )
            if (zoom >= Camera.LABEL_ZOOM) {
                val label = measurer.measure(name, PortLabelStyle)
                val x = if (dir == PortDirection.INPUT) {
                    r.left + PatchModule.LABEL_INSET
                } else {
                    r.right - PatchModule.LABEL_INSET - label.size.width
                }
                drawText(label, topLeft = Offset(x, at.y - label.size.height / 2f))
            }
        }
    }
    ports(PortDirection.INPUT, module.type.inputs)
    ports(PortDirection.OUTPUT, module.type.outputs)
}

// ---------------------------------------------------------------- demo state

@Composable
fun rememberDemoPatch(): Patch = remember {
    Patch().apply {
        val steps = add(Types.Steps, Offset(40f, 40f))
        val osc = add(Types.Osc, Offset(220f, 40f))
        val filter = add(Types.Filter, Offset(400f, 40f))
        val env = add(Types.Env, Offset(220f, 190f))
        val out = add(Types.Out, Offset(580f, 90f))

        connect(PortRef(steps.id, PortDirection.OUTPUT, 0), PortRef(osc.id, PortDirection.INPUT, 0))
        connect(PortRef(steps.id, PortDirection.OUTPUT, 1), PortRef(env.id, PortDirection.INPUT, 0))
        connect(PortRef(osc.id, PortDirection.OUTPUT, 0), PortRef(filter.id, PortDirection.INPUT, 0))
        connect(PortRef(env.id, PortDirection.OUTPUT, 0), PortRef(filter.id, PortDirection.INPUT, 1))
        connect(PortRef(filter.id, PortDirection.OUTPUT, 0), PortRef(out.id, PortDirection.INPUT, 0))
    }
}
