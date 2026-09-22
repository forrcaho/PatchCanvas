package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope's model and its editor geometry.
 *
 * The shape's arithmetic is asserted on both sides of the boundary: [envShape] here and the
 * same expression in EnvNode, because the picture and the sound being different expressions
 * of the same curve is the one failure that would make the whole redesign worse than the
 * four knobs it replaced.
 */
class EnvelopeTest {

    private val area = Rect(0f, 0f, 800f, 400f)

    /** The reference device, as ModulationPanelTest uses it. */
    private val frame = Frame(
        canvas = Size(2404f, 1080f),
        density = 2.4375f,
        insetLeft = 160f,
        insetTop = 54f,
        insetRight = 0f,
        insetBottom = 58f,
    )

    private fun env(): Pair<Patch, PatchModule> {
        val patch = Patch()
        return patch to patch.add(Types.Env, Offset.Zero)!!
    }

    @Test
    fun `a new envelope is the A D S R that every Env has had`() {
        val (_, env) = env()
        assertEquals(DEFAULT_ENVELOPE, env.segments.toList())
        assertEquals("exactly one of them waits", 1, env.segments.count { it.sustain })
    }

    @Test
    fun `Env has no knobs, and its panel is the shape`() {
        assertTrue("the four stages are gone", Types.Env.params.isEmpty())
        assertEquals(GridKind.ENVELOPE, Types.Env.grid)
        assertTrue("and it still opens a panel", Types.Env.hasPanel)
    }

    /**
     * The shape is 0 at 0 and 1 at 1 for every curvature, which is what lets a segment's
     * curve be a control about *how* it travels and not about where it ends up.
     */
    @Test
    fun `a curve keeps both of its ends wherever it is bent`() {
        listOf(-1f, -0.5f, 0f, 0.3f, 1f).forEach { curve ->
            assertEquals("starts at 0 for $curve", 0f, envShape(0f, curve), 1e-5f)
            assertEquals("ends at 1 for $curve", 1f, envShape(1f, curve), 1e-5f)
        }
        assertEquals("and 0 is a straight line", 0.5f, envShape(0.5f, 0f), 1e-5f)
        assertTrue("positive leaves fast", envShape(0.5f, 0.8f) > 0.6f)
        assertTrue("negative leaves slow", envShape(0.5f, -0.8f) < 0.4f)
    }

    @Test
    fun `at most one segment sustains, however many times it is asked for`() {
        val (_, env) = env()
        env.setSustain(0)
        assertEquals(listOf(true, false, false), env.segments.map { it.sustain })
        env.setSustain(2)
        assertEquals("setting another moves it", listOf(false, false, true), env.segments.map { it.sustain })
        env.setSustain(2)
        assertEquals("and asking again clears it", 0, env.segments.count { it.sustain })
    }

    /**
     * Splitting adds somewhere to bend without changing what the envelope currently sounds
     * like: the new node lands on the line it was tapped on.
     */
    @Test
    fun `splitting a segment leaves the shape where it was`() {
        val (_, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(1f, 1f, 0f))
        assertTrue(env.splitSegment(0, 0.25f))

        assertEquals(2, env.segments.size)
        assertEquals("the two halves add up to the whole", 1f, env.envelopeSpan, 1e-4f)
        assertEquals("and the new node sits on the old line", 0.25f, env.segments[0].level, 1e-4f)
        assertEquals(1f, env.segments[1].level, 1e-4f)
    }

    @Test
    fun `an envelope holds so many segments and no more`() {
        val (_, env) = env()
        while (env.segments.size < MAX_SEGMENTS) assertTrue(env.splitSegment(0, 0.5f))
        assertEquals(MAX_SEGMENTS, env.segments.size)
        assertFalse("and then it is full", env.splitSegment(0, 0.5f))
    }

    @Test
    fun `the last segment cannot be taken away`() {
        val (_, env) = env()
        while (env.segments.size > 1) assertTrue(env.removeSegment(env.segments.size - 1))
        assertFalse(env.removeSegment(0))
        assertEquals("an envelope is always at least one segment", 1, env.segments.size)
    }

