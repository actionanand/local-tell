package com.actionanand.localtell.app.saved

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData

/** The same boundary is used for local documents, cloud documents and external intents. */
object BackupFileFormat {
    const val MIME_TYPE = "application/octet-stream"

    fun filename(now: ZonedDateTime = ZonedDateTime.now(), uniqueId: String = UUID.randomUUID().toString()): String {
        require(uniqueId.matches(Regex("[A-Za-z0-9-]{1,100}")))
        val timestamp = now.format(DateTimeFormatter.ofPattern("dd-MMM-yyyy-hh-mma", Locale.ENGLISH))
        return "LocalTell-$timestamp-$uniqueId.bklt"
    }

    fun requireFilename(name: String?) {
        require(name != null && name.length in 6..255 && name.endsWith(".bklt", ignoreCase = true)) {
            "Expected a .bklt backup filename"
        }
        require(name.none { it == '/' || it == '\\' || it.isISOControl() })
    }

    fun decode(name: String?, bytes: ByteArray): AppBackupData {
        requireFilename(name)
        require(bytes.size <= AppBackupCodec.MAX_BYTES)
        val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return AppBackupCodec.decode(utf8.decode(ByteBuffer.wrap(bytes)).toString())
    }
}
