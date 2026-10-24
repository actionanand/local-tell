package com.actionanand.localtell.app.dropbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Clock
import java.time.DayOfWeek
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal enum class DropboxBackupStatus {
    UP_TO_DATE,
    PENDING,
    SYNCING,
    FAILED,
    CONFLICT,
    OFFLINE,
    REAUTHORIZATION_REQUIRED,
    SAVED_LIMIT_EXCEEDED,
    INTERRUPTED,
}

internal enum class DropboxBackupError {
    NETWORK,
    AUTHORIZATION,
    STORAGE,
    UNKNOWN,
    DROPBOX_API,
    DROPBOX_PATH,
    DROPBOX_PERMISSION,
    DROPBOX_QUOTA,
    INVALID_BACKUP,
    DROPBOX_TEMPORARY,
}

internal data class DropboxBackupUiState(
    val frequency: DropboxBackupFrequency = DropboxBackupFrequency.OFF,
    val status: DropboxBackupStatus = DropboxBackupStatus.UP_TO_DATE,
    val lastSuccessfulAt: Long? = null,
    /** Data up to this time is in Dropbox; earlier than [lastSuccessfulAt] when edits arrived during upload. */
    val coveredThrough: Long? = null,
    val lastAttemptedAt: Long? = null,
    /** When Dropbox data was last restored onto this device; never counts as a backup. */
    val lastRestoredAt: Long? = null,
    val error: DropboxBackupError? = null,
    val pendingChanges: Boolean = false,
    val conflictPath: String? = null,
    val conflictFingerprint: String? = null,
    val conflictRemoteState: String? = null,
    /** The CONFLICT status only offers a restore: this device had no data when Dropbox was checked. */
    val restoreAvailable: Boolean = false,
    val diagnostic: DropboxBackupDiagnostic? = null,
    val weeklyDay: DayOfWeek = DropboxBackupSchedule.DEFAULT_WEEKDAY,
    /** Only occurrences after this time count as missed, so choosing a schedule never triggers an upload. */
    val scheduleSince: Long? = null,
    /** Occurrence whose scheduled backup failed permanently; recoverable failures never set it. */
    val scheduledBlockedOccurrence: Long? = null,
) {
    val reviewDialog: DropboxReviewDialog?
        get() = when {
            status != DropboxBackupStatus.CONFLICT -> null
            restoreAvailable -> DropboxReviewDialog.RESTORE
            else -> DropboxReviewDialog.CONFLICT
        }
}

internal enum class DropboxReviewDialog { RESTORE, CONFLICT }

