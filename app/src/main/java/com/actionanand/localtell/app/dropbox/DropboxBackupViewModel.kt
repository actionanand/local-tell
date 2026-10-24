package com.actionanand.localtell.app.dropbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupRepository
import com.actionanand.localtell.app.backup.SavedPlacesChanges
import com.actionanand.localtell.app.journey.JourneyHistoryChanges
import com.actionanand.localtell.app.saved.SavedPlacesOverflow
import com.actionanand.localtell.app.saved.SavedPlacesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DropboxBackupViewModel(application: Application) : AndroidViewModel(application) {
    private val store = DropboxBackupStateStore(application)
    private val repository = AppBackupRepository(application)
    private val savedPlaces = SavedPlacesRepository(application)
    private var accountHash: String? = null
    private val mutableSavedPlacesExcess = MutableStateFlow(0)
    private var removingOverflow = false
    private var refreshJob: Job? = null
    val state: StateFlow<DropboxBackupUiState> = store.state

    /** Number of Saved Places above the limit after a Dropbox merge; 0 when within the limit. */
    val savedPlacesExcess: StateFlow<Int> = mutableSavedPlacesExcess.asStateFlow()

    init {
        viewModelScope.launch {
            combine(SavedPlacesChanges.version, JourneyHistoryChanges.version) { _, _ -> Unit }
                .collect { refreshPending() }
        }
    }

    fun selectAccount(hash: String?) {
        accountHash = hash
        store.selectAccount(hash)
        if (hash == null) {
            DropboxBackupScheduler.cancel(getApplication())
        } else {
            DropboxBackupScheduler.update(getApplication(), store.frequency(), connected = true)
            refreshPending()
            recoverStaleSync(hash)
        }
    }

    fun setFrequency(frequency: DropboxBackupFrequency) {
        if (!store.setFrequency(frequency)) {
            accountHash?.let { store.markFailure(it, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE) }
            return
        }
        if (!frequency.syncAllowed) {
            WorkManager.getInstance(getApplication()).cancelUniqueWork(DropboxBackupWorker.MANUAL_WORK)
        }
        DropboxBackupScheduler.update(getApplication(), frequency, connected = accountHash != null)
        store.selectAccount(accountHash)
    }

    fun syncNow() {
        val account = accountHash ?: return
        val current = state.value
        if (!current.frequency.syncAllowed || current.status == DropboxBackupStatus.SYNCING) return
        if (mutableSavedPlacesExcess.value > 0) {
            store.markSavedLimitExceeded(account)
            return
        }
        if (!isDropboxNetworkAvailable(getApplication())) {
            store.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
            return
        }
        DropboxBackupWorker.enqueue(getApplication(), account)
    }

    fun useLocalBackup() = resolve(DropboxBackupWorker.ACTION_USE_LOCAL)

    fun mergeDropboxBackup() = resolve(DropboxBackupWorker.ACTION_MERGE_REMOTE)

    fun restoreDropboxBackup() = resolve(DropboxBackupWorker.ACTION_RESTORE_REMOTE)

    /** Deletes only the oldest places beyond the limit, after the user explicitly confirmed it. */
    fun removeOldestExtraPlaces() {
        if (removingOverflow) return
        val confirmedMaximum = mutableSavedPlacesExcess.value
        if (confirmedMaximum == 0) return
        removingOverflow = true
        viewModelScope.launch {
            try {
                savedPlaces.removeOldestOverflow(confirmedMaximum)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                accountHash?.let { store.markFailure(it, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE) }
            } finally {
                removingOverflow = false
            }
        }
    }

    private fun recoverStaleSync(account: String) {
        val current = state.value
        if (current.status != DropboxBackupStatus.SYNCING) return
        val attemptedAt = current.lastAttemptedAt ?: return
        viewModelScope.launch {
            val manager = WorkManager.getInstance(getApplication())
            val running = listOf(DropboxBackupWorker.MANUAL_WORK, DropboxBackupScheduler.PERIODIC_WORK).any { name ->
                manager.getWorkInfosForUniqueWorkFlow(name).first().any { it.state == WorkInfo.State.RUNNING }
            }
            if (!running && account == accountHash) store.recoverStaleSync(account, attemptedAt)
        }
    }

    private fun resolve(action: String) {
        val account = accountHash ?: return
        val current = state.value
        if (current.status != DropboxBackupStatus.CONFLICT || !current.frequency.syncAllowed) return
        if (current.restoreAvailable != (action == DropboxBackupWorker.ACTION_RESTORE_REMOTE)) return
        if (!isDropboxNetworkAvailable(getApplication())) {
            store.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
            return
        }
        DropboxBackupWorker.enqueue(
            getApplication(),
            account,
            action,
            current.conflictPath,
            current.conflictFingerprint.orEmpty(),
            current.conflictRemoteState,
        )
    }

    private fun refreshPending() {
        refreshJob?.cancel()
        val account = accountHash
        val session = DropboxBackupSessionStore(getApplication()).current()
        val revision = account?.let(store::revision)
        refreshJob = viewModelScope.launch {
            try {
                val local = withContext(Dispatchers.IO) { repository.snapshot() }
                if (account != accountHash || session != DropboxBackupSessionStore(getApplication()).current()) return@launch
                val excess = SavedPlacesOverflow.excess(local.savedPlaces.size)
                mutableSavedPlacesExcess.value = excess
                if (account == null || revision == null || session?.account != account) return@launch
                if (excess > 0) {
                    store.markSavedLimitExceeded(account, revision)
                    return@launch
                }
                val baseline = store.baseline(account)
                val localFingerprint = AppBackupCodec.fingerprint(local)
                store.markPending(account, if (baseline == null) !local.isEmpty else localFingerprint != baseline, revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (account != null && account == accountHash && revision == store.revision(account) &&
                    session == DropboxBackupSessionStore(getApplication()).current()) {
                    store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE)
                }
            }
        }
    }

    override fun onCleared() {
        store.close()
        CoroutineScope(Dispatchers.IO).launch {
            repository.close()
            savedPlaces.close()
        }
        super.onCleared()
    }
}
