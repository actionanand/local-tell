package com.actionanand.localtell.app.saved

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

data class SavedPlacesUiState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val message: Int? = null,
    val messageSequence: Long = 0,
    val restore: List<SavedPlace>? = null,
    val preview: RestorePreview? = null,
    val limitReached: Boolean = false,
    val preparedBackup: PreparedBackup? = null,
    val chooseBackupDestination: Boolean = false,
)

/** One Activity-scoped owner shared by every bookmark entry point. IO never runs in composition. */
class SavedPlacesViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SavedPlacesRepository(app)
    private val snapshots by lazy {
        BackupSnapshots(java.io.File(getApplication<Application>().filesDir, "saved-place-backups"))
    }
    private val snapshotCleanup = CoroutineScope(Dispatchers.IO)
    val places = repository.places
    private val mutableState = MutableStateFlow(SavedPlacesUiState())
    val state = mutableState.asStateFlow()

    private val initialLoad = load()

    private fun load() = operation { repository.refresh(); mutableState.value = mutableState.value.copy(loaded = true) }
    fun refresh() { load() }

    private fun feedback(resource: Int) {
        mutableState.value = mutableState.value.copy(message = resource, messageSequence = mutableState.value.messageSequence + 1)
    }

    private fun operation(errorResource: Int = R.string.saved_error, action: suspend () -> Unit): kotlinx.coroutines.Job? {
        if (mutableState.value.busy) return null
        mutableState.value = mutableState.value.copy(busy = true)
        return viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback(errorResource) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    fun save(place: SavedPlace) = operation {
        when (repository.save(place.copy(id = java.util.UUID.randomUUID().toString(), savedAt = System.currentTimeMillis()))) {
            SaveResult.Saved -> feedback(R.string.saved_success)
            SaveResult.Duplicate -> feedback(R.string.saved_duplicate)
            SaveResult.Full -> mutableState.value = mutableState.value.copy(limitReached = true)
        }
    }

    fun dismissLimit() { mutableState.value = mutableState.value.copy(limitReached = false) }

    /** Called only after the UI confirmation; captured IDs cannot accidentally include new rows. */
    fun delete(ids: Set<String>) = operation { repository.delete(ids); feedback(R.string.saved_removed) }

    fun dismissRestore() { mutableState.value = mutableState.value.copy(restore = null, preview = null) }

    fun prepareBackup() {
        operation {
            // Initialize private storage on IO too; the helper owns the snapshot until publication.
            val snapshotStore = withContext(Dispatchers.IO) { snapshots }
            snapshotStore.prepareForPublication(
                content = { SavedPlacesBackupCodec.encode(repository.places.value) },
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
                val places = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    BackupDocumentIO.read(
                        displayName = { resolver.backupDisplayName(uri) },
                        openInput = { requireNotNull(resolver.openInputStream(uri)) },
                    )
                }
                repository.refresh()
                val preview = repository.preview(places)
                mutableState.value = mutableState.value.copy(loaded = true, restore = places, preview = preview)
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
        if (mutableState.value.preview?.fits != true) return
        operation {
            repository.merge(incoming) // Revalidates under the repository transaction lock.
            dismissRestore()
            feedback(R.string.saved_restored)
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
