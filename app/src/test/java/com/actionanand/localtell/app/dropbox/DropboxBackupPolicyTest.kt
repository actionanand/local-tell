package com.actionanand.localtell.app.dropbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DropboxBackupPolicyTest {
    @Test fun fourBackupModesPersistByStablePreferenceValues() {
        assertEquals(listOf("off", "manual", "daily", "weekly"), DropboxBackupFrequency.entries.map { it.preferenceValue })
        DropboxBackupFrequency.entries.forEach { assertEquals(it, DropboxBackupFrequency.fromPreference(it.preferenceValue)) }
    }

    @Test fun missingOrUnknownPreferenceDefaultsToOff() {
        assertEquals(DropboxBackupFrequency.OFF, DropboxBackupFrequency.fromPreference(null))
        assertEquals(DropboxBackupFrequency.OFF, DropboxBackupFrequency.fromPreference("hourly"))
    }

    @Test fun offBlocksAllSyncWhileManualAllowsOnlyTapToSync() {
        assertFalse(DropboxBackupFrequency.OFF.syncAllowed)
        assertTrue(DropboxBackupFrequency.MANUAL.syncAllowed)
        assertNull(DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.OFF, connected = true))
        assertNull(DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.MANUAL, connected = true))
    }

    @Test fun dailyAndWeeklyScheduleOnlyWhileConnected() {
        assertEquals(1L, DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.DAILY, connected = true))
        assertEquals(7L, DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.WEEKLY, connected = true))
        assertNull(DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.DAILY, connected = false))
        assertNull(DropboxBackupSchedulePolicy.periodDays(DropboxBackupFrequency.WEEKLY, connected = false))
    }

    @Test fun retentionKeepsNewestThreeIncludingJustUploaded() {
        val files = (1..5).map(::file)
        val obsolete = DropboxBackupRetention.obsolete(files, files.map(::DropboxOwnedBackup), files.last())
        assertEquals(listOf("id:2", "id:1"), obsolete.map { it.id })
    }

    @Test fun retentionDeletesNothingWithThreeOrFewerSnapshots() {
        val files = (1..3).map(::file)
        assertTrue(DropboxBackupRetention.obsolete(files, files.map(::DropboxOwnedBackup), files.last()).isEmpty())
        assertTrue(DropboxBackupRetention.obsolete(emptyList(), emptyList(), file(9)).isEmpty())
    }

    @Test fun retentionNeverDeletesTheVerifiedUploadEvenIfListedOldest() {
        val files = (1..4).map(::file)
        val obsolete = DropboxBackupRetention.obsolete(files, files.map(::DropboxOwnedBackup), files.first()).map { it.id }
        assertFalse("id:1" in obsolete)
        assertEquals(listOf("id:2"), obsolete)
    }

    @Test fun fourthUploadRemovesOnlyFirstOwnedBackup() {
        val files = (1..4).map(::file)
        assertEquals(listOf("id:1"),
            DropboxBackupRetention.obsolete(files, files.map(::DropboxOwnedBackup), files.last()).map { it.id })
    }

    @Test fun rapidUploadsUseVerifiedUploadOrderWhenServerTimestampsTie() {
        val files = (1..5).map { file(it).copy(modifiedAtMillis = 1L) }
        assertEquals(listOf("id:2", "id:1"),
            DropboxBackupRetention.obsolete(files, files.map(::DropboxOwnedBackup), files.last()).map { it.id })
    }

    @Test fun manualAndLegacyFilesAreNotOwnedEvenWithIdenticalLookingNamesAndFolder() {
        val owned = (1..5).map(::file)
        val manual = file(0).copy(id = "id:manual", name = owned.first().name, path = "/localtell-managed-backups/manual.bklt")
        val legacy = file(8).copy(path = "/LocalTell-legacy.bklt")
        val unrelated = file(9).copy(name = "notes.txt")
        val obsolete = DropboxBackupRetention.obsolete(owned + manual + legacy + unrelated, owned.map(::DropboxOwnedBackup), owned.last())
        assertEquals(listOf("id:2", "id:1"), obsolete.map { it.id })
    }

    @Test fun changedMetadataOrMissingUploadRecordNeverAuthorizesDeletion() {
        val files = (1..5).map(::file)
        val records = files.map(::DropboxOwnedBackup)
        for (changed in listOf(files.first().copy(revision = "changed"), files.first().copy(contentHash = "b".repeat(64)),
            files.first().copy(id = "id:replacement"), files.first().copy(path = "/moved.bklt"), files.first().copy(size = 99))) {
            assertFalse(DropboxBackupRetention.obsolete(listOf(changed) + files.drop(1), records, files.last()).contains(changed))
        }
        assertTrue(DropboxBackupRetention.obsolete(files, records.dropLast(1), files.last()).isEmpty())
        assertTrue(DropboxBackupRetention.obsolete(files, records + records.first(), files.last()).isEmpty())
    }

    @Test fun queuedAccountAndSessionMustStillMatchVerifiedAuthorization() {
        val session = DropboxBackupSession("A", "generation-1", "credential-1")
        assertTrue(session.accepts("A", "generation-1", "A", "credential-1"))
        assertFalse(session.accepts("B", "generation-1", "A", "credential-1"))
        assertFalse(session.accepts("A", "generation-old", "A", "credential-1"))
        assertFalse(session.accepts("A", "generation-1", "B", "credential-1"))
        assertFalse(session.accepts("A", "generation-1", "A", "credential-2"))
    }

    @Test fun detectConcurrentChangesEvenWhenTheyDoNotSortNewest() {
        val files = listOf(file(1), file(2))
        assertFalse(DropboxRemoteState.unchanged(files, listOf(file(1).copy(revision = "new"), file(2))))
        assertFalse(DropboxRemoteState.unchanged(files, files + file(0)))
        assertTrue(DropboxRemoteState.unchanged(files, files + file(3), excludingId = "id:3"))
        assertEquals(DropboxRemoteState.token(files), DropboxRemoteState.token(files.reversed()))
        assertFalse(DropboxRemoteState.token(files) == DropboxRemoteState.token(listOf(file(1).copy(revision = "new"), file(2))))
    }

    @Test fun syncProtocolDetectsConflictsAndNeverOverwritesNewerRemote() {
        assertEquals(DropboxSyncDecision.UP_TO_DATE, DropboxSyncProtocol.decide("L", "L", null))
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("L", null, null))
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("L2", "B", "B"))
        assertEquals(DropboxSyncDecision.CONFLICT, DropboxSyncProtocol.decide("L2", "R2", "B"))
        assertEquals(DropboxSyncDecision.CONFLICT, DropboxSyncProtocol.decide("L", "R", null))
    }

    @Test fun afterMergeAdoptingRemoteBaselineLetsTrimmedLocalUploadWithoutNewConflict() {
        // Merge adopts remote "R" as baseline; trimming over-limit places changes local to "T".
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("T", "R", "R"))
    }

    @Test fun emptyDeviceWithExistingDropboxBackupOffersRestoreInsteadOfConflict() {
        assertEquals(DropboxSyncDecision.RESTORE_REMOTE, DropboxSyncProtocol.decide("E", "R", null, localEmpty = true))
        assertEquals(DropboxSyncDecision.RESTORE_REMOTE, DropboxSyncProtocol.decide("E", "R", "B", localEmpty = true))
        // A wiped device must never push an empty snapshot over a backup it previously synced.
        assertEquals(DropboxSyncDecision.RESTORE_REMOTE, DropboxSyncProtocol.decide("E", "B", "B", localEmpty = true))
        assertEquals(DropboxSyncDecision.RESTORE_REMOTE,
            DropboxSyncProtocol.decide("E", "R", "B", localEmpty = true, requireReview = true))
    }

    @Test fun emptyDropboxFolderOrEmptyRemoteBackupUploadsWithoutAsking() {
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("L", null, null))
        // Previously synced, then the managed folder was emptied: nothing remote can be lost.
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("L", null, "B"))
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("L", null, "B", requireReview = true))
        assertEquals(DropboxSyncDecision.UPLOAD_LOCAL, DropboxSyncProtocol.decide("L", "E", null, remoteEmpty = true))
    }

    @Test fun differingDataOnBothSidesIsAConflict() {
        assertEquals(DropboxSyncDecision.CONFLICT, DropboxSyncProtocol.decide("L", "R", null))
        assertEquals(DropboxSyncDecision.CONFLICT, DropboxSyncProtocol.decide("L", "R", "B"))
        // An unresolved conflict or externally changed owned file disables the baseline shortcut.
        assertEquals(DropboxSyncDecision.CONFLICT, DropboxSyncProtocol.decide("L", "B", "B", requireReview = true))
    }

    @Test fun synchronizedDataNeverAsksEvenWithStaleConflictOrEmptySides() {
        assertEquals(DropboxSyncDecision.UP_TO_DATE, DropboxSyncProtocol.decide("L", "L", "B", requireReview = true))
        assertEquals(DropboxSyncDecision.UP_TO_DATE,
            DropboxSyncProtocol.decide("E", "E", null, localEmpty = true, remoteEmpty = true))
    }

    @Test fun restoreOfferKeepsUnownedRemoteFilesOutOfRetention() {
        // Restore never routes through upload, so managed-backup bookkeeping cannot alter it.
        val remote = file(1)
        assertEquals(DropboxSyncDecision.RESTORE_REMOTE,
            managedSyncDecision(DropboxSyncDecision.RESTORE_REMOTE, remote, emptyList()))
        assertTrue(DropboxBackupRetention.obsolete(listOf(remote), emptyList(), remote).isEmpty())
    }

    @Test fun reviewDialogMatchesConflictKind() {
        assertNull(DropboxBackupUiState(status = DropboxBackupStatus.UP_TO_DATE, restoreAvailable = true).reviewDialog)
        assertNull(DropboxBackupUiState(status = DropboxBackupStatus.PENDING).reviewDialog)
        assertEquals(DropboxReviewDialog.RESTORE,
            DropboxBackupUiState(status = DropboxBackupStatus.CONFLICT, restoreAvailable = true).reviewDialog)
        assertEquals(DropboxReviewDialog.CONFLICT, DropboxBackupUiState(status = DropboxBackupStatus.CONFLICT).reviewDialog)
    }

    private fun file(index: Int) = DropboxRemoteFile(
        "/localtell-managed-backups/localtell-$index.bklt", "LocalTell-$index.bklt", index * 1000L,
        "id:$index", "revision-$index", 20, "a".repeat(64),
    )
}
