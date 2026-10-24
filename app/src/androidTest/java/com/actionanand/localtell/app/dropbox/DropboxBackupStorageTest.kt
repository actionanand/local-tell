package com.actionanand.localtell.app.dropbox

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.actionanand.localtell.app.AppLimits
import com.actionanand.localtell.app.DataLimits
import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData
import com.actionanand.localtell.app.backup.AppBackupRepository
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.saved.SaveResult
import com.actionanand.localtell.app.saved.SavedPlace
import com.actionanand.localtell.app.saved.SavedPlaceSource
import com.actionanand.localtell.app.saved.SavedPlacesRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** All fixtures use isolated database paths/preferences, never the installed user's databases. */
@RunWith(AndroidJUnit4::class)
class DropboxBackupStorageTest {
    @Test fun pendingUploadSurvivesRecreationAndCorruptReceiptBlocksRecovery() = runBlocking {
        fixture { context, _, _ ->
            val account = "e".repeat(64)
            val file = DropboxRemoteFile(
                "/localtell-managed-backups/localtell-test.bklt", "LocalTell-test.bklt",
                1L, "id:test", "revision", 100L, "a".repeat(64),
            )
            val pending = DropboxPendingUpload(file, "b".repeat(64), "c".repeat(64))
            assertTrue(DropboxBackupOwnership(context).savePending(account, pending))
            val restored = DropboxBackupOwnership(context)
            assertEquals(pending, restored.pending(account))
            context.getSharedPreferences("localtell_dropbox_ownership", Context.MODE_PRIVATE)
                .edit().putString("$account.pending", "broken").commit()
            try {
                restored.pending(account)
                fail("Expected corrupt receipt rejection")
            } catch (_: IllegalStateException) {
                assertTrue(restored.read(account).orEmpty().isEmpty())
            }
        }
    }

    @Test fun staleSyncingIsRecoveredOnlyForTheSameAttempt() = runBlocking {
        fixture { context, _, _ ->
            val account = "d".repeat(64)
            val store = DropboxBackupStateStore(context)
            try {
                store.beginAttempt(account, now = 1000L)
                assertFalse(store.recoverStaleSync(account, attemptedAt = 999L))
                store.selectAccount(account)
                assertEquals(DropboxBackupStatus.SYNCING, store.state.value.status)
                assertTrue(store.recoverStaleSync(account, attemptedAt = 1000L))
                store.selectAccount(account)
                assertEquals(DropboxBackupStatus.INTERRUPTED, store.state.value.status)
                assertFalse(store.recoverStaleSync(account, attemptedAt = 1000L))
            } finally {
                store.close()
            }
        }
    }

