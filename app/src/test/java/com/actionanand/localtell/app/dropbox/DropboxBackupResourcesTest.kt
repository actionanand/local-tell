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
            assertTrue(localized.getValue("dropbox_location")
                .endsWith(DropboxBackupOwnership.MANAGED_DIRECTORY.removePrefix("/")))
            // The folder-help toggle and its Apps / AR Apps paragraph were removed from the Dropbox card.
            for (removed in listOf("dropbox_folder_scope", "dropbox_folder_help", "dropbox_hide_help")) {
                assertFalse("$removed must stay removed in $directory", localized.containsKey(removed))
            }
            assertTrue(localized.getValue("dropbox_retention").isNotBlank())
            assertTrue(localized.getValue("backup_privacy").isNotBlank())
        }
        assertTrue(english.getValue("backup_privacy").contains("not encrypted"))
        assertEquals("Only backups created through LocalTell are managed automatically.", english.getValue("dropbox_retention"))
        assertFalse(english.getValue("dropbox_retention").contains("%"))
        assertTrue(english.getValue("dropbox_copy_diagnostic").isNotBlank())
    }

    @Test fun restoreAndConflictDialogStringsExistInAllLanguages() {
        for (directory in listOf("values", "values-ta", "values-sa")) {
            val localized = strings(directory)
            for (name in listOf(
                "dropbox_restore_title", "dropbox_restore_message", "dropbox_restore_backup", "dropbox_last_restored",
                "dropbox_status_restore_available", "dropbox_use_local", "dropbox_merge_remote", "cancel",
            )) {
                assertTrue("$name missing in $directory", localized[name]?.isNotBlank() == true)
            }
            val raw = File("src/main/res/$directory/strings.xml").readText()
            for (name in listOf("dropbox_restore_title", "dropbox_restore_message", "dropbox_restore_backup")) {
                assertEquals("$name must be declared once in $directory", 1, Regex("name=\"$name\"").findAll(raw).count())
                assertFalse("$name contains markup in $directory", localized.getValue(name).contains('<'))
            }
            assertTrue(localized.getValue("dropbox_last_restored").contains("%1\$s"))
        }
        assertEquals("Restore backup", strings("values").getValue("dropbox_restore_backup"))
    }

    @Test fun dropboxReviewDialogsKeepActionsCompactAndVisible() {
        val source = File("src/main/java/com/actionanand/localtell/app/backup/BackupRestoreScreen.kt").readText()
        val start = source.indexOf("val reviewDialog = backupState.reviewDialog")
        assertTrue(start >= 0)
        val dialogs = source.substring(start, source.indexOf("private fun DropboxBackupFrequency.labelRes"))
        for (forbidden in listOf("weight(", "SpaceBetween", "Modifier.height(", ".height(", "heightIn(", "Spacer(")) {
            assertFalse("$forbidden must not be used in the Dropbox review dialogs", dialogs.contains(forbidden))
        }
        assertEquals(2, Regex("verticalScroll\\(rememberScrollState\\(\\)\\)").findAll(dialogs).count())
        val conflict = dialogs.substring(dialogs.indexOf("DropboxReviewDialog.CONFLICT"))
        // All three conflict actions, Cancel included, live in one 8 dp stack and nothing is left in dismissButton.
        assertFalse(conflict.contains("dismissButton"))
        val stack = conflict.substring(conflict.indexOf("Arrangement.spacedBy(8.dp)"))
        assertEquals(3, Regex("TextButton\\(").findAll(stack).count())
        assertTrue(stack.contains("R.string.cancel"))
    }
}
