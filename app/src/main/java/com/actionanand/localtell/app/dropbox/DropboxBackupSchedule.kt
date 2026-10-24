package com.actionanand.localtell.app.dropbox

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** What the schedule UI reports for the active Daily/Weekly schedule. */
internal enum class DropboxScheduleState { SCHEDULED, QUEUED, SYNCING, WAITING_FOR_INTERNET, OVERDUE, FAILED, PAUSED }

/**
 * Calendar-anchored Daily/Weekly schedule. Occurrences are always local 2:00 AM on calendar dates, so
 * delayed, catch-up or manual backups never shift later occurrences. All inputs are explicit for testing.
 */
internal object DropboxBackupSchedule {
    val PREFERRED_TIME: LocalTime = LocalTime.of(2, 0)
    val DEFAULT_WEEKDAY: DayOfWeek = DayOfWeek.SUNDAY

    // ZonedDateTime.of moves a DST-gap time forward and picks the earlier offset in an overlap.
    fun occurrenceOn(date: LocalDate, zone: ZoneId): Instant = ZonedDateTime.of(date, PREFERRED_TIME, zone).toInstant()

    /** First regular occurrence strictly after [now], or null when the frequency has no schedule. */
    fun next(frequency: DropboxBackupFrequency, weekday: DayOfWeek, now: Instant, zone: ZoneId): Instant? {
        val today = now.atZone(zone).toLocalDate()
        val (first, step) = when (frequency) {
            DropboxBackupFrequency.DAILY -> today to 1L
            DropboxBackupFrequency.WEEKLY -> today.with(TemporalAdjusters.nextOrSame(weekday)) to 7L
            else -> return null
        }
        return generateSequence(first) { it.plusDays(step) }.map { occurrenceOn(it, zone) }.first { it.isAfter(now) }
    }

    /** Most recent regular occurrence at or before [now]. */
    fun latest(frequency: DropboxBackupFrequency, weekday: DayOfWeek, now: Instant, zone: ZoneId): Instant? {
        val today = now.atZone(zone).toLocalDate()
        val (first, step) = when (frequency) {
            DropboxBackupFrequency.DAILY -> today to 1L
            DropboxBackupFrequency.WEEKLY -> today.with(TemporalAdjusters.previousOrSame(weekday)) to 7L
            else -> return null
        }
        return generateSequence(first) { it.minusDays(step) }.map { occurrenceOn(it, zone) }.first { !it.isAfter(now) }
    }

    /**
     * The missed occurrence that still needs a backup. Only occurrences after the schedule was chosen
     * count, and any successful backup at or after it satisfies it together with all earlier ones.
     */
    fun overdue(
        frequency: DropboxBackupFrequency,
        weekday: DayOfWeek,
        now: Instant,
        zone: ZoneId,
        scheduledSince: Long?,
        lastSuccessfulAt: Long?,
    ): Instant? {
        val due = latest(frequency, weekday, now, zone) ?: return null
        if (scheduledSince == null || due.toEpochMilli() <= scheduledSince) return null
        if (lastSuccessfulAt != null && lastSuccessfulAt >= due.toEpochMilli()) return null
        return due
    }

    /**
     * Recoverable failures (offline, temporary Dropbox errors, interrupted work) never block recovery.
     * A permanent or authorization failure blocks only its own occurrence for the same Dropbox session,
     * so reconnecting or the next occurrence tries again. User-action states wait for the user.
     */
    fun shouldCatchUp(
        overdue: Instant?,
        status: DropboxBackupStatus,
        block: DropboxScheduledBlock?,
        sessionGeneration: String?,
    ): Boolean {
        if (overdue == null) return false
        if (status == DropboxBackupStatus.CONFLICT || status == DropboxBackupStatus.SAVED_LIMIT_EXCEEDED) return false
        return block == null || block.occurrence != overdue.toEpochMilli() || block.generation != sessionGeneration
    }

    /**
     * Re-checked whenever queued scheduled work starts. The run always serves the occurrence that is due
     * *now*, so a Monday job that only runs on Tuesday backs up Tuesday's data instead of being dropped.
     */
    fun scheduledRun(
        queuedScheduleId: Long?,
        currentScheduleId: Long?,
        scheduleActive: Boolean,
        dueOccurrence: Long?,
    ): DropboxScheduledRun = when {
        !scheduleActive || queuedScheduleId == null || queuedScheduleId != currentScheduleId -> DropboxScheduledRun.SKIP_STALE
        dueOccurrence == null -> DropboxScheduledRun.SKIP_SATISFIED
        else -> DropboxScheduledRun.RUN
    }

    /**
     * After a scheduled run ends without a WorkManager retry, another run is needed when an occurrence is
     * still unsatisfied and recoverable, e.g. a later occurrence became due while this run was in progress.
     */
    fun followUpAfterScheduledRun(
        scheduleCurrent: Boolean,
        overdue: Instant?,
        status: DropboxBackupStatus,
        block: DropboxScheduledBlock?,
        sessionGeneration: String?,
    ): Boolean = scheduleCurrent && shouldCatchUp(overdue, status, block, sessionGeneration)

    /**
     * When a backup completes with no local edits since its snapshot, it covers everything up to completion;
     * otherwise it only covers data up to the snapshot, so a later occurrence still gets a fresh backup.
     */
    fun coveredAt(completedAt: Long, snapshotAt: Long, changedSinceSnapshot: Boolean): Long =
        if (changedSinceSnapshot) minOf(snapshotAt, completedAt) else completedAt

