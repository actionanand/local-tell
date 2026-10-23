package com.actionanand.localtell.app.dropbox

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val lastAttemptedAt: Long? = null,
    val error: DropboxBackupError? = null,
    val pendingChanges: Boolean = false,
    val conflictPath: String? = null,
    val conflictFingerprint: String? = null,
    val conflictRemoteState: String? = null,
    val diagnostic: DropboxBackupDiagnostic? = null,
)

/** Persisted, account-scoped sync metadata. Tokens and backup contents are never stored here. */
internal class DropboxBackupStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var accountHash: String? = null
    private val mutableState = MutableStateFlow(readState(null))
    val state: StateFlow<DropboxBackupUiState> = mutableState
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == FREQUENCY || key?.startsWith(ACCOUNT_PREFIX) == true) {
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

    fun setFrequency(frequency: DropboxBackupFrequency): Boolean =
        preferences.edit().putString(FREQUENCY, frequency.preferenceValue).commit()

    fun frequency(): DropboxBackupFrequency =
        DropboxBackupFrequency.fromPreference(preferences.getString(FREQUENCY, DropboxBackupFrequency.OFF.preferenceValue))

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

    fun markSuccess(account: String, fingerprint: String, pending: Boolean, now: Long = System.currentTimeMillis()) = synchronized(stateLock) {
        editAccount(account).putString(key(account, STATUS), (if (pending) DropboxBackupStatus.PENDING else DropboxBackupStatus.UP_TO_DATE).name)
            .putLong(key(account, LAST_SUCCESSFUL), now).putString(key(account, BASELINE_FINGERPRINT), fingerprint)
            .putBoolean(key(account, PENDING), pending).remove(key(account, ERROR))
            .remove(key(account, CONFLICT_PATH)).remove(key(account, CONFLICT_FINGERPRINT))
            .remove(key(account, CONFLICT_REMOTE_STATE)).remove(key(account, DIAGNOSTIC)).apply()
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
            .remove(key(account, CONFLICT_REMOTE_STATE)).apply()
    }

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
    ) = synchronized(stateLock) {
        editAccount(account).putString(key(account, STATUS), DropboxBackupStatus.CONFLICT.name)
            .putLong(key(account, LAST_ATTEMPTED), now)
            .putString(key(account, CONFLICT_PATH), path)
            .putString(key(account, CONFLICT_FINGERPRINT), fingerprint)
            .putString(key(account, CONFLICT_REMOTE_STATE), remoteState)
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
            lastAttemptedAt = scopedKey(LAST_ATTEMPTED)?.let { preferences.getLong(it, 0L).takeIf { time -> time > 0 } },
            error = scopedKey(ERROR)?.let { preferences.getString(it, null) }
                ?.let { runCatching { DropboxBackupError.valueOf(it) }.getOrNull() },
            pendingChanges = scopedKey(PENDING)?.let { preferences.getBoolean(it, false) } ?: false,
            conflictPath = scopedKey(CONFLICT_PATH)?.let { preferences.getString(it, null) },
            conflictFingerprint = scopedKey(CONFLICT_FINGERPRINT)?.let { preferences.getString(it, null) },
            conflictRemoteState = scopedKey(CONFLICT_REMOTE_STATE)?.let { preferences.getString(it, null) },
            diagnostic = scopedKey(DIAGNOSTIC)?.let { DropboxBackupDiagnostic.decode(preferences.getString(it, null)) },
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
        const val ACCOUNT_PREFIX = "account."
        const val STATUS = "status"
        const val LAST_SUCCESSFUL = "last_successful"
        const val LAST_ATTEMPTED = "last_attempted"
        const val ERROR = "error"
        const val PENDING = "pending"
        const val BASELINE_FINGERPRINT = "baseline_fingerprint"
        const val CONFLICT_PATH = "conflict_path"
        const val CONFLICT_FINGERPRINT = "conflict_fingerprint"
        const val CONFLICT_REMOTE_STATE = "conflict_remote_state"
        const val DIAGNOSTIC = "diagnostic"
        const val REVISION = "revision"
    }
}

internal object DropboxBackupScheduler {
    const val PERIODIC_WORK = "dropbox-automatic-backup"

    fun update(context: Context, frequency: DropboxBackupFrequency, connected: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val session = DropboxBackupSessionStore(context).current()
        val interval = DropboxBackupSchedulePolicy.periodDays(frequency, connected && session != null)
        if (interval == null) {
            manager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val activeSession = requireNotNull(session)
        val request = PeriodicWorkRequestBuilder<DropboxBackupWorker>(interval, TimeUnit.DAYS)
            .setInputData(androidx.work.workDataOf(
                DropboxBackupWorker.KEY_ACCOUNT_HASH to activeSession.account,
                DropboxBackupWorker.KEY_SESSION to activeSession.generation,
                DropboxBackupWorker.KEY_SCHEDULED to true,
            ))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        // UPDATE keeps one unique job and applies a changed interval without duplicating work.
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(DropboxBackupWorker.MANUAL_WORK)
        }
    }

    fun scheduleConfigured(context: Context) {
        val store = DropboxBackupStateStore(context)
        val connected = DropboxCredentialStore(context).load() is StoredCredentialResult.Available
        update(context, store.frequency(), connected)
        store.close()
    }
}