/** Persisted, account-scoped sync metadata. Tokens and backup contents are never stored here. */
internal class DropboxBackupStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var accountHash: String? = null
    private val mutableState = MutableStateFlow(readState(null))
    val state: StateFlow<DropboxBackupUiState> = mutableState
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == FREQUENCY || key == WEEKLY_DAY || key == SCHEDULE_SINCE || key?.startsWith(ACCOUNT_PREFIX) == true) {
            mutableState.value = readState(accountHash)
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun selectAccount(hash: String?) {
        accountHash = hash
        mutableState.value = readState(hash)
    }

    fun setFrequency(frequency: DropboxBackupFrequency, now: Long = System.currentTimeMillis()): Boolean {
        val editor = preferences.edit().putString(FREQUENCY, frequency.preferenceValue)
        if (frequency != frequency()) editor.putLong(SCHEDULE_SINCE, now)
        return editor.commit()
    }

    fun frequency(): DropboxBackupFrequency =
        DropboxBackupFrequency.fromPreference(preferences.getString(FREQUENCY, DropboxBackupFrequency.OFF.preferenceValue))

    fun weekday(): DayOfWeek = preferences.getString(WEEKLY_DAY, null)
        ?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() } ?: DropboxBackupSchedule.DEFAULT_WEEKDAY

    fun setWeekday(day: DayOfWeek, now: Long = System.currentTimeMillis()): Boolean {
        if (day == weekday() && preferences.contains(WEEKLY_DAY)) return true
        return preferences.edit().putString(WEEKLY_DAY, day.name).putLong(SCHEDULE_SINCE, now).commit()
    }

    fun scheduleSince(): Long? = preferences.getLong(SCHEDULE_SINCE, 0L).takeIf { it > 0 }

    /**
     * Upgrades interval-based settings once. Existing selections are kept, Weekly defaults to Sunday, and
     * the anchor starts now so the upgrade itself never counts as a missed backup. Returns true when run.
     */
    fun migrateSchedule(now: Long = System.currentTimeMillis()): Boolean {
        if (preferences.getInt(SCHEDULE_VERSION, 0) >= CURRENT_SCHEDULE_VERSION) return false
        val editor = preferences.edit().putInt(SCHEDULE_VERSION, CURRENT_SCHEDULE_VERSION)
        if (!preferences.contains(SCHEDULE_SINCE)) editor.putLong(SCHEDULE_SINCE, now)
        editor.putString(WEEKLY_DAY, weekday().name)
        return editor.commit()
    }

    fun status(account: String): DropboxBackupStatus = readStatus(account)

    fun lastSuccessfulAt(account: String): Long? = preferences.getLong(key(account, LAST_SUCCESSFUL), 0L).takeIf { it > 0 }

    fun coveredThrough(account: String): Long? =
        preferences.getLong(key(account, COVERED_THROUGH), 0L).takeIf { it > 0 } ?: lastSuccessfulAt(account)

    fun scheduledBlock(account: String): DropboxScheduledBlock? {
        val occurrence = preferences.getLong(key(account, SCHEDULED_BLOCK_OCCURRENCE), 0L).takeIf { it > 0 } ?: return null
        val generation = preferences.getString(key(account, SCHEDULED_BLOCK_GENERATION), null) ?: return null
        return DropboxScheduledBlock(occurrence, generation)
    }

    // No revision bump: recording a block must not invalidate pending-change refreshes.
    fun markScheduledBlocked(account: String, block: DropboxScheduledBlock) {
        require(account.matches(Regex("[a-f0-9]{64}")))
        preferences.edit().putLong(key(account, SCHEDULED_BLOCK_OCCURRENCE), block.occurrence)
            .putString(key(account, SCHEDULED_BLOCK_GENERATION), block.generation).apply()
    }

    fun retryThrottle(account: String): DropboxRetryThrottle? {
        val occurrence = preferences.getLong(key(account, RETRY_OCCURRENCE), 0L).takeIf { it > 0 } ?: return null
        val generation = preferences.getString(key(account, RETRY_GENERATION), null) ?: return null
        return DropboxRetryThrottle(
            occurrence, generation,
            preferences.getInt(key(account, RETRY_FAILURES), 0), preferences.getLong(key(account, RETRY_NOT_BEFORE), 0L),
        )
    }

    // Committed synchronously: the follow-up scheduled right after reads it from another store instance.
    fun recordRetryThrottle(account: String, throttle: DropboxRetryThrottle): Boolean {
        require(account.matches(Regex("[a-f0-9]{64}")))
        return preferences.edit().putLong(key(account, RETRY_OCCURRENCE), throttle.occurrence)
            .putString(key(account, RETRY_GENERATION), throttle.generation)
            .putInt(key(account, RETRY_FAILURES), throttle.failures)
            .putLong(key(account, RETRY_NOT_BEFORE), throttle.notBefore).commit()
    }

    fun revision(account: String): Long = preferences.getLong(key(account, REVISION), 0L)

    fun beginAttempt(account: String, now: Long = System.currentTimeMillis()) = synchronized(stateLock) {
        editAccount(account).putString(key(account, STATUS), DropboxBackupStatus.SYNCING.name)
            .putLong(key(account, LAST_ATTEMPTED), now).remove(key(account, ERROR))
            .remove(key(account, DIAGNOSTIC)).apply()
    }

    fun markInterrupted(account: String) = synchronized(stateLock) {
        val hasConflict = preferences.contains(key(account, CONFLICT_FINGERPRINT))
        if (readStatus(account) == DropboxBackupStatus.SAVED_LIMIT_EXCEEDED) return@synchronized
        val status = when {
            hasConflict -> DropboxBackupStatus.CONFLICT
            else -> DropboxBackupStatus.INTERRUPTED
        }
        editAccount(account).putString(key(account, STATUS), status.name).apply()
    }

    /**
     * Clears a "Syncing" status left behind when the process died mid-run. [attemptedAt] must still match,
     * so a worker that started after the caller checked WorkManager is never overwritten.
     */
    fun recoverStaleSync(account: String, attemptedAt: Long): Boolean = synchronized(stateLock) {
        if (readStatus(account) != DropboxBackupStatus.SYNCING ||
            preferences.getLong(key(account, LAST_ATTEMPTED), 0L) != attemptedAt) return@synchronized false
        markInterrupted(account)
        true
    }

    fun markSuccess(
        account: String,
        fingerprint: String,
        pending: Boolean,
        now: Long = System.currentTimeMillis(),
        coveredThrough: Long = now,
    ) = synchronized(stateLock) {
        editAccount(account).putString(key(account, STATUS), (if (pending) DropboxBackupStatus.PENDING else DropboxBackupStatus.UP_TO_DATE).name)
            .putLong(key(account, LAST_SUCCESSFUL), now).putLong(key(account, COVERED_THROUGH), coveredThrough)
            .remove(key(account, RETRY_OCCURRENCE)).remove(key(account, RETRY_GENERATION))
            .remove(key(account, RETRY_FAILURES)).remove(key(account, RETRY_NOT_BEFORE))
            .putString(key(account, BASELINE_FINGERPRINT), fingerprint)
            .putBoolean(key(account, PENDING), pending).remove(key(account, ERROR))
            .remove(key(account, CONFLICT_PATH)).remove(key(account, CONFLICT_FINGERPRINT))
            .remove(key(account, CONFLICT_REMOTE_STATE)).remove(key(account, RESTORE_OFFER))
            .remove(key(account, DIAGNOSTIC)).apply()
    }

    fun markPending(account: String, pending: Boolean, expectedRevision: Long) = synchronized(stateLock) {
        if (revision(account) != expectedRevision) return@synchronized
        val builder = editAccount(account).putBoolean(key(account, PENDING), pending)
        val status = readStatus(account)
        when {
            // Called only once Saved Places is back within its limit, so the pause is lifted.
            status == DropboxBackupStatus.SAVED_LIMIT_EXCEEDED ->
                builder.putString(key(account, STATUS), (if (pending) DropboxBackupStatus.PENDING else DropboxBackupStatus.UP_TO_DATE).name)
            pending && status == DropboxBackupStatus.UP_TO_DATE -> builder.putString(key(account, STATUS), DropboxBackupStatus.PENDING.name)
            !pending && status == DropboxBackupStatus.PENDING -> builder.putString(key(account, STATUS), DropboxBackupStatus.UP_TO_DATE.name)
        }
        builder.apply()
    }

    /** After merging Dropbox data into this device, the merged remote snapshot is the new common ancestor. */
    fun adoptBaseline(account: String, fingerprint: String) = synchronized(stateLock) {
        editAccount(account).putString(key(account, BASELINE_FINGERPRINT), fingerprint)
            .remove(key(account, CONFLICT_PATH)).remove(key(account, CONFLICT_FINGERPRINT))
            .remove(key(account, CONFLICT_REMOTE_STATE)).remove(key(account, RESTORE_OFFER)).apply()
    }

    /** Restore adopts the Dropbox snapshot as baseline but leaves the last-successful-backup time untouched. */
    fun markRestored(account: String, fingerprint: String, pending: Boolean, restoredAt: Long?) = synchronized(stateLock) {
        val builder = clearReview(editAccount(account), account, pending)
            .putString(key(account, BASELINE_FINGERPRINT), fingerprint)
        if (restoredAt != null) builder.putLong(key(account, LAST_RESTORED), restoredAt)
        builder.apply()
    }

    /** The reviewed Dropbox backup no longer exists, so there is nothing left to choose. */
    fun clearReview(account: String, pending: Boolean) = synchronized(stateLock) {
        clearReview(editAccount(account), account, pending).apply()
    }

    private fun clearReview(builder: SharedPreferences.Editor, account: String, pending: Boolean): SharedPreferences.Editor =
        builder.putString(key(account, STATUS), (if (pending) DropboxBackupStatus.PENDING else DropboxBackupStatus.UP_TO_DATE).name)
            .putBoolean(key(account, PENDING), pending).remove(key(account, ERROR))
            .remove(key(account, CONFLICT_PATH)).remove(key(account, CONFLICT_FINGERPRINT))
            .remove(key(account, CONFLICT_REMOTE_STATE)).remove(key(account, RESTORE_OFFER))
            .remove(key(account, DIAGNOSTIC))

    fun markSavedLimitExceeded(account: String, expectedRevision: Long? = null) = synchronized(stateLock) {
        if (expectedRevision != null && revision(account) != expectedRevision) return@synchronized
        editAccount(account).putString(key(account, STATUS), DropboxBackupStatus.SAVED_LIMIT_EXCEEDED.name)
            .putBoolean(key(account, PENDING), true).remove(key(account, ERROR)).apply()
    }

    fun baseline(account: String): String? = preferences.getString(key(account, BASELINE_FINGERPRINT), null)

    fun markFailure(
        account: String,
        status: DropboxBackupStatus,
        error: DropboxBackupError?,
        now: Long = System.currentTimeMillis(),
        diagnostic: DropboxBackupDiagnostic? = null,
    ) = synchronized(stateLock) {
        require(status in setOf(DropboxBackupStatus.FAILED, DropboxBackupStatus.OFFLINE, DropboxBackupStatus.REAUTHORIZATION_REQUIRED))
        val builder = editAccount(account).putString(key(account, STATUS), status.name).putLong(key(account, LAST_ATTEMPTED), now)
        if (error == null) builder.remove(key(account, ERROR)) else builder.putString(key(account, ERROR), error.name)
        if (diagnostic == null) builder.remove(key(account, DIAGNOSTIC))
        else builder.putString(key(account, DIAGNOSTIC), diagnostic.encode())
        // Retrying a failed conflict resolution must not lose the user's unresolved conflict.
        if (preferences.contains(key(account, CONFLICT_FINGERPRINT))) {
            builder.putString(key(account, STATUS), DropboxBackupStatus.CONFLICT.name)
        }
        builder.apply()
    }

    fun markConflict(
        account: String,
        path: String?,
        fingerprint: String?,
        now: Long = System.currentTimeMillis(),
        remoteState: String? = null,
        restore: Boolean = false,
    ) = synchronized(stateLock) {
        editAccount(account).putString(key(account, STATUS), DropboxBackupStatus.CONFLICT.name)
            .putLong(key(account, LAST_ATTEMPTED), now)
            .putString(key(account, CONFLICT_PATH), path)
            .putString(key(account, CONFLICT_FINGERPRINT), fingerprint)
            .putString(key(account, CONFLICT_REMOTE_STATE), remoteState)
            .putBoolean(key(account, RESTORE_OFFER), restore)
            .remove(key(account, ERROR))
            .apply()
    }

    fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun readState(account: String?): DropboxBackupUiState {
        fun scopedKey(name: String) = account?.let { key(it, name) }
        return DropboxBackupUiState(
            frequency = frequency(),
            status = scopedKey(STATUS)?.let { preferences.getString(it, null) }
                ?.let { runCatching { DropboxBackupStatus.valueOf(it) }.getOrNull() }
                ?: DropboxBackupStatus.UP_TO_DATE,
            lastSuccessfulAt = scopedKey(LAST_SUCCESSFUL)?.let { preferences.getLong(it, 0L).takeIf { time -> time > 0 } },
            coveredThrough = account?.let(::coveredThrough),
            lastAttemptedAt = scopedKey(LAST_ATTEMPTED)?.let { preferences.getLong(it, 0L).takeIf { time -> time > 0 } },
            lastRestoredAt = scopedKey(LAST_RESTORED)?.let { preferences.getLong(it, 0L).takeIf { time -> time > 0 } },
            error = scopedKey(ERROR)?.let { preferences.getString(it, null) }
                ?.let { runCatching { DropboxBackupError.valueOf(it) }.getOrNull() },
            pendingChanges = scopedKey(PENDING)?.let { preferences.getBoolean(it, false) } ?: false,
            conflictPath = scopedKey(CONFLICT_PATH)?.let { preferences.getString(it, null) },
            conflictFingerprint = scopedKey(CONFLICT_FINGERPRINT)?.let { preferences.getString(it, null) },
            conflictRemoteState = scopedKey(CONFLICT_REMOTE_STATE)?.let { preferences.getString(it, null) },
            restoreAvailable = scopedKey(RESTORE_OFFER)?.let { preferences.getBoolean(it, false) } ?: false,
            diagnostic = scopedKey(DIAGNOSTIC)?.let { DropboxBackupDiagnostic.decode(preferences.getString(it, null)) },
            weeklyDay = weekday(),
            scheduleSince = scheduleSince(),
            scheduledBlockedOccurrence = scopedKey(SCHEDULED_BLOCK_OCCURRENCE)
                ?.let { preferences.getLong(it, 0L).takeIf { time -> time > 0 } },
        )
    }

    private fun editAccount(account: String): SharedPreferences.Editor {
        require(account.matches(Regex("[a-f0-9]{64}")))
        return preferences.edit().putLong(key(account, REVISION), revision(account) + 1L)
    }
    private fun key(account: String, name: String): String = "$ACCOUNT_PREFIX$account.$name"
    private fun readStatus(account: String): DropboxBackupStatus =
        preferences.getString(key(account, STATUS), DropboxBackupStatus.UP_TO_DATE.name)
            ?.let { runCatching { DropboxBackupStatus.valueOf(it) }.getOrNull() }
            ?: DropboxBackupStatus.UP_TO_DATE

    private companion object {
        val stateLock = Any()
        const val PREFERENCES = "localtell_dropbox_backup"
        const val FREQUENCY = "frequency"
        const val WEEKLY_DAY = "weekly_day"
        const val SCHEDULE_SINCE = "schedule_since"
        const val SCHEDULE_VERSION = "schedule_version"
        const val CURRENT_SCHEDULE_VERSION = 3
        const val SCHEDULED_BLOCK_OCCURRENCE = "scheduled_block_occurrence"
        const val SCHEDULED_BLOCK_GENERATION = "scheduled_block_generation"
        const val RETRY_OCCURRENCE = "retry_occurrence"
        const val RETRY_GENERATION = "retry_generation"
        const val RETRY_FAILURES = "retry_failures"
        const val RETRY_NOT_BEFORE = "retry_not_before"
        const val ACCOUNT_PREFIX = "account."
        const val STATUS = "status"
        const val LAST_SUCCESSFUL = "last_successful"
        const val COVERED_THROUGH = "covered_through"
        const val LAST_ATTEMPTED = "last_attempted"
        const val LAST_RESTORED = "last_restored"
        const val ERROR = "error"
        const val PENDING = "pending"
        const val BASELINE_FINGERPRINT = "baseline_fingerprint"
        const val CONFLICT_PATH = "conflict_path"
        const val CONFLICT_FINGERPRINT = "conflict_fingerprint"
        const val CONFLICT_REMOTE_STATE = "conflict_remote_state"
        const val RESTORE_OFFER = "restore_offer"
        const val DIAGNOSTIC = "diagnostic"
        const val REVISION = "revision"
    }
}

