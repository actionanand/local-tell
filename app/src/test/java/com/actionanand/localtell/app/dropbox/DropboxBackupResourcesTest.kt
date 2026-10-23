package com.actionanand.localtell.app.dropbox

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class DropboxBackupResourcesTest {
    private fun strings(directory: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$directory/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    @Test fun dropboxResourcesExistInAllLanguagesWithMatchingFormatArguments() {
        val english = strings("values").filterKeys { it.startsWith("dropbox_") }
        val arguments = Regex("%\\d+\\$[ds]")
        for (directory in listOf("values-ta", "values-sa")) {
            val translated = strings(directory).filterKeys { it.startsWith("dropbox_") }
            assertEquals(english.keys, translated.keys)
            english.forEach { (name, text) ->
                assertEquals(name, arguments.findAll(text).map { it.value }.toSet(),
                    arguments.findAll(translated.getValue(name)).map { it.value }.toSet())
            }
        }
    }

    @Test fun englishApostrophesAreEscapedForAndroidAndBackupLocationIsAccurate() {
        val english = strings("values")
        english.filterKeys { it.startsWith("dropbox_") }.forEach { (name, value) ->
            assertFalse("Unescaped apostrophe in $name", Regex("(?<!\\\\)'").containsMatchIn(value))
        }
        for (directory in listOf("values", "values-ta", "values-sa")) {
            val localized = strings(directory)
            assertTrue(localized.getValue("dropbox_location").contains("Apps / LocalTell / localtell-managed-backups"))
            assertTrue(localized.getValue("dropbox_folder_scope").contains("Apps / AR Apps / LocalTell"))
            assertTrue(localized.getValue("backup_privacy").isNotBlank())
        }
        assertTrue(english.getValue("backup_privacy").contains("not encrypted"))
        assertEquals("Only backups created through LocalTell are managed automatically.", english.getValue("dropbox_retention"))
        assertFalse(english.getValue("dropbox_retention").contains("%"))
        assertTrue(english.getValue("dropbox_copy_diagnostic").isNotBlank())
    }
}
