package com.stephaneperez.notepad.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the CLAUDE.md string rules: every key exists in English, French and Spanish,
 * with the same format placeholders, and apostrophes are escaped (an unescaped `'`
 * breaks the AAPT build).
 */
class StringResourcesTest {

    // Gradle runs unit tests with the module directory (app/) as working directory.
    private val resDir = File("src/main/res")
    private val locales = listOf("values", "values-fr", "values-es")
    private val placeholder = Regex("%\\d+\\$[a-z]")

    /** Key ("name" or "name#quantity") -> text. */
    private fun load(locale: String): Map<String, String> {
        val file = File(resDir, "$locale/strings.xml")
        assertTrue("missing $file", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val entries = mutableMapOf<String, String>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as Element
            entries[e.getAttribute("name")] = e.textContent
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val p = plurals.item(i) as Element
            val items = p.getElementsByTagName("item")
            for (j in 0 until items.length) {
                val item = items.item(j) as Element
                entries["${p.getAttribute("name")}#${item.getAttribute("quantity")}"] = item.textContent
            }
        }
        return entries
    }

    @Test
    fun everyLocaleHasTheSameKeys() {
        val reference = load("values").keys
        for (locale in locales.drop(1)) {
            assertEquals("keys in $locale", reference.sorted(), load(locale).keys.sorted())
        }
    }

    @Test
    fun placeholdersMatchTheEnglishStrings() {
        val reference = load("values")
        for (locale in locales.drop(1)) {
            for ((key, text) in load(locale)) {
                assertEquals(
                    "placeholders of $key in $locale",
                    placeholder.findAll(reference.getValue(key)).map { it.value }.toSet(),
                    placeholder.findAll(text).map { it.value }.toSet(),
                )
            }
        }
    }

    @Test
    fun apostrophesAreEscaped() {
        for (locale in locales) {
            File(resDir, "$locale/strings.xml").readLines().forEachIndexed { index, line ->
                if (line.trimStart().startsWith("<?xml")) return@forEachIndexed
                assertTrue(
                    "unescaped apostrophe in $locale/strings.xml:${index + 1}: $line",
                    !Regex("(?<!\\\\)'").containsMatchIn(line),
                )
            }
        }
    }
}