/**
 * Calendar-anchored scheduling. A unique periodic "alarm" job has its next run overridden to the next
 * local 2:00 AM occurrence; each run re-applies the override with UPDATE, so there is no growing chain
 * and the period is only a fallback if an override could not be stored. Scheduled backups use their own
 * unique work name so they can never be swallowed by a pending manual sync; the worker mutex keeps
 * every sync serial, and a scheduled run re-checks whether it is still needed before touching Dropbox.
 */
internal object DropboxBackupScheduler {
    const val ALARM_WORK = "dropbox-calendar-alarm"
    const val SCHEDULED_SYNC_WORK = "dropbox-scheduled-sync"
    /** One-time re-check after a scheduler failure or an unfinished scheduled run; never moves the alarm. */
    const val RECOVERY_WORK = "dropbox-schedule-recovery"
    const val RECOVERY_DELAY_MINUTES = 15L
    const val FOLLOW_UP_DELAY_MINUTES = 1L
    /** Interval-based periodic work from earlier releases and the pre-release one-time alarm. */
    val LEGACY_WORK = listOf("dropbox-automatic-backup", "dropbox-scheduled-backup")
    private const val TAG = "DropboxBackupScheduler"
    private val lock = Any()
    private val background = Executors.newSingleThreadExecutor { Thread(it, "dropbox-schedule").apply { isDaemon = true } }