    @Test
    fun `segments round-trip through the file`() {
        val (patch, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(0.3f, 0.7f, -0.4f))
        env.segments.add(EnvSegment(1.25f, 0.2f, 0.9f, sustain = true))
        val json = patch.toJson()
        assertTrue(json.contains("\"version\":14"))
        // Legible in the file, which is why velocity and these go through Float.toString:
        // widening 0.3f to a double writes 0.30000001192092896.
        assertTrue("and readable with cat: $json", json.contains("[0.3,0.7,-0.4,0]"))

        val back = patchFromJson(json)!!.modules.first { it.type == Types.Env }
        assertEquals(env.segments.toList(), back.segments.toList())
    }

    /** A hand-edited file cannot give the engine two places to park. */
    @Test
    fun `a file naming two sustains keeps only the first`() {
        val (patch, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(0.1f, 1f, 0f, sustain = true))
        env.segments.add(EnvSegment(0.1f, 0.5f, 0f))
        val wild = patch.toJson().replace("[0.1,0.5,0.0,0]", "[0.1,0.5,0.0,1]")
        val back = patchFromJson(wild)!!.modules.first { it.type == Types.Env }
        assertEquals(1, back.segments.count { it.sustain })
        assertTrue("and it is the first", back.segments[0].sustain)
    }

    @Test
    fun `a file with no readable segment keeps the default rather than going silent`() {
        val (patch, _) = env()
        val wild = patch.toJson().replace(Regex("\"segments\":\\[.*?]]"), "\"segments\":[]")
        val back = patchFromJson(wild)!!.modules.first { it.type == Types.Env }
        assertEquals(DEFAULT_ENVELOPE, back.segments.toList())
    }

    // ------------------------------------------------------------------ the editor

    /**
     * The axis is a round number at or above the envelope's own length, never the length.
     * If it were the length, the last node would sit on the edge and could not be dragged
     * any longer, and every other node would slide whenever any segment changed.
     */
    @Test
    fun `the time axis is a round number above the envelope's own length`() {
        assertTrue(envelopeAxis(0.375f) >= 0.375f)
        assertEquals(0.5f, envelopeAxis(0.375f))
        assertEquals(1f, envelopeAxis(0.6f))
        assertEquals(0.5f, envelopeAxis(0.5f))
    }

    @Test
    fun `a node is where the shape puts it, and is grabbed there`() {
        val (_, env) = env()
        val geo = envGeometry(area, env, 1f)
        val nodes = envNodes(geo, env)
        assertEquals(env.segments.size, nodes.size)

        nodes.forEachIndexed { i, p ->
            assertEquals("node $i is grabbed at its own position", i, envNodeAt(geo, env, p, 1f))
        }
        assertEquals(
            "and nowhere near one grabs nothing",
            -1,
            envNodeAt(geo, env, Offset(area.right - 1f, area.top + 1f), 1f),
        )
    }

    /** A node wins over the line it sits on, so the two never compete for one finger. */
    @Test
    fun `a segment is grabbed along its curve, away from the nodes`() {
        val (_, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(1f, 1f, 0.7f))
        val geo = envGeometry(area, env, 1f)

        val midX = geo.x(0.5f)
        val onCurve = Offset(midX, envCurveY(geo, env, 0, 0.5f))
        assertEquals("on the bent line", 0, envSegmentAt(geo, env, onCurve, 1f))
        // The straight chord at this x is far below the bend, which is the point of testing
        // against the curve rather than against a line between the two ends.
        val chord = geo.y(0.5f)
        assertTrue("and the curve is well above its own chord", onCurve.y < chord - 40f)
    }

