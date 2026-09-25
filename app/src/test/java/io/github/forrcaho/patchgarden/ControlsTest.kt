package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A subpatch's Controls panel, which was its Knobs panel until 2026-09-25: a knob promotes
 * through every level rather than one, a promoted knob can be given a jack on the box, and
 * inside a box a chip beside the breadcrumb opens the same panel.
 */
class ControlsTest {

    /** An oscillator into a filter, in a box, in a box: two levels between the knob and the top. */
    private class Nest {
        val patch = Patch()
        val lfo = patch.add(Types.Lfo, Offset(0f, 300f))!!
        val osc = patch.add(Types.Osc, Offset(200f, 0f))!!
        val filter = patch.add(Types.Filter, Offset(400f, 0f))!!
        val cutoff = 0
        val row get() = ParamRow(filter, cutoff)
        val inner: PatchModule
        val outer: PatchModule

        init {
            check(patch.connect(PortRef(osc.id, PortDirection.OUTPUT, 0), PortRef(filter.id, PortDirection.INPUT, 0)))
            check(patch.connect(PortRef(filter.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0)))
            inner = patch.makeSubpatch(setOf(osc.id, filter.id))!!
            outer = patch.makeSubpatch(setOf(inner.id))!!
        }

        /** The cutoff sent out to both boxes, as two taps on two panels would. */
        fun promoteAll() {
            check(patch.togglePromotion(filter, filter, cutoff))
            check(patch.togglePromotion(inner, filter, cutoff))
        }

        val lfoOut get() = PortRef(lfo.id, PortDirection.OUTPUT, 0)
        val cutoffJack get() = PortRef(filter.id, PortDirection.MOD, cutoff)
    }

    @Test
    fun `a knob promotes through every level, one box at a time`() {
        val n = Nest()
        val ref = ParamRef(n.filter.id, n.cutoff)

        // Its own panel sends it to the box it sits in.
        assertEquals(Patch.ChipState.OFF, n.patch.promoteChip(n.filter, n.filter, n.cutoff))
        assertEquals("not on the outer panel until it reaches the inner one",
            Patch.ChipState.NONE, n.patch.promoteChip(n.outer, n.filter, n.cutoff))
        assertTrue(n.patch.togglePromotion(n.filter, n.filter, n.cutoff))
        assertEquals(listOf(ref), n.inner.subpatchPorts!!.promoted.toList())

        // The inner box's Controls panel sends it on to the outer one.
        assertEquals(Patch.ChipState.OFF, n.patch.promoteChip(n.inner, n.filter, n.cutoff))
        assertTrue(n.patch.togglePromotion(n.inner, n.filter, n.cutoff))
        assertEquals(listOf(ref), n.outer.subpatchPorts!!.promoted.toList())
        assertEquals("and the outer panel shows the filter's own knob",
            listOf(n.row), n.patch.panelRows(n.outer))

        // At the top there is nowhere further out: the chip is there, faint, and does nothing.
        assertEquals(Patch.ChipState.ON, n.patch.promoteChip(n.inner, n.filter, n.cutoff))
        assertEquals(Patch.ChipState.DISABLED, n.patch.promoteChip(n.outer, n.filter, n.cutoff))
        assertFalse(n.patch.togglePromotion(n.outer, n.filter, n.cutoff))
    }

    @Test
    fun `taking a knob back takes it back from every box further out`() {
        val n = Nest()
        n.promoteAll()

        // From the inner box's panel, only the outer box loses it: the inner one still has it.
        assertTrue(n.patch.togglePromotion(n.inner, n.filter, n.cutoff))
        assertTrue(n.outer.subpatchPorts!!.promoted.isEmpty())
        assertEquals(1, n.inner.subpatchPorts!!.promoted.size)

        // From its own panel, every box loses it -- the outer one reached it through the inner.
        check(n.patch.togglePromotion(n.inner, n.filter, n.cutoff))
        assertTrue(n.patch.togglePromotion(n.filter, n.filter, n.cutoff))
        assertTrue(n.inner.subpatchPorts!!.promoted.isEmpty())
        assertTrue(n.outer.subpatchPorts!!.promoted.isEmpty())
    }

