package io.github.forrcaho.patchgarden

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notices the app carries, and the page that shows them.
 *
 * The licenses of what the app bundles ask for their notices to travel with every copy. The
 * notices file is assembled by hand, which is exactly how a library vendored next month would
 * ship without its own: so every vendored library's LICENSE is asserted to be in it, word for
 * word, rather than trusted to have been pasted in.
 */
class NoticesTest {

    private val notices = File("src/main/assets/$NOTICES_ASSET")

    /** Line breaks and indentation aside, since the file is laid out for reading. */
    private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun contains(haystack: List<String>, needle: List<String>): Boolean =
        (0..haystack.size - needle.size).any { i -> haystack.subList(i, i + needle.size) == needle }

    @Test
    fun `every vendored library's license is in the notices, whole`() {
        val carried = words(notices.readText())
        val vendored = File("src/main/cpp/vendor").listFiles { f -> f.isDirectory }.orEmpty()
        assertTrue("there is vendored code to check", vendored.isNotEmpty())
        vendored.forEach { dir ->
            val license = File(dir, "LICENSE")
            assertTrue("${dir.name} carries a LICENSE", license.isFile)
            assertTrue(
                "${dir.name}'s LICENSE is in $NOTICES_ASSET",
                contains(carried, words(license.readText())),
            )
        }
    }

    /** Oboe, AndroidX and the Kotlin libraries: one copy of the license covers them all. */
    @Test
    fun `the Apache license is carried whole for what the build pulls in`() {
        val text = notices.readText()
        listOf("Oboe", "AndroidX", "Kotlin").forEach {
            assertTrue("$it is named", text.contains(it))
        }
        assertTrue(text.contains("Apache License"))
        assertTrue("the full text, not a pointer to it", text.contains("END OF TERMS AND CONDITIONS"))
    }

    /** Reachable from the app: the last tile of the menu that speaks for the whole patch. */
    @Test
    fun `the canvas menu ends with the licenses`() {
        val empty = menuItems(Patch(), null)
        assertEquals(MenuItem.Licenses, empty.last())
        val full = menuItems(Patch().apply { add(Types.Osc, Offset.Zero) }, null)
        assertEquals(MenuItem.Licenses, full.last())
        assertTrue("and nowhere a module's menu is", MenuItem.Licenses !in menuItems(Patch().apply { add(Types.Osc, Offset.Zero) }, 100L))
    }

    /**
     * Reflowed for the screen: at font scale 1.5 the file as written wrapped a second time,
     * stranding words on lines of their own and breaking every rule of `=` in two.
     */
    @Test
    fun `the page reflows the notices into headings and whole paragraphs`() {
        val blocks = reflowNotices(notices.readText())
        assertTrue("no rule survives onto the page", blocks.none { it.text.contains("=====") })
        assertTrue("each section is a heading", blocks.count { it.heading } >= 4)
        val grant = blocks.first { it.text.startsWith("Permission is hereby granted") }
        assertTrue("an MIT paragraph is one line to wrap, not ten", '\n' !in grant.text)
        // Structure where the indent changes: the component list keeps its lines.
        val oboe = blocks.first { it.text.contains("Oboe (com.google.oboe:oboe)") }
        assertTrue("an indented line under Oboe keeps its own line", "\n" in oboe.text)
        // And nothing is lost: the words in order are the file's, rules aside.
        assertEquals(
            words(notices.readText()).filterNot { w -> w.all { it == '=' } },
            words(blocks.joinToString(" ") { it.text }),
        )
    }
}
