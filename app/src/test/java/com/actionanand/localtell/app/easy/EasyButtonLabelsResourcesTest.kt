package com.actionanand.localtell.app.easy

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class EasyButtonLabelsResourcesTest {
    private val root = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")

    private fun strings(directory: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "$directory/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test fun easyButtonLabelsUseRequestedTranslations() {
        val expected = mapOf(
            "values" to listOf("Open in Google Maps", "Copy Google Maps link", "Uber"),
            "values-ta" to listOf("கூகிள் வரைபடத்தில் திறக்கவும்", "கூகிள் வரைபட இணைப்பை நகலெடுக்கவும்", "உபெர்"),
            "values-sa" to listOf("गूगल् मानचित्रे उद्घाटयतु", "गूगल् मानचित्रस्य सङ्केतं प्रतिलिपीकरोतु", "ऊबर्"),
        )
        expected.forEach { (directory, labels) ->
            val values = strings(directory)
            assertEquals(directory, labels, listOf(values["easy_open_maps"], values["easy_copy_maps"], values["easy_uber_button"]))
        }
    }

    @Test fun sharedUberBrandAndDialogStringsStayUnchanged() {
        listOf("values", "values-ta", "values-sa").forEach { directory ->
            assertEquals(directory, "Uber", strings(directory)["easy_uber"])
        }
        assertEquals("Pickup location", strings("values")["easy_uber_pickup"])
        assertEquals("Destination", strings("values")["easy_uber_destination"])
        assertEquals("ஏறும் இடம்", strings("values-ta")["easy_uber_pickup"])
        assertEquals("गन्तव्यम्", strings("values-sa")["easy_uber_destination"])
    }
}
