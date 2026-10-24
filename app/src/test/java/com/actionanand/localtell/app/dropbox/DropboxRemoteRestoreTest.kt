package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData
import com.actionanand.localtell.app.journey.JourneyPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DropboxRemoteRestoreTest {
    private val empty = AppBackupData(emptyList(), emptyList())
    private val remoteData = data("Remote")
    private val remoteFile = file(1)

    @Test fun freshInstallRestoresWithoutUploadDeletionOrOwnership() = runBlocking {
        val remote = FakeRemote(remoteFile to remoteData)
        val target = FakeTarget(empty)
        val outcome = restore(remote, target, ownership = emptyList())
        assertEquals(DropboxRestoreOutcome.Restored(fingerprint(remoteData), merged = true), outcome)
        assertEquals(remoteData, target.data)
        remote.assertUnmodified()
        assertEquals(listOf(remoteFile), remote.files)
    }

    @Test fun localRecordsAddedAfterDialogAppearedBecomeAGenuineConflict() = runBlocking {
        val remote = FakeRemote(remoteFile to remoteData)
        val local = data("Added later")
        val target = FakeTarget(local)
        val outcome = restore(remote, target)
        assertEquals(DropboxRestoreOutcome.Review(remoteFile.path, fingerprint(remoteData), token(remote.files), restore = false), outcome)
        assertEquals(local, target.data)
        assertEquals(0, target.restoreCalls)
        remote.assertUnmodified()
    }

    @Test fun recordsAddedInsideTheRestoreWindowAreNeverMixedInSilently() = runBlocking {
        val remote = FakeRemote(remoteFile to remoteData)
        val added = data("Raced")
        val target = FakeTarget(empty).apply { beforeRestore = { data = added } }
        val outcome = restore(remote, target)
        assertTrue(outcome is DropboxRestoreOutcome.Review && !outcome.restore)
        assertEquals(added, target.data)
        remote.assertUnmodified()
    }

    @Test fun staleLocalRecordsConflictEvenWhenDropboxMatchesTheOldBaseline() = runBlocking {
        val remote = FakeRemote(remoteFile to remoteData)
        val outcome = restore(remote, FakeTarget(data("New local")), baseline = fingerprint(remoteData))
        assertTrue(outcome is DropboxRestoreOutcome.Review && !outcome.restore)
        remote.assertUnmodified()
    }

    @Test fun deviceAlreadyHoldingTheDropboxDataAdoptsBaselineWithoutWriting() = runBlocking {
        val remote = FakeRemote(remoteFile to remoteData)
        val target = FakeTarget(remoteData)
        assertEquals(DropboxRestoreOutcome.Restored(fingerprint(remoteData), merged = false), restore(remote, target))
        assertEquals(0, target.restoreCalls)
        remote.assertUnmodified()
    }

    @Test fun missingRemoteBackupClearsTheOfferWithoutUploading() = runBlocking {
        val remote = FakeRemote()
        assertEquals(DropboxRestoreOutcome.NothingToRestore(pending = false), restore(remote, FakeTarget(empty), files = emptyList()))
        assertEquals(DropboxRestoreOutcome.NothingToRestore(pending = true), restore(remote, FakeTarget(data("Local")), files = emptyList()))
        remote.assertUnmodified()
    }

    @Test fun remoteChangedBeforeConfirmationAsksAgainWithTheNewSnapshot() = runBlocking {
        val remote = FakeRemote(remoteFile to remoteData, file(2) to data("Newer"))
        val target = FakeTarget(empty)
        val outcome = restore(remote, target, files = remote.files)
        assertTrue(outcome is DropboxRestoreOutcome.Review && outcome.restore)
        assertEquals(token(remote.files), (outcome as DropboxRestoreOutcome.Review).remoteState)
        assertEquals(empty, target.data)
        assertEquals(0, target.restoreCalls)
        remote.assertUnmodified()
    }

    @Test fun remoteContentReplacedAtTheSamePathIsNotRestored() = runBlocking {
        val replaced = data("Replaced")
        val remote = FakeRemote(remoteFile to replaced)
        val target = FakeTarget(empty)
        val outcome = restore(remote, target, request = DropboxRestoreRequest(remoteFile.path, fingerprint(remoteData), token(remote.files)))
        assertEquals(DropboxRestoreOutcome.Review(remoteFile.path, fingerprint(replaced), token(remote.files), restore = true), outcome)
        assertEquals(0, target.restoreCalls)
    }

    @Test fun remoteChangedBetweenValidationAndWriteStopsTheRestore() = runBlocking {
        val remote = FakeRemote(remoteFile to remoteData)
        val arrival = file(2)
        remote.beforeListing = { remote.add(arrival, data("Arrived")) }
        val target = FakeTarget(empty)
        val outcome = restore(remote, target)
        assertTrue(outcome is DropboxRestoreOutcome.Review && outcome.restore && outcome.path == arrival.path)
        assertEquals(0, target.restoreCalls)
        remote.assertUnmodified()
    }

    private suspend fun restore(
        remote: FakeRemote,
        target: FakeTarget,
        ownership: List<DropboxOwnedBackup> = emptyList(),
        baseline: String? = null,
        files: List<DropboxRemoteFile> = listOf(remoteFile),
        request: DropboxRestoreRequest = DropboxRestoreRequest(remoteFile.path, fingerprint(remoteData), token(listOf(remoteFile))),
    ): DropboxRestoreOutcome = DropboxRemoteRestore(remote, target, checkActive = {})
        .execute(request, files, ownership, baseline)

    private class FakeTarget(var data: AppBackupData) : DropboxRestoreTarget {
        var restoreCalls = 0
        var beforeRestore: () -> Unit = {}
        override suspend fun snapshot(): AppBackupData = data
        override suspend fun restoreIntoEmpty(data: AppBackupData): Boolean {
            restoreCalls++
            beforeRestore()
            if (!this.data.isEmpty) return false
            this.data = data
            return true
        }
    }

    private class FakeRemote(vararg entries: Pair<DropboxRemoteFile, AppBackupData>) : DropboxBackupRemote {
        private val contents = linkedMapOf(*entries)
        val files: List<DropboxRemoteFile> get() = contents.keys.toList()
        var beforeListing: () -> Unit = {}
        private var uploads = 0
        private var deletes = 0
        fun add(file: DropboxRemoteFile, data: AppBackupData) { contents[file] = data }
        override fun snapshots(): List<DropboxRemoteFile> {
            beforeListing()
            beforeListing = {}
            return files
        }
        override fun download(file: DropboxRemoteFile): AppBackupData = checkNotNull(contents[file])
        override fun upload(data: AppBackupData): DropboxRemoteFile { uploads++; error("Restore must not upload") }
        override fun deleteOwned(file: DropboxRemoteFile) { deletes++; error("Restore must not delete") }
        fun assertUnmodified() {
            assertEquals(0, uploads)
            assertEquals(0, deletes)
        }
    }

    private fun data(area: String) = AppBackupData(emptyList(), listOf(
        JourneyPoint(
            id = 0, timestamp = 1_700_000_000_000, areaName = area,
            district = null, state = null, radio = "NR", plmn = "405-869", cellId = 1, confidence = 100,
        ),
    ))

    private fun fingerprint(data: AppBackupData) = AppBackupCodec.fingerprint(data)
    private fun token(files: List<DropboxRemoteFile>) = DropboxRemoteState.token(files)

    private fun file(index: Int) = DropboxRemoteFile(
        "/localtell-managed-backups/localtell-$index.bklt", "LocalTell-$index.bklt", index * 1000L,
        "id:$index", "revision-$index", 20, "a".repeat(64),
    )
}
