package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DropboxVerifiedUploadTest {
    private val data = AppBackupData(emptyList(), emptyList())

    @Test fun fourthAndFifthVerifiedUploadsKeepExactlyLatestThreeAndNeverDeleteManualFiles() {
        val remote = FakeRemote()
        val ownership = FakeOwnership()
        val manual = file(0).copy(id = "id:manual")
        remote.files += manual
        repeat(5) {
            assertTrue(DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, remote.files.toList()) is DropboxUploadResult.Verified)
        }
        assertEquals(setOf(manual.id, "id:3", "id:4", "id:5"), remote.files.map { it.id }.toSet())
        assertEquals(listOf("id:1", "id:2"), remote.deleted.map { it.id })
    }

    @Test fun uploadFailureNeverTriggersVerificationOrCleanup() {
        val remote = FakeRemote().apply { failUpload = true }
        assertThrows(IOException::class.java) {
            DropboxVerifiedUpload(remote, FakeOwnership()) {}.execute("A", data, emptyList())
        }
        assertEquals(0, remote.downloads)
        assertTrue(remote.deleted.isEmpty())
    }

    @Test fun verificationFailureNeverRecordsOwnershipOrDeletesAnything() {
        val remote = FakeRemote().apply { failDownload = true }
        val ownership = FakeOwnership()
        assertThrows(DropboxBackupValidationException::class.java) {
            DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, emptyList())
        }
        assertTrue(ownership.records.isEmpty())
        assertTrue(remote.deleted.isEmpty())
    }

    @Test fun mismatchedDownloadedContentsNeverTriggerCleanup() {
        val remote = FakeRemote().apply {
            downloadedData = AppBackupData(emptyList(), listOf(
                com.actionanand.localtell.app.journey.JourneyPoint(
                    id = 0, timestamp = 1_700_000_000_000, areaName = "Different",
                    district = null, state = null, radio = "NR", plmn = "405-869", cellId = 1, confidence = 100,
                ),
            ))
        }
        val ownership = FakeOwnership()
        assertThrows(IllegalArgumentException::class.java) {
            DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, emptyList())
        }
        assertTrue(ownership.records.isEmpty())
        assertTrue(remote.deleted.isEmpty())
    }

    @Test fun preUploadConflictStopsUploadAndCleanup() {
        val remote = FakeRemote().apply { files += file(9) }
        val result = DropboxVerifiedUpload(remote, FakeOwnership()) {}.execute("A", data, emptyList())
        assertTrue(result is DropboxUploadResult.Conflict)
        assertEquals(0, remote.uploads)
        assertTrue(remote.deleted.isEmpty())
    }

    @Test fun concurrentDeviceChangeDuringUploadPreservesConflictWithoutCleanup() {
        val remote = FakeRemote().apply { duringUpload = { files += file(9) } }
        val result = DropboxVerifiedUpload(remote, FakeOwnership()) {}.execute("A", data, emptyList())
        assertTrue(result is DropboxUploadResult.Conflict)
        assertEquals("id:1", (result as DropboxUploadResult.Conflict).ignoredUploadId)
        assertTrue(result.files.any { it.id == "id:9" })
        assertTrue(remote.deleted.isEmpty())
    }

    @Test fun disconnectOrCancellationDuringUploadStopsVerificationAndDeletion() {
        var active = true
        val remote = FakeRemote().apply { duringUpload = { active = false } }
        assertThrows(CancellationException::class.java) {
            DropboxVerifiedUpload(remote, FakeOwnership()) {
                if (!active) throw CancellationException("Disconnected")
            }.execute("A", data, emptyList())
        }
        assertEquals(0, remote.downloads)
        assertTrue(remote.deleted.isEmpty())
    }

    @Test fun corruptOwnershipLedgerPreservesAllFilesAndReportsStorageError() {
        val remote = FakeRemote()
        val records = FakeOwnership().apply { invalid = true }
        assertThrows(IllegalStateException::class.java) {
            DropboxVerifiedUpload(remote, records) {}.execute("A", data, emptyList())
        }
        assertTrue(remote.deleted.isEmpty())
    }

    @Test fun ownershipCodecRejectsMissingMalformedOrInconsistentRecords() {
        assertNull(DropboxBackupOwnership.decode("not json"))
        assertNull(DropboxBackupOwnership.decode("[{}]"))
        val records = listOf(DropboxOwnedBackup(file(1)))
        assertEquals(records, DropboxBackupOwnership.decode(DropboxBackupOwnership.encode(records)))
        assertNull(DropboxBackupOwnership.decode(DropboxBackupOwnership.encode(records).replace("revision-1", "revision-2")))
        assertNull(DropboxBackupOwnership.decode(DropboxBackupOwnership.encode(records + records)))
        assertNull(DropboxBackupOwnership.decode(DropboxBackupOwnership.encode(listOf(DropboxOwnedBackup(file(1).copy(path = "/legacy.bklt"))))))
    }

    private inner class FakeRemote : DropboxBackupRemote {
        val files = mutableListOf<DropboxRemoteFile>()
        val deleted = mutableListOf<DropboxRemoteFile>()
        var uploads = 0
        var downloads = 0
        var failUpload = false
        var failDownload = false
        var downloadedData = data
        var duringUpload: () -> Unit = {}
        override fun snapshots() = files.toList()
        override fun download(file: DropboxRemoteFile): AppBackupData {
            downloads++
            if (failDownload) throw DropboxBackupValidationException("Invalid or oversized remote backup")
            return downloadedData
        }
        override fun upload(data: AppBackupData): DropboxRemoteFile {
            if (failUpload) throw IOException("Upload interrupted")
            val uploaded = file(++uploads)
            files += uploaded
            duringUpload()
            return uploaded
        }
        override fun deleteOwned(file: DropboxRemoteFile) {
            deleted += file
            files.remove(file)
        }
    }

    private class FakeOwnership : DropboxOwnershipRecords {
        val records = mutableListOf<DropboxOwnedBackup>()
        var invalid = false
        override fun read(account: String): List<DropboxOwnedBackup>? = if (invalid) null else records.toList()
        override fun record(account: String, file: DropboxRemoteFile): Boolean {
            if (invalid) return false
            records += DropboxOwnedBackup(file)
            return true
        }
        override fun forget(account: String, id: String): Boolean {
            records.removeAll { it.file.id == id }
            return true
        }
    }

    private fun file(index: Int) = DropboxRemoteFile(
        "/localtell-managed-backups/localtell-$index.bklt", "LocalTell-$index.bklt", index.toLong(),
        "id:$index", "revision-$index", AppBackupCodec.encode(data).toByteArray().size.toLong(), "a".repeat(64),
    )
}