    /**
     * Re-evaluates the schedule off the main thread; repeated calls never duplicate work. A failure is
     * logged and recovered by the next trigger (foreground, alarm fallback period, clock change).
     */
    fun reconcileAsync(context: Context, scheduleChanged: Boolean = false, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        try {
            background.execute {
                try {
                    reconcile(app, scheduleChanged = scheduleChanged)
                } catch (error: Exception) {
                    Log.w(TAG, "Schedule reconciliation failed: ${error.javaClass.simpleName}")
                } finally {
                    onDone()
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Schedule reconciliation rejected: ${error.javaClass.simpleName}")
            onDone()
        }
    }

    /**
     * Arms the alarm for the next regular occurrence and, when an earlier occurrence was missed, queues one
     * network-constrained catch-up backup. Reads WorkManager state instead of trusting earlier enqueues and
     * waits for each operation, so a failure throws and is retried. Must not run on the main thread.
     */
    fun reconcile(
        context: Context,
        scheduleChanged: Boolean = false,
        clock: Clock = Clock.systemDefaultZone(),
        activeSession: () -> DropboxBackupSession? = { connectedSession(context) },
    ) = synchronized(lock) {
        val app = context.applicationContext
        val manager = WorkManager.getInstance(app)
        if (!CloudBackupFeature.enabled) {
            cancel(app)
            return@synchronized
        }
        // Work queued for the previous configuration must not run; the worker also re-checks the schedule id.
        if (scheduleChanged) manager.cancelUniqueWork(SCHEDULED_SYNC_WORK).result.get()
        val store = DropboxBackupStateStore(app)
        try {
            val frequency = store.frequency()
            val weekday = store.weekday()
            val session = activeSession()
            val now = clock.instant()
            val period = DropboxBackupSchedulePolicy.periodDays(frequency, connected = session != null)
            val next = period?.let { DropboxBackupSchedule.next(frequency, weekday, now, clock.zone) }
            if (session == null || period == null || next == null) {
                // Also removes a running alarm, whose fallback period would otherwise keep waking it.
                manager.cancelUniqueWork(ALARM_WORK).result.get()
                manager.cancelUniqueWork(SCHEDULED_SYNC_WORK).result.get()
                manager.cancelUniqueWork(RECOVERY_WORK).result.get()
                return@synchronized
            }
            val target = next.toEpochMilli()
            val unfinished = manager.getWorkInfosForUniqueWork(ALARM_WORK).get()
                .filter { !it.state.isFinished }.map { it.tags }
            if (!DropboxBackupSchedule.alarmIsCurrent(unfinished, target)) {
                val alarm = PeriodicWorkRequestBuilder<DropboxScheduleWorker>(period, TimeUnit.DAYS)
                    .setNextScheduleTimeOverride(target)
                    .addTag(DropboxBackupSchedule.alarmTag(target))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .build()
                // UPDATE never cancels a running alarm; a change made from inside it applies to the next run.
                manager.enqueueUniquePeriodicWork(ALARM_WORK, ExistingPeriodicWorkPolicy.UPDATE, alarm).result.get()
            }
            val account = session.account
            val overdue = DropboxBackupSchedule.overdue(
                frequency, weekday, now, clock.zone, store.scheduleSince(), store.coveredThrough(account),
            )
            if (DropboxBackupSchedule.shouldCatchUp(overdue, store.status(account), store.scheduledBlock(account), session.generation)) {
                val due = checkNotNull(overdue)
                // Immediate unless earlier runs of this occurrence failed; then wait out the persisted backoff.
                val notBefore = DropboxBackupSchedule.retryNotBefore(
                    store.retryThrottle(account), due, session.generation, now.toEpochMilli(),
                )
                DropboxBackupWorker.enqueueScheduled(
                    app, session, due.toEpochMilli(), checkNotNull(store.scheduleSince()),
                    delayMillis = notBefore?.let { it - now.toEpochMilli() } ?: 0L,
                )
            }
        } finally {
            store.close()
        }
    }

    private fun connectedSession(context: Context): DropboxBackupSession? {
        val session = DropboxBackupSessionStore(context).current() ?: return null
        return session.takeIf { DropboxCredentialStore(context).load() is StoredCredentialResult.Available }
    }

    /**
     * Called when a scheduled run ends without a WorkManager retry. If an occurrence that became due
     * meanwhile is still unsatisfied and recoverable, a short follow-up re-check is queued; it cannot
     * re-enqueue directly because this run still holds the unique scheduled-sync name. Returns true if queued.
     */
    fun followUpAfterScheduledRun(
        context: Context,
        scheduleId: Long,
        sessionGeneration: String?,
        clock: Clock = Clock.systemDefaultZone(),
    ): Boolean {
        val app = context.applicationContext
        val session = DropboxBackupSessionStore(app).current()?.takeIf { it.generation == sessionGeneration } ?: return false
        val store = DropboxBackupStateStore(app)
        val now = clock.millis()
        var notBefore: Long? = null
        val needed = try {
            val frequency = store.frequency()
            val overdue = DropboxBackupSchedule.overdue(
                frequency, store.weekday(), clock.instant(), clock.zone, store.scheduleSince(), store.coveredThrough(session.account),
            )
            notBefore = overdue?.let {
                DropboxBackupSchedule.retryNotBefore(store.retryThrottle(session.account), it, session.generation, now)
            }
            DropboxBackupSchedule.followUpAfterScheduledRun(
                scheduleCurrent = frequency.periodDays != null && scheduleId == store.scheduleSince(),
                overdue = overdue,
                status = store.status(session.account),
                block = store.scheduledBlock(session.account),
                sessionGeneration = session.generation,
            )
        } finally {
            store.close()
        }
        if (needed) {
            val delay = maxOf(TimeUnit.MINUTES.toMillis(FOLLOW_UP_DELAY_MINUTES), (notBefore ?: now) - now)
            requestRecovery(app, delay, clock)
        }
        return needed
    }

    /**
     * A separate one-time job, so the alarm's calendar override (which takes precedence over a periodic
     * worker's retry backoff) is never disturbed. An earlier pending request wins; a request made while a
     * recovery is running runs after it instead of being dropped.
     */
    fun requestRecovery(
        context: Context,
        delayMillis: Long = TimeUnit.MINUTES.toMillis(RECOVERY_DELAY_MINUTES),
        clock: Clock = Clock.systemDefaultZone(),
    ) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val existing = manager.getWorkInfosForUniqueWork(RECOVERY_WORK).get()
            .filter { !it.state.isFinished }
            .map { DropboxPendingWork(it.state == WorkInfo.State.RUNNING, it.nextScheduleTimeMillis) }
        val policy = when (DropboxBackupSchedule.recoveryEnqueue(existing, clock.millis() + delayMillis)) {
            DropboxRecoveryEnqueue.KEEP -> ExistingWorkPolicy.KEEP
            DropboxRecoveryEnqueue.REPLACE -> ExistingWorkPolicy.REPLACE
            DropboxRecoveryEnqueue.APPEND -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        val request = OneTimeWorkRequestBuilder<DropboxScheduleWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DropboxScheduleWorker.KEY_RECOVERY to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RECOVERY_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniqueWork(RECOVERY_WORK, policy, request).result.get()
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork(ALARM_WORK)
            cancelUniqueWork(SCHEDULED_SYNC_WORK)
            cancelUniqueWork(RECOVERY_WORK)
            LEGACY_WORK.forEach { cancelUniqueWork(it) }
            cancelUniqueWork(DropboxBackupWorker.MANUAL_WORK)
        }
    }

    /** One-time upgrade: keeps preferences and history, removes interval-based or pre-release work. */
    fun migrate(context: Context) {
        val app = context.applicationContext
        val store = DropboxBackupStateStore(app)
        val migrated = try { store.migrateSchedule() } finally { store.close() }
        if (migrated) WorkManager.getInstance(app).apply { LEGACY_WORK.forEach { cancelUniqueWork(it).result.get() } }
    }

    fun scheduleConfigured(context: Context) {
        if (CloudBackupFeature.startupAction() == CloudBackupFeature.StartupAction.CANCEL_SCHEDULED_WORK) {
            // Only WorkManager jobs are removed; credentials, sessions and backup files stay untouched.
            cancel(context)
            return
        }
        val app = context.applicationContext
        try {
            background.execute {
                try {
                    migrate(app)
                } catch (error: Exception) {
                    Log.w(TAG, "Schedule migration failed: ${error.javaClass.simpleName}")
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Schedule migration rejected: ${error.javaClass.simpleName}")
        }
        reconcileAsync(app)
    }
}

/** Runs at (or, when Android defers it, after) a scheduled occurrence; never uploads by itself. */
internal class DropboxScheduleWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = guardCloudBackupWork(
        enabled = CloudBackupFeature.enabled,
        onDisabled = {
            DropboxBackupScheduler.cancel(applicationContext)
            Result.success()
        },
    ) {
        withContext(Dispatchers.IO) {
            val recovery = inputData.getBoolean(KEY_RECOVERY, false)
            scheduleAttempt(
                onError = { Log.w("DropboxScheduleWorker", "Scheduled reconcile failed: ${it.javaClass.simpleName}") },
                // The alarm's retry would be postponed by its own calendar override, so it hands off instead.
                recover = if (recovery) null else ({ DropboxBackupScheduler.requestRecovery(applicationContext) }),
            ) {
                DropboxBackupScheduler.reconcile(applicationContext)
            }
        }
    }

    companion object {
        const val KEY_RECOVERY = "recovery"
    }
}

/**
 * A scheduler failure never silently ends automatic backups: [recover] hands off to the recovery job, or,
 * when there is none (the recovery job itself) or the hand-off also fails, WorkManager retries with backoff.
 */
internal fun scheduleAttempt(
    onError: (Exception) -> Unit,
    recover: (() -> Unit)? = null,
    block: () -> Unit,
): ListenableWorker.Result = try {
    block()
    ListenableWorker.Result.success()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onError(error)
    try {
        if (recover == null) ListenableWorker.Result.retry() else {
            recover()
            ListenableWorker.Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (handOff: Exception) {
        onError(handOff)
        ListenableWorker.Result.retry()
    }
}

/** Timezone or manual clock changes move the local 2:00 AM anchor, so the alarm is recomputed. */
internal class DropboxScheduleTimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!CloudBackupFeature.enabled) return
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED && intent.action != Intent.ACTION_TIME_CHANGED) return
        val pending = goAsync()
        DropboxBackupScheduler.reconcileAsync(context) { pending.finish() }
    }
}
