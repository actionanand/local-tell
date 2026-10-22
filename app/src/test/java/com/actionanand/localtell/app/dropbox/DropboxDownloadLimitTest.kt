package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData
import com.actionanand.localtell.app.saved.BackupFileFormat
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException

class DropboxDownloadLimitTest {
    @Test fun rejectsOversizedOrNegativeMetadataBeforeAnyWrite() {
        assertThrows(DropboxBackupValidationException::class.java) { DropboxDownloadLimit(AppBackupCodec.MAX_BYTES + 1L) }
        assertThrows(DropboxBackupValidationException::class.java) { DropboxDownloadLimit(-1) }
    }

    @Test fun boundedBodyRejectsLyingMetadataAndSingleByteOverflow() {
        val output = DropboxDownloadLimit(0, limit = 4)
        output.write(byteArrayOf(1, 2, 3, 4))
        assertThrows(DropboxBackupValidationException::class.java) { output.write(5) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.bytes())
    }

    @Test fun boundedBodyRejectsOversizedChunkBeforeAllocatingOrAppendingIt() {
        val output = DropboxDownloadLimit(1, limit = 4)
        output.write(byteArrayOf(1, 2), 0, 2)
        assertThrows(DropboxBackupValidationException::class.java) { output.write(byteArrayOf(3, 4, 5), 0, 3) }
        assertEquals(2, output.bytes().size)
    }

    @Test fun exactMaximumIsAcceptedButNextChunkIsRejected() {
        val output = DropboxDownloadLimit(AppBackupCodec.MAX_BYTES.toLong())
        val chunk = ByteArray(4096)
        repeat(AppBackupCodec.MAX_BYTES / chunk.size) { output.write(chunk) }
        assertEquals(AppBackupCodec.MAX_BYTES, output.bytes().size)
        assertThrows(DropboxBackupValidationException::class.java) { output.write(chunk) }
    }

    @Test fun disconnectDuringDownloadStopsBeforeAppendingMoreBytes() {
        var active = true
        val output = DropboxDownloadLimit(4, checkActive = {
            if (!active) throw CancellationException("Disconnected")
        })
        output.write(1)
        active = false
        assertThrows(CancellationException::class.java) { output.write(byteArrayOf(2, 3)) }
        assertEquals(1, output.bytes().size)
    }

    @Test fun validDownloadedBodyStillUsesExistingCodecAndFilenameValidation() {
        val data = AppBackupData(emptyList(), emptyList())
        val bytes = AppBackupCodec.encode(data).toByteArray()
        val output = DropboxDownloadLimit(bytes.size.toLong())
        output.write(bytes)
        assertEquals(data, BackupFileFormat.decode("LocalTell-test.bklt", output.bytes()))
        assertThrows(IllegalArgumentException::class.java) { BackupFileFormat.decode("wrong.txt", output.bytes()) }
        assertThrows(Exception::class.java) { BackupFileFormat.decode("LocalTell-test.bklt", "not json".toByteArray()) }
        val corrupted = AppBackupCodec.encode(data).replace("\"sha256\":\"", "\"sha256\":\"bad")
        assertThrows(Exception::class.java) { BackupFileFormat.decode("LocalTell-test.bklt", corrupted.toByteArray()) }
        assertThrows(Exception::class.java) { BackupFileFormat.decode("LocalTell-test.bklt", byteArrayOf(0xff.toByte())) }
    }
}
