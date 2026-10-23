package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class DropboxVerifiedUploadTest {
    private val data = AppBackupData(emptyList(), emptyList())

    @Test fun firstVerifiedUploadCreatesMissingManagedFolder() {
        val remote = FakeRemote().apply { folderExists = false }
        assertTrue(remote.snapshots().isEmpty())
        assertTrue(DropboxVerifiedUpload(remote, FakeOwnership()) {}
            .execute("A", data, emptyList()) is DropboxUploadResult.Verified)
        assertEquals(1, remote.folderCreations)
        assertEquals(1, remote.uploads)
        assertEquals(1, remote.downloads)
    }

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
        assertEquals(0, remote.uploads)
    }

    @Test fun failedVerificationResumesTheSameUploadInsteadOfUploadingAgain() {
        val remote = FakeRemote().apply { failDownload = true }
        val ownership = FakeOwnership()
        assertThrows(DropboxBackupValidationException::class.java) {
            DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, emptyList())
        }
        assertNotNull(ownership.pending("A"))
        remote.failDownload = false
        assertTrue(DropboxVerifiedUpload(remote, ownership) {}
            .execute("A", data, remote.files.toList()) is DropboxUploadResult.Verified)
        assertEquals(1, remote.uploads)
        assertNull(ownership.pending("A"))
    }

    @Test fun interruptedOrChangedVerificationDownloadResumesWithoutDuplicateUpload() {
        val failures = listOf<() -> Exception>(
            { com.dropbox.core.NetworkIOException(IOException("stream reset")) },
            { DropboxDownloadMismatchException("Dropbox download metadata changed") },
            { com.dropbox.core.BadRequestException("req", "rejected") },
        )
        for (failure in failures) {
            val remote = FakeRemote().apply { verifyError = failure }
            val ownership = FakeOwnership()
            val thrown = assertThrows(Exception::class.java) {
                DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, emptyList())
            }
            assertEquals(failure().javaClass, thrown.javaClass)
            assertNotNull(ownership.pending("A"))
            assertTrue(ownership.records.isEmpty())
            assertTrue(remote.deleted.isEmpty())
            remote.verifyError = null
            assertTrue(DropboxVerifiedUpload(remote, ownership) {}
                .execute("A", data, remote.files.toList()) is DropboxUploadResult.Verified)
            assertEquals(1, remote.uploads)
            assertEquals(1, remote.files.size)
            assertNull(ownership.pending("A"))
        }
    }

    @Test fun failedOwnershipWriteRecoversWithoutASecondUpload() {
        val remote = FakeRemote()
        val ownership = FakeOwnership().apply { failRecord = true }
        assertThrows(IllegalStateException::class.java) {
            DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, emptyList())
        }
        assertTrue(remote.deleted.isEmpty())
        ownership.failRecord = false
        assertTrue(DropboxVerifiedUpload(remote, ownership) {}
            .execute("A", data, remote.files.toList()) is DropboxUploadResult.Verified)
        assertEquals(1, remote.uploads)
    }

    @Test fun replacementOfPendingUploadCannotBeReportedAsSuccessfulOrDeleted() {
        val remote = FakeRemote().apply { failDownload = true }
        val ownership = FakeOwnership()
        assertThrows(DropboxBackupValidationException::class.java) {
            DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, emptyList())
        }
        remote.failDownload = false
        remote.files[0] = remote.files[0].copy(revision = "changed")
        assertTrue(DropboxVerifiedUpload(remote, ownership) {}
            .execute("A", data, remote.files.toList()) is DropboxUploadResult.Conflict)
        assertEquals(1, remote.uploads)
        assertTrue(remote.deleted.isEmpty())
        assertNull(ownership.pending("A"))
    }

    @Test fun failedCleanupResumesWithoutDuplicatingTheUpload() {
        val remote = FakeRemote()
        val ownership = FakeOwnership()
        repeat(3) { DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, remote.files.toList()) }
        remote.failDelete = true
        assertThrows(IOException::class.java) {
            DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, remote.files.toList())
        }
        assertEquals(4, remote.uploads)
        remote.failDelete = false
        assertTrue(DropboxVerifiedUpload(remote, ownership) {}
            .execute("A", data, remote.files.toList()) is DropboxUploadResult.Verified)
        assertEquals(4, remote.uploads)
        assertEquals(3, remote.files.size)
    }

    @Test fun otherDeviceBackupsStayVisibleAsSyncBaseline() {
        val remote = FakeRemote()
        val otherDevice = file(7)
        remote.files += otherDevice
        val baseline = selectRemoteBaseline(remote.snapshots(), null, emptyList(), remote::download)
        assertEquals(otherDevice, baseline.file)
        assertNotNull(baseline.data)
    }

    @Test fun malformedUnknownFileIsSkippedAndPreservedButNotBlockingSync() {
        val remote = FakeRemote()
        // Distinct from the IDs FakeRemote assigns to new uploads.
        val valid = file(20)
        val malformed = file(25)
        remote.files += listOf(valid, malformed)
        remote.malformedIds = setOf(malformed.id)
        val baseline = selectRemoteBaseline(remote.snapshots(), null, emptyList(), remote::download)
        assertEquals(valid, baseline.file)
        val onlyMalformed = selectRemoteBaseline(listOf(malformed), null, emptyList(), remote::download)
        assertNull(onlyMalformed.file)
        assertTrue(DropboxVerifiedUpload(remote, FakeOwnership()) {}
            .execute("A", data, remote.snapshots()) is DropboxUploadResult.Verified)
        assertTrue(remote.files.contains(malformed))
        assertTrue(remote.deleted.isEmpty())
    }

    @Test fun malformedOwnedBackupIsReportedInsteadOfSkipped() {
        val remote = FakeRemote()
        val owned = file(5)
        remote.files += listOf(file(1), owned)
        remote.malformedIds = setOf(owned.id)
        assertThrows(DropboxBackupValidationException::class.java) {
            selectRemoteBaseline(remote.snapshots(), null, listOf(DropboxOwnedBackup(owned)), remote::download)
        }
    }

    @Test fun transportFailuresAreNeverMistakenForMalformedFiles() {
        val failure = com.dropbox.core.NetworkIOException(IOException("offline"))
        assertSame(failure, assertThrows(com.dropbox.core.NetworkIOException::class.java) {
            selectRemoteBaseline(listOf(file(1)), null, emptyList()) { throw failure }
        })
    }

    @Test fun conflictPathIsPreferredOverNewerFiles() {
        val remote = FakeRemote()
        remote.files += listOf(file(1), file(9))
        assertEquals(file(1), selectRemoteBaseline(remote.snapshots(), file(1).path, emptyList(), remote::download).file)
        assertEquals(file(9), selectRemoteBaseline(remote.snapshots(), null, emptyList(), remote::download).file)
    }

    @Test fun modifiedKnownFileNeverAuthorizesDeletion() {
        val original = file(1)
        val changed = original.copy(revision = "manually-replaced")
        assertFalse(DropboxOwnedBackup(original).matches(changed))
        assertTrue(DropboxBackupRetention.obsolete(listOf(changed, file(2), file(3), file(4), file(5)),
            listOf(DropboxOwnedBackup(original)), file(5)).none { it.id == original.id })
    }

    @Test fun newlyArrivingDeviceBackupStillStopsManagedUploadCleanup() {
        val remote = FakeRemote().apply { duringUpload = { files += file(99) } }
        val ownership = FakeOwnership()
        val result = DropboxVerifiedUpload(remote, ownership) {}.execute("A", data, remote.snapshots())
        assertTrue(result is DropboxUploadResult.Conflict)
        assertTrue(remote.deleted.isEmpty())
        assertEquals(1, ownership.records.size)
        assertEquals("id:1", ownership.records.single().file.id)
        assertNull(ownership.pending("A"))
    }

    @Test fun identicalManualOrOtherDeviceFileCannotFakeASuccessfulManagedSync() {
        val manual = file(7)
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL,
            managedSyncDecision(DropboxSyncDecision.UP_TO_DATE, manual, emptyList()))
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL,
            managedSyncDecision(DropboxSyncDecision.UP_TO_DATE, manual.copy(revision = "changed"), listOf(DropboxOwnedBackup(manual))))
        assertEquals(DropboxSyncDecision.UP_TO_DATE,
            managedSyncDecision(DropboxSyncDecision.UP_TO_DATE, manual, listOf(DropboxOwnedBackup(manual))))
        // Conflicts are never downgraded into uploads.
        assertEquals(DropboxSyncDecision.CONFLICT,
            managedSyncDecision(DropboxSyncDecision.CONFLICT, manual, emptyList()))
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
        var failDelete = false
        var malformedIds = emptySet<String>()
        var folderExists = true
        var folderCreations = 0
        private val folder = DropboxBackupFolder(
            isFolder = {
                if (!folderExists) throw com.dropbox.core.v2.files.GetMetadataErrorException(
                    "metadata", "req", null,
                    com.dropbox.core.v2.files.GetMetadataError.path(com.dropbox.core.v2.files.LookupError.NOT_FOUND),
                )
                true
            },
            create = { folderExists = true; folderCreations++ },
        )
        var downloadedData = data
        var duringUpload: () -> Unit = {}
        override fun snapshots() = folder.discover {
            if (!folderExists) throw com.dropbox.core.v2.files.ListFolderErrorException(
                "list", "req", null,
                com.dropbox.core.v2.files.ListFolderError.path(com.dropbox.core.v2.files.LookupError.NOT_FOUND),
            )
            files.toList()
        }
        override fun download(file: DropboxRemoteFile): AppBackupData {
            downloads++
            if (failDownload || file.id in malformedIds) throw DropboxBackupValidationException("Invalid or oversized remote backup")
            return downloadedData
        }
        var verifyError: (() -> Exception)? = null
        override fun verify(file: DropboxRemoteFile): AppBackupData {
            verifyError?.let { throw it() }
            return download(file)
        }
        override fun upload(data: AppBackupData): DropboxRemoteFile {
            if (failUpload) throw IOException("Upload interrupted")
            folder.prepare()
            val uploaded = file(++uploads)
            files += uploaded
            duringUpload()
            return uploaded
        }
        override fun deleteOwned(file: DropboxRemoteFile) {
            if (failDelete) throw IOException("Cleanup interrupted")
            deleted += file
            files.remove(file)
        }
    }

    private class FakeOwnership : DropboxOwnershipRecords {
        val records = mutableListOf<DropboxOwnedBackup>()
        var invalid = false
        var failRecord = false
        private var pendingUpload: DropboxPendingUpload? = null
        override fun pending(account: String): DropboxPendingUpload? = pendingUpload
        override fun savePending(account: String, upload: DropboxPendingUpload): Boolean {
            pendingUpload = upload
            return true
        }
        override fun clearPending(account: String): Boolean {
            pendingUpload = null
            return true
        }
        override fun read(account: String): List<DropboxOwnedBackup>? = if (invalid) null else records.toList()
        override fun record(account: String, file: DropboxRemoteFile): Boolean {
            if (invalid || failRecord) return false
            records.removeAll { it.file.id == file.id }
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
