package io.github.forrcaho.patchcanvas

import androidx.compose.ui.geometry.Offset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A patch file is untrusted input: it can be older than the code, hand-edited, or
 * truncated. Every one of these cases has to load as "ignore that entry", never as a
 * crash on launch.
 */
class PatchJsonTest {

    private fun sample(): Patch = demoPatch()

    @Test
    fun `round trip preserves modules, positions and cables`() {
        val original = sample()
        val restored = patchFromJson(original.toJson())!!

        assertEquals(original.free.size, restored.free.size)
        assertEquals(original.connections.size, restored.connections.size)

        original.free.zip(restored.free).forEach { (a, b) ->
            assertEquals(a.id, b.id)
            assertEquals(a.type.name, b.type.name)
            assertEquals(a.position.x, b.position.x, 0.001f)
            assertEquals(a.position.y, b.position.y, 0.001f)
        }
        assertEquals(original.connections.toSet(), restored.connections.toSet())
    }

    @Test
    fun `cables into the rails survive a reload`() {
        val original = sample()
        val restored = patchFromJson(original.toJson())!!

        val before = original.connections.filter { it.to.moduleId == OUT_ID }
        val after = restored.connections.filter { it.to.moduleId == OUT_ID }
        assertTrue("the demo patch should reach the output", before.isNotEmpty())
        assertEquals(before.toSet(), after.toSet())
        after.forEach { assertNotNull(restored.module(it.from.moduleId)) }
    }

    @Test
    fun `reload does not duplicate the rails`() {
        val restored = patchFromJson(sample().toJson())!!
        assertEquals(2, restored.pinned.size)
        assertEquals(1, restored.modules.count { it.id == OUT_ID })
        assertEquals(1, restored.modules.count { it.id == IN_ID })
    }

    @Test
    fun `a live microphone is never persisted`() {
        // It is runtime state, not part of the patch. Saving it meant a force-stop with
        // the mic on reloaded showing a live In rail with no stream behind it.
        val p = sample().apply { inputEnabled = true }
        assertTrue("the mic must not come back on by itself", !patchFromJson(p.toJson())!!.inputEnabled)
        assertTrue(!p.toJson().contains("inputEnabled"))
    }

    @Test
    fun `an older file carrying inputEnabled still loads`() {
        val legacy = JSONObject(sample().toJson()).put("inputEnabled", true).toString()
        val restored = patchFromJson(legacy)
        assertNotNull(restored)
        assertTrue("and the stale flag is ignored", !restored!!.inputEnabled)
    }

    @Test
    fun `an id from the file is not handed out again`() {
        val restored = patchFromJson(sample().toJson())!!
        val existing = restored.free.map { it.id }.toSet()
        val fresh = restored.add(Types.Osc, Offset.Zero)!!
        assertTrue("reused id ${fresh.id}", fresh.id !in existing)
    }

    @Test
    fun `a future format version is refused rather than half-read`() {
        val text = JSONObject(sample().toJson()).put("version", 99).toString()
        assertNull(patchFromJson(text))
    }

    @Test
    fun `an unknown module type is skipped, not fatal`() {
        val root = JSONObject(sample().toJson())
        root.getJSONArray("modules").getJSONObject(0).put("type", "Vocoder9000")
        val restored = patchFromJson(root.toString())
        assertNotNull(restored)
        assertEquals(sample().free.size - 1, restored!!.free.size)
    }

    @Test
    fun `a cable naming a missing module is skipped`() {
        val root = JSONObject(sample().toJson())
        root.getJSONArray("connections").getJSONObject(0).put("from", 9999L)
        val restored = patchFromJson(root.toString())!!
        assertEquals(sample().connections.size - 1, restored.connections.size)
    }

    @Test
    fun `a cable with an out of range port index is skipped`() {
        val root = JSONObject(sample().toJson())
        root.getJSONArray("connections").getJSONObject(0).put("toPort", 42)
        val restored = patchFromJson(root.toString())!!
        assertEquals(sample().connections.size - 1, restored.connections.size)
    }

    /**
     * Found on the emulator, loading a file written while typing was advisory: three of
     * its four cables came back, and the fourth was gone from the file on disk before
     * anything had been touched. Refusing is loud; dropping was silent and permanent.
     */
    @Test
    fun `a cable whose kinds no longer agree refuses the whole file`() {
        val root = JSONObject(sample().toJson())
        // The filter's audio output into the oscillator's note input, which is what a file
        // written under advisory typing can contain. Both ports still exist, so neither the
        // missing-module check nor the port-range one catches it.
        val filter = sample().free.first { it.type.name == "Filter" }
        val osc = sample().free.first { it.type.name == "Osc" }
        root.getJSONArray("connections").put(
            JSONObject().put("from", filter.id).put("fromPort", 0).put("to", osc.id).put("toPort", 0),
        )

        assertNull("a file with an illegal cable is refused whole", patchFromJson(root.toString()))
        assertNotNull("while the same file without it still loads", patchFromJson(sample().toJson()))
    }

