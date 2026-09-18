package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Saving a group and loading it back into another patch.
 *
 * A saved group is a patch file holding one group, so the interesting claims are not about
 * JSON -- that path is the patch's own and already tested -- but about what survives the
 * round trip, and about the copy being a copy: fresh ids, independent of the original and
 * of every other copy of it.
 */
class GroupLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun saved(): Pair<GroupFixture, String> {
        val f = GroupFixture()
        val group = f.patch.group(setOf(f.osc.id, f.filter.id))!!
        group.name = "Filt Osc"
        f.patch.enterScope(group.id)
        f.patch.promote(f.filter, 0)
        f.patch.scope = TOP
        f.osc.setParam(0, 0.25f)
        return f to f.patch.groupToJson(group)!!
    }

    @Test
    fun `a saved group comes back with its modules, cables, ports, knobs and names`() {
        val (f, json) = saved()
        val original = f.patch.modules.first { it.type == Types.Group }

        val patch = Patch()
        val loaded = patch.loadGroup(json, Offset(120f, 80f))!!

        assertEquals("Filt Osc", loaded.name)
        assertEquals(Offset(120f, 80f), loaded.position)
        assertEquals(TOP, loaded.parent)
        assertEquals(
            original.ports(PortDirection.INPUT).map { it.name to it.kind },
            loaded.ports(PortDirection.INPUT).map { it.name to it.kind },
        )
        assertEquals(
            original.ports(PortDirection.OUTPUT).map { it.name to it.kind },
            loaded.ports(PortDirection.OUTPUT).map { it.name to it.kind },
        )

        // What is inside, by type and by knob, and the cables between them.
        val inside = patch.modules.filter { it.parent == loaded.id && !it.isPinned }
        assertEquals(setOf("Osc", "Filter"), inside.map { it.type.name }.toSet())
        assertEquals(0.25f, inside.first { it.type == Types.Osc }.params[0], 0.001f)
        val filter = inside.first { it.type == Types.Filter }
        assertTrue("the filter's exposed cutoff came too", 0 in filter.modRanges)
        assertEquals(
            "the promoted knob points at the copy",
            listOf(ParamRef(filter.id, 0)),
            loaded.groupPorts?.promoted?.toList(),
        )
        assertEquals(
            "the cable inside is the same cable",
            1,
            patch.connections.count { it.from.moduleId == inside.first { m -> m.type == Types.Osc }.id },
        )
    }

    @Test
    fun `loading twice gives two groups that share nothing`() {
        val (_, json) = saved()
        val patch = Patch()
        val first = patch.loadGroup(json, Offset.Zero)!!
        val second = patch.loadGroup(json, Offset(200f, 0f))!!

        assertNotEquals(first.id, second.id)
        val insideFirst = patch.descendants(first.id)
        val insideSecond = patch.descendants(second.id)
        assertTrue("no module is in both", insideFirst.intersect(insideSecond).isEmpty())

        // Turning one group's knob leaves the other where it was.
        val filterOf = { g: PatchModule ->
            patch.modules.first { it.parent == g.id && it.type == Types.Filter }
        }
        filterOf(first).setParam(1, 0.9f)
        assertNotEquals(0.9f, filterOf(second).params[1])
        // And their promoted knobs point at their own insides.
        assertEquals(listOf(ParamRef(filterOf(first).id, 0)), first.groupPorts?.promoted?.toList())
        assertEquals(listOf(ParamRef(filterOf(second).id, 0)), second.groupPorts?.promoted?.toList())
    }

    @Test
    fun `a group loads into the scope being looked at`() {
        val (_, json) = saved()
        val patch = Patch()
        val outer = patch.group(setOf(patch.add(Types.Mix, Offset.Zero)!!.id))!!
        patch.enterScope(outer.id)

        val loaded = patch.loadGroup(json, Offset.Zero)!!
        assertEquals("it lands where you are looking", outer.id, loaded.parent)
        assertTrue("and it is a group inside a group", patch.descendants(outer.id).contains(loaded.id))
    }

    @Test
    fun `a file that is not one group is refused`() {
        val patch = Patch()
        assertNull("a whole patch is not a group", patch.loadGroup(demoPatch().toJson(), Offset.Zero))
        assertNull("nor is nonsense", patch.loadGroup("{\"version\":7}", Offset.Zero))
        assertNull("nor is a version this build cannot read", patch.loadGroup("{\"version\":2}", Offset.Zero))

        // A group and something else beside it is not a saved group either, even though the
        // group is the first thing in the file: the rest of the patch would be lost quietly.
        val mixed = Patch()
        val group = mixed.group(setOf(mixed.add(Types.Osc, Offset.Zero)!!.id))!!
        val stray = mixed.add(Types.Mix, Offset(400f, 0f))!!
        assertTrue(
            "the group has to come first, or this proves nothing",
            mixed.free.indexOfFirst { it.id == group.id } < mixed.free.indexOfFirst { it.id == stray.id },
        )
        assertNull("a group with company is refused", patch.loadGroup(mixed.toJson(), Offset.Zero))

        assertTrue("and nothing of it landed", patch.free.isEmpty())
    }

    @Test
    fun `the whole patch saves as one group and leaves the patch alone`() {
        val patch = demoPatch()
        val before = patch.toJson()

        val json = patch.patchToGroupJson("Whole thing")!!
        assertEquals("the patch is exactly as it was", before, patch.toJson())

        val into = Patch()
        val loaded = into.loadGroup(json, Offset.Zero)!!
        assertEquals("Whole thing", loaded.name)
        // What the patch sent to Out becomes what the group sends out.
        assertTrue("it has something to say", loaded.ports(PortDirection.OUTPUT).isNotEmpty())
        assertEquals(
            "everything that was in the patch is inside it",
            patch.free.size,
            into.modules.count { it.parent == loaded.id && !it.isPinned },
        )
    }

    /**
     * Found on the phone, 2026-09-18: a patch with a group at its top level, and cables
     * crossing into it, came back from "Save patch" with the same cables in a different
     * order. The engine heard nothing -- it diffs sets -- but the autosave saw a new file,
     * so the save became an undo step that did nothing. The demo patch above could not show
     * it: its cables happen to be re-added in the order they started in.
     */
    @Test
    fun `saving the whole patch leaves a patch with groups byte for byte alone`() {
        val f = GroupFixture()
        f.patch.group(setOf(f.osc.id, f.filter.id))
        val before = f.patch.toJson()

        assertNotNull(f.patch.patchToGroupJson("Whole thing"))
        assertEquals(before, f.patch.toJson())
    }

    @Test
    fun `the library keeps files beside the scales, and never replaces one silently`() {
        val library = GroupLibrary(folder.newFolder("groups"))
        val (_, json) = saved()

        assertTrue(library.names().isEmpty())
        assertTrue(library.write("Filt Osc", json))
        assertEquals(listOf("Filt Osc"), library.names())
        assertTrue(library.exists("Filt Osc"))
        assertNotNull(library.read("Filt Osc"))
        assertNull(library.read("nothing of that name"))

        // "Keep both" never writes over what is there.
        assertEquals("Filt Osc 2", library.freeName("Filt Osc"))
        library.write("Filt Osc 2", json)
        assertEquals("Filt Osc 3", library.freeName("Filt Osc"))
        assertEquals("a free name is returned as it is", "Bass", library.freeName("Bass"))

        // Sorted for the list, regardless of case.
        library.write("apple", json)
        assertEquals(listOf("apple", "Filt Osc", "Filt Osc 2"), library.names())
    }

    @Test
    fun `a name that would escape the folder cannot`() {
        assertEquals("Bass_Lead", GroupLibrary.safeName("Bass/Lead"))
        // A dot is not kept either, which is what makes ".." impossible rather than handled.
        GroupLibrary.safeName("../../etc/passwd").let { safe ->
            assertFalse(safe, safe.contains('/'))
            assertFalse(safe, safe.contains(".."))
        }
        assertEquals("", GroupLibrary.safeName("  "))
        assertTrue(GroupLibrary.safeName("x".repeat(80)).length <= MAX_NAME)

        val library = GroupLibrary(folder.newFolder("safe"))
        // A blank name has nothing to save under. "///" is not blank -- it saves as "___",
        // an odd name for something the user typed, but its own file and nobody else's.
        assertFalse("a blank name saves nowhere", library.write("   ", "{}"))
        assertTrue(library.write("///", "{}"))
        assertEquals(listOf("___"), library.names())
    }
}
