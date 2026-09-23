package io.github.forrcaho.patchgarden

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

    /**
     * A segment owns its whole column, fill included, and a node still wins near itself.
     *
     * The fill is the part that looks like the segment and the part a finger goes for. For
     * two builds only a band around the stroke responded, so a touch in the middle of the
     * fill did nothing -- traced from the phone as three attempts 200 to 300px below the
     * line, each reported as NOTHING. Drawing one thing and targeting another is the failure
     * this editor keeps finding new ways to make.
     */
    @Test
    fun `a segment owns its whole column, including the fill under it`() {
        val (_, env) = env()
        env.segments.clear()
        // Straight, so the midpoint sits halfway up and there is room under it to aim at.
        env.segments.add(EnvSegment(0.1f, 1f, 0f))
        env.segments.add(EnvSegment(0.1f, 0f, 0f))
        val geo = envGeometry(area, env, 1f)

        val times = env.segmentTimes
        val midX = (geo.x(times[0]) + geo.x(times[1])) / 2f
        val onLine = envCurveY(geo, env, 1, 0.5f)
        val floor = geo.y(0f)

        // Deep in the fill: three quarters of the way from the line down to the floor.
        val inFill = Offset(midX, onLine + 0.75f * (floor - onLine))
        assertTrue("the probe is well below the line", inFill.y > onLine + 40f)
        assertEquals("and the fill still grabs the segment", 1, envSegmentAt(geo, env, inFill))

        // Above the line too: the column is the target, not the ink.
        assertEquals(1, envSegmentAt(geo, env, Offset(midX, geo.area.top + 4f)))

        // Each column is its own segment, and past the envelope's end nothing is.
        assertEquals(0, envSegmentAt(geo, env, Offset(geo.x(times[0]) - 20f, onLine)))
        assertEquals(-1, envSegmentAt(geo, env, Offset(geo.x(times[1]) + 40f, onLine)))

        // But a *tap* still has to point at the line, so the fill cannot grow a node by
        // accident: bending is an adjustment, adding one changes what the envelope is.
        assertTrue("on the line", envOnCurve(geo, env, 1, Offset(midX, onLine), 1f))
        assertFalse("in the fill", envOnCurve(geo, env, 1, inFill, 1f))
    }

    /** A node still wins over the column it sits in, so the two never compete for a finger. */
    @Test
    fun `a node wins over its own column`() {
        val (_, env) = env()
        val geo = envGeometry(area, env, 1f)
        envNodes(geo, env).forEachIndexed { i, p ->
            assertEquals("node $i is grabbed at its own position", i, envNodeAt(geo, env, p, 1f))
        }
    }

    /** The rails are what keep the keypad off the shape. See ENV_RAIL. */
    @Test
    fun `the rails sit above and below the curve, never over it`() {
        val (_, env) = env()
        val levels = envLevelRail(area, 1f)
        val times = envTimeRail(area, 1f)
        val curve = envCurveArea(area, 1f)
        assertTrue(
            "the curve sits between them",
            curve.top >= levels.bottom && curve.bottom <= times.top,
        )
        val geo = envGeometry(area, env, 1f)
        val edges = envCellEdges(geo, env, 10f)
        assertEquals("and the curve area is not a rail", -1, envCellAt(times, edges, curve.center))
        assertEquals(
            "on either side",
            -1,
            envLevelCellAt(levels, envLevelCells(geo, env, levels, 10f), curve.center),
        )
    }

    /**
     * A level belongs to a node, so its chip sits over the node -- not across the segment,
     * which is what the times below do and what made the old `hold` cell read as "this
     * segment is held" when what holds is the one value at its end.
     */
    @Test
    fun `a level chip sits centered over its own node when there is room`() {
        val (_, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(0.1f, 1f, 0f))
        env.segments.add(EnvSegment(0.1f, 0.5f, 0f))
        env.segments.add(EnvSegment(0.2f, 0f, 0f))
        val geo = envGeometry(area, env, 1f)
        val rail = envLevelRail(area, 1f)
        val cells = envLevelCells(geo, env, rail, 40f)

        assertEquals(env.segments.size, cells.size)
        envNodes(geo, env).forEachIndexed { i, node ->
            assertEquals("chip $i is centered on node $i", node.x, cells[i].center.x, 0.5f)
            assertEquals("and is a whole floor wide", 40f, cells[i].width, 0.01f)
        }
    }

    /**
     * Crowding is ordinary -- a 5ms attack sits almost on the rail's end, and two nodes a few
     * milliseconds apart want the same spot -- and each chip moves as little as it can.
     *
     * The pair is the case that tells a nearest fit from a merely valid one: shoving the
     * second chip a whole width to the right also keeps them apart, and leaves it a whole
     * width from its node. Shared out, the two sit evenly either side of the pair.
     */
    @Test
    fun `crowded level chips move apart as little as they can`() {
        val (_, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(0.005f, 1f, 0f))  // on top of the rail's left end
        env.segments.add(EnvSegment(0.1f, 0.5f, 0f))
        env.segments.add(EnvSegment(0.001f, 0.4f, 0f)) // a hair after the one before it
        env.segments.add(EnvSegment(0.1f, 0f, 0f))
        val geo = envGeometry(area, env, 1f)
        val rail = envLevelRail(area, 1f)
        val w = 60f
        val cells = envLevelCells(geo, env, rail, w)
        val nodes = envNodes(geo, env)

        assertEquals("the first chip cannot hang off the rail", rail.left, cells[0].left, 0.01f)
        cells.zipWithNext().forEach { (a, b) ->
            assertTrue("chips must not overlap: ${a.right} then ${b.left}", b.left >= a.right - 0.01f)
        }
        // The pair, straddling the midpoint of its two nodes.
        val pairCenter = (nodes[1].x + nodes[2].x) / 2f
        assertEquals(pairCenter - w / 2f, cells[1].center.x, 0.5f)
        assertEquals(pairCenter + w / 2f, cells[2].center.x, 0.5f)
        assertEquals("and the uncrowded one stays put", nodes[3].x, cells[3].center.x, 0.5f)
    }

    /** At the cap, with every segment tiny, the chips still fit the rail and keep their order. */
    @Test
    fun `level chips fit the rail however many there are`() {
        val (_, env) = env()
        env.segments.clear()
        repeat(MAX_SEGMENTS) { env.segments.add(EnvSegment(0.002f, 0.5f, 0f)) }
        val geo = envGeometry(area, env, 1f)
        val rail = envLevelRail(area, 1f)
        val cells = envLevelCells(geo, env, rail, 200f)  // floors that cannot possibly all fit
        assertEquals(MAX_SEGMENTS, cells.size)
        cells.zipWithNext().forEach { (a, b) ->
            assertTrue("in order and apart: ${a.right} then ${b.left}", b.left >= a.right - 0.01f)
        }
        assertTrue(cells.first().left >= rail.left - 0.01f)
        assertTrue(cells.last().right <= rail.right + 0.01f)
    }

    /**
     * The rail holds nothing but levels, so a finger near one is asking for it -- and the empty
     * stretch between two far-apart chips is the rail's own, so it answers nothing.
     */
    @Test
    fun `a touch in the level rail finds the level it is near, and only that`() {
        val (_, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(0.05f, 1f, 0f))
        env.segments.add(EnvSegment(0.4f, 0f, 0f))
        val geo = envGeometry(area, env, 1f)
        val rail = envLevelRail(area, 1f)
        val cells = envLevelCells(geo, env, rail, 40f)
        val y = rail.center.y

        assertEquals(0, envLevelCellAt(rail, cells, Offset(cells[0].center.x, y)))
        assertEquals(1, envLevelCellAt(rail, cells, Offset(cells[1].center.x, y)))
        assertEquals(
            "just past a chip's edge still means that chip",
            1,
            envLevelCellAt(rail, cells, Offset(cells[1].left - 10f, y)),
        )
        val between = (cells[0].right + cells[1].left) / 2f
        assertTrue("the probe is far from both", cells[1].left - cells[0].right > 2f * 40f)
        assertEquals("the empty stretch is nothing", -1, envLevelCellAt(rail, cells, Offset(between, y)))
    }

    /** 0 to 1 and unitless, as `res` and `chance` read. */
    @Test
    fun `a node's level is typed from 0 to 1`() {
        assertEquals(0f, SEGMENT_LEVEL.min)
        assertEquals(1f, SEGMENT_LEVEL.max)
        assertEquals("", SEGMENT_LEVEL.unit)
        assertEquals("0.6", SEGMENT_LEVEL.format(0.6f))
        assertEquals("typed past the top is the top", 1f, keypadValue("1.5", SEGMENT_LEVEL))
    }

    /**
     * A long press on a node opens its menu, as a long press on a module does on the canvas:
     * the release and the removal, the two things done *to* a node rather than with it.
     *
     * The release was never put on a tap, which was free, for the reason removal left it: a
     * touch that never clears the slop is a tap, and the node most often touched and left
     * where it is is the release node itself -- with a note held, being tuned by ear.
     */
    @Test
    fun `a node's menu offers its release and its removal`() {
        val (patch, env) = env()  // the default: its second node is the one that waits
        assertEquals(
            listOf(MenuItem.ReleaseAt(env.id, 0), MenuItem.RemoveNode(env.id, 0)),
            menuItems(patch, env.id, node = 0),
        )
        assertEquals(
            "the node that waits offers to stop waiting",
            listOf(MenuItem.NoRelease(env.id, 1), MenuItem.RemoveNode(env.id, 1)),
            menuItems(patch, env.id, node = 1),
        )
        while (env.segments.size > 1) env.removeSegment(env.segments.size - 1)
        assertEquals(
            "and the last node cannot be removed, so it is not offered",
            listOf(if (env.segments[0].sustain) MenuItem.NoRelease(env.id, 0) else MenuItem.ReleaseAt(env.id, 0)),
            menuItems(patch, env.id, node = 0),
        )
        assertEquals(
            "a node that is not there offers nothing",
            emptyList<MenuItem>(),
            menuItems(patch, env.id, node = 5),
        )
    }

    /**
     * A cell is as wide as its segment is long, so the rails read against the shape above
     * them rather than beside it.
     *
     * They were divided evenly, which put the `hold` chip nowhere near its own dashed line
     * and the last cell over empty canvas past the end of the curve -- the editor's habit of
     * drawing one thing and meaning another, in its third and last known form.
     */
    @Test
    fun `a rail cell is as wide as its segment is long`() {
        val (_, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(0.1f, 1f, 0f))
        env.segments.add(EnvSegment(0.3f, 0.5f, 0f))  // three times as long
        val geo = envGeometry(area, env, 1f)
        val edges = envCellEdges(geo, env, 1f)  // a floor low enough to bind on neither

        val first = edges[1] - edges[0]
        val second = edges[2] - edges[1]
        assertEquals("three times as long, three times as wide", 3f, second / first, 0.01f)

        // And they land on the segments' own columns, which is the whole point.
        val times = env.segmentTimes
        assertEquals(geo.x(0f), edges[0], 0.5f)
        assertEquals(geo.x(times[0]), edges[1], 0.5f)
        assertEquals(geo.x(times[1]), edges[2], 0.5f)
    }

    /**
     * However short the segment, its cell still has to be tappable and still has to say
     * "5ms" -- which is why the even division existed in the first place.
     */
    @Test
    fun `a short segment's cell keeps a floor wide enough for its text`() {
        val (_, env) = env()
        env.segments.clear()
        env.segments.add(EnvSegment(0.005f, 1f, 0f))  // half a percent of the axis
        env.segments.add(EnvSegment(1f, 0f, 0f))
        val geo = envGeometry(area, env, 1f)
        val floor = 60f
        val edges = envCellEdges(geo, env, floor)

        assertTrue(
            "the 5ms cell is ${edges[1] - edges[0]}px, floor $floor",
            edges[1] - edges[0] >= floor - 0.5f,
        )
        assertTrue("and the long one keeps the rest", edges[2] - edges[1] > floor)
        // Contiguous, always: a gap between cells is a touch that hits nothing.
        assertEquals(envCellEdges(geo, env, floor).size, env.segments.size + 1)
    }

    /** Even at the cap, with every segment tiny, no cell overlaps another. */
    @Test
    fun `the cells stay in order and never overlap, however they are squeezed`() {
        val (_, env) = env()
        env.segments.clear()
        repeat(MAX_SEGMENTS) { env.segments.add(EnvSegment(0.002f, 0.5f, 0f)) }
        val geo = envGeometry(area, env, 1f)
        val edges = envCellEdges(geo, env, 200f)  // floors that cannot possibly all fit
        assertEquals(MAX_SEGMENTS + 1, edges.size)
        edges.zipWithNext().forEach { (a, b) ->
            assertTrue("edges must ascend, got $a then $b", b > a)
        }
        assertTrue("and stay inside the panel", edges.last() <= geo.area.right + 0.5f)
    }

    /** Sized to hold a label, so it reads the font setting; see CLAUDE.md. */
    @Test
    fun `the cell floor grows with the text size`() {
        assertTrue(envCellMin(1f, 1.5f) > envCellMin(1f, 1f))
        assertEquals(ENV_CELL_MIN * 1.5f * 2f, envCellMin(2f, 1.5f), 0.001f)
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
     * A drag bends the line the way the finger went, on a falling segment as well as a
     * rising one.
     *
     * Asserted against the drawn *midpoint* rather than against the curvature number, which
     * is the whole point: curvature's sign is a fact about shape -- leaves fast, arrives slow
     * -- and that shape puts a rising segment's middle high and a falling segment's middle
     * low. Mapping the finger onto the number therefore moved the line backwards on half of
     * all segments. It shipped because the device check confirmed the number moved and never
     * looked at where the line went.
     */
    @Test
    fun `a drag bends the line the way the finger went, uphill or down`() {
        val d = frame.density
        listOf(
            "rising" to listOf(EnvSegment(0.2f, 1f, 0f)),
            "falling" to listOf(EnvSegment(0.2f, 1f, 0f), EnvSegment(0.2f, 0f, 0f)),
        ).forEach { (name, shape) ->
            val (_, env) = env()
            env.segments.clear()
            env.segments.addAll(shape)
            val index = env.segments.size - 1
            val geo = envGeometry(area, env, d)
            val before = envCurveY(geo, env, index, 0.5f)

            // Downwards, in screen pixels, which is what y increasing means.
            val rises = env.segments[index].level >= envFrom(env, index)
            env.setSegment(
                index,
                env.segments[index].copy(curve = envCurveAfterDrag(0f, 120f, rises, d)),
            )
            val after = envCurveY(envGeometry(area, env, d), env, index, 0.5f)
            assertTrue(
                "$name: dragging down must lower the line, was $before -> $after",
                after > before,
            )
        }
    }

    /**
     * Bending a segment takes a deliberate drag, not a flick.
     *
     * ENV_CURVE_TRAVEL was 90dp, which put the whole range from -1 to +1 inside 439px on the
     * reference device against a curve area 631px tall -- so any real drag slammed the curve
     * to a limit and left it there, and a control pinned at its maximum reads exactly like a
     * control that is broken. The report was "the curvature won't move", from a patch whose
     * every segment sat at 1.0.
     *
     * Pinned as a *relationship* rather than as a number, because the number is a feel and
     * will be tuned: what must stay true is that one drag down the editor cannot cross the
     * whole range. The drag is relative to where the curve already was, so a second drag
     * carries on and nothing is unreachable.
     */
    @Test
    fun `bending a segment takes more than one drag down the editor`() {
        val grid = panelGrid(panelRect(frame), frame.density, Types.Env)
        val curve = envCurveArea(grid, frame.density)
        val wholeRange = 2f * ENV_CURVE_TRAVEL * frame.density
        assertTrue(
            "the range is $wholeRange px against an editor ${curve.height} px tall",
            wholeRange > curve.height,
        )
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
