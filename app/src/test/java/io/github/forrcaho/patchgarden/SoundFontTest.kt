package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SF module's page of instruments and what the file keeps of it. The sound is the
 * node tests'; this is the geometry a finger lands on and the name a patch remembers.
 */
class SoundFontTest {

    private val frame = Frame(
        canvas = Size(2404f, 1080f),
        density = 2.4375f,
        insetLeft = 160f,
        insetTop = 54f,
        insetRight = 0f,
        insetBottom = 58f,
    )
    private val d = frame.density
    private val panel = panelRect(frame)

    /** GeneralUser GS's count, near enough: what the page has to hold. */
    private val count = 274

    @Test
    fun `the page scrolls through every preset, and each is on it once`() {
        val page = presetPage(panel, d, fontCount = 1)
        assertTrue("several columns on the reference device", page.columns >= 3)
        assertTrue("and several rows", page.rows >= 4)

        val seen = mutableListOf<Int>()
        for (scroll in 0..page.maxScroll(count)) {
            val tiles = page.tiles(count, scroll)
            tiles.forEach { (_, rect) ->
                assertTrue("a tile inside the page", page.area.contains(rect.topLeft) && rect.right <= page.area.right + 0.5f && rect.bottom <= page.area.bottom + 0.5f)
            }
            tiles.forEachIndexed { i, (_, a) ->
                tiles.drop(i + 1).forEach { (_, b) -> assertTrue("tiles overlap", !a.overlaps(b)) }
            }
            // Each scroll shows one more row of what comes next.
            seen += tiles.map { it.first }
        }
        assertEquals("the last preset is reachable", count - 1, seen.max())
        assertEquals("and so is every other", (0 until count).toSet(), seen.toSet())
        assertEquals("scrolling past the end shows the end", page.tiles(count, page.maxScroll(count)), page.tiles(count, 10_000))
    }

    @Test
    fun `at the reference device's text size the tiles grow and still fit`() {
        val page = presetPage(panel, d, fontCount = 2, fontScale = 1.5f)
        assertTrue("taller for the text", page.tileH >= 1.5f * PRESET_TILE_H * d)
        assertTrue("still rows to choose from", page.rows >= 3)
        page.tiles(count, 0).forEach { (_, rect) -> assertTrue(rect.bottom <= page.area.bottom + 0.5f) }
    }

    @Test
    fun `opening the page shows the chosen preset`() {
        val page = presetPage(panel, d, fontCount = 1)
        listOf(0, 40, 200, count - 1).forEach { chosen ->
            val shown = page.tiles(count, page.scrollTo(chosen, count)).map { it.first }
            assertTrue("preset $chosen is on the page it opens at", chosen in shown)
        }
    }

    @Test
    fun `a page asked for the chosen preset finds it once the list is there`() {
        val page = presetPage(panel, d, fontCount = 1)
        val presets = (0 until count).map { SoundFontPreset(it / 128, it % 128, "p$it") }
        val chosen = presets[200]
        val scroll = page.resolve(SCROLL_TO_CHOSEN, presets, chosen.code)
        assertTrue(200 in page.tiles(count, scroll).map { it.first })
        assertEquals("with no list yet, the top", 0, page.resolve(SCROLL_TO_CHOSEN, emptyList(), chosen.code))
        assertEquals("a scroll that is a number is that number", 3, page.resolve(3, presets, chosen.code))
    }

    @Test
    fun `the banks there are sit above the presets`() {
        // One is still a choice to make: a new SF has no bank until one is picked, since
        // none ship with the app.
        val none = presetPage(panel, d, fontCount = 0)
        val one = presetPage(panel, d, fontCount = 1)
        val two = presetPage(panel, d, fontCount = 2)
        assertTrue("nothing to choose from, no strip", none.fonts.isEmpty())
        assertEquals(1, one.fonts.size)
        assertEquals(2, two.fonts.size)
        two.fonts.forEach { strip -> assertTrue("above the tiles", strip.bottom <= two.area.top) }
        assertTrue("the chip clears the panel's title", panelPresetChip(panel, d).left > panel.center.x)
    }

    @Test
    fun `the font round-trips through the file, and an SF without one has none`() {
        val patch = Patch()
        val sf = patch.add(Types.Sf, Offset.Zero)!!
        sf.font = "My Bank"
        sf.setParam(SF_PRESET, presetCode(0, 81).toFloat())
        val back = patchFromJson(patch.toJson())!!
        val again = back.modules.first { it.type == Types.Sf }
        assertEquals("My Bank", again.font)
        assertEquals(presetCode(0, 81).toFloat(), again.params[SF_PRESET])

        // A file that names no bank leaves the module asking for one.
        val bare = org.json.JSONObject(patch.toJson()).apply {
            val modules = getJSONArray("modules")
            for (i in 0 until modules.length()) modules.getJSONObject(i).remove("font")
        }.toString()
        assertTrue("the edit found the field", !bare.contains("My Bank"))
        assertNull(patchFromJson(bare)!!.modules.first { it.type == Types.Sf }.font)

        // Nothing else carries one.
        assertTrue(back.modules.filter { it.type != Types.Sf }.all { it.font == null })
    }

    @Test
    fun `a duplicate plays the same font`() {
        val patch = Patch()
        val sf = patch.add(Types.Sf, Offset.Zero)!!
        sf.font = "My Bank"
        assertEquals("My Bank", patch.duplicate(sf)!!.font)
    }

    @Test
    fun `presets are told apart the way GM charts number them`() {
        assertEquals("1", presetDetail(SoundFontPreset(0, 0, "Grand Piano")))
        assertEquals("kit 1", presetDetail(SoundFontPreset(128, 0, "Standard")))
        assertEquals("82 · bank 8", presetDetail(SoundFontPreset(8, 81, "Saw 2")))
    }
}
