package com.actionanand.localtell.app.saved

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class SavedPlacesResourcesTest {
    @Test fun `all bookmark labels are unique and translated with matching placeholders`() {
        val root = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        fun strings(directory: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(File(root, "$directory/strings.xml"))
            val nodes = doc.getElementsByTagName("string")
            val pairs = (0 until nodes.length).map { i ->
                val node = nodes.item(i)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            assertEquals("Duplicate resources in $directory", pairs.size, pairs.map { it.first }.distinct().size)
            return pairs.filter { it.first.startsWith("saved_") }.toMap()
        }
        val english = strings("values")
        assertTrue(english.isNotEmpty())
        val placeholders = Regex("%(?:[0-9]+\\$)?(?:[0-9.]+)?[sdf]")
        listOf("values-ta", "values-sa").forEach { directory ->
            val translated = strings(directory)
            assertEquals(english.keys, translated.keys)
            english.forEach { (key, value) ->
                assertTrue(requireNotNull(translated[key]).isNotBlank())
                assertEquals(key, placeholders.findAll(value).map { it.value }.sorted().toList(),
                    placeholders.findAll(requireNotNull(translated[key])).map { it.value }.sorted().toList())
            }
        }
    }
}
