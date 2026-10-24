package com.actionanand.localtell.app.dropbox

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real WorkManager enqueue decisions with a test WorkManager, isolated preferences and a fixed clock. */
@RunWith(AndroidJUnit4::class)
class DropboxSchedulerWorkManagerTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "dropbox-schedule-${UUID.randomUUID()}"
    private val preferenceNames = mutableSetOf<String>()
    private val context: Context = object : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            base.getSharedPreferences("$prefix-$name".also { preferenceNames += it }, mode)
    }
    private val zone = ZoneId.of("Asia/Kolkata")
    private val account = "a".repeat(64)
    private lateinit var manager: WorkManager
    private lateinit var store: DropboxBackupStateStore
    private lateinit var sessions: DropboxBackupSessionStore

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Before fun setUp() {
        assumeTrue("Cloud backup is disabled in this build", CloudBackupFeature.enabled)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            base, Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        manager = WorkManager.getInstance(context)
        sessions = DropboxBackupSessionStore(context)
        sessions.activate(account, StoredDropboxCredential("fake-access", null, "fake-refresh"))
        store = DropboxBackupStateStore(context)
        // Daily chosen on 20 Oct; last backup completed 23 Oct, so the 24 Oct 02:00 occurrence was missed.
        store.setFrequency(DropboxBackupFrequency.DAILY, now = at("2026-10-20T12:00").toEpochMilli())
        store.markSuccess(account, "fingerprint", pending = false, now = at("2026-10-23T02:05").toEpochMilli())
    }

    @After fun tearDown() {
        if (!::manager.isInitialized) return
        manager.cancelAllWork().result.get()
        store.close()
        preferenceNames.forEach { base.deleteSharedPreferences(it) }
    }

    private fun reconcile(now: String, scheduleChanged: Boolean = false, session: () -> DropboxBackupSession? = { sessions.current() }) =
        DropboxBackupScheduler.reconcile(
            context, scheduleChanged = scheduleChanged, clock = Clock.fixed(at(now), zone), activeSession = session,
        )

    private fun unfinished(name: String): List<WorkInfo> = manager.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    private fun alarmTarget(): Long? = unfinished(DropboxBackupScheduler.ALARM_WORK).singleOrNull()?.tags
        ?.firstOrNull { it.startsWith("dropbox-alarm-target:") }?.substringAfter(':')?.toLong()

    @Test fun dailyAlarmTargetsTheNextLocalTwoAmAndWeeklyTheSelectedDay() {
        reconcile("2026-10-24T10:00")
        assertEquals(at("2026-10-25T02:00").toEpochMilli(), alarmTarget())
        store.setFrequency(DropboxBackupFrequency.WEEKLY, now = at("2026-10-24T10:01").toEpochMilli())
        reconcile("2026-10-24T10:01", scheduleChanged = true)
        assertEquals(at("2026-10-25T02:00").toEpochMilli(), alarmTarget())
        store.setWeekday(DayOfWeek.WEDNESDAY, now = at("2026-10-24T10:02").toEpochMilli())
        reconcile("2026-10-24T10:02", scheduleChanged = true)
        assertEquals(at("2026-10-28T02:00").toEpochMilli(), alarmTarget())
        assertEquals(1, unfinished(DropboxBackupScheduler.ALARM_WORK).size)
    }

    @Test fun missedBackupAfterRestartQueuesOneNetworkConstrainedCatchUpAndKeepsTheAnchor() {
        reconcile("2026-10-24T10:00")
        val catchUp = unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single()
        assertEquals(WorkInfo.State.ENQUEUED, catchUp.state)
        assertEquals(NetworkType.CONNECTED, catchUp.constraints.requiredNetworkType)
        assertEquals(at("2026-10-25T02:00").toEpochMilli(), alarmTarget())
    }

    @Test fun repeatedForegroundEventsNeverDuplicateOrReplaceWork() {
        reconcile("2026-10-24T10:00")
        val alarmId = unfinished(DropboxBackupScheduler.ALARM_WORK).single().id
        val syncId = unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single().id
        repeat(5) { reconcile("2026-10-24T10:0${it + 1}") }
        assertEquals(alarmId, unfinished(DropboxBackupScheduler.ALARM_WORK).single().id)
        assertEquals(syncId, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single().id)
    }

    @Test fun pendingManualSyncDoesNotSwallowTheScheduledOccurrenceAndIsNotCancelled() {
        val manual = OneTimeWorkRequestBuilder<DropboxBackupWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        manager.enqueueUniqueWork(DropboxBackupWorker.MANUAL_WORK, ExistingWorkPolicy.KEEP, manual).result.get()
        reconcile("2026-10-24T10:00")
        assertEquals(manual.id, unfinished(DropboxBackupWorker.MANUAL_WORK).single().id)
        assertEquals(1, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).size)
    }

    @Test fun manualSyncSucceedingAfterTheOccurrenceSatisfiesIt() = runBlocking {
        val occurrence = at("2026-10-24T02:00").toEpochMilli()
        store.markSuccess(account, "fingerprint", pending = false, now = at("2026-10-24T07:00").toEpochMilli())
        reconcile("2026-10-24T10:00")
        assertTrue(unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).isEmpty())
        // The worker evaluates the due occurrence with the device clock, so cover "now" for that part.
        store.markSuccess(account, "fingerprint", pending = false, now = System.currentTimeMillis())
        store.selectAccount(account)
        val attemptedBefore = store.state.value.lastAttemptedAt
        // A scheduled run that was already queued finishes without any Dropbox access or status change.
        assertEquals(ListenableWorker.Result.success(), scheduledWorker(occurrence, store.scheduleSince()!!).doWork())
        store.selectAccount(account)
        assertEquals(DropboxBackupStatus.UP_TO_DATE, store.state.value.status)
        assertEquals(attemptedBefore, store.state.value.lastAttemptedAt)
        assertTrue(unfinished(DropboxBackupScheduler.RECOVERY_WORK).isEmpty())
    }

    @Test fun mondayJobStillPendingWhenTuesdayIsDueIsKeptNotDuplicated() {
        reconcile("2026-10-26T10:00") // Monday occurrence missed: Monday job queued.
        val mondayJob = unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single().id
        val manual = OneTimeWorkRequestBuilder<DropboxBackupWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        manager.enqueueUniqueWork(DropboxBackupWorker.MANUAL_WORK, ExistingWorkPolicy.KEEP, manual).result.get()
        reconcile("2026-10-27T03:00") // Tuesday due while the Monday job still waits or retries.
        // The same job serves Tuesday when it runs, because it evaluates the occurrence due at start.
        assertEquals(mondayJob, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single().id)
        assertEquals(manual.id, unfinished(DropboxBackupWorker.MANUAL_WORK).single().id)
        assertEquals(at("2026-10-28T02:00").toEpochMilli(), alarmTarget())
    }

    @Test fun mondayJobFailingOrCancelledAfterTuesdayIsDueRecoversTuesdayWithoutWaitingForWednesday() {
        reconcile("2026-10-26T10:00")
        val generation = sessions.current()!!.generation
        val scheduleId = store.scheduleSince()!!
        val manual = OneTimeWorkRequestBuilder<DropboxBackupWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        manager.enqueueUniqueWork(DropboxBackupWorker.MANUAL_WORK, ExistingWorkPolicy.KEEP, manual).result.get()
        // The Monday run ended (recoverable failure or cancellation) at Tuesday 03:00.
        store.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
        manager.cancelUniqueWork(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).result.get()
        val tuesday = Clock.fixed(at("2026-10-27T03:00"), zone)
        assertTrue(DropboxBackupScheduler.followUpAfterScheduledRun(context, scheduleId, generation, tuesday))
        assertEquals(WorkInfo.State.ENQUEUED, unfinished(DropboxBackupScheduler.RECOVERY_WORK).single().state)
        // The recovery re-check queues Tuesday's backup; the manual sync and the alarm are untouched.
        reconcile("2026-10-27T03:01")
        assertEquals(1, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).size)
        assertEquals(manual.id, unfinished(DropboxBackupWorker.MANUAL_WORK).single().id)
        assertEquals(at("2026-10-28T02:00").toEpochMilli(), alarmTarget())
    }

    @Test fun mondayBackupFinishingAfterTuesdayTwoAmCoversTuesdayOnlyWithoutEditsDuringUpload() {
        reconcile("2026-10-26T10:00")
        val generation = sessions.current()!!.generation
        val scheduleId = store.scheduleSince()!!
        val tuesday = Clock.fixed(at("2026-10-27T02:11"), zone)
        val completed = at("2026-10-27T02:10").toEpochMilli()
        // Complete dataset uploaded: Tuesday is satisfied, so no second upload is queued.
        store.markSuccess(account, "fingerprint", pending = false, now = completed)
        assertFalse(DropboxBackupScheduler.followUpAfterScheduledRun(context, scheduleId, generation, tuesday))
        assertTrue(unfinished(DropboxBackupScheduler.RECOVERY_WORK).isEmpty())
        // Edits arrived after the 01:55 snapshot: Tuesday still needs the newer data.
        store.markSuccess(account, "fingerprint", pending = true, now = completed,
            coveredThrough = at("2026-10-27T01:55").toEpochMilli())
        assertTrue(DropboxBackupScheduler.followUpAfterScheduledRun(context, scheduleId, generation, tuesday))
        assertEquals(1, unfinished(DropboxBackupScheduler.RECOVERY_WORK).size)
    }

    @Test fun repeatedRecoverableFailuresDelayTheNextCatchUpInsteadOfLooping() {
        val due = at("2026-10-24T02:00").toEpochMilli()
        val generation = sessions.current()!!.generation
        store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.DROPBOX_TEMPORARY)
        assertTrue(store.recordRetryThrottle(account, DropboxRetryThrottle(due, generation, 2, at("2026-10-24T10:30").toEpochMilli())))
        repeat(3) { reconcile("2026-10-24T10:00") }
        val catchUp = unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single()
        assertEquals(TimeUnit.MINUTES.toMillis(30), catchUp.initialDelayMillis)
    }

    @Test fun overdueBackupWithoutEarlierFailureOrAfterReconnectStartsImmediately() {
        reconcile("2026-10-24T10:00")
        assertEquals(0L, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single().initialDelayMillis)
        manager.cancelUniqueWork(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).result.get()
        val due = at("2026-10-24T02:00").toEpochMilli()
        store.recordRetryThrottle(account, DropboxRetryThrottle(due, "previous-session", 5, at("2026-10-24T16:00").toEpochMilli()))
        reconcile("2026-10-24T10:01")
        assertEquals(0L, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single().initialDelayMillis)
    }

    @Test fun followUpAfterAFailedUploadWaitsForThePersistedBackoff() {
        reconcile("2026-10-26T10:00")
        val generation = sessions.current()!!.generation
        val scheduleId = store.scheduleSince()!!
        manager.cancelUniqueWork(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).result.get()
        // Temporary Dropbox error after the upload began: no WorkManager retry, throttle recorded instead.
        store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.DROPBOX_TEMPORARY)
        val failedAt = at("2026-10-26T10:05").toEpochMilli()
        store.recordRetryThrottle(account, DropboxBackupSchedule.afterRecoverableFailure(
            store.retryThrottle(account), at("2026-10-26T02:00").toEpochMilli(), generation, failedAt,
        ))
        assertTrue(DropboxBackupScheduler.followUpAfterScheduledRun(
            context, scheduleId, generation, Clock.fixed(Instant.ofEpochMilli(failedAt), zone),
        ))
        assertEquals(TimeUnit.MINUTES.toMillis(15), unfinished(DropboxBackupScheduler.RECOVERY_WORK).single().initialDelayMillis)
    }

    @Test fun successfulBackupClearsTheRetryThrottle() {
        val due = at("2026-10-24T02:00").toEpochMilli()
        store.recordRetryThrottle(account, DropboxRetryThrottle(due, sessions.current()!!.generation, 4, Long.MAX_VALUE))
        store.markSuccess(account, "fingerprint", pending = false, now = at("2026-10-24T11:00").toEpochMilli())
        assertNull(store.retryThrottle(account))
    }

    @Test fun alarmFailureKeepsTheCalendarAlarmAndQueuesATimelyRecovery() {
        reconcile("2026-10-24T10:00")
        val alarm = unfinished(DropboxBackupScheduler.ALARM_WORK).single()
        val result = scheduleAttempt({}, { DropboxBackupScheduler.requestRecovery(context) }) {
            throw IllegalStateException("enqueue failed")
        }
        // Success, not retry: a periodic retry would be postponed until the next 2:00 AM override.
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(WorkInfo.State.ENQUEUED, unfinished(DropboxBackupScheduler.RECOVERY_WORK).single().state)
        assertEquals(alarm.id, unfinished(DropboxBackupScheduler.ALARM_WORK).single().id)
        assertEquals(at("2026-10-25T02:00").toEpochMilli(), alarmTarget())
        // Repeated failures keep a single pending recovery.
        scheduleAttempt({}, { DropboxBackupScheduler.requestRecovery(context) }) { throw IllegalStateException("again") }
        assertEquals(1, unfinished(DropboxBackupScheduler.RECOVERY_WORK).size)
    }

    @Test fun manualSyncFailingLeavesTheOccurrenceRecoverable() {
        store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.DROPBOX_TEMPORARY)
        reconcile("2026-10-24T10:00")
        assertEquals(1, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).size)
    }

    @Test fun offlineFailureIsRequeuedToRunWhenConnectivityReturns() {
        store.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
        reconcile("2026-10-24T10:00")
        val catchUp = unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).single()
        assertEquals(NetworkType.CONNECTED, catchUp.constraints.requiredNetworkType)
    }

    @Test fun permanentFailureBlocksOnlyItsOccurrenceAndSession() {
        val occurrence = at("2026-10-24T02:00").toEpochMilli()
        store.markScheduledBlocked(account, DropboxScheduledBlock(occurrence, sessions.current()!!.generation))
        reconcile("2026-10-24T10:00")
        assertTrue(unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).isEmpty())
        // Reconnecting creates a new session generation, so the backup is attempted again.
        sessions.invalidate()
        sessions.activate(account, StoredDropboxCredential("fake-access", null, "fake-refresh"))
        reconcile("2026-10-24T10:05")
        assertEquals(1, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).size)
    }

    @Test fun weeklyDayChangedWhileWorkIsPendingCancelsObsoleteWorkAndRetargets() = runBlocking {
        store.setFrequency(DropboxBackupFrequency.WEEKLY, now = at("2026-10-01T12:00").toEpochMilli())
        reconcile("2026-10-26T09:00") // Monday after a missed Sunday.
        assertEquals(1, unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).size)
        val oldScheduleId = store.scheduleSince()!!
        store.setWeekday(DayOfWeek.WEDNESDAY, now = at("2026-10-26T09:01").toEpochMilli())
        reconcile("2026-10-26T09:01", scheduleChanged = true)
        assertTrue(unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).isEmpty())
        assertEquals(at("2026-10-28T02:00").toEpochMilli(), alarmTarget())
        // Even if obsolete work had already started, it ends without touching Dropbox.
        assertEquals(ListenableWorker.Result.success(),
            scheduledWorker(at("2026-10-25T02:00").toEpochMilli(), oldScheduleId).doWork())
    }

    @Test fun switchingOffOrManualDuringPendingWorkRemovesAllScheduledWork() {
        reconcile("2026-10-24T10:00")
        store.setFrequency(DropboxBackupFrequency.MANUAL, now = at("2026-10-24T10:01").toEpochMilli())
        reconcile("2026-10-24T10:01", scheduleChanged = true)
        assertTrue(unfinished(DropboxBackupScheduler.ALARM_WORK).isEmpty())
        assertTrue(unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).isEmpty())
        store.setFrequency(DropboxBackupFrequency.OFF, now = at("2026-10-24T10:02").toEpochMilli())
        reconcile("2026-10-24T10:02", scheduleChanged = true)
        assertTrue(unfinished(DropboxBackupScheduler.ALARM_WORK).isEmpty())
    }

    @Test fun disconnectingRemovesScheduledWorkButKeepsPreferences() {
        reconcile("2026-10-24T10:00")
        reconcile("2026-10-24T10:01") { null }
        assertTrue(unfinished(DropboxBackupScheduler.ALARM_WORK).isEmpty())
        assertTrue(unfinished(DropboxBackupScheduler.SCHEDULED_SYNC_WORK).isEmpty())
        assertEquals(DropboxBackupFrequency.DAILY, store.frequency())
    }

    @Test fun cancelledAlarmIsRecoveredOnTheNextReconcile() {
        reconcile("2026-10-24T10:00")
        manager.cancelUniqueWork(DropboxBackupScheduler.ALARM_WORK).result.get()
        assertNull(alarmTarget())
        reconcile("2026-10-24T11:00")
        assertEquals(at("2026-10-25T02:00").toEpochMilli(), alarmTarget())
    }

    @Test fun schedulerFailureIsRetriedAndTheNextReconcileRecovers() {
        val failed = scheduleAttempt({}) {
            DropboxBackupScheduler.reconcile(context, activeSession = { throw IllegalStateException("storage unavailable") })
        }
        assertEquals(ListenableWorker.Result.retry(), failed)
        reconcile("2026-10-24T10:00")
        assertEquals(at("2026-10-25T02:00").toEpochMilli(), alarmTarget())
    }

    @Test fun migrationCancelsIntervalWorkAndKeepsPreferencesAndHistory() {
        val legacy = PeriodicWorkRequestBuilder<DropboxScheduleWorker>(7, TimeUnit.DAYS).setInitialDelay(1, TimeUnit.DAYS).build()
        manager.enqueueUniquePeriodicWork("dropbox-automatic-backup", ExistingPeriodicWorkPolicy.KEEP, legacy).result.get()
        context.getSharedPreferences("localtell_dropbox_backup", Context.MODE_PRIVATE).edit()
            .putString("frequency", "weekly").remove("weekly_day").remove("schedule_version").commit()
        DropboxBackupScheduler.migrate(context)
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(legacy.id).get()!!.state)
        assertEquals(DropboxBackupFrequency.WEEKLY, store.frequency())
        assertEquals(DayOfWeek.SUNDAY, store.weekday())
        assertEquals(at("2026-10-23T02:05").toEpochMilli(), store.lastSuccessfulAt(account))
    }

    private fun scheduledWorker(occurrence: Long, scheduleId: Long): DropboxBackupWorker {
        val session = sessions.current()!!
        return TestListenableWorkerBuilder<DropboxBackupWorker>(context)
            .setInputData(workDataOf(
                DropboxBackupWorker.KEY_ACCOUNT_HASH to account,
                DropboxBackupWorker.KEY_SESSION to session.generation,
                DropboxBackupWorker.KEY_SCHEDULED to true,
                DropboxBackupWorker.KEY_OCCURRENCE to occurrence,
                DropboxBackupWorker.KEY_SCHEDULE_ID to scheduleId,
            ))
            .build()
    }
}
