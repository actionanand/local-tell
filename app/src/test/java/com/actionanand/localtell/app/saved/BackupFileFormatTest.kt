package com.actionanand.localtell.app.saved

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class BackupFileFormatTest {
    @Test fun `readable unique filenames always use bklt regardless of default language`() {
        val now = ZonedDateTime.parse("2026-10-18T20:30:00+05:30[Asia/Kolkata]")
        assertEquals("LocalTell-18-Oct-2026-08-30PM-fixed.bklt", BackupFileFormat.filename(now, "fixed"))
        assertNotEquals(BackupFileFormat.filename(now), BackupFileFormat.filename(now))
        BackupFileFormat.requireFilename(BackupFileFormat.filename(now))
    }

    @Test fun `only provider supplied bklt filenames are accepted`() {
        listOf(null, "", ".bklt", "backup.json", "backup.txt", "backup.zip", "backup.bklt.json",
            "backup.bklt ", "../backup.bklt", "dir\\backup.bklt", "bad\u0000.bklt").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { BackupFileFormat.requireFilename(name) }
        }
        BackupFileFormat.requireFilename("சேமிப்பு.bklt")
        BackupFileFormat.requireFilename("स्थानानि.BKLT")
    }

    @Test fun `valid bklt roundtrip preserves Unicode original coordinates and metadata`() {
        val place = SavedPlacesBackupCodec.decode(legacyBackupFixture().toString()).single()
        val bytes = SavedPlacesBackupCodec.encode(listOf(place)).toByteArray(Charsets.UTF_8)
        assertEquals(listOf(place), BackupFileFormat.decode("LocalTell.bklt", bytes))
        listOf("LocalTell.json", "LocalTell.txt", "LocalTell.zip", null).forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { BackupFileFormat.decode(name, bytes) }
        }
    }

    @Test fun `bklt file on disk roundtrips through the same validation boundary`() {
        val place = SavedPlacesBackupCodec.decode(legacyBackupFixture().toString()).single()
        val file = java.io.File.createTempFile("LocalTell-", ".bklt")
        try {
            file.writeText(SavedPlacesBackupCodec.encode(listOf(place)), Charsets.UTF_8)
            assertEquals(listOf(place), BackupFileFormat.decode(file.name, file.readBytes()))
        } finally { file.delete() }
    }

    @Test fun `renaming invalid files cannot bypass content UTF8 or schema validation`() {
        val invalid = listOf("not a backup", "PK\u0003\u0004", "{", "{}", "[]", "null",
            "{\"format\":\"other\",\"schemaVersion\":1}",
            legacyBackupFixture().put("schemaVersion", 999).toString())
        invalid.forEach { content ->
            assertThrows(Exception::class.java) { BackupFileFormat.decode("renamed.bklt", content.toByteArray(Charsets.UTF_8)) }
        }
        assertThrows(Exception::class.java) { BackupFileFormat.decode("utf8.bklt", byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(Exception::class.java) { BackupFileFormat.decode("large.bklt", ByteArray(SavedPlacesBackupCodec.MAX_BYTES + 1)) }
    }

    @Test fun `new backups detect changed payload and checksum corruption`() {
        val place = SavedPlacesBackupCodec.decode(legacyBackupFixture().toString()).single()
        val root = JSONObject(SavedPlacesBackupCodec.encode(listOf(place)))
        assertEquals(2, root.getInt("schemaVersion"))
        val changed = JSONObject(root.toString()).put("payload", root.getString("payload").replace("8.18", "8.19"))
        assertThrows(Exception::class.java) { BackupFileFormat.decode("changed.bklt", changed.toString().toByteArray()) }
        root.put("sha256", "0".repeat(64))
        assertThrows(Exception::class.java) { BackupFileFormat.decode("corrupted.bklt", root.toString().toByteArray()) }
    }

    @Test fun `legacy content remains readable through bklt and is never rewritten into json files`() {
        val fixture = legacyBackupFixture().toString().toByteArray(Charsets.UTF_8)
        assertEquals("fixture", BackupFileFormat.decode("legacy.bklt", fixture).single().id)
        assertThrows(Exception::class.java) { BackupFileFormat.decode("legacy.json", fixture) }
    }

    @Test fun `strict parser rejects duplicate keys trailing commas comments and excessive nesting`() {
        listOf("{\"x\":1,\"x\":2}", "{\"x\":1,\"\\u0078\":2}", "{\"x\":1,}", "[1,]", "{x:1}", "{'x':1}",
            "{/* comment */\"x\":1}", "{\"x\":01}", "{\"x\":NaN}", "{}{}",
            "\"\\uD800\"", "\"\\uDC00\"", "[".repeat(18) + "0" + "]".repeat(18)).forEach { input ->
            assertThrows(Exception::class.java) { StrictBackupJson(input).validate() }
        }
        StrictBackupJson("{\"places\":[true,false,null,-1.25e2,\"தமிழ் / संस्कृतम् / \\u0061\"]}").validate()
    }
}
