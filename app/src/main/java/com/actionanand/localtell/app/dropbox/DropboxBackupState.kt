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
}

internal enum class DropboxBackupError {
    NETWORK,
    AUTHORIZATION,
    STORAGE,
    UNKNOWN,
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

    fun beginAttempt(account: String, now: Long = System.currentTimeMillis()) {
        editAccount(account).putString(key(account, STATUS), DropboxBackupStatus.SYNCING.name)
            .putLong(key(account, LAST_ATTEMPTED), now).remove(key(account, ERROR)).apply()
    }

    fun markInterrupted(account: String) {
        val hasConflict = preferences.contains(key(account, CONFLICT_FINGERPRINT))
        val pending = preferences.getBoolean(key(account, PENDING), false)
        if (readStatus(account) == DropboxBackupStatus.SAVED_LIMIT_EXCEEDED) return
        val status = when {
            hasConflict -> DropboxBackupStatus.CONFLICT
            pending -> DropboxBackupStatus.PENDING
            else -> DropboxBackupStatus.UP_TO_DATE
        }
        editAccount(account).putString(key(account, STATUS), status.name).apply()
    }

    fun markSuccess(account: String, fingerprint: String, now: Long = System.currentTimeMillis()) {
        editAccount(account).putString(key(account, STATUS), DropboxBackupStatus.UP_TO_DATE.name)
            .putLong(key(account, LAST_SUCCESSFUL), now).putString(key(account, BASELINE_FINGERPRINT), fingerprint)
            .putBoolean(key(account, PENDING), false).remove(key(account, ERROR))
            .remove(key(account, CONFLICT_PATH)).remove(key(account, CONFLICT_FINGERPRINT)).apply()
    }

    fun markPending(account: String, pending: Boolean) {
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
    fun adoptBaseline(account: String, fingerprint: String) {
        editAccount(account).putString(key(account, BASELINE_FINGERPRINT), fingerprint)
            .remove(key(account, CONFLICT_PATH)).remove(key(account, CONFLICT_FINGERPRINT)).apply()
    }

    fun markSavedLimitExceeded(account: String) {
        editAccount(account).putString(key(account, STATUS), DropboxBackupStatus.SAVED_LIMIT_EXCEEDED.name)
            .putBoolean(key(account, PENDING), true).remove(key(account, ERROR)).apply()
    }

    fun baseline(account: String): String? = preferences.getString(key(account, BASELINE_FINGERPRINT), null)

    fun markFailure(
        account: String,
        status: DropboxBackupStatus,
        error: DropboxBackupError?,
        now: Long = System.currentTimeMillis(),
    ) {
        require(status in setOf(DropboxBackupStatus.FAILED, DropboxBackupStatus.OFFLINE, DropboxBackupStatus.REAUTHORIZATION_REQUIRED))
        val builder = editAccount(account).putString(key(account, STATUS), status.name).putLong(key(account, LAST_ATTEMPTED), now)
        if (error == null) builder.remove(key(account, ERROR)) else builder.putString(key(account, ERROR), error.name)
        builder.remove(key(account, CONFLICT_PATH)).remove(key(account, CONFLICT_FINGERPRINT)).apply()
    }

    fun markConflict(
        account: String,
        path: String?,
        fingerprint: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        editAccount(account).putString(key(account, STATUS), DropboxBackupStatus.CONFLICT.name)
            .putLong(key(account, LAST_ATTEMPTED), now)
            .putString(key(account, CONFLICT_PATH), path)
            .putString(key(account, CONFLICT_FINGERPRINT), fingerprint)
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
        )
    }

    private fun editAccount(account: String): SharedPreferences.Editor {
        require(account.matches(Regex("[a-f0-9]{64}")))
        return preferences.edit()
    }
    private fun key(account: String, name: String): String = "$ACCOUNT_PREFIX$account.$name"
    private fun readStatus(account: String): DropboxBackupStatus =
        preferences.getString(key(account, STATUS), DropboxBackupStatus.UP_TO_DATE.name)
            ?.let { runCatching { DropboxBackupStatus.valueOf(it) }.getOrNull() }
            ?: DropboxBackupStatus.UP_TO_DATE

    private companion object {
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
    }
}

internal object DropboxBackupScheduler {
    private const val PERIODIC_WORK = "dropbox-automatic-backup"

    fun update(context: Context, frequency: DropboxBackupFrequency, connected: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val interval = DropboxBackupSchedulePolicy.periodDays(frequency, connected)
        if (interval == null) {
            manager.cancelUniquePeriodicWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<DropboxBackupWorker>(interval, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        // UPDATE keeps one unique job and applies a changed interval without duplicating work.
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).apply {
            cancelUniquePeriodicWork(PERIODIC_WORK)
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
