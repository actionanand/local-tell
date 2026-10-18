package com.actionanand.localtell.app.backup

import com.actionanand.localtell.app.saved.*

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppBackupUiState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val message: Int? = null,
    val messageSequence: Long = 0,
    val restore: AppBackupData? = null,
    val preview: AppRestorePlan? = null,
    val savedCount: Int = 0,
    val journeyCount: Int = 0,
    val preparedBackup: PreparedBackup? = null,
    val chooseBackupDestination: Boolean = false,
)

/** Shared app-data backup owner. Provider and SQLite work run on IO. */
class AppBackupViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = AppBackupRepository(app)
    private val snapshots by lazy {
        BackupSnapshots(java.io.File(getApplication<Application>().filesDir, "saved-place-backups"))
    }
    private val snapshotCleanup = CoroutineScope(Dispatchers.IO)
    private val mutableState = MutableStateFlow(AppBackupUiState())
    val state = mutableState.asStateFlow()

    private val initialLoad = load()
    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(SavedPlacesChanges.version,
                com.actionanand.localtell.app.journey.JourneyHistoryChanges.version) { a, b -> a to b }
                .collect {
                    try { updateCounts() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { reportFailure(failure, R.string.backup_error) }
                }
        }
    }

    private suspend fun updateCounts() {
        val data = repository.snapshot()
        mutableState.value = mutableState.value.copy(loaded = true, savedCount = data.savedPlaces.size, journeyCount = data.journeys.size)
    }
    private fun load() = operation { updateCounts() }
    fun refresh() { load() }

    private fun feedback(resource: Int) {
        mutableState.value = mutableState.value.copy(message = resource, messageSequence = mutableState.value.messageSequence + 1)
    }

    private fun reportFailure(failure: Exception, fallback: Int) {
        feedback(if (failure is BackupStorageModeException) R.string.backup_storage_mode else fallback)
    }

    private fun operation(errorResource: Int = R.string.backup_error, action: suspend () -> Unit): kotlinx.coroutines.Job? {
        if (mutableState.value.busy) return null
        mutableState.value = mutableState.value.copy(busy = true)
        return viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reportFailure(failure, errorResource) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun dismissRestore() { mutableState.value = mutableState.value.copy(restore = null, preview = null) }

    fun prepareBackup() {
        // Reuse an owned snapshot rather than orphaning it if a picker is still open.
        if (mutableState.value.preparedBackup != null) {
            mutableState.value = mutableState.value.copy(chooseBackupDestination = true)
            return
        }
        operation {
            val data = repository.snapshot()
            if (data.isEmpty) { feedback(R.string.backup_empty); return@operation }
            // Initialize private storage on IO too; the helper owns the snapshot until publication.
            val snapshotStore = withContext(Dispatchers.IO) { snapshots }
            snapshotStore.prepareForPublication(
                content = { AppBackupCodec.encode(data) },
                build = { snapshot ->
                    val app = getApplication<Application>()
                    PreparedBackup(snapshot, androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.backup-files", snapshot.file))
                },
                publish = { prepared ->
                    mutableState.value = mutableState.value.copy(preparedBackup = prepared, chooseBackupDestination = true)
                },
            )
        }
    }

    fun destinationPickerStarted() { mutableState.value = mutableState.value.copy(chooseBackupDestination = false) }
    fun cancelBackup() {
        val prepared = mutableState.value.preparedBackup
        mutableState.value = mutableState.value.copy(preparedBackup = null, chooseBackupDestination = false)
        prepared?.snapshot?.let { snapshot ->
            snapshot.release()
            // An independent, finite IO task also completes if the Activity/ViewModel is cleared.
            snapshotCleanup.launch { snapshots.discardUnused(snapshot) }
        }
    }

    private fun finishSnapshotUse(snapshot: BackupSnapshot) {
        snapshot.endUse()
        // Retry a Cancel that arrived while preparation/IO still held its lease.
        snapshotCleanup.launch { snapshots.discardUnused(snapshot) }
    }

    fun shareBackup(share: (PreparedBackup) -> Unit) {
        val prepared = mutableState.value.preparedBackup ?: return
        if (mutableState.value.busy || !prepared.snapshot.beginUse()) return
        val job = operation(errorResource = R.string.saved_backup_destination_unavailable) {
            // Persist the retention timestamp before a recipient can receive its URI.
            withContext(Dispatchers.IO) { snapshots.retain(prepared.snapshot) }
            if (mutableState.value.preparedBackup !== prepared) return@operation
            prepared.snapshot.markUsed()
            share(prepared)
            cancelBackup()
            feedback(R.string.saved_backup_handoff) // Chooser launch is not upload confirmation.
        }
        if (job == null) finishSnapshotUse(prepared.snapshot)
        else job.invokeOnCompletion { finishSnapshotUse(prepared.snapshot) }
    }

    fun export(uri: Uri): kotlinx.coroutines.Job? {
        val prepared = mutableState.value.preparedBackup ?: run { documentUnavailable(); return null }
        if (mutableState.value.busy || !prepared.snapshot.beginUse()) return null
        val job = operation(errorResource = R.string.saved_backup_write_error) {
            try {
                withContext(Dispatchers.IO) {
                    snapshots.retain(prepared.snapshot)
                    prepared.snapshot.markUsed()
                    val resolver = getApplication<Application>().contentResolver
                    BackupDocumentIO.write(
                        displayName = { resolver.backupDisplayName(uri) },
                        openInput = { prepared.file.inputStream() },
                        openOutput = { requireNotNull(resolver.openOutputStream(uri, "wt")) },
                    )
                }
                cancelBackup()
                feedback(R.string.saved_exported)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(chooseBackupDestination = mutableState.value.preparedBackup != null)
                throw failure
            }
        }
        if (job == null) finishSnapshotUse(prepared.snapshot)
        else job.invokeOnCompletion { finishSnapshotUse(prepared.snapshot) }
        return job
    }

    fun inspectBackup(uri: Uri) {
        dismissRestore() // A rejected/busy new request must not leave an old preview to confirm.
        viewModelScope.launch {
            initialLoad?.join() // Incoming files can arrive before the first DB read completes.
            if (mutableState.value.busy) { feedback(R.string.saved_backup_busy); return@launch }
            operation(errorResource = R.string.saved_backup_invalid) {
                val data = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    BackupDocumentIO.read(
                        displayName = { resolver.backupDisplayName(uri) },
                        openInput = { requireNotNull(resolver.openInputStream(uri)) },
                    )
                }
                val preview = repository.preview(data)
                mutableState.value = mutableState.value.copy(loaded = true, restore = data, preview = preview)
            }
        }
    }

    fun documentUnavailable() { feedback(R.string.saved_error) }
    fun backupDestinationUnavailable() {
        mutableState.value = mutableState.value.copy(chooseBackupDestination = mutableState.value.preparedBackup != null)
        feedback(R.string.saved_backup_destination_unavailable)
    }
    fun restorePickerUnavailable() { feedback(R.string.saved_restore_picker_unavailable) }

    fun restore() {
        val incoming = mutableState.value.restore ?: return
        if (mutableState.value.preview?.saved?.fits != true) return
        operation {
            repository.restore(incoming) // Revalidates under the repository transaction lock.
            dismissRestore()
            feedback(R.string.backup_restored)
            try { updateCounts() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Restore already committed; do not report it as failed. */ }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mutableState.value.preparedBackup?.snapshot?.let { snapshot ->
            snapshot.release()
            snapshotCleanup.launch { snapshots.discardUnused(snapshot) }
        }
        // The mutex waits for any cancelled IO operation's transaction/finally block to finish.
        CoroutineScope(Dispatchers.IO).launch { repository.close() }
    }
}