    @Test fun backupFailureDiagnosticsSurviveStoreRecreationWithoutChangingLastSuccess() = runBlocking {
        fixture { context, _, _ ->
            val account = "f".repeat(64)
            val diagnostic = DropboxBackupDiagnostic(
                DropboxBackupStage.PREPARE_BACKUP_FOLDER, "CreateFolderErrorException",
                "NO_WRITE_PERMISSION", "request-id", false, 2000L,
            )
            val first = DropboxBackupStateStore(context)
            first.markSuccess(account, "fingerprint", pending = false, now = 1000L)
            first.beginAttempt(account, now = 1500L)
            first.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.DROPBOX_PERMISSION,
                now = 2000L, diagnostic = diagnostic)
            first.close()
            val restored = DropboxBackupStateStore(context)
            try {
                restored.selectAccount(account)
                assertEquals(diagnostic, restored.state.value.diagnostic)
                assertEquals(1000L, restored.state.value.lastSuccessfulAt)
                assertEquals(2000L, restored.state.value.lastAttemptedAt)
                restored.markInterrupted(account)
                restored.selectAccount(account)
                assertEquals(DropboxBackupStatus.INTERRUPTED, restored.state.value.status)
            } finally {
                restored.close()
            }
        }
    }

    @Test fun overflowMergePreservesAllPlacesAndExplicitTrimmingReturnsToConfiguredLimit() = runBlocking {
        fixture { _, repository, saved ->
            val count = (AppLimits.current.maxSavedPlaces + 2).coerceAtMost(DataLimits.MAX_SAVED_BOUND)
            repository.mergeKeepingAllPlaces(AppBackupData((1..count).map(::place), emptyList()))
            assertEquals(count, repository.snapshot().savedPlaces.size)
            if (count > AppLimits.current.maxSavedPlaces) {
                assertEquals(SaveResult.Full, saved.save(place(count + 1)))
                // No implicit trimming happened. The user has explicitly approved only one deletion.
                saved.removeOldestOverflow(1)
                assertEquals(count - 1, repository.snapshot().savedPlaces.size)
                saved.removeOldestOverflow(count - AppLimits.current.maxSavedPlaces)
                val kept = repository.snapshot().savedPlaces
                assertEquals(AppLimits.current.maxSavedPlaces, kept.size)
                assertFalse(kept.any { it.id == "p-1" })
            }
        }
    }

    @Test fun cancelledSessionBeforeCommitRollsBackEveryInsertedPlace() = runBlocking {
        fixture { _, repository, _ ->
            repository.mergeKeepingAllPlaces(AppBackupData(listOf(place(1)), emptyList()))
            val before = repository.snapshot()
            var checks = 0
            try {
                val incoming = AppBackupData(listOf(place(2), place(3)), listOf(
                    JourneyPoint(
                        id = 0, timestamp = 1_700_000_000_000, areaName = "Rollback test",
                        district = null, state = null, radio = "NR", plmn = "405-869", cellId = 1, confidence = 100,
                    ),
                ))
                repository.mergeKeepingAllPlaces(incoming) {
                    if (++checks == 2) throw CancellationException("Disconnect before commit")
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(before, repository.snapshot())
            }
        }
    }

    @Test fun hardBoundOrInvalidBackupDoesNotChangeLocalDatabase() = runBlocking {
        fixture { _, repository, _ ->
            repository.mergeKeepingAllPlaces(AppBackupData(listOf(place(1)), emptyList()))
            val before = repository.snapshot()
            val incoming = AppBackupData((2..(DataLimits.MAX_SAVED_BOUND + 1)).map(::place), emptyList())
            try {
                repository.mergeKeepingAllPlaces(incoming)
                fail("Expected hard bound rejection")
            } catch (_: IllegalArgumentException) {
                assertEquals(before, repository.snapshot())
            }
            try {
                repository.mergeKeepingAllPlaces(AppBackupData(listOf(place(2).copy(latitude = Double.NaN)), emptyList()))
                fail("Expected validation rejection")
            } catch (_: IllegalArgumentException) {
                assertEquals(before, repository.snapshot())
            }
        }
    }

    @Test fun localEditDuringUploadStaysPendingAndStaleRefreshCannotClearIt() = runBlocking {
        fixture { context, repository, saved ->
            val store = DropboxBackupStateStore(context)
            val account = "a".repeat(64)
            try {
                store.selectAccount(account)
                repository.mergeKeepingAllPlaces(AppBackupData(listOf(place(1)), emptyList()))
                val uploadedFingerprint = AppBackupCodec.fingerprint(repository.snapshot())
                val staleRevision = store.revision(account)
                saved.delete(setOf("p-1"))
                completeDropboxBackup(store, repository, account, uploadedFingerprint) {}
                store.markPending(account, false, staleRevision)
                store.selectAccount(account)
                assertTrue(store.state.value.pendingChanges)
                assertEquals(DropboxBackupStatus.PENDING, store.state.value.status)
                assertNotNull(store.state.value.lastSuccessfulAt)
                store.markConflict(account, "/conflict.bklt", "remote")
                store.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
                store.selectAccount(account)
                assertEquals(DropboxBackupStatus.CONFLICT, store.state.value.status)
                assertEquals("remote", store.state.value.conflictFingerprint)
            } finally {
                store.close()
            }
        }
    }

    @Test fun savedLimitPauseClearsOnlyAfterCurrentCountIsWithinLimitAndSessionInvalidatesOnDisconnect() = runBlocking {
        fixture { context, _, _ ->
            val store = DropboxBackupStateStore(context)
            try {
                val account = "b".repeat(64)
                store.selectAccount(account)
                store.markSavedLimitExceeded(account)
                store.markPending(account, true, store.revision(account))
                store.selectAccount(account)
                assertEquals(DropboxBackupStatus.PENDING, store.state.value.status)
                val sessions = DropboxBackupSessionStore(context)
                val credential = StoredDropboxCredential("fake-access", null, "fake-refresh")
                sessions.activate(account, credential)
                val first = requireNotNull(sessions.current())
                sessions.invalidate()
                assertNull(sessions.current())
                sessions.activate(account, credential)
                assertNotEquals(first.generation, sessions.current()?.generation)
                sessions.activate("c".repeat(64), credential)
                assertNotEquals(first.account, sessions.current()?.account)
            } finally {
                store.close()
            }
        }
    }

    @Test fun failedBackupRetainsSuccessTimestampAndConflictMetadataAcrossStoreRecreation() = runBlocking {
        fixture { context, _, _ ->
            val account = "d".repeat(64)
            val first = DropboxBackupStateStore(context)
            first.markSuccess(account, "verified-fingerprint", pending = true, now = 1000L)
            first.markConflict(account, "/remote.bklt", "remote-fingerprint", remoteState = "remote-state")
            first.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE, now = 2000L)
            first.close()
            val restored = DropboxBackupStateStore(context)
            try {
                restored.selectAccount(account)
                assertEquals(1000L, restored.state.value.lastSuccessfulAt)
                assertEquals(2000L, restored.state.value.lastAttemptedAt)
                assertEquals("remote-state", restored.state.value.conflictRemoteState)
                assertEquals(DropboxBackupStatus.CONFLICT, restored.state.value.status)
                assertTrue(restored.state.value.pendingChanges)
            } finally {
                restored.close()
            }
        }
    }

    @Test fun restoreOfferSurvivesFailureAndIsClearedByMergeOrSuccess() = runBlocking {
        fixture { context, _, _ ->
            val account = "e".repeat(64)
            val store = DropboxBackupStateStore(context)
            try {
                store.markConflict(account, "/remote.bklt", "remote", remoteState = "state", restore = true)
                store.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
                store.selectAccount(account)
                assertEquals(DropboxReviewDialog.RESTORE, store.state.value.reviewDialog)
                store.adoptBaseline(account, "remote")
                store.selectAccount(account)
                assertFalse(store.state.value.restoreAvailable)
                store.markConflict(account, "/remote.bklt", "remote", restore = true)
                store.markConflict(account, "/remote.bklt", "remote")
                store.selectAccount(account)
                assertEquals(DropboxReviewDialog.CONFLICT, store.state.value.reviewDialog)
                store.markConflict(account, "/remote.bklt", "remote", restore = true)
                store.markSuccess(account, "remote", pending = false)
                store.selectAccount(account)
                assertFalse(store.state.value.restoreAvailable)
                assertNull(store.state.value.reviewDialog)
            } finally {
                store.close()
            }
        }
    }

    @Test fun freshInstallRestoreWritesLocallyAdoptsBaselineAndNeverTouchesDropboxOrBackupTime() = runBlocking {
        fixture { context, repository, _ ->
            val account = "f".repeat(64)
            val store = DropboxBackupStateStore(context)
            val remoteData = AppBackupData(listOf(place(1)), emptyList())
            val file = DropboxRemoteFile("/localtell-managed-backups/localtell-1.bklt", "LocalTell-1.bklt", 1000L,
                "id:1", "revision-1", 20, "a".repeat(64))
            val remote = object : DropboxBackupRemote {
                override fun snapshots() = listOf(file)
                override fun download(file: DropboxRemoteFile) = remoteData
                override fun upload(data: AppBackupData): DropboxRemoteFile = throw AssertionError("Restore uploaded")
                override fun deleteOwned(file: DropboxRemoteFile) = throw AssertionError("Restore deleted")
            }
            try {
                val fingerprint = AppBackupCodec.fingerprint(remoteData)
                val token = DropboxRemoteState.token(listOf(file))
                store.markConflict(account, file.path, fingerprint, remoteState = token, restore = true)
                val outcome = DropboxRemoteRestore(remote, repositoryRestoreTarget(repository) {}, {})
                    .execute(DropboxRestoreRequest(file.path, fingerprint, token), listOf(file), emptyList(), store.baseline(account))
                completeDropboxRestore(store, repository, account, outcome, {}, now = 5000L)
                store.selectAccount(account)
                assertEquals(fingerprint, AppBackupCodec.fingerprint(repository.snapshot()))
                assertEquals(fingerprint, store.baseline(account))
                assertEquals(DropboxBackupStatus.UP_TO_DATE, store.state.value.status)
                assertFalse(store.state.value.restoreAvailable)
                assertNull(store.state.value.conflictFingerprint)
                assertNull(store.state.value.lastSuccessfulAt)
                assertEquals(5000L, store.state.value.lastRestoredAt)
                assertFalse(store.state.value.pendingChanges)
            } finally {
                store.close()
            }
        }
    }

    @Test fun restoreIntoEmptyRefusesToWriteOnceLocalRecordsExist() = runBlocking {
        fixture { _, repository, _ ->
            repository.mergeKeepingAllPlaces(AppBackupData(listOf(place(1)), emptyList()))
            val before = repository.snapshot()
            assertFalse(repository.restoreIntoEmpty(AppBackupData(listOf(place(2)), emptyList())))
            assertEquals(before, repository.snapshot())
        }
    }

    @Test fun restoreCompletionKeepsEarlierBackupTimeAndOnlyRecordsActualRestores() = runBlocking {
        fixture { context, _, _ ->
            val account = "1".repeat(64)
            val store = DropboxBackupStateStore(context)
            try {
                store.markSuccess(account, "old", pending = false, now = 1000L)
                store.markConflict(account, "/remote.bklt", "remote", restore = true)
                store.markRestored(account, "remote", pending = false, restoredAt = null)
                store.selectAccount(account)
                assertEquals(1000L, store.state.value.lastSuccessfulAt)
                assertNull(store.state.value.lastRestoredAt)
                assertEquals("remote", store.baseline(account))
                assertNull(store.state.value.reviewDialog)
                store.markConflict(account, "/remote.bklt", "remote", restore = true)
                store.clearReview(account, pending = true)
                store.selectAccount(account)
                assertEquals(DropboxBackupStatus.PENDING, store.state.value.status)
                assertEquals("remote", store.baseline(account))
                assertEquals(1000L, store.state.value.lastSuccessfulAt)
                assertNull(store.state.value.reviewDialog)
            } finally {
                store.close()
            }
        }
    }

    @Test fun migrationKeepsExistingScheduleDefaultsWeeklyToSundayAndNeverCountsTheUpgradeAsMissed() = runBlocking {
        fixture { context, _, _ ->
            val legacy = context.getSharedPreferences("localtell_dropbox_backup", Context.MODE_PRIVATE)
            legacy.edit().putString("frequency", "weekly").commit()
            val store = DropboxBackupStateStore(context)
            try {
                assertTrue(store.migrateSchedule(now = 5_000L))
                assertEquals(DropboxBackupFrequency.WEEKLY, store.frequency())
                assertEquals(java.time.DayOfWeek.SUNDAY, store.weekday())
                assertEquals(5_000L, store.scheduleSince())
                assertFalse(store.migrateSchedule(now = 9_000L))
                assertEquals(5_000L, store.scheduleSince())
            } finally {
                store.close()
            }
        }
    }

    @Test fun migrationKeepsASavedWeekdayAndDoesNotEnableADisabledSchedule() = runBlocking {
        fixture { context, _, _ ->
            context.getSharedPreferences("localtell_dropbox_backup", Context.MODE_PRIVATE).edit()
                .putString("frequency", "off").putString("weekly_day", "WEDNESDAY").commit()
            val store = DropboxBackupStateStore(context)
            try {
                store.migrateSchedule(now = 1_000L)
                assertEquals(DropboxBackupFrequency.OFF, store.frequency())
                assertEquals(java.time.DayOfWeek.WEDNESDAY, store.weekday())
            } finally {
                store.close()
            }
        }
    }

    @Test fun scheduleSurvivesProcessRestartAndOnlyRealChangesReanchorIt() = runBlocking {
        fixture { context, _, _ ->
            val account = "2".repeat(64)
            val first = DropboxBackupStateStore(context)
            assertTrue(first.setFrequency(DropboxBackupFrequency.WEEKLY, now = 1_000L))
            assertTrue(first.setWeekday(java.time.DayOfWeek.FRIDAY, now = 2_000L))
            first.markSuccess(account, "fingerprint", pending = false, now = 3_000L)
            val revision = first.revision(account)
            first.markScheduledBlocked(account, DropboxScheduledBlock(4_000L, "generation"))
            assertEquals(revision, first.revision(account))
            first.close()
            val restarted = DropboxBackupStateStore(context)
            try {
                restarted.selectAccount(account)
                assertEquals(DropboxBackupFrequency.WEEKLY, restarted.state.value.frequency)
                assertEquals(java.time.DayOfWeek.FRIDAY, restarted.state.value.weeklyDay)
                assertEquals(2_000L, restarted.state.value.scheduleSince)
                assertEquals(4_000L, restarted.state.value.scheduledBlockedOccurrence)
                assertEquals(DropboxScheduledBlock(4_000L, "generation"), restarted.scheduledBlock(account))
                assertEquals(3_000L, restarted.lastSuccessfulAt(account))
                // Re-selecting the same mode or day must not postpone an overdue backup.
                restarted.setFrequency(DropboxBackupFrequency.WEEKLY, now = 8_000L)
                restarted.setWeekday(java.time.DayOfWeek.FRIDAY, now = 8_000L)
                assertEquals(2_000L, restarted.scheduleSince())
                restarted.setFrequency(DropboxBackupFrequency.DAILY, now = 9_000L)
                assertEquals(9_000L, restarted.scheduleSince())
                // A manual or scheduled success never touches the schedule anchor.
                restarted.markSuccess(account, "next", pending = false, now = 10_000L)
                assertEquals(9_000L, restarted.scheduleSince())
            } finally {
                restarted.close()
            }
        }
    }

    private suspend fun fixture(action: suspend (Context, AppBackupRepository, SavedPlacesRepository) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "dropbox-safety-${UUID.randomUUID()}"
        val directory = File(base.cacheDir, prefix).apply { check(mkdir()) }
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String) = File(directory, name)
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$prefix-$name".also { preferenceNames += it }, mode)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?,
            ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
        }
        val repository = AppBackupRepository(context)
        val saved = SavedPlacesRepository(context)
        try {
            action(context, repository, saved)
        } finally {
            repository.close()
            saved.close()
            preferenceNames.forEach { base.deleteSharedPreferences(it) }
            directory.listFiles()?.forEach { check(it.delete()) }
            check(directory.delete())
        }
    }

    private fun place(number: Int) = SavedPlace(
        id = "p-$number", displayName = "Place $number", localityName = "Locality $number",
        subDistrict = null, district = null, state = null, countryCode = "IN",
        latitude = 8.0 + number * 0.01, longitude = 77.0, savedAt = number.toLong(),
        source = SavedPlaceSource.EASY,
    )
}
