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

    /**
     * Format 1 had a Clock module where format 2 has a tempo. Nothing saved in format 1
     * was worth keeping, but a working upgrade is the pattern the next format change
     * copies -- and without it every older file silently became the demo patch.
     */
    @Test
    fun `a format 1 file takes its tempo from its Clock and drops the Clock`() {
        val root = JSONObject(sample().toJson()).put("version", 1)
        root.remove("tempo")
        val steps = sample().free.first { it.type.stepCount > 0 }
        root.getJSONArray("modules").put(
            JSONObject()
                .put("id", 500L)
                .put("type", "Clock")
                .put("x", 0.0)
                .put("y", 0.0)
                .put("params", JSONObject().put("bpm", 90.0)),
        )
        root.getJSONArray("connections").put(
            JSONObject().put("from", 500L).put("fromPort", 0).put("to", steps.id).put("toPort", 0),
        )

        val restored = patchFromJson(root.toString())
        assertNotNull("a format 1 file must still load", restored)
        assertEquals(90f, restored!!.tempo, 0.0001f)
        assertNull("the Clock is gone", restored.module(500L))
        assertTrue("and so is its cable", restored.connections.none { it.from.moduleId == 500L })
        assertEquals(sample().free.size, restored.free.size)
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

    /** Format 2 had one scale where format 3 has a list; the scale there was becomes the only entry. */
    @Test
    fun `a format 2 file's scale becomes a list of one`() {
        val library = ScaleLibrary.of(java.io.File("src/main/assets/scales"))
        val root = JSONObject(sample().toJson()).put("version", 2).put("scale", "Major")
        root.remove("scales")

        val restored = patchFromJson(root.toString(), library)!!
        assertEquals(listOf("Major"), restored.scales.map { it.scale.name })
    }

    @Test
    fun `a format 1 file with no Clock loads at the default tempo`() {
        val root = JSONObject(sample().toJson()).put("version", 1)
        root.remove("tempo")
        assertEquals(TEMPO.default, patchFromJson(root.toString())!!.tempo, 0.0001f)
    }
}
