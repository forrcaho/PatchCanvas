package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Groups are how a patch is organized, never how it sounds. The one property that matters
 * most is therefore checkable on the desk: whatever is grouped, ungrouped, nested or
 * rewired, [Patch.engineConnections] -- the cables the engine actually gets -- says exactly
 * what the same patch said loose.
 */
class GroupTest {

    @Test
    fun `grouping leaves the cables the engine has exactly as they were`() {
        val f = GroupFixture()
        val before = f.patch.engineConnections()
        assertEquals("the fixture has nine real cables", 9, before.size)

        val group = f.patch.group(setOf(f.osc.id, f.filter.id))
        assertNotNull(group)
        assertEquals(before, f.patch.engineConnections())
        assertTrue("nothing structural reaches the engine", f.patch.engineModules.none { it.type.structural })
    }

    @Test
    fun `a group's ports come from the cables that crossed its edge`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.osc.id, f.filter.id))!!

        // In: the two note sources stay separate ports, since each is its own source, and the
        // modulator onto cutoff is a third. Out: the filter's one output, however many
        // places it went.
        val inputs = group.ports(PortDirection.INPUT)
        val outputs = group.ports(PortDirection.OUTPUT)
        assertEquals(listOf(SignalKind.NOTE, SignalKind.NOTE, SignalKind.MODULATION), inputs.map { it.kind })
        assertEquals(listOf(SignalKind.AUDIO), outputs.map { it.kind })

        // The rails inside turn the box's ports around.
        val railIn = f.patch.groupRail(group.id, Types.GroupIn)!!
        val railOut = f.patch.groupRail(group.id, Types.GroupOut)!!
        // toList() on both sides: a group's ports are a snapshot list, which compares by
        // identity -- these two would pass only because they happen to be one list.
        assertEquals(inputs.toList(), railIn.ports(PortDirection.OUTPUT).toList())
        assertEquals(outputs.toList(), railOut.ports(PortDirection.INPUT).toList())
        assertTrue(railIn.ports(PortDirection.INPUT).isEmpty())
        assertTrue(railOut.ports(PortDirection.OUTPUT).isEmpty())

        assertEquals(group.id, f.osc.parent)
        assertEquals(group.id, f.filter.parent)
        assertEquals(TOP, group.parent)
    }

    @Test
    fun `ungrouping gives back the same cables and the same scope`() {
        val f = GroupFixture()
        val cables = f.patch.connections.toSet()
        val group = f.patch.group(setOf(f.osc.id, f.filter.id))!!
        f.patch.ungroup(group)

        assertEquals(cables, f.patch.connections.toSet())
        assertTrue(f.patch.modules.none { it.type.structural })
        assertEquals(TOP, f.osc.parent)
        assertEquals(TOP, f.filter.parent)
    }

    @Test
    fun `groups nest, and still sound the same`() {
        val f = GroupFixture()
        val cables = f.patch.connections.toSet()
        val flat = f.patch.engineConnections()

        val inner = f.patch.group(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.group(setOf(inner.id, f.lfo.id, f.mix.id))!!
        assertEquals(outer.id, inner.parent)
        assertEquals(flat, f.patch.engineConnections())

        f.patch.ungroup(outer)
        f.patch.ungroup(inner)
        assertEquals(cables, f.patch.connections.toSet())
    }

    @Test
    fun `patching a new source into a group's input replaces it for everything inside`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.filter.id))!!
        val audioIn = group.ports(PortDirection.INPUT).indexOfFirst { it.kind == SignalKind.AUDIO }
        val other = f.patch.add(Types.Osc, Offset(0f, 400f))!!

        assertTrue(
            f.patch.connect(
                PortRef(other.id, PortDirection.OUTPUT, 0),
                PortRef(group.id, PortDirection.INPUT, audioIn),
            ),
        )
        val flat = f.patch.engineConnections()
        val intoFilter = flat.filter { it.to == PortRef(f.filter.id, PortDirection.INPUT, 0) }
        assertEquals(listOf(PortRef(other.id, PortDirection.OUTPUT, 0)), intoFilter.map { it.from })
    }

    @Test
    fun `an unplugged group port stays, and can be plugged back into`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.filter.id))!!
        val count = group.ports(PortDirection.OUTPUT).size
        f.patch.disconnect(PortRef(group.id, PortDirection.OUTPUT, 0))
        assertEquals("the port outlives its cables", count, group.ports(PortDirection.OUTPUT).size)
        assertTrue(
            f.patch.connect(PortRef(group.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0)),
        )
        assertTrue(Connection(PortRef(f.filter.id, PortDirection.OUTPUT, 0), PortRef(OUT_ID, PortDirection.INPUT, 0)) in f.patch.engineConnections())
    }

    @Test
    fun `nothing is grouped that cannot be`() {
        val f = GroupFixture()
        assertNull("nothing chosen", f.patch.group(emptySet()))
        assertNull("a rail", f.patch.group(setOf(f.osc.id, OUT_ID)))
        assertNull("an id that is not there", f.patch.group(setOf(f.osc.id, 9999L)))
        val group = f.patch.group(setOf(f.osc.id))!!
        assertNull("modules from two scopes", f.patch.group(setOf(f.filter.id, f.osc.id)))
        assertEquals(group.id, f.osc.parent)
    }

    @Test
    fun `deleting a group takes everything inside it`() {
        val f = GroupFixture()
        val inner = f.patch.group(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.group(setOf(inner.id, f.lfo.id))!!
        f.patch.remove(outer)

        listOf(outer.id, inner.id, f.osc.id, f.filter.id, f.lfo.id).forEach {
            assertNull("module $it is gone", f.patch.module(it))
        }
        assertTrue(f.patch.modules.none { it.type.structural })
        val left = f.patch.modules.map { it.id }.toSet()
        assertTrue("no cable names a module that went", f.patch.connections.all { it.from.moduleId in left && it.to.moduleId in left })
    }

    @Test
    fun `a duplicated group copies what is inside and nothing outside`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.osc.id, f.filter.id))!!
        val before = f.patch.engineConnections().size
        val copy = f.patch.duplicate(group)!!

        assertEquals(Types.Group, copy.type)
        assertEquals(group.ports(PortDirection.INPUT).toList(), copy.ports(PortDirection.INPUT).toList())
        val insideCopy = f.patch.descendants(copy.id).mapNotNull { f.patch.module(it) }
        assertEquals(listOf("Filter", "Osc"), insideCopy.filter { !it.type.structural }.map { it.type.name }.sorted())
        // Its inside is wired (osc into filter, and the filter's output onto its right rail),
        // but it is patched to nothing outside, so the engine gains exactly one real cable.
        assertEquals(before + 1, f.patch.engineConnections().size)
        // A separate set of ports: changing one group's does not change the other's.
        assertFalse(group.groupPorts === copy.groupPorts)
    }

    @Test
    fun `a group's contents are shown only when it is entered`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.osc.id, f.filter.id))!!
        assertTrue(group in f.patch.shownFree)
        assertFalse(f.osc in f.patch.shownFree)
        assertEquals(setOf(OUT_ID, IN_ID), f.patch.shownRails.map { it.id }.toSet())

        f.patch.scope = group.id
        assertEquals(setOf(f.osc.id, f.filter.id), f.patch.shownFree.map { it.id }.toSet())
        assertEquals(
            setOf(Types.GroupIn, Types.GroupOut),
            f.patch.shownRails.map { it.type }.toSet(),
        )
        val added = f.patch.add(Types.Lfo, Offset.Zero)!!
        assertEquals("a module added inside goes inside", group.id, added.parent)

        f.patch.remove(group)
        assertEquals("a scope that has gone falls back to the top", TOP, f.patch.scopeOrTop)
    }

    @Test
    fun `groups survive saving and loading, byte for byte`() {
        val f = GroupFixture()
        val inner = f.patch.group(setOf(f.osc.id, f.filter.id))!!
        f.patch.group(setOf(inner.id, f.lfo.id))
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

    @Test
    fun `a format 5 file still loads, as a patch with no groups`() {
        val f = GroupFixture()
        val json = org.json.JSONObject(f.patch.toJson()).put("version", 5).toString()
        val restored = patchFromJson(json)
        assertNotNull(restored)
        assertEquals(f.patch.engineConnections(), restored!!.engineConnections())
    }

    @Test
    fun `a parent that is not a group puts the module at the top`() {
        val f = GroupFixture()
        val root = org.json.JSONObject(f.patch.toJson())
        root.getJSONArray("modules").getJSONObject(0).put("parent", f.osc.id) // not a group
        val restored = patchFromJson(root.toString())!!
        assertTrue(restored.modules.filter { !it.isPinned }.all { it.parent == TOP })
    }

    @Test
    fun `the breadcrumb runs from the patch down to where you are`() {
        val f = GroupFixture()
        val inner = f.patch.group(setOf(f.osc.id, f.filter.id))!!
        val outer = f.patch.group(setOf(inner.id, f.lfo.id))!!
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
    fun `the breadcrumb and the group buttons clear the controls already on screen`() {
        (0 until 3).forEach { level ->
            val chip = frame.breadcrumbChip(level)
            assertFalse("crumb $level over the scale chip", chip.overlaps(frame.scaleChip()))
            assertFalse("crumb $level over the transport chip", chip.overlaps(frame.transportChip()))
            assertTrue("crumb $level on screen", chip.right <= frame.canvas.width)
        }
        val done = frame.selectionButton(done = true)
        val cancel = frame.selectionButton(done = false)
        assertFalse("group and cancel apart", done.overlaps(cancel))
        for (button in listOf(done, cancel)) {
            assertFalse(button.overlaps(frame.historyRect(redo = false)))
            assertFalse(button.overlaps(frame.historyRect(redo = true)))
            assertTrue("above the gesture bar", button.bottom <= frame.canvas.height - frame.insetBottom)
        }
    }

    @Test
    fun `a port can be added after grouping, by patching to a rail`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.lfo.id, f.env.id))!!
        val outputsBefore = group.ports(PortDirection.OUTPUT).map { it }

        // The envelope's output already leaves the group (to the mix's level); give the LFO
        // an output of its own, and patch that outside to the mix's level as well.
        assertTrue(f.patch.addGroupPort(group.id, PortRef(f.lfo.id, PortDirection.OUTPUT, 0)))
        val added = group.ports(PortDirection.OUTPUT)
        assertEquals("existing ports stay where they were", outputsBefore, added.take(outputsBefore.size))
        assertEquals(Port("out", SignalKind.MODULATION), added.last())

        val index = added.size - 1
        assertTrue(f.patch.connect(PortRef(group.id, PortDirection.OUTPUT, index), PortRef(f.mix.id, PortDirection.MOD, 1)))
        assertTrue(
            "the new port carries the LFO all the way out",
            Connection(PortRef(f.lfo.id, PortDirection.OUTPUT, 0), PortRef(f.mix.id, PortDirection.MOD, 1)) in f.patch.engineConnections(),
        )

        // And an input: the envelope's notes, fed from a second new port on the left rail.
        val inputs = group.ports(PortDirection.INPUT).size
        f.patch.disconnect(PortRef(f.env.id, PortDirection.INPUT, 0))
        assertTrue(f.patch.addGroupPort(group.id, PortRef(f.env.id, PortDirection.INPUT, 0)))
        assertEquals(inputs + 1, group.ports(PortDirection.INPUT).size)
        assertEquals(SignalKind.NOTE, group.ports(PortDirection.INPUT).last().kind)
    }

    @Test
    fun `a port is not added for a jack outside the group or on its rails`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.osc.id))!!
        val count = group.ports(PortDirection.OUTPUT).size
        assertFalse(f.patch.addGroupPort(group.id, PortRef(f.filter.id, PortDirection.OUTPUT, 0)))
        val railIn = f.patch.groupRail(group.id, Types.GroupIn)!!
        assertFalse(f.patch.addGroupPort(group.id, PortRef(railIn.id, PortDirection.OUTPUT, 0)))
        assertEquals(count, group.ports(PortDirection.OUTPUT).size)
    }

    /**
     * The slot has to be exactly where the port will appear, or it is a target that lies.
     * A rail is centered like In and Out, so it re-centers as it grows -- the slot is
     * measured against the rail as it will be, not as it is.
     */
    @Test
    fun `the slot marks where the next port will land`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.lfo.id, f.env.id))!!
        f.patch.enterScope(group.id)
        val railOut = f.patch.groupRail(group.id, Types.GroupOut)!!
        val source = PortRef(f.lfo.id, PortDirection.OUTPUT, 0)

        val slot = groupPortSlot(frame, railOut, PortDirection.INPUT)
        assertTrue(f.patch.addGroupPort(group.id, source))

        val index = railOut.ports(PortDirection.INPUT).size - 1
        val landed = portIn(
            frame.railRect(railOut), frame.density, PortDirection.INPUT, index,
            railOut.ports(PortDirection.INPUT).size, railOut.portsBody * frame.density,
        )
        assertEquals("the slot's x", slot.x, landed.x, 0.01f)
        assertEquals("the slot's y", slot.y, landed.y, 0.01f)
    }

    @Test
    fun `the slot answers only for a jack inside the group being looked at`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.lfo.id, f.env.id))!!
        val railOut = f.patch.groupRail(group.id, Types.GroupOut)!!
        val inside = PortRef(f.lfo.id, PortDirection.OUTPUT, 0)
        val slot = groupPortSlot(frame, railOut, PortDirection.INPUT)
        val touch = 24f * frame.density

        assertFalse("at the top level there is no rail to add to", f.patch.groupPortSlotHit(frame, inside, slot, touch))
        f.patch.enterScope(group.id)
        assertTrue(f.patch.groupPortSlotHit(frame, inside, slot, touch))
        assertFalse(
            "a jack outside this group asks for nothing",
            f.patch.groupPortSlotHit(frame, PortRef(f.filter.id, PortDirection.OUTPUT, 0), slot, touch),
        )
        assertFalse(
            "and an output's slot is on the right rail, not the left",
            f.patch.groupPortSlotHit(
                frame, inside,
                groupPortSlot(frame, f.patch.groupRail(group.id, Types.GroupIn)!!, PortDirection.OUTPUT), touch,
            ),
        )
    }

    @Test
    fun `a group's rails are centered, like the patch's own`() {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.lfo.id, f.env.id))!!
        val railOut = f.patch.groupRail(group.id, Types.GroupOut)!!
        val out = f.patch.module(OUT_ID)!!
        val middle = { r: androidx.compose.ui.geometry.Rect -> (r.top + r.bottom) / 2f }
        assertEquals(middle(frame.railRect(out)), middle(frame.railRect(railOut)), 0.01f)
    }

    @Test
    fun `undo through a group restores it exactly`() {
        val f = GroupFixture()
        val loose = f.patch.toJson()
        f.patch.group(setOf(f.osc.id, f.filter.id))
        val grouped = f.patch.toJson()

        f.patch.replaceWith(patchFromJson(loose)!!)
        assertEquals(loose, f.patch.toJson())
        f.patch.replaceWith(patchFromJson(grouped)!!)
        assertEquals(grouped, f.patch.toJson())
        assertEquals(1, f.patch.modules.count { it.type == Types.Group })
    }
}
