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
        val restored = patchFromJson(sample().toJson())!!
        val toOut = restored.connections.filter { it.to.moduleId == OUT_ID }
        assertEquals(1, toOut.size)
        assertNotNull(restored.module(toOut.single().from.moduleId))
    }

    @Test
    fun `reload does not duplicate the rails`() {
        val restored = patchFromJson(sample().toJson())!!
        assertEquals(2, restored.pinned.size)
        assertEquals(1, restored.modules.count { it.id == OUT_ID })
        assertEquals(1, restored.modules.count { it.id == IN_ID })
    }

    @Test
    fun `enabled input round trips`() {
        val p = sample().apply { inputEnabled = true }
        assertTrue(patchFromJson(p.toJson())!!.inputEnabled)
        assertTrue(!patchFromJson(sample().toJson())!!.inputEnabled)
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
}