    /**
     * Forrest's call: the chip that sends a knob outward is on every panel that could ever
     * have one, faint where there is no box around it, since a chip that appears only inside a
     * subpatch teaches nothing where most patches are built.
     */
    @Test
    fun `the promote chip is on the top of the patch too, faint`() {
        val patch = Patch()
        val filter = patch.add(Types.Filter, Offset.Zero)!!
        filter.type.rowParams.forEach {
            assertEquals(Patch.ChipState.DISABLED, patch.promoteChip(filter, filter, it))
        }
        assertFalse(patch.togglePromotion(filter, filter, 0))

        // No chip at all on a row nothing could promote: a rail's, and a box's own.
        val out = patch.module(OUT_ID)!!
        assertEquals(Patch.ChipState.NONE, patch.promoteChip(out, out, 0))
        val poly = patch.add(Types.Poly, Offset(0f, 300f))!!
        poly.type.rowParams.forEach {
            assertEquals(Patch.ChipState.NONE, patch.promoteChip(poly, poly, it))
        }
    }

    @Test
    fun `a full box greys the chip rather than hiding it`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id, f.lfo.id, f.mix.id, f.env.id))!!
        val every = listOf(f.osc, f.filter, f.lfo, f.mix, f.env).flatMap { m -> m.type.rowParams.map { m to it } }
        val (taken, left) = every.partition { (m, i) -> f.patch.togglePromotion(m, m, i) }
        assertEquals(MAX_PROMOTED, subpatch.subpatchPorts!!.promoted.size)
        assertTrue(taken.all { (m, i) -> f.patch.promoteChip(m, m, i) == Patch.ChipState.ON })
        assertTrue(left.isNotEmpty())
        assertTrue(left.all { (m, i) -> f.patch.promoteChip(m, m, i) == Patch.ChipState.DISABLED })
    }

    /**
     * The chain a hand would build: the knob exposed, the inner box given a port reaching it,
     * the outer given one reaching that. The engine hears one cable from the modulator to the
     * knob, as it does through any chain of subpatch ports.
     */
    @Test
    fun `a jack from outside reaches the knob through every box`() {
        val n = Nest()
        n.promoteAll()
        assertEquals(Patch.ChipState.OFF, n.patch.jackChip(n.outer, n.row))
        assertNull(n.patch.boxJackFor(n.outer, n.row))

        assertTrue(n.patch.exposeThrough(n.outer, n.row))
        assertTrue("exposed, since it was not", n.filter.isExposed(n.cutoff))
        assertEquals(Patch.ChipState.ON, n.patch.jackChip(n.outer, n.row))
        assertEquals("and on the inner box's panel too, which it reaches through",
            Patch.ChipState.ON, n.patch.jackChip(n.inner, n.row))
        val k = n.patch.boxJackFor(n.outer, n.row)
        assertNotNull(k)
        assertEquals("named and typed as the jack it reaches, as a hand-made one is",
            n.patch.port(n.cutoffJack), n.outer.subpatchPorts!!.inputs[k!!])
        assertFalse("twice is once", n.patch.exposeThrough(n.outer, n.row))

        assertTrue(n.patch.connect(n.lfoOut, PortRef(n.outer.id, PortDirection.INPUT, k)))
        assertTrue(
            "the LFO arrives at the cutoff as one cable",
            Connection(n.lfoOut, n.cutoffJack) in n.patch.engineConnections(),
        )
        assertEquals("and brackets on the row say it is swept", n.filter.modRanges[n.cutoff], n.patch.rangeOf(n.filter, n.cutoff))
    }

    @Test
    fun `taking the jack away leaves the patch as the chip found it`() {
        val n = Nest()
        n.promoteAll()
        val before = n.patch.toJson()
        val engine = n.patch.engineConnections()

        check(n.patch.exposeThrough(n.outer, n.row))
        assertTrue(n.patch.unexposeThrough(n.outer, n.row))
        assertEquals("ports gone at both levels and the knob unexposed", before, n.patch.toJson())

        // With something patched in from outside, the cable goes with the jack.
        check(n.patch.exposeThrough(n.outer, n.row))
        check(n.patch.connect(n.lfoOut, PortRef(n.outer.id, PortDirection.INPUT, n.patch.boxJackFor(n.outer, n.row)!!)))
        assertTrue(n.patch.unexposeThrough(n.outer, n.row))
        assertEquals(engine, n.patch.engineConnections())
        assertEquals(before, n.patch.toJson())
        assertFalse("and once is all", n.patch.unexposeThrough(n.outer, n.row))
    }

    /**
     * Turned off on the inner box's panel, the outer box's jack goes too: it reached the
     * knob through the inner one, and left behind it would be a jack going nowhere.
     */
    @Test
    fun `taking the jack away inside takes the outer box's with it`() {
        val n = Nest()
        n.promoteAll()
        val before = n.patch.toJson()
        check(n.patch.exposeThrough(n.outer, n.row))
        check(n.patch.connect(n.lfoOut, PortRef(n.outer.id, PortDirection.INPUT, n.patch.boxJackFor(n.outer, n.row)!!)))

        assertEquals(Patch.ChipState.ON, n.patch.jackChip(n.inner, n.row))
        assertTrue(n.patch.unexposeThrough(n.inner, n.row))
        assertTrue("no jack left on the outer box", n.outer.subpatchPorts!!.inputs.isEmpty())
        assertFalse(n.patch.engineConnections().any { it.to == n.cutoffJack })
        assertEquals(before, n.patch.toJson())
    }

    /**
     * A box's input fans out inside like any output does, so a port that carries this knob
     * may carry something else too. Taking this knob's jack away takes only its cable.
     */
    @Test
    fun `a port that feeds something else as well keeps doing that`() {
        val n = Nest()
        n.promoteAll()
        check(n.patch.exposeThrough(n.outer, n.row))
        val innerPort = n.patch.boxJackFor(n.inner, n.row)!!
        val rail = n.patch.subpatchRail(n.inner.id, Types.SubpatchIn)!!
        val res = PortRef(n.filter.id, PortDirection.MOD, 1)
        check(n.patch.expose(n.filter, 1, ModRange(0f, 0.5f)))
        check(n.patch.connect(PortRef(rail.id, PortDirection.OUTPUT, innerPort), res))
        check(n.patch.connect(n.lfoOut, PortRef(n.outer.id, PortDirection.INPUT, n.patch.boxJackFor(n.outer, n.row)!!)))
        val innerPorts = n.inner.subpatchPorts!!.inputs.size

        assertTrue(n.patch.unexposeThrough(n.outer, n.row))
        assertEquals("the outer port carried only this, and went", 0, n.outer.subpatchPorts!!.inputs.size)
        assertEquals("the inner one feeds res as well, and stayed", innerPorts, n.inner.subpatchPorts!!.inputs.size)
        assertTrue(Connection(PortRef(rail.id, PortDirection.OUTPUT, innerPort), res) in n.patch.connections)
        assertFalse(n.patch.connections.any { it.to == n.cutoffJack })
        assertFalse(n.filter.isExposed(n.cutoff))
        assertTrue("res kept its own jack", n.filter.isExposed(1))
    }

    /**
     * A knob can be patched from one place. One already swept from inside the box gets a
     * faint chip rather than a live one that would quietly swap the modulator inside for
     * whatever is patched outside.
     */
    @Test
    fun `a knob already patched inside cannot take a jack from outside`() {
        val n = Nest()
        n.promoteAll()
        n.patch.enterScope(n.outer.id)
        n.patch.enterScope(n.inner.id)
        val inside = n.patch.add(Types.Lfo, Offset(0f, 300f))!!
        check(n.patch.expose(n.filter, n.cutoff, ModRange(200f, 4000f)))
        check(n.patch.connect(PortRef(inside.id, PortDirection.OUTPUT, 0), n.cutoffJack))
        val before = n.patch.toJson()

        assertEquals(Patch.ChipState.DISABLED, n.patch.jackChip(n.outer, n.row))
        assertFalse(n.patch.exposeThrough(n.outer, n.row))
        assertEquals("refused, not half made", before, n.patch.toJson())
    }

    /**
     * A knob has one way in, so a driven knob's chain ends at the port that drives it rather
     * than at a second jack -- and in a poly voice that port is usually taken by an Env
     * inside, which is exactly the case the faint chip is for.
     */
    @Test
    fun `a driven knob's jack from outside is the port that drives it`() {
        val patch = Patch()
        val lfo = patch.add(Types.Lfo, Offset(0f, 300f))!!
        val amp = patch.add(Types.Amp, Offset(200f, 0f))!!
        val env = patch.add(Types.Env, Offset(0f, 0f))!!
        val box = patch.makeSubpatch(setOf(amp.id, env.id))!!
        box.subpatchPorts!!.promoted += ParamRef(amp.id, 0)
        val row = ParamRow(amp, 0)
        val gain = PortRef(amp.id, PortDirection.INPUT, 1)

        assertEquals(Patch.ChipState.OFF, patch.jackChip(box, row))
        assertTrue(patch.exposeThrough(box, row))
        assertFalse("never exposed: its port is its jack", amp.isExposed(0))
        val k = patch.boxJackFor(box, row)!!
        assertEquals(SignalKind.MODULATION, box.subpatchPorts!!.inputs[k].kind)
        check(patch.connect(PortRef(lfo.id, PortDirection.OUTPUT, 0), PortRef(box.id, PortDirection.INPUT, k)))
        assertTrue(Connection(PortRef(lfo.id, PortDirection.OUTPUT, 0), gain) in patch.engineConnections())
        assertNotNull("bracketed, since its port is patched", patch.rangeOf(amp, 0))

        assertTrue(patch.unexposeThrough(box, row))
        assertTrue(box.subpatchPorts!!.inputs.isEmpty())
        assertNull(patch.rangeOf(amp, 0))

        // The poly voice's case: an Env inside already opens it.
        check(patch.connect(PortRef(env.id, PortDirection.OUTPUT, 0), gain))
        assertEquals(Patch.ChipState.DISABLED, patch.jackChip(box, row))
        assertFalse(patch.exposeThrough(box, row))
        assertTrue("and the Env is still what opens it",
            Connection(PortRef(env.id, PortDirection.OUTPUT, 0), gain) in patch.connections)
    }

    /** The reference device in landscape, as ModulationPanelTest measures it. */
    private val frame = Frame(
        canvas = Size(2404f, 1080f),
        density = 2.4375f,
        insetLeft = 160f,
        insetTop = 54f,
        insetRight = 0f,
        insetBottom = 58f,
    )

    @Test
    fun `inside a box, a chip beside the breadcrumb opens its Controls`() {
        val n = Nest()
        n.patch.enterScope(n.outer.id)
        n.patch.enterScope(n.inner.id)
        val chip = frame.breadcrumbChip(n.patch.scopePath().size)
        assertNull("no controls, no chip", n.patch.controlsChipBox())
        assertNull(n.patch.controlsChipAt(frame, chip.center))

        n.promoteAll()
        assertEquals(n.inner, n.patch.controlsChipBox())
        assertEquals(n.inner, n.patch.controlsChipAt(frame, chip.center))
        assertNull("the crumb before it is still the crumb",
            n.patch.controlsChipAt(frame, frame.breadcrumbChip(n.patch.scopePath().size - 1).center))
        assertFalse(chip.overlaps(frame.breadcrumbChip(n.patch.scopePath().size - 1)))
        assertFalse("clear of the transport", chip.overlaps(frame.transportChip()))
        assertTrue(chip.right <= frame.canvas.width)

        // Not drawn over an open panel, so not hit there either.
        n.osc.expanded = true
        assertNull(n.patch.controlsChipAt(frame, chip.center))
        n.osc.expanded = false

        // And at the top of the patch there is no box being looked inside.
        n.patch.scope = TOP
        assertNull(n.patch.controlsChipBox())
    }

    @Test
    fun `a box's menu offers its Controls only when it has some`() {
        val n = Nest()
        assertFalse(menuItems(n.patch, n.inner.id).any { it is MenuItem.Controls })
        n.promoteAll()
        assertTrue(MenuItem.Controls(n.inner.id) in menuItems(n.patch, n.inner.id))
        assertTrue(MenuItem.Controls(n.outer.id) in menuItems(n.patch, n.outer.id))
    }
}
