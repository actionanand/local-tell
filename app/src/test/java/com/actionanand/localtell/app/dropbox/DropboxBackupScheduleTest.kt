package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.dropbox.DropboxBackupFrequency.DAILY
import com.actionanand.localtell.app.dropbox.DropboxBackupFrequency.MANUAL
import com.actionanand.localtell.app.dropbox.DropboxBackupFrequency.OFF
import com.actionanand.localtell.app.dropbox.DropboxBackupFrequency.WEEKLY
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DropboxBackupScheduleTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val newYork = ZoneId.of("America/New_York")
    private val sunday = DayOfWeek.SUNDAY

    // 2026-10-25 is a Sunday.
    private fun at(text: String, zone: ZoneId = kolkata): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test fun dailyBeforeTwoRunsTodayAndAfterTwoRunsTomorrow() {
        assertEquals(at("2026-10-24T02:00"), DropboxBackupSchedule.next(DAILY, sunday, at("2026-10-24T01:59"), kolkata))
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.next(DAILY, sunday, at("2026-10-24T02:00"), kolkata))
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.next(DAILY, sunday, at("2026-10-24T18:00"), kolkata))
    }

    @Test fun weeklyDefaultsToSundayAtTwo() {
        assertEquals(DayOfWeek.SUNDAY, DropboxBackupSchedule.DEFAULT_WEEKDAY)
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.next(WEEKLY, DropboxBackupSchedule.DEFAULT_WEEKDAY, at("2026-10-24T09:00"), kolkata))
    }

    @Test fun everySelectableWeekdayResolvesToItsNextCalendarOccurrence() {
        val now = at("2026-10-21T09:00") // Wednesday
        val expected = mapOf(
            DayOfWeek.MONDAY to "2026-10-26", DayOfWeek.TUESDAY to "2026-10-27", DayOfWeek.WEDNESDAY to "2026-10-28",
            DayOfWeek.THURSDAY to "2026-10-22", DayOfWeek.FRIDAY to "2026-10-23", DayOfWeek.SATURDAY to "2026-10-24",
            DayOfWeek.SUNDAY to "2026-10-25",
        )
        DayOfWeek.entries.forEach { day ->
            val next = DropboxBackupSchedule.next(WEEKLY, day, now, kolkata)!!
            assertEquals(day, next.atZone(kolkata).dayOfWeek)
            assertEquals(at("${expected.getValue(day)}T02:00"), next)
        }
    }

    @Test fun selectedWeekdayUsesTodayWhenStillUpcomingAndNextWeekOncePassed() {
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.next(WEEKLY, sunday, at("2026-10-25T01:00"), kolkata))
        assertEquals(at("2026-11-01T02:00"), DropboxBackupSchedule.next(WEEKLY, sunday, at("2026-10-25T02:30"), kolkata))
    }

    @Test fun missedDailyBackupIsDetectedAndNextRegularBackupStaysAtTwo() {
        val since = at("2026-10-20T12:00").toEpochMilli()
        val lastSuccess = at("2026-10-23T02:05").toEpochMilli()
        val opened = at("2026-10-24T10:00")
        val overdue = DropboxBackupSchedule.overdue(DAILY, sunday, opened, kolkata, since, lastSuccess)
        assertEquals(at("2026-10-24T02:00"), overdue)
        // A catch-up that finishes at 10:00 does not move the anchor.
        val caughtUp = at("2026-10-24T10:01")
        assertNull(DropboxBackupSchedule.overdue(DAILY, sunday, caughtUp, kolkata, since, caughtUp.toEpochMilli()))
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.next(DAILY, sunday, caughtUp, kolkata))
    }

    @Test fun missedWeeklyBackupRecoversOnMondayAndNextStaysSunday() {
        val since = at("2026-10-01T12:00").toEpochMilli()
        val lastSuccess = at("2026-10-18T02:03").toEpochMilli()
        val monday = at("2026-10-26T09:00")
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.overdue(WEEKLY, sunday, monday, kolkata, since, lastSuccess))
        assertEquals(at("2026-11-01T02:00"), DropboxBackupSchedule.next(WEEKLY, sunday, monday, kolkata))
    }

    @Test fun oneSuccessfulBackupSatisfiesEveryAccumulatedMissedOccurrence() {
        val since = at("2026-10-01T12:00").toEpochMilli()
        val lastSuccess = at("2026-10-10T02:00").toEpochMilli()
        val now = at("2026-10-24T10:00")
        // Two weeks of missed days collapse into the single latest occurrence.
        assertEquals(at("2026-10-24T02:00"), DropboxBackupSchedule.overdue(DAILY, sunday, now, kolkata, since, lastSuccess))
        val oneBackup = at("2026-10-24T10:05")
        assertNull(DropboxBackupSchedule.overdue(DAILY, sunday, oneBackup, kolkata, since, oneBackup.toEpochMilli()))
    }

    @Test fun enablingAfterTwoAmDoesNotCreateAnImmediateBackup() {
        val enabledAt = at("2026-10-24T09:00")
        assertNull(DropboxBackupSchedule.overdue(DAILY, sunday, at("2026-10-24T09:01"), kolkata, enabledAt.toEpochMilli(), null))
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.next(DAILY, sunday, enabledAt, kolkata))
        // Unknown anchor (never chosen or not migrated) never counts as missed.
        assertNull(DropboxBackupSchedule.overdue(DAILY, sunday, at("2026-10-30T09:00"), kolkata, null, null))
    }

    @Test fun manualSyncNeverMovesTheScheduleButCanSatisfyAnOverdueOccurrence() {
        val since = at("2026-10-20T12:00").toEpochMilli()
        val manualAt = at("2026-10-24T18:00")
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.next(DAILY, sunday, manualAt, kolkata))
        // A manual sync before 2:00 AM does not satisfy that later occurrence.
        val nextMorning = at("2026-10-25T08:00")
        assertEquals(at("2026-10-25T02:00"),
            DropboxBackupSchedule.overdue(DAILY, sunday, nextMorning, kolkata, since, manualAt.toEpochMilli()))
        // A manual sync after an overdue occurrence covers it, so no second upload is queued.
        val lateManual = at("2026-10-25T07:00").toEpochMilli()
        assertNull(DropboxBackupSchedule.overdue(DAILY, sunday, nextMorning, kolkata, since, lateManual))
    }

    @Test fun recoverableFailuresNeverSuppressCatchUpButPermanentOnesWaitForTheNextOccurrence() {
        val due = at("2026-10-24T02:00")
        val occurrence = due.toEpochMilli()
        assertTrue(DropboxBackupSchedule.shouldCatchUp(due, DropboxBackupStatus.UP_TO_DATE, null, "g1"))
        // Offline, temporary Dropbox errors and interrupted work leave no block, so every opportunity retries.
        for (status in listOf(DropboxBackupStatus.OFFLINE, DropboxBackupStatus.FAILED, DropboxBackupStatus.INTERRUPTED)) {
            assertTrue(DropboxBackupSchedule.shouldCatchUp(due, status, null, "g1"))
        }
        val blocked = DropboxScheduledBlock(occurrence, "g1")
        assertFalse(DropboxBackupSchedule.shouldCatchUp(due, DropboxBackupStatus.FAILED, blocked, "g1"))
        // Reconnecting creates a new session generation and lifts an authorization block.
        assertTrue(DropboxBackupSchedule.shouldCatchUp(due, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, blocked, "g2"))
        // The next occurrence is always attempted again.
        val tomorrow = at("2026-10-25T02:00")
        assertTrue(DropboxBackupSchedule.shouldCatchUp(tomorrow, DropboxBackupStatus.FAILED, blocked, "g1"))
        assertFalse(DropboxBackupSchedule.shouldCatchUp(null, DropboxBackupStatus.UP_TO_DATE, null, "g1"))
    }

    @Test fun failureRecoveryDistinguishesTransientAuthorizationAndPermanentErrors() {
        assertEquals(DropboxFailureRecovery.AUTOMATIC, DropboxBackupSchedule.recovery(DropboxBackupError.NETWORK))
        assertEquals(DropboxFailureRecovery.AUTOMATIC, DropboxBackupSchedule.recovery(DropboxBackupError.DROPBOX_TEMPORARY))
        assertEquals(DropboxFailureRecovery.AFTER_REAUTHORIZATION, DropboxBackupSchedule.recovery(DropboxBackupError.AUTHORIZATION))
        for (permanent in listOf(
            DropboxBackupError.INVALID_BACKUP, DropboxBackupError.DROPBOX_PATH, DropboxBackupError.DROPBOX_PERMISSION,
            DropboxBackupError.DROPBOX_QUOTA, DropboxBackupError.STORAGE, DropboxBackupError.DROPBOX_API,
            DropboxBackupError.UNKNOWN,
        )) {
            assertEquals(DropboxFailureRecovery.NEXT_OCCURRENCE, DropboxBackupSchedule.recovery(permanent))
        }
    }

    @Test fun scheduledRunStartingAfterAManualSuccessIsSkippedButAfterAManualFailureRuns() {
        val since = at("2026-10-20T12:00").toEpochMilli()
        val now = at("2026-10-24T10:00")
        fun due(coveredThrough: Long?) =
            DropboxBackupSchedule.overdue(DAILY, sunday, now, kolkata, since, coveredThrough)?.toEpochMilli()
        val manualSuccess = at("2026-10-24T07:00").toEpochMilli()
        assertEquals(DropboxScheduledRun.SKIP_SATISFIED, DropboxBackupSchedule.scheduledRun(since, since, true, due(manualSuccess)))
        // A failed or cancelled manual sync leaves coverage before the occurrence.
        val olderSuccess = at("2026-10-23T02:05").toEpochMilli()
        assertEquals(DropboxScheduledRun.RUN, DropboxBackupSchedule.scheduledRun(since, since, true, due(olderSuccess)))
        assertEquals(DropboxScheduledRun.RUN, DropboxBackupSchedule.scheduledRun(since, since, true, due(null)))
    }

    @Test fun queuedWorkFromAnObsoleteScheduleNeverRuns() {
        val occurrence = at("2026-10-24T02:00").toEpochMilli()
        // Daily->Weekly, Weekly->Daily and Sunday->Wednesday all re-anchor and change the schedule id.
        assertEquals(DropboxScheduledRun.SKIP_STALE, DropboxBackupSchedule.scheduledRun(1_000L, 2_000L, true, occurrence))
        // Manual only or Off.
        assertEquals(DropboxScheduledRun.SKIP_STALE, DropboxBackupSchedule.scheduledRun(1_000L, 1_000L, false, occurrence))
        // Work queued by the interval-based release carries no schedule id.
        assertEquals(DropboxScheduledRun.SKIP_STALE, DropboxBackupSchedule.scheduledRun(null, 1_000L, true, occurrence))
    }

    @Test fun mondayJobRunningAfterTuesdayTwoAmServesTuesday() {
        val since = at("2026-10-20T12:00").toEpochMilli()
        val sundayCoverage = at("2026-10-25T02:05").toEpochMilli() // Monday 26 Oct was then missed.
        // The Monday job was retrying and only starts on Tuesday 27 Oct at 03:00.
        val due = DropboxBackupSchedule.overdue(DAILY, sunday, at("2026-10-27T03:00"), kolkata, since, sundayCoverage)
        assertEquals(at("2026-10-27T02:00"), due)
        assertEquals(DropboxScheduledRun.RUN, DropboxBackupSchedule.scheduledRun(since, since, true, due?.toEpochMilli()))
        // The next regular occurrence is unchanged by the late catch-up.
        assertEquals(at("2026-10-28T02:00"), DropboxBackupSchedule.next(DAILY, sunday, at("2026-10-27T03:10"), kolkata))
    }

    @Test fun mondayBackupFinishingAfterTuesdayTwoAmSatisfiesTuesdayOnlyWithTheCompleteDataset() {
        val since = at("2026-10-20T12:00").toEpochMilli()
        val snapshot = at("2026-10-27T01:55").toEpochMilli()
        val completed = at("2026-10-27T02:10").toEpochMilli()
        val after = at("2026-10-27T02:11")
        // No edits since the snapshot: the uploaded data is the complete dataset at completion.
        val unchanged = DropboxBackupSchedule.coveredAt(completed, snapshot, changedSinceSnapshot = false)
        assertNull(DropboxBackupSchedule.overdue(DAILY, sunday, after, kolkata, since, unchanged))
        // Edits arrived during the upload: Tuesday still needs a backup of the newer data.
        val changed = DropboxBackupSchedule.coveredAt(completed, snapshot, changedSinceSnapshot = true)
        assertEquals(at("2026-10-27T02:00"), DropboxBackupSchedule.overdue(DAILY, sunday, after, kolkata, since, changed))
    }

    @Test fun followUpAfterAnOverlappingRunOnlyWhenTuesdayIsStillRecoverable() {
        val tuesday = at("2026-10-27T02:00")
        // Monday job failed (recoverable) or was cancelled after Tuesday became due: re-check without waiting for Wednesday.
        assertTrue(DropboxBackupSchedule.followUpAfterScheduledRun(true, tuesday, DropboxBackupStatus.OFFLINE, null, "g1"))
        assertTrue(DropboxBackupSchedule.followUpAfterScheduledRun(true, tuesday, DropboxBackupStatus.INTERRUPTED, null, "g1"))
        // A permanent failure recorded for Monday does not block Tuesday.
        val mondayBlock = DropboxScheduledBlock(at("2026-10-26T02:00").toEpochMilli(), "g1")
        assertTrue(DropboxBackupSchedule.followUpAfterScheduledRun(true, tuesday, DropboxBackupStatus.FAILED, mondayBlock, "g1"))
        // Covered by the Monday success, a schedule change, Tuesday itself blocked, or user action needed.
        assertFalse(DropboxBackupSchedule.followUpAfterScheduledRun(true, null, DropboxBackupStatus.UP_TO_DATE, null, "g1"))
        assertFalse(DropboxBackupSchedule.followUpAfterScheduledRun(false, tuesday, DropboxBackupStatus.OFFLINE, null, "g1"))
        val tuesdayBlock = DropboxScheduledBlock(tuesday.toEpochMilli(), "g1")
        assertFalse(DropboxBackupSchedule.followUpAfterScheduledRun(true, tuesday, DropboxBackupStatus.FAILED, tuesdayBlock, "g1"))
        assertFalse(DropboxBackupSchedule.followUpAfterScheduledRun(true, tuesday, DropboxBackupStatus.CONFLICT, null, "g1"))
    }

    @Test fun alarmIsKeptOnlyWhenExactlyOneUnfinishedJobTargetsTheCurrentOccurrence() {
        val target = at("2026-10-25T02:00").toEpochMilli()
        val tag = DropboxBackupSchedule.alarmTag(target)
        assertTrue(DropboxBackupSchedule.alarmIsCurrent(listOf(setOf(tag)), target))
        // Missing or cancelled (finished work is filtered out by the caller).
        assertFalse(DropboxBackupSchedule.alarmIsCurrent(emptyList(), target))
        // Stale occurrence after a weekday, timezone or clock change.
        assertFalse(DropboxBackupSchedule.alarmIsCurrent(listOf(setOf(DropboxBackupSchedule.alarmTag(target - 86_400_000L))), target))
        assertFalse(DropboxBackupSchedule.alarmIsCurrent(listOf(setOf(tag), setOf(tag)), target))
    }

    @Test fun alarmFailureHandsOffToRecoveryInsteadOfARetryTheCalendarOverrideWouldPostpone() {
        var logged = 0
        var recoveries = 0
        assertEquals(androidx.work.ListenableWorker.Result.success(), scheduleAttempt({ logged++ }, { recoveries++ }) {})
        assertEquals(0, recoveries)
        // The alarm keeps its 2:00 AM override and succeeds; the separate recovery job re-checks soon.
        assertEquals(androidx.work.ListenableWorker.Result.success(),
            scheduleAttempt({ logged++ }, { recoveries++ }) { throw java.util.concurrent.ExecutionException(IllegalStateException("enqueue failed")) })
        assertEquals(1, recoveries)
        // If the hand-off itself fails, WorkManager backoff is the last resort.
        assertEquals(androidx.work.ListenableWorker.Result.retry(),
            scheduleAttempt({ logged++ }, { throw IllegalStateException("database full") }) { throw IllegalStateException("enqueue failed") })
        assertEquals(3, logged)
    }

    @Test fun recoveryJobFailureIsRetriedWithBackoff() {
        var logged = 0
        assertEquals(androidx.work.ListenableWorker.Result.success(), scheduleAttempt({ logged++ }) {})
        assertEquals(androidx.work.ListenableWorker.Result.retry(),
            scheduleAttempt({ logged++ }) { throw java.util.concurrent.ExecutionException(IllegalStateException("enqueue failed")) })
        assertEquals(1, logged)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            scheduleAttempt({ logged++ }) { throw java.util.concurrent.CancellationException("stopped") }
        }
    }

    @Test fun issuesNeedingUserActionNeverTriggerAutomaticRetries() {
        val due = at("2026-10-24T02:00")
        assertFalse(DropboxBackupSchedule.shouldCatchUp(due, DropboxBackupStatus.CONFLICT, null, "g1"))
        assertFalse(DropboxBackupSchedule.shouldCatchUp(due, DropboxBackupStatus.SAVED_LIMIT_EXCEEDED, null, "g1"))
    }

    @Test fun switchingDailyAndWeeklyOrTurningOffChangesOnlyTheCalendarRule() {
        val now = at("2026-10-21T09:00")
        assertEquals(at("2026-10-22T02:00"), DropboxBackupSchedule.next(DAILY, sunday, now, kolkata))
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.next(WEEKLY, sunday, now, kolkata))
        assertNull(DropboxBackupSchedule.next(OFF, sunday, now, kolkata))
        assertNull(DropboxBackupSchedule.next(MANUAL, sunday, now, kolkata))
        assertNull(DropboxBackupSchedule.overdue(OFF, sunday, now, kolkata, 1L, null))
        // Re-anchoring on a switch means earlier occurrences of the new rule are not missed backups.
        assertNull(DropboxBackupSchedule.overdue(WEEKLY, sunday, now, kolkata, now.toEpochMilli(), null))
    }

    @Test fun daylightSavingGapMovesTwoAmForwardAndOverlapUsesTheFirstTwoAm() {
        // 2026-03-08 02:00 does not exist in New York; the occurrence becomes 03:00 EDT.
        val gap = DropboxBackupSchedule.next(DAILY, sunday, at("2026-03-07T12:00", newYork), newYork)!!
        assertEquals(LocalDateTime.parse("2026-03-08T03:00"), LocalDateTime.ofInstant(gap, newYork))
        // 2026-11-01 01:00-02:00 repeats; 02:00 EST is unambiguous and is used once.
        val overlap = DropboxBackupSchedule.next(DAILY, sunday, at("2026-10-31T12:00", newYork), newYork)!!
        assertEquals(LocalDateTime.parse("2026-11-01T02:00"), LocalDateTime.ofInstant(overlap, newYork))
        assertEquals(at("2026-11-02T02:00", newYork), DropboxBackupSchedule.next(DAILY, sunday, overlap, newYork))
    }

    @Test fun timezoneChangeRecomputesTheLocalAnchorInsteadOfKeepingAFixedDelay() {
        val instant = at("2026-10-24T09:00")
        val inKolkata = DropboxBackupSchedule.next(DAILY, sunday, instant, kolkata)!!
        val inNewYork = DropboxBackupSchedule.next(DAILY, sunday, instant, newYork)!!
        assertNotEquals(inKolkata, inNewYork)
        assertEquals(2, inNewYork.atZone(newYork).hour)
        assertEquals(2, inKolkata.atZone(kolkata).hour)
    }

    @Test fun systemClockMovedBackwardDoesNotReportAFutureBackupAsMissed() {
        val since = at("2026-10-20T12:00").toEpochMilli()
        val successInFuture = at("2026-10-26T02:10").toEpochMilli()
        assertNull(DropboxBackupSchedule.overdue(DAILY, sunday, at("2026-10-24T10:00"), kolkata, since, successInFuture))
    }

    @Test fun scheduleStatusReflectsQueuedSyncingWaitingOverdueFailedAndPaused() {
        val due = at("2026-10-24T02:00")
        val ok = DropboxBackupStatus.UP_TO_DATE
        assertEquals(DropboxScheduleState.SCHEDULED, DropboxBackupSchedule.displayState(ok, null, null, false))
        assertEquals(DropboxScheduleState.SYNCING, DropboxBackupSchedule.displayState(DropboxBackupStatus.SYNCING, due, null, true))
        assertEquals(DropboxScheduleState.WAITING_FOR_INTERNET,
            DropboxBackupSchedule.displayState(DropboxBackupStatus.OFFLINE, due, null, true))
        assertEquals(DropboxScheduleState.QUEUED, DropboxBackupSchedule.displayState(ok, due, null, true))
        assertEquals(DropboxScheduleState.OVERDUE, DropboxBackupSchedule.displayState(ok, due, null, false))
        assertEquals(DropboxScheduleState.FAILED,
            DropboxBackupSchedule.displayState(DropboxBackupStatus.FAILED, due, due.toEpochMilli(), false))
        // A recoverable failure stays overdue and recovers automatically.
        assertEquals(DropboxScheduleState.OVERDUE, DropboxBackupSchedule.displayState(DropboxBackupStatus.FAILED, due, null, false))
        assertEquals(DropboxScheduleState.PAUSED,
            DropboxBackupSchedule.displayState(DropboxBackupStatus.REAUTHORIZATION_REQUIRED, due, null, false))
        assertEquals(DropboxScheduleState.PAUSED, DropboxBackupSchedule.displayState(DropboxBackupStatus.CONFLICT, null, null, false))
        // A failed manual sync does not make an up-to-date schedule look overdue.
        assertEquals(DropboxScheduleState.SCHEDULED, DropboxBackupSchedule.displayState(DropboxBackupStatus.FAILED, null, null, false))
    }

    @Test fun repeatedTemporaryFailuresBackOffExponentiallyAndAreCapped() {
        val occurrence = at("2026-10-27T02:00").toEpochMilli()
        var now = at("2026-10-27T02:05").toEpochMilli()
        var throttle: DropboxRetryThrottle? = null
        val delays = (1..8).map {
            throttle = DropboxBackupSchedule.afterRecoverableFailure(throttle, occurrence, "g1", now)
            (throttle!!.notBefore - now).also { now = throttle!!.notBefore }
        }
        assertEquals(listOf(15L, 30L, 60L, 120L, 240L, 360L, 360L, 360L).map { it * 60_000L }, delays)
        assertEquals(8, throttle!!.failures)
    }

    @Test fun failureAfterUploadBeganCannotCreateAOneMinuteLoop() {
        // Upload began, so WorkManager does not retry; the persisted throttle delays the next attempt instead.
        val due = at("2026-10-27T02:00")
        val failedAt = at("2026-10-27T02:20").toEpochMilli()
        val throttle = DropboxBackupSchedule.afterRecoverableFailure(null, due.toEpochMilli(), "g1", failedAt)
        val oneMinuteLater = failedAt + 60_000L
        assertEquals(failedAt + 15 * 60_000L, DropboxBackupSchedule.retryNotBefore(throttle, due, "g1", oneMinuteLater))
        // Repeated foreground events or follow-ups cannot shorten it.
        repeat(10) { assertNotNull(DropboxBackupSchedule.retryNotBefore(throttle, due, "g1", oneMinuteLater + it * 1_000L)) }
    }

    @Test fun exhaustedRetriesDoNotResetIntoAnImmediateBurstWhenANewJobIsCreated() {
        val due = at("2026-10-27T02:00")
        val first = DropboxBackupSchedule.afterRecoverableFailure(null, due.toEpochMilli(), "g1", at("2026-10-27T03:00").toEpochMilli())
        // A recreated job starts its WorkManager attempt count at zero, but the persisted count continues.
        val second = DropboxBackupSchedule.afterRecoverableFailure(first, due.toEpochMilli(), "g1", first.notBefore + 1)
        assertEquals(2, second.failures)
        assertEquals(30 * 60_000L, second.notBefore - (first.notBefore + 1))
        assertNotNull(DropboxBackupSchedule.retryNotBefore(second, due, "g1", first.notBefore + 2))
    }

    @Test fun newOccurrenceOrReconnectionGetsAFreshImmediateAttemptAndExpiryLiftsTheThrottle() {
        val monday = at("2026-10-26T02:00")
        val tuesday = at("2026-10-27T02:00")
        var throttle: DropboxRetryThrottle? = null
        repeat(5) { throttle = DropboxBackupSchedule.afterRecoverableFailure(throttle, monday.toEpochMilli(), "g1", at("2026-10-26T20:00").toEpochMilli()) }
        val tuesdayMorning = at("2026-10-27T02:01").toEpochMilli()
        // A new occurrence is never suppressed by the previous one's backoff.
        assertNull(DropboxBackupSchedule.retryNotBefore(throttle, tuesday, "g1", tuesdayMorning))
        // Reconnecting creates a new session generation.
        assertNull(DropboxBackupSchedule.retryNotBefore(throttle, monday, "g2", tuesdayMorning))
        // Once the backoff has elapsed (e.g. connectivity returned later), the attempt is immediate.
        assertNull(DropboxBackupSchedule.retryNotBefore(throttle, monday, "g1", throttle!!.notBefore))
        assertEquals(1, DropboxBackupSchedule.afterRecoverableFailure(throttle, tuesday.toEpochMilli(), "g1", tuesdayMorning).failures)
    }

    @Test fun overdueBackupWithoutEarlierFailureAttemptsImmediately() {
        val due = at("2026-10-24T02:00")
        assertNull(DropboxBackupSchedule.retryNotBefore(null, due, "g1", at("2026-10-24T10:00").toEpochMilli()))
    }

    @Test fun followUpIsNeverLostBehindARunningRecoveryAndEarlierRequestsWin() {
        val desired = 10_000L
        assertEquals(DropboxRecoveryEnqueue.KEEP, DropboxBackupSchedule.recoveryEnqueue(emptyList(), desired))
        // A running recovery would make KEEP silently drop the request; it runs after it instead.
        assertEquals(DropboxRecoveryEnqueue.APPEND,
            DropboxBackupSchedule.recoveryEnqueue(listOf(DropboxPendingWork(running = true, runAt = 0L)), desired))
        // Already appended behind the running one: keep that single follow-up.
        assertEquals(DropboxRecoveryEnqueue.KEEP, DropboxBackupSchedule.recoveryEnqueue(
            listOf(DropboxPendingWork(true, 0L), DropboxPendingWork(false, Long.MAX_VALUE)), desired))
        // A one-minute follow-up replaces a pending 15-minute recovery; a later request keeps the earlier one.
        assertEquals(DropboxRecoveryEnqueue.REPLACE,
            DropboxBackupSchedule.recoveryEnqueue(listOf(DropboxPendingWork(false, desired + 900_000L)), desired))
        assertEquals(DropboxRecoveryEnqueue.KEEP,
            DropboxBackupSchedule.recoveryEnqueue(listOf(DropboxPendingWork(false, desired - 1L)), desired))
    }

    @Test fun offAndManualNeverShowAnActiveNextBackupAndOnlyTheSelectedRowDoes() {
        val now = at("2026-10-24T10:00")
        for (selected in listOf(OFF, MANUAL)) {
            for (row in DropboxBackupFrequency.entries) {
                assertNull(DropboxBackupSchedule.activeNextBackup(selected, row, sunday, now, kolkata))
            }
        }
        assertEquals(at("2026-10-25T02:00"), DropboxBackupSchedule.activeNextBackup(DAILY, DAILY, sunday, now, kolkata))
        assertNull(DropboxBackupSchedule.activeNextBackup(DAILY, WEEKLY, sunday, now, kolkata))
        assertEquals(at("2026-10-28T02:00"),
            DropboxBackupSchedule.activeNextBackup(WEEKLY, WEEKLY, DayOfWeek.WEDNESDAY, now, kolkata))
        assertNull(DropboxBackupSchedule.activeNextBackup(WEEKLY, DAILY, sunday, now, kolkata))
    }

    @Test fun schedulingUsesSeparateUniqueWorkACalendarOverrideAndNoExactAlarms() {
        val dropbox = "src/main/java/com/actionanand/localtell/app/dropbox"
        val state = File("$dropbox/DropboxBackupState.kt").readText()
        val worker = File("$dropbox/DropboxBackupWorker.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(state.contains(".setNextScheduleTimeOverride(target)"))
        assertTrue(state.contains("ExistingPeriodicWorkPolicy.UPDATE"))
        // REPLACE/APPEND are only used for the tiny recovery re-check, never for the alarm or any sync.
        val recovery = state.substring(state.indexOf("fun requestRecovery("), state.indexOf("fun cancel(context: Context)"))
        val outsideRecovery = state.replace(recovery, "")
        assertTrue(recovery.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertFalse(outsideRecovery.contains("APPEND_OR_REPLACE"))
        assertFalse(outsideRecovery.contains("ExistingWorkPolicy.REPLACE"))
        assertFalse(state.contains("inline fun scheduleAttempt"))
        assertFalse(worker.contains("Result.Retry"))
        assertTrue(state.contains("LEGACY_WORK.forEach"))
        // Scheduled backups never share the manual work name, so a pending manual sync cannot swallow them.
        assertTrue(worker.contains("enqueueUniqueWork(DropboxBackupScheduler.SCHEDULED_SYNC_WORK, ExistingWorkPolicy.KEEP, request)"))
        assertFalse(worker.contains("enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE"))
        assertTrue(worker.contains("setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(worker.contains("BackoffPolicy.EXPONENTIAL"))
        assertFalse(manifest.contains("SCHEDULE_EXACT_ALARM"))
        assertFalse(manifest.contains("USE_EXACT_ALARM"))
        assertFalse(manifest.contains("WAKE_LOCK"))
        assertTrue(manifest.contains("android.intent.action.TIMEZONE_CHANGED"))
        assertTrue(manifest.contains("android.intent.action.TIME_SET"))
    }
}