    fun recovery(error: DropboxBackupError): DropboxFailureRecovery = when (error) {
        DropboxBackupError.NETWORK, DropboxBackupError.DROPBOX_TEMPORARY -> DropboxFailureRecovery.AUTOMATIC
        DropboxBackupError.AUTHORIZATION -> DropboxFailureRecovery.AFTER_REAUTHORIZATION
        else -> DropboxFailureRecovery.NEXT_OCCURRENCE
    }

    fun alarmTag(target: Long): String = "dropbox-alarm-target:$target"

    /** Only the selected Daily/Weekly option has a real next backup; other rows show just their rule. */
    fun activeNextBackup(
        selected: DropboxBackupFrequency,
        row: DropboxBackupFrequency,
        weekday: DayOfWeek,
        now: Instant,
        zone: ZoneId,
    ): Instant? = if (row == selected) next(row, weekday, now, zone) else null

    const val RETRY_BASE_MILLIS = 15L * 60_000L
    const val RETRY_MAX_MILLIS = 6L * 60L * 60_000L

    /** Doubles from 15 minutes per consecutive failed run of the same occurrence, capped at 6 hours. */
    fun retryDelayMillis(failures: Int): Long =
        (RETRY_BASE_MILLIS shl (failures - 1).coerceIn(0, 10)).coerceAtMost(RETRY_MAX_MILLIS)

    /**
     * Durable throttle after a scheduled run ends with a recoverable failure. The count survives job
     * recreation, so a new job cannot restart a rapid burst; a new occurrence or session starts at one.
     */
    fun afterRecoverableFailure(
        previous: DropboxRetryThrottle?,
        occurrence: Long,
        generation: String,
        now: Long,
    ): DropboxRetryThrottle {
        val failures = if (previous != null && previous.occurrence == occurrence && previous.generation == generation) {
            previous.failures + 1
        } else 1
        return DropboxRetryThrottle(occurrence, generation, failures, now + retryDelayMillis(failures))
    }

    /** Earliest automatic attempt for [overdue]; null means attempt immediately. */
    fun retryNotBefore(throttle: DropboxRetryThrottle?, overdue: Instant, sessionGeneration: String?, now: Long): Long? =
        throttle?.takeIf {
            it.occurrence == overdue.toEpochMilli() && it.generation == sessionGeneration && it.notBefore > now
        }?.notBefore

    /**
     * How to queue a recovery re-check at [desiredAt] given the existing unique work. A running recovery
     * cannot be replaced or kept (that would lose the request), so the new one runs after it.
     */
    fun recoveryEnqueue(existing: List<DropboxPendingWork>, desiredAt: Long): DropboxRecoveryEnqueue {
        val waiting = existing.filter { !it.running }
        return when {
            waiting.isNotEmpty() -> if (existing.none { it.running } && waiting.all { it.runAt > desiredAt }) {
                DropboxRecoveryEnqueue.REPLACE
            } else DropboxRecoveryEnqueue.KEEP
            existing.any { it.running } -> DropboxRecoveryEnqueue.APPEND
            else -> DropboxRecoveryEnqueue.KEEP
        }
    }

    /** Only an unfinished alarm already aimed at [target] is kept; missing, cancelled or stale ones are re-armed. */
    fun alarmIsCurrent(unfinishedAlarmTags: List<Set<String>>, target: Long): Boolean =
        unfinishedAlarmTags.size == 1 && alarmTag(target) in unfinishedAlarmTags.single()

    fun displayState(
        status: DropboxBackupStatus,
        overdue: Instant?,
        blockedOccurrence: Long?,
        queued: Boolean,
    ): DropboxScheduleState = when {
        status == DropboxBackupStatus.SYNCING -> DropboxScheduleState.SYNCING
        status == DropboxBackupStatus.CONFLICT || status == DropboxBackupStatus.SAVED_LIMIT_EXCEEDED ||
            status == DropboxBackupStatus.REAUTHORIZATION_REQUIRED -> DropboxScheduleState.PAUSED
        overdue == null -> DropboxScheduleState.SCHEDULED
        blockedOccurrence == overdue.toEpochMilli() -> DropboxScheduleState.FAILED
        status == DropboxBackupStatus.OFFLINE -> DropboxScheduleState.WAITING_FOR_INTERNET
        queued -> DropboxScheduleState.QUEUED
        else -> DropboxScheduleState.OVERDUE
    }
}

/** A scheduled occurrence that failed permanently for one Dropbox session generation. */
internal data class DropboxScheduledBlock(val occurrence: Long, val generation: String)

/** Persisted backoff for repeated recoverable failures of one occurrence in one Dropbox session. */
internal data class DropboxRetryThrottle(val occurrence: Long, val generation: String, val failures: Int, val notBefore: Long)

/** Unfinished recovery work as seen in WorkManager. */
internal data class DropboxPendingWork(val running: Boolean, val runAt: Long)

internal enum class DropboxRecoveryEnqueue { KEEP, REPLACE, APPEND }

internal enum class DropboxScheduledRun { RUN, SKIP_STALE, SKIP_SATISFIED }

internal enum class DropboxFailureRecovery { AUTOMATIC, AFTER_REAUTHORIZATION, NEXT_OCCURRENCE }