    /** The rails are what keep the sustain and the keypad off the shape. See ENV_RAIL. */
    @Test
    fun `the rails divide evenly by segment and never overlap the curve`() {
        val (_, env) = env()
        val sustain = envSustainRail(area, 1f)
        val times = envTimeRail(area, 1f)
        val curve = envCurveArea(area, 1f)
        assertTrue("the curve sits between them", curve.top >= sustain.bottom && curve.bottom <= times.top)

        val count = env.segments.size
        repeat(count) { i ->
            val cell = envCell(sustain, count, i)
            assertEquals("cell $i is hit at its own centre", i, envCellAt(sustain, count, cell.center))
        }
        assertEquals("and the curve area is not a rail", -1, envCellAt(sustain, count, curve.center))
    }

    /** Typed in milliseconds, because that is the number anyone says out loud. */
    @Test
    fun `a segment's time is typed in milliseconds`() {
        assertEquals("ms", SEGMENT_TIME.unit)
        assertEquals(SEGMENT_MAX_TIME * 1000f, SEGMENT_TIME.max)
        assertEquals("5ms", envTime(0.005f))
        assertEquals("1.5s", envTime(1.5f))
    }

    /**
     * Every path that copies a module carries the shape, not just undo.
     *
     * All three were copying dots and none knew about a second kind of grid, so an undone
     * envelope, a duplicated one and one loaded from the library each came back as the
     * A/D/S/R default -- silently, and only for the module just edited. They share
     * [PatchModule.copyGridFrom] now, and this is what says so.
     */
    @Test
    fun `duplicating an envelope carries its shape`() {
        val (patch, env) = env()
        env.setSegment(0, EnvSegment(0.44f, 0.8f, -0.5f))
        val copy = patch.duplicate(env)!!
        assertEquals(env.segments.toList(), copy.segments.toList())
    }

    @Test
    fun `a subpatch adopted from the library carries its envelope`() {
        val (patch, env) = env()
        env.setSegment(0, EnvSegment(0.44f, 0.8f, -0.5f))
        val shape = env.segments.toList()
        val subpatch = patch.makeSubpatch(setOf(env.id))!!

        val into = Patch()
        val adopted = into.adoptSubpatch(patch, subpatch, Offset.Zero, TOP)
        assertNotNull(adopted)
        val inside = into.modules.first { it.type == Types.Env }
        assertEquals(shape, inside.segments.toList())
    }

    /**
     * The way out of a panel is a tap outside it, and the envelope's editor must not be able
     * to eat one.
     *
     * Its gesture loop ends in an unconditional return, so for one build it swallowed every
     * touch on the screen and an open Env could not be closed at all -- no breadcrumb, since
     * that is hidden while any panel is open, and no border tap either. The panel was a room
     * with no door. The fix is that the loop only claims touches inside its own grid, and this
     * is the geometry that makes the fix true: there is somewhere outside the panel to tap,
     * and none of it is inside the grid.
     */
    @Test
    fun `the envelope's editor cannot swallow the tap that closes the panel`() {
        val panel = panelRect(frame)
        val grid = panelGrid(panel, frame.density, Types.Env)
        assertTrue("the grid is inside the panel", panel.contains(grid.topLeft))

        // The border the close is measured against: points on the canvas, outside the panel.
        val outside = listOf(
            Offset(frame.insetLeft + 1f, frame.canvas.height / 2f),
            Offset(frame.canvas.width / 2f, frame.insetTop + 1f),
            Offset(frame.canvas.width / 2f, frame.canvas.height - frame.insetBottom - 1f),
        )
        outside.forEach {
            assertFalse("$it must be outside the panel, or there is no way out", panel.contains(it))
            assertFalse("$it must not be claimed by the envelope's grid", grid.contains(it))
        }
    }

    /** Anything that changes what toJson emits changes what is undoable; see ReplaceWithTest. */
    @Test
    fun `an envelope survives replaceWith byte for byte`() {
        val (patch, env) = env()
        env.setSegment(1, env.segments[1].copy(level = 0.42f, curve = -0.3f))
        val json = patch.toJson()
        val restored = patchFromJson(json)
        assertNotNull(restored)
        patch.replaceWith(restored!!)
        assertEquals(json, patch.toJson())
    }
}
