package io.github.forrcaho.patchcanvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot

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
 *     target is always ~24dp of glass regardless of zoom, so patching works zoomed out.
 *
 *  3. One unified gesture loop rather than competing detectors. Compose's
 *     detectTransformGestures / detectDragGestures / detectTapGestures all consume
 *     events, so stacking them fights over the same pointer. awaitEachGesture lets us
 *     decide once, on the first move, what this gesture actually is.
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

/** Modules live in world units. 1 world unit ≈ 1dp at scale 1.0. */
class PatchModule(
    val id: Long,
    val type: ModuleType,
    position: Offset,
) {
    var position by mutableStateOf(position)

    val size: Size get() = Size(WIDTH, HEIGHT)
    val bounds: Rect get() = Rect(position, size)

    /** Ports are spread down the left (inputs) and right (outputs) edges. */
    fun portOffset(dir: PortDirection, index: Int): Offset {
        val names = if (dir == PortDirection.INPUT) type.inputs else type.outputs
        val x = if (dir == PortDirection.INPUT) position.x else position.x + WIDTH
        val y = position.y + HEIGHT * (index + 0.5f) / names.size.coerceAtLeast(1)
        return Offset(x, y)
    }

    companion object {
        const val WIDTH = 116f
        const val HEIGHT = 84f
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

/** Camera. World -> screen is `world * scale + pan`. */
class Camera {
    var scale by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    fun toWorld(screen: Offset) = (screen - pan) / scale
    fun toScreen(world: Offset) = world * scale + pan

    fun zoomAround(pivotScreen: Offset, factor: Float) {
        val newScale = (scale * factor).coerceIn(0.35f, 2.5f)
        val pivotWorld = toWorld(pivotScreen)
        scale = newScale
        pan = pivotScreen - pivotWorld * newScale
    }
}

// ---------------------------------------------------------------- the composable

@Composable
fun PatchCanvas(
    patch: Patch,
    modifier: Modifier = Modifier,
    portTouchRadius: Dp = 24.dp,
) {
    val camera = remember { Camera() }
    var interaction by remember { mutableStateOf<Interaction>(Interaction.Idle) }

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
                    val startScale = camera.scale
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
                                camera.pan += now.centroid - prev.centroid
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
                                camera.pan = startPan + (change.position - down.position)
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
            scale(camera.scale, camera.scale, pivot = Offset.Zero)
        }) {
            patch.connections.forEach { conn ->
                val a = patch.portPosition(conn.from) ?: return@forEach
                val b = patch.portPosition(conn.to) ?: return@forEach
                drawCable(a, b, Color(0xFF8A93A3), 2.5f / camera.scale)
            }

            patch.modules.forEach { module ->
                drawModule(
                    module = module,
                    armed = (interaction as? Interaction.Connecting)?.source,
                    scale = camera.scale,
                )
            }
        }

        // Halo on the armed port, drawn unscaled so it always reads as a real target.
        (interaction as? Interaction.Connecting)?.let { state ->
            patch.portPosition(state.source)?.let { world ->
                drawCircle(
                    color = Color(0xFF7FD1C1).copy(alpha = 0.28f),
                    radius = touchPx,
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
 * Screen-space hit test: the touch radius is a fixed number of physical pixels, so a
 * port stays equally tappable at every zoom level.
 */
private fun Patch.hitPort(camera: Camera, screen: Offset, radiusPx: Float): PortRef? {
    var best: PortRef? = null
    var bestDist = radiusPx

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

private fun DrawScope.drawCable(a: Offset, b: Offset, color: Color, width: Float) {
    val slack = ((b.x - a.x) * 0.5f).coerceAtLeast(28f)
    val path = Path().apply {
        moveTo(a.x, a.y)
        cubicTo(a.x + slack, a.y, b.x - slack, b.y, b.x, b.y)
    }
    drawPath(path, color, style = Stroke(width = width))
}

private fun DrawScope.drawModule(module: PatchModule, armed: PortRef?, scale: Float) {
    val r = module.bounds

    drawRoundRect(
        color = Color(0xFF232830),
        topLeft = r.topLeft,
        size = r.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
    )
    drawRoundRect(
        color = module.type.accent.copy(alpha = 0.55f),
        topLeft = r.topLeft,
        size = r.size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
        style = Stroke(width = 1.5f / scale),
    )

    fun ports(dir: PortDirection, names: List<String>) {
        names.indices.forEach { i ->
            val ref = PortRef(module.id, dir, i)
            val at = module.portOffset(dir, i)
            val lit = ref == armed
            drawCircle(
                color = if (lit) module.type.accent else Color(0xFF6E7684),
                radius = if (lit) 8f else 5.5f,
                center = at,
            )
        }
    }
    ports(PortDirection.INPUT, module.type.inputs)
    ports(PortDirection.OUTPUT, module.type.outputs)
}

// ---------------------------------------------------------------- demo state

@Composable
fun rememberDemoPatch(): Patch = remember {
    Patch().apply {
        val steps = add(Types.Steps, Offset(60f, 60f))
        val osc = add(Types.Osc, Offset(240f, 60f))
        val filter = add(Types.Filter, Offset(420f, 60f))
        val env = add(Types.Env, Offset(240f, 200f))
        val out = add(Types.Out, Offset(600f, 120f))

        connect(PortRef(steps.id, PortDirection.OUTPUT, 0), PortRef(osc.id, PortDirection.INPUT, 0))
        connect(PortRef(steps.id, PortDirection.OUTPUT, 1), PortRef(env.id, PortDirection.INPUT, 0))
        connect(PortRef(osc.id, PortDirection.OUTPUT, 0), PortRef(filter.id, PortDirection.INPUT, 0))
        connect(PortRef(env.id, PortDirection.OUTPUT, 0), PortRef(filter.id, PortDirection.INPUT, 1))
        connect(PortRef(filter.id, PortDirection.OUTPUT, 0), PortRef(out.id, PortDirection.INPUT, 0))
    }
}