    @Test
    fun `garbage loads as nothing rather than throwing`() {
        assertNull(patchFromJson("not json at all"))
        assertNull(patchFromJson(""))
        assertNull(patchFromJson("{}"))
    }

    @Test
    fun `an empty patch round trips`() {
        val restored = patchFromJson(Patch().toJson())!!
        assertTrue(restored.free.isEmpty())
        assertTrue(restored.connections.isEmpty())
        assertEquals(2, restored.pinned.size)
    }

    @Test
    fun `tempo and beats per bar survive a reload`() {
        val p = sample().apply {
            tempo = 97f
            beatsPerBar = 7
        }
        val restored = patchFromJson(p.toJson())!!
        assertEquals(97f, restored.tempo, 0.0001f)
        assertEquals(7, restored.beatsPerBar)
    }

    @Test
    fun `a tempo or bar length outside the range is clamped on load`() {
        val root = JSONObject(sample().toJson()).put("tempo", 100000.0).put("beatsPerBar", -3)
        val restored = patchFromJson(root.toString())!!
        assertEquals(TEMPO.max, restored.tempo, 0.0001f)
        assertEquals(BEATS_PER_BAR.min.toInt(), restored.beatsPerBar)
    }

    /** Files from before keys existed carry no root, and were all in C. */
    @Test
    fun `an entry with no root is in C, and an absurd one is clamped`() {
        val root = JSONObject(sample().toJson())
        val entries = root.getJSONArray("scales")
        entries.getJSONObject(0).remove("root")
        entries.put(JSONObject().put("name", "12-TET").put("bars", 1).put("beats", 0).put("root", 99999.0))

        val restored = patchFromJson(root.toString())!!
        assertEquals(listOf(0f, TUNE_RANGE), restored.scales.map { it.rootCents })
    }

    /**
     * Every older format is refused, not converted.
     *
     * Format 5 retired modules rather than renaming fields, and a 4 could only have been
     * walked up to it silently -- a patch built around a VCA an envelope opened comes back
     * as a filter fed by nothing, quieter than it was left, reporting success. The 1, 2
     * and 3 ladders ended at 4, so they could never complete either. What makes refusing
     * affordable is that PatchStore.load sets the file aside rather than letting the demo
     * patch overwrite it.
     *
     * 5 to 8 joined them at format 9, which is the first change since 5 that takes something
     * away: a "Group" is a type this build does not have, and Osc's and FM's knob lists lost
     * an envelope from the middle, so every index after the first moved.
     */
    @Test
    fun `every format older than this one is refused`() {
        (1..8).forEach { version ->
            val root = JSONObject(sample().toJson()).put("version", version)
            assertNull("format $version must not load", patchFromJson(root.toString()))
        }
        assertNotNull("and the current one still does", patchFromJson(sample().toJson()))
    }

    @Test
    fun `a patch has a name, saved and undone, and absent until it is given one`() {
        val patch = demoPatch()
        assertEquals("Patch", patch.title)
        assertTrue("nothing in the file until it is named", !patch.toJson().contains("\"name\":\"Patch\""))

        patch.name = "Rhythm study"
        val json = patch.toJson()
        assertEquals("Rhythm study", patchFromJson(json)!!.title)
        assertEquals("and the round trip is byte for byte", json, patchFromJson(json)!!.toJson())

        // Undo restores it through the model, like every other part of a patch.
        val before = demoPatch().toJson()
        patch.replaceWith(patchFromJson(before)!!)
        assertEquals("Patch", patch.title)
    }

    @Test
    fun `a new patch is empty, in the default tuning and tempo, and undoes back`() {
        val patch = demoPatch()
        patch.name = "Something"
        patch.tempo = 96f
        patch.scales = listOf(ScaleEntry(Scale.Chromatic, rootCents = 300f))
        val before = patch.toJson()

        patch.reset()
        assertTrue("nothing left but the rails", patch.free.isEmpty())
        assertTrue(patch.connections.isEmpty())
        assertEquals("Patch", patch.title)
        assertEquals(TEMPO.default, patch.tempo)
        assertEquals(0f, patch.scales.single().rootCents)
        assertEquals("and the rails are back to their defaults", 1f, patch.module(OUT_ID)!!.params[0])

        patch.replaceWith(patchFromJson(before)!!)
        assertEquals("undo brings the whole patch back", before, patch.toJson())
    }
}
