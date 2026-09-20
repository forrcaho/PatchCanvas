package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subpatches are how a patch is organized, never how it sounds. The one property that matters
 * most is therefore checkable on the desk: whatever is subpatched, unpacked, nested or
 * rewired, [Patch.engineConnections] -- the cables the engine actually gets -- says exactly
 * what the same patch said loose.
 */
class SubpatchTest {

    @Test
    fun `subpatching leaves the cables the engine has exactly as they were`() {
        val f = SubpatchFixture()
        val before = f.patch.engineConnections()
        assertEquals("the fixture has nine real cables", 9, before.size)

        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))
        assertNotNull(subpatch)
        assertEquals(before, f.patch.engineConnections())
        assertTrue("nothing structural reaches the engine", f.patch.engineModules.none { it.type.structural })
    }

    @Test
    fun `a subpatch's ports come from the cables that crossed its edge`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!

        // In: the two note sources stay separate ports, since each is its own source, and the
        // modulator onto cutoff is a third. Out: the filter's one output, however many
        // places it went.
        val inputs = subpatch.ports(PortDirection.INPUT)
        val outputs = subpatch.ports(PortDirection.OUTPUT)
        assertEquals(listOf(SignalKind.NOTE, SignalKind.NOTE, SignalKind.MODULATION), inputs.map { it.kind })
        assertEquals(listOf(SignalKind.AUDIO), outputs.map { it.kind })

        // The rails inside turn the box's ports around.
        val railIn = f.patch.subpatchRail(subpatch.id, Types.SubpatchIn)!!
        val railOut = f.patch.subpatchRail(subpatch.id, Types.SubpatchOut)!!
        // toList() on both sides: a subpatch's ports are a snapshot list, which compares by
        // identity -- these two would pass only because they happen to be one list.
        assertEquals(inputs.toList(), railIn.ports(PortDirection.OUTPUT).toList())
        assertEquals(outputs.toList(), railOut.ports(PortDirection.INPUT).toList())
        assertTrue(railIn.ports(PortDirection.INPUT).isEmpty())
        assertTrue(railOut.ports(PortDirection.OUTPUT).isEmpty())

        assertEquals(subpatch.id, f.osc.parent)
        assertEquals(subpatch.id, f.filter.parent)
        assertEquals(TOP, subpatch.parent)
    }

    @Test
    fun `unpacking gives back the same cables and the same scope`() {
        val f = SubpatchFixture()
        val cables = f.patch.connections.toSet()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        f.patch.unpack(subpatch)

        assertEquals(cables, f.patch.connections.toSet())
        assertTrue(f.patch.modules.none { it.type.structural })
        assertEquals(TOP, f.osc.parent)
        assertEquals(TOP, f.filter.parent)
    }

    @Test
    fun `subpatches nest, and still sound the same`() {
        val f = SubpatchFixture()
        val cables = f.patch.connections.toSet()
        val flat = f.patch.engineConnections()

        val inner = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.makeSubpatch(setOf(inner.id, f.lfo.id, f.mix.id))!!
        assertEquals(outer.id, inner.parent)
        assertEquals(flat, f.patch.engineConnections())

        f.patch.unpack(outer)
        f.patch.unpack(inner)
        assertEquals(cables, f.patch.connections.toSet())
    }

    @Test
    fun `patching a new source into a subpatch's input replaces it for everything inside`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.filter.id))!!
        val audioIn = subpatch.ports(PortDirection.INPUT).indexOfFirst { it.kind == SignalKind.AUDIO }
        val other = f.patch.add(Types.Osc, Offset(0f, 400f))!!

        assertTrue(
            f.patch.connect(
                PortRef(other.id, PortDirection.OUTPUT, 0),
                PortRef(subpatch.id, PortDirection.INPUT, audioIn),
            ),
        )
        val flat = f.patch.engineConnections()
        val intoFilter = flat.filter { it.to == PortRef(f.filter.id, PortDirection.INPUT, 0) }
        assertEquals(listOf(PortRef(other.id, PortDirection.OUTPUT, 0)), intoFilter.map { it.from })
    }

    @Test
    fun `an unplugged subpatch port stays, and can be plugged back into`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.filter.id))!!
        val count = subpatch.ports(PortDirection.OUTPUT).size
        f.patch.disconnect(PortRef(subpatch.id, PortDirection.OUTPUT, 0))
        assertEquals("the port outlives its cables", count, subpatch.ports(PortDirection.OUTPUT).size)
        assertTrue(
            f.patch.connect(PortRef(subpatch.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0)),
        )
        assertTrue(Connection(PortRef(f.filter.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0)) in f.patch.engineConnections())
    }

    @Test
    fun `nothing is subpatched that cannot be`() {
        val f = SubpatchFixture()
        assertNull("nothing chosen", f.patch.makeSubpatch(emptySet()))
        assertNull("a rail", f.patch.makeSubpatch(setOf(f.osc.id, OUT_ID)))
        assertNull("an id that is not there", f.patch.makeSubpatch(setOf(f.osc.id, 9999L)))
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id))!!
        assertNull("modules from two scopes", f.patch.makeSubpatch(setOf(f.filter.id, f.osc.id)))
        assertEquals(subpatch.id, f.osc.parent)
    }

    @Test
    fun `deleting a subpatch takes everything inside it`() {
        val f = SubpatchFixture()
        val inner = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.makeSubpatch(setOf(inner.id, f.lfo.id))!!
        f.patch.remove(outer)

        listOf(outer.id, inner.id, f.osc.id, f.filter.id, f.lfo.id).forEach {
            assertNull("module $it is gone", f.patch.module(it))
        }
        assertTrue(f.patch.modules.none { it.type.structural })
        val left = f.patch.modules.map { it.id }.toSet()
        assertTrue("no cable names a module that went", f.patch.connections.all { it.from.moduleId in left && it.to.moduleId in left })
    }

    @Test
    fun `a duplicated subpatch copies what is inside and nothing outside`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val before = f.patch.engineConnections().size
        val copy = f.patch.duplicate(subpatch)!!

        assertEquals(Types.Subpatch, copy.type)
        assertEquals(subpatch.ports(PortDirection.INPUT).toList(), copy.ports(PortDirection.INPUT).toList())
        val insideCopy = f.patch.descendants(copy.id).mapNotNull { f.patch.module(it) }
        assertEquals(listOf("Filter", "Osc"), insideCopy.filter { !it.type.structural }.map { it.type.name }.sorted())
        // Its inside is wired (osc into filter, and the filter's output onto its right rail),
        // but it is patched to nothing outside, so the engine gains exactly one real cable.
        assertEquals(before + 1, f.patch.engineConnections().size)
        // A separate set of ports: changing one subpatch's does not change the other's.
        assertFalse(subpatch.subpatchPorts === copy.subpatchPorts)
    }

    @Test
    fun `a subpatch's contents are shown only when it is entered`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        assertTrue(subpatch in f.patch.shownFree)
        assertFalse(f.osc in f.patch.shownFree)
        assertEquals(setOf(OUT_ID, IN_ID), f.patch.shownRails.map { it.id }.toSet())

        f.patch.scope = subpatch.id
        assertEquals(setOf(f.osc.id, f.filter.id), f.patch.shownFree.map { it.id }.toSet())
        assertEquals(
            setOf(Types.SubpatchIn, Types.SubpatchOut),
            f.patch.shownRails.map { it.type }.toSet(),
        )
        val added = f.patch.add(Types.Lfo, Offset.Zero)!!
        assertEquals("a module added inside goes inside", subpatch.id, added.parent)

        f.patch.remove(subpatch)
        assertEquals("a scope that has gone falls back to the top", TOP, f.patch.scopeOrTop)
    }

    @Test
    fun `subpatches survive saving and loading, byte for byte`() {
        val f = SubpatchFixture()
        val inner = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        f.patch.makeSubpatch(setOf(inner.id, f.lfo.id))
        val json = f.patch.toJson()

        val restored = patchFromJson(json)
        assertNotNull(restored)
        assertEquals(json, restored!!.toJson())
        assertEquals(f.patch.engineConnections(), restored.engineConnections())
        // Where each module is, compared directly. A file that lost every parent reloads to
        // a flat patch that writes the same JSON again and sounds the same -- so neither
        // check above can see it, and this one is what can.
        assertEquals(
            f.patch.modules.associate { it.id to it.parent },
            restored.modules.associate { it.id to it.parent },
        )
    }

    /**
     * The additive run from 5 to 8 ended at 9. A format 8 file calls this module a "Group",
     * which is not a type this build has, so it would be skipped as retired and everything
     * inside it would go with it -- silently, and then autosaved that way.
     */
    @Test
    fun `a format 8 file is refused rather than read as a patch with no subpatches`() {
        val f = SubpatchFixture()
        val json = org.json.JSONObject(f.patch.toJson()).put("version", 8).toString()
        assertNull(patchFromJson(json))
    }

    @Test
    fun `a parent that is not a subpatch puts the module at the top`() {
        val f = SubpatchFixture()
        val root = org.json.JSONObject(f.patch.toJson())
        root.getJSONArray("modules").getJSONObject(0).put("parent", f.osc.id) // not a subpatch
        val restored = patchFromJson(root.toString())!!
        assertTrue(restored.modules.filter { !it.isPinned }.all { it.parent == TOP })
    }

    @Test
    fun `the breadcrumb runs from the patch down to where you are`() {
        val f = SubpatchFixture()
        val inner = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.makeSubpatch(setOf(inner.id, f.lfo.id))!!
        assertEquals(listOf(TOP), f.patch.scopePath())
        f.patch.enterScope(inner.id)
        assertEquals(listOf(TOP, outer.id, inner.id), f.patch.scopePath())

        f.osc.expanded = true
        f.patch.enterScope(outer.id)
        assertFalse("a panel belongs to where you were", f.osc.expanded)
        assertEquals(listOf(TOP, outer.id), f.patch.scopePath())
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
    fun `the breadcrumb and the subpatch buttons clear the controls already on screen`() {
        (0 until 3).forEach { level ->
            val chip = frame.breadcrumbChip(level)
            assertFalse("crumb $level over the scale chip", chip.overlaps(frame.scaleChip()))
            assertFalse("crumb $level over the transport chip", chip.overlaps(frame.transportChip()))
            assertTrue("crumb $level on screen", chip.right <= frame.canvas.width)
        }
        val done = frame.selectionButton(done = true)
        val cancel = frame.selectionButton(done = false)
        assertFalse("subpatch and cancel apart", done.overlaps(cancel))
        for (button in listOf(done, cancel)) {
            assertFalse(button.overlaps(frame.historyRect(redo = false)))
            assertFalse(button.overlaps(frame.historyRect(redo = true)))
            assertTrue("above the gesture bar", button.bottom <= frame.canvas.height - frame.insetBottom)
        }
    }

    @Test
    fun `a port can be added after subpatching, by patching to a rail`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.lfo.id, f.env.id))!!
        val outputsBefore = subpatch.ports(PortDirection.OUTPUT).map { it }

        // The envelope's output already leaves the subpatch (to the mix's level); give the LFO
        // an output of its own, and patch that outside to the mix's level as well.
        assertTrue(f.patch.addSubpatchPort(subpatch.id, PortRef(f.lfo.id, PortDirection.OUTPUT, 0)))
        val added = subpatch.ports(PortDirection.OUTPUT)
        assertEquals("existing ports stay where they were", outputsBefore, added.take(outputsBefore.size))
        assertEquals(Port("out", SignalKind.MODULATION), added.last())

        val index = added.size - 1
        assertTrue(f.patch.connect(PortRef(subpatch.id, PortDirection.OUTPUT, index), PortRef(f.mix.id, PortDirection.MOD, 1)))
        assertTrue(
            "the new port carries the LFO all the way out",
            Connection(PortRef(f.lfo.id, PortDirection.OUTPUT, 0), PortRef(f.mix.id, PortDirection.MOD, 1)) in f.patch.engineConnections(),
        )

        // And an input: the envelope's notes, fed from a second new port on the left rail.
        val inputs = subpatch.ports(PortDirection.INPUT).size
        f.patch.disconnect(PortRef(f.env.id, PortDirection.INPUT, 0))
        assertTrue(f.patch.addSubpatchPort(subpatch.id, PortRef(f.env.id, PortDirection.INPUT, 0)))
        assertEquals(inputs + 1, subpatch.ports(PortDirection.INPUT).size)
        assertEquals(SignalKind.NOTE, subpatch.ports(PortDirection.INPUT).last().kind)
    }

    @Test
    fun `a port is not added for a jack outside the subpatch or on its rails`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id))!!
        val count = subpatch.ports(PortDirection.OUTPUT).size
        assertFalse(f.patch.addSubpatchPort(subpatch.id, PortRef(f.filter.id, PortDirection.OUTPUT, 0)))
        val railIn = f.patch.subpatchRail(subpatch.id, Types.SubpatchIn)!!
        assertFalse(f.patch.addSubpatchPort(subpatch.id, PortRef(railIn.id, PortDirection.OUTPUT, 0)))
        assertEquals(count, subpatch.ports(PortDirection.OUTPUT).size)
    }

    /**
     * The slot has to be exactly where the port will appear, or it is a target that lies.
     * A rail is centered like In and Out, so it re-centers as it grows -- the slot is
     * measured against the rail as it will be, not as it is.
     */
    @Test
    fun `the slot marks where the next port will land`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.lfo.id, f.env.id))!!
        f.patch.enterScope(subpatch.id)
        val railOut = f.patch.subpatchRail(subpatch.id, Types.SubpatchOut)!!
        val source = PortRef(f.lfo.id, PortDirection.OUTPUT, 0)

        val slot = subpatchPortSlot(frame, railOut, PortDirection.INPUT)
        assertTrue(f.patch.addSubpatchPort(subpatch.id, source))

        val index = railOut.ports(PortDirection.INPUT).size - 1
        val landed = portIn(
            frame.railRect(railOut), frame.density, PortDirection.INPUT, index,
            railOut.ports(PortDirection.INPUT).size, railOut.portsBody * frame.density,
        )
        assertEquals("the slot's x", slot.x, landed.x, 0.01f)
        assertEquals("the slot's y", slot.y, landed.y, 0.01f)
    }

    @Test
    fun `the slot answers only for a jack inside the subpatch being looked at`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.lfo.id, f.env.id))!!
        val railOut = f.patch.subpatchRail(subpatch.id, Types.SubpatchOut)!!
        val inside = PortRef(f.lfo.id, PortDirection.OUTPUT, 0)
        val slot = subpatchPortSlot(frame, railOut, PortDirection.INPUT)
        val touch = 24f * frame.density

        assertFalse("at the top level there is no rail to add to", f.patch.subpatchPortSlotHit(frame, inside, slot, touch))
        f.patch.enterScope(subpatch.id)
        assertTrue(f.patch.subpatchPortSlotHit(frame, inside, slot, touch))
        assertFalse(
            "a jack outside this subpatch asks for nothing",
            f.patch.subpatchPortSlotHit(frame, PortRef(f.filter.id, PortDirection.OUTPUT, 0), slot, touch),
        )
        assertFalse(
            "and an output's slot is on the right rail, not the left",
            f.patch.subpatchPortSlotHit(
                frame, inside,
                subpatchPortSlot(frame, f.patch.subpatchRail(subpatch.id, Types.SubpatchIn)!!, PortDirection.OUTPUT), touch,
            ),
        )
    }

    @Test
    fun `a subpatch's rails are centered, like the patch's own`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.lfo.id, f.env.id))!!
        val railOut = f.patch.subpatchRail(subpatch.id, Types.SubpatchOut)!!
        val out = f.patch.module(OUT_ID)!!
        val middle = { r: androidx.compose.ui.geometry.Rect -> (r.top + r.bottom) / 2f }
        assertEquals(middle(frame.railRect(out)), middle(frame.railRect(railOut)), 0.01f)
    }

    @Test
    fun `undo through a subpatch restores it exactly`() {
        val f = SubpatchFixture()
        val loose = f.patch.toJson()
        f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))
        val subpatched = f.patch.toJson()

        f.patch.replaceWith(patchFromJson(loose)!!)
        assertEquals(loose, f.patch.toJson())
        f.patch.replaceWith(patchFromJson(subpatched)!!)
        assertEquals(subpatched, f.patch.toJson())
        assertEquals(1, f.patch.modules.count { it.type == Types.Subpatch })
    }

    @Test
    fun `subpatches are numbered, and a number comes round again when its name is taken off`() {
        val f = SubpatchFixture()
        val first = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val second = f.patch.makeSubpatch(setOf(f.lfo.id, f.env.id))!!
        assertEquals("Subpatch 1", first.name)
        assertEquals("Subpatch 2", second.name)
        assertEquals("the box and the breadcrumb both read this", "Subpatch 2", second.title)

        // Naming one for what it does takes its number out of use, and the next subpatch
        // gets the lowest one free rather than counting subpatches.
        second.name = "Reverb"
        assertEquals("Subpatch 2", f.patch.nextBoxName(Types.Subpatch))
        first.name = "Bass"
        assertEquals("Subpatch 1", f.patch.nextBoxName(Types.Subpatch))
    }

    @Test
    fun `a module with no name of its own goes by its type`() {
        val f = SubpatchFixture()
        assertNull(f.osc.name)
        assertEquals("Osc", f.osc.title)
        f.osc.name = "Bass"
        assertEquals("Bass", f.osc.title)
    }

    @Test
    fun `a duplicated subpatch is numbered afresh, and the subpatches inside it keep their names`() {
        val f = SubpatchFixture()
        val inner = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.makeSubpatch(setOf(inner.id, f.mix.id))!!
        inner.name = "Tone"

        val copy = f.patch.duplicate(outer)!!
        assertEquals("Subpatch 3", copy.name)
        val copiedInner = f.patch.modules.first { it.parent == copy.id && it.type == Types.Subpatch }
        assertEquals("Tone", copiedInner.name)
    }

    @Test
    fun `a name is saved, reloaded and undone`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        f.osc.name = "Bass"
        val named = f.patch.toJson()

        val reloaded = patchFromJson(named)!!
        assertEquals("Subpatch 1", reloaded.module(subpatch.id)?.name)
        assertEquals("Bass", reloaded.module(f.osc.id)?.name)
        assertEquals("a reload has to serialize back to the same bytes", named, reloaded.toJson())

        subpatch.name = "Reverb"
        f.patch.replaceWith(patchFromJson(named)!!)
        assertEquals("Subpatch 1", f.patch.module(subpatch.id)?.name)
        assertEquals(named, f.patch.toJson())
    }

    @Test
    fun `a crumb says which subpatch it is, so holding it can rename that one`() {
        val f = SubpatchFixture()
        val inner = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.makeSubpatch(setOf(inner.id, f.mix.id))!!

        // One chip at the top level, the patch's own, which is where a patch is named.
        f.patch.scope = TOP
        assertEquals(listOf(TOP), f.patch.scopePath())
        assertEquals(TOP, f.patch.breadcrumbAt(frame, frame.breadcrumbChip(0).center))
        assertNull("and nothing beside it", f.patch.breadcrumbAt(frame, frame.breadcrumbChip(1).center))

        f.patch.enterScope(outer.id)
        f.patch.enterScope(inner.id)
        // "Patch > Subpatch 2 > Subpatch 1", and each chip answers for its own level.
        assertEquals(listOf(TOP, outer.id, inner.id), f.patch.scopePath())
        assertEquals(TOP, f.patch.breadcrumbAt(frame, frame.breadcrumbChip(0).center))
        assertEquals(outer.id, f.patch.breadcrumbAt(frame, frame.breadcrumbChip(1).center))
        assertEquals(inner.id, f.patch.breadcrumbAt(frame, frame.breadcrumbChip(2).center))

        // Below the chips is the canvas, where a long press means the add menu.
        val under = frame.breadcrumbChip(1).let { Offset(it.center.x, it.bottom + 40f) }
        assertNull(f.patch.breadcrumbAt(frame, under))

        // No breadcrumb is drawn over an open panel, so none is hit either.
        f.mix.expanded = true
        assertNull(f.patch.breadcrumbAt(frame, frame.breadcrumbChip(1).center))
    }

    @Test
    fun `a knob promoted to a subpatch's edge is the one inside, not a copy of it`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val cutoff = f.filter.type.rowParams.first()

        // Only from inside: the chip that promotes is on a panel opened in this scope.
        assertFalse("not from the top level", f.patch.promote(f.filter, cutoff))
        f.patch.enterScope(subpatch.id)
        assertTrue(f.patch.promote(f.filter, cutoff))
        assertTrue(f.patch.isPromoted(f.filter, cutoff))
        assertFalse("twice is once", f.patch.promote(f.filter, cutoff))
        assertFalse("a module in another scope is not this subpatch's to promote", f.patch.promote(f.mix, 0))

        // The subpatch's panel shows the row, and it is the filter's own.
        val rows = f.patch.panelRows(subpatch)
        assertEquals(listOf(ParamRow(f.filter, cutoff)), rows)
        rows.first().owner.setParam(cutoff, 777f)
        assertEquals("turning it turns the filter", 777f, f.filter.params[cutoff], 0.001f)

        assertTrue(f.patch.unpromote(f.filter, cutoff))
        assertTrue(f.patch.panelRows(subpatch).isEmpty())
    }

    @Test
    fun `a subpatch carries no more knobs than a module does`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id, f.lfo.id, f.mix.id, f.env.id))!!
        f.patch.enterScope(subpatch.id)

        val inside = listOf(f.osc, f.filter, f.lfo, f.mix, f.env)
        val every = inside.flatMap { m -> m.type.rowParams.map { m to it } }
        assertTrue("the fixture has more knobs than a subpatch may take", every.size > MAX_PROMOTED)
        val taken = every.count { (m, i) -> f.patch.promote(m, i) }
        assertEquals(MAX_PROMOTED, taken)
        assertEquals(MAX_PROMOTED, f.patch.panelRows(subpatch).size)
    }

    @Test
    fun `promoted knobs survive a save and an undo, and follow a duplicate`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val cutoff = f.filter.type.rowParams.first()
        f.patch.enterScope(subpatch.id)
        f.patch.promote(f.filter, cutoff)
        f.patch.scope = TOP
        val saved = f.patch.toJson()

        val reloaded = patchFromJson(saved)!!
        assertEquals(
            listOf(ParamRef(f.filter.id, cutoff)),
            reloaded.module(subpatch.id)?.subpatchPorts?.promoted?.toList(),
        )
        assertEquals("a reload has to serialize back to the same bytes", saved, reloaded.toJson())

        // A duplicate's knobs are its own copies' knobs, not the original's.
        val copy = f.patch.duplicate(subpatch)!!
        val copied = copy.subpatchPorts!!.promoted.single()
        assertNotEquals(f.filter.id, copied.moduleId)
        assertEquals(copy.id, f.patch.module(copied.moduleId)?.parent)

        // And a knob whose module is deleted goes with it, rather than riding along in the file.
        f.patch.remove(f.filter)
        assertTrue(subpatch.subpatchPorts!!.promoted.isEmpty())
        f.patch.replaceWith(patchFromJson(saved)!!)
        assertEquals(saved, f.patch.toJson())
    }

    /**
     * Forrest's report, 2026-09-17: expose a knob inside a subpatch, give the subpatch a port for
     * it, then unexpose. The jack the port reached stops existing, and the port was left on
     * the rail and on the subpatch's box -- a jack you could patch into that went nowhere.
     */
    @Test
    fun `a subpatch port goes when the jack it reached inside stops existing`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.lfo.id, f.env.id))!!
        f.patch.enterScope(subpatch.id)

        val rate = 0
        f.patch.expose(f.lfo, rate, initialModRange(f.lfo.type.params[rate], f.lfo.params[rate]))
        val before = subpatch.ports(PortDirection.INPUT).size
        assertTrue(f.patch.addSubpatchPort(subpatch.id, PortRef(f.lfo.id, PortDirection.MOD, rate)))
        assertEquals(before + 1, subpatch.ports(PortDirection.INPUT).size)

        f.patch.unexpose(f.lfo, rate)
        assertEquals("the port goes with the jack", before, subpatch.ports(PortDirection.INPUT).size)
        val rail = f.patch.subpatchRail(subpatch.id, Types.SubpatchIn)!!
        assertEquals(before, rail.ports(PortDirection.OUTPUT).size)
    }

    /**
     * The same for a module deleted inside a subpatch, and with a second port after the one
     * that goes -- removing a port renumbers every cable that named a later one.
     */
    @Test
    fun `removing a module inside takes its subpatch ports with it, and the rest still reach`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val inputs = subpatch.ports(PortDirection.INPUT).toList()
        assertEquals("steps, drone, and the lfo onto cutoff", 3, inputs.size)

        // What the last port carries, and where it goes, has to survive losing an earlier one.
        val engineBefore = f.patch.engineConnections()
        val lfoCable = engineBefore.filter { it.to.moduleId == f.filter.id && it.to.dir == PortDirection.MOD }
        assertTrue(lfoCable.isNotEmpty())

        // The Osc takes the two note ports; deleting it leaves them reaching nothing.
        f.patch.remove(f.osc)

        assertEquals(listOf(SignalKind.MODULATION), subpatch.ports(PortDirection.INPUT).map { it.kind })
        assertEquals(
            "the modulation cable still lands where it did",
            lfoCable.toSet(),
            f.patch.engineConnections().filter { it.to.moduleId == f.filter.id && it.to.dir == PortDirection.MOD }.toSet(),
        )

        // The port that survived was the third, and every cable that named it has to say so
        // now that it is the first. Stale indices happen to resolve -- both ends are wrong
        // by the same amount -- so the engine hears the right thing while the jack is drawn
        // off the end of the box.
        val rail = f.patch.subpatchRail(subpatch.id, Types.SubpatchIn)!!
        val count = subpatch.ports(PortDirection.INPUT).size
        f.patch.connections.forEach { c ->
            listOf(c.from, c.to).forEach { ref ->
                val names = (ref.moduleId == subpatch.id && ref.dir == PortDirection.INPUT) ||
                    (ref.moduleId == rail.id && ref.dir == PortDirection.OUTPUT)
                if (names) assertTrue("cable names port ${ref.index} of $count", ref.index < count)
            }
        }
    }

    /**
     * One port can feed several modules inside. Losing one of them is not losing the port:
     * it is stored, not derived, and the others are still on it.
     */
    @Test
    fun `a port feeding two modules survives losing one of them`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.env.id))!!
        val rail = f.patch.subpatchRail(subpatch.id, Types.SubpatchIn)!!
        // Steps feeds both the Osc and the Env, so one port inside goes to two places.
        val shared = f.patch.connections.filter { it.from.moduleId == rail.id }
            .groupBy { it.from.index }.entries.first { it.value.size > 1 }
        assertEquals(2, shared.value.size)

        val before = subpatch.ports(PortDirection.INPUT).size
        f.patch.remove(f.env)
        assertEquals("the port stays for what is still on it", before, subpatch.ports(PortDirection.INPUT).size)
        assertTrue(f.patch.connections.any { it.from.moduleId == rail.id && it.from.index == shared.key })
    }

    /**
     * A port left over from before the prune existed, or one added by mistake, has to be
     * removable by hand: a long press on its jack, from the box outside or the rail inside,
     * and both have to name the same port.
     */
    @Test
    fun `a subpatch's jack knows which port it is, from either side`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val railIn = f.patch.subpatchRail(subpatch.id, Types.SubpatchIn)!!
        val railOut = f.patch.subpatchRail(subpatch.id, Types.SubpatchOut)!!

        assertEquals(
            Triple(subpatch, PortDirection.INPUT, 2),
            f.patch.subpatchPortAt(PortRef(subpatch.id, PortDirection.INPUT, 2)),
        )
        assertEquals(
            "the left rail's output is the subpatch's input",
            Triple(subpatch, PortDirection.INPUT, 2),
            f.patch.subpatchPortAt(PortRef(railIn.id, PortDirection.OUTPUT, 2)),
        )
        assertEquals(
            Triple(subpatch, PortDirection.OUTPUT, 0),
            f.patch.subpatchPortAt(PortRef(railOut.id, PortDirection.INPUT, 0)),
        )
        // The patch's own rails are pinned too, and their ports belong to the audio device.
        assertNull(f.patch.subpatchPortAt(PortRef(OUT_ID, PortDirection.INPUT, 0)))
        assertNull(f.patch.subpatchPortAt(PortRef(f.osc.id, PortDirection.INPUT, 0)))
    }

    @Test
    fun `removing a port by hand takes its cables and leaves the sound of the rest`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val before = f.patch.engineConnections()
        val cutoffCable = before.filter { it.to.moduleId == f.filter.id && it.to.dir == PortDirection.MOD }

        // The first input is Steps' notes; taking it off unpatches Steps from the Osc and
        // leaves everything else exactly as it was.
        assertTrue(f.patch.removeSubpatchPort(subpatch, PortDirection.INPUT, 0))
        assertEquals(2, subpatch.ports(PortDirection.INPUT).size)

        val after = f.patch.engineConnections()
        assertTrue(
            "only the cable through that port is gone",
            (before - after.toSet()).all { it.from.moduleId == f.steps.id },
        )
        assertEquals("what the rest carried is untouched", cutoffCable.toSet(),
            after.filter { it.to.moduleId == f.filter.id && it.to.dir == PortDirection.MOD }.toSet())
    }

    /**
     * Found saving a patch whose top level was two subpatches, 2026-09-17.
     *
     * Unpacking moved the modules inside back out -- unless they were subpatches themselves,
     * which the filter skipped along with the two rails it was written to skip. A nested
     * subpatch was left pointing at a parent that had just been deleted: still in the patch,
     * still playing, and drawn in no scope at all, since every view asks for the modules
     * whose parent is the one being looked at. Only a reload rescued it, because the file
     * reader puts a module with an unknown parent back at the top.
     */
    @Test
    fun `unpacking puts a nested subpatch back, not into nowhere`() {
        val f = SubpatchFixture()
        val inner = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.makeSubpatch(setOf(inner.id, f.lfo.id))!!

        f.patch.unpack(outer)

        assertEquals("the nested subpatch comes back out", TOP, inner.parent)
        assertEquals("as does anything beside it", TOP, f.lfo.parent)
        assertTrue("and it is drawn where it now lives", f.patch.shownFree.any { it.id == inner.id })
        assertTrue("its own contents came with it", f.patch.descendants(inner.id).contains(f.osc.id))
        assertNull("the subpatch that held them is gone", f.patch.module(outer.id))
    }

    /**
     * Found on the phone, 2026-09-19: a second output made by mistake, its cable moved to the
     * port that was meant, and the empty one stayed with nothing to say it was unused.
     */
    @Test
    fun `a subpatch port with nothing on either side goes by itself`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        f.patch.enterScope(subpatch.id)
        val rail = f.patch.subpatchRail(subpatch.id, Types.SubpatchOut)!!
        val before = subpatch.ports(PortDirection.OUTPUT).size

        // A second output, as dragging a jack to the rail's + slot makes.
        assertTrue(f.patch.addSubpatchPort(subpatch.id, PortRef(f.osc.id, PortDirection.OUTPUT, 0)))
        assertEquals(before + 1, subpatch.ports(PortDirection.OUTPUT).size)

        // Taking its cable back leaves it empty on both sides, so it goes.
        f.patch.disconnect(PortRef(rail.id, PortDirection.INPUT, before))
        assertEquals(before, subpatch.ports(PortDirection.OUTPUT).size)

        // The port that is still in use is untouched, and so are its cables.
        assertTrue("what was patched still is", f.patch.connections.any { it.to.moduleId == rail.id })
        assertTrue(f.patch.engineConnections().isNotEmpty())
    }

    @Test
    fun `a port patched on one side only stays, so it can be plugged back in`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val rail = f.patch.subpatchRail(subpatch.id, Types.SubpatchOut)!!
        val outside = f.patch.connections.first { it.from.moduleId == subpatch.id }
        assertEquals("the subpatch feeds something outside", subpatch.id, outside.from.moduleId)

        // Unplug what feeds it from inside: the box's jack is still patched outward, so the
        // port is one a cable is being moved on, not one nobody wants.
        f.patch.disconnect(PortRef(rail.id, PortDirection.INPUT, outside.from.index))
        assertTrue(
            "the jack is still there to plug back into",
            outside.from.index < subpatch.ports(PortDirection.OUTPUT).size,
        )
    }

    @Test
    fun `a saved file's unused port is gone when it opens`() {
        val f = SubpatchFixture()
        val subpatch = f.patch.makeSubpatch(setOf(f.osc.id, f.filter.id))!!
        val before = subpatch.ports(PortDirection.OUTPUT).size
        assertTrue(f.patch.addSubpatchPort(subpatch.id, PortRef(f.osc.id, PortDirection.OUTPUT, 0)))

        // Written with the port and its cable, then the cable taken out by hand, which is
        // what a file from before the sweep looks like.
        val rail = f.patch.subpatchRail(subpatch.id, Types.SubpatchOut)!!
        val json = org.json.JSONObject(f.patch.toJson()).apply {
            val cables = getJSONArray("connections")
            for (i in cables.length() - 1 downTo 0) {
                val c = cables.getJSONObject(i)
                if (c.optLong("to") == rail.id && c.optInt("toPort") == before) cables.remove(i)
            }
        }.toString()

        val opened = patchFromJson(json)!!
        val reopened = opened.modules.first { it.type == Types.Subpatch }
        assertEquals(before, reopened.ports(PortDirection.OUTPUT).size)
        assertEquals("and it stays gone", json.let { patchFromJson(opened.toJson())!! }
            .modules.first { it.type == Types.Subpatch }.ports(PortDirection.OUTPUT).size, before)
    }
}
