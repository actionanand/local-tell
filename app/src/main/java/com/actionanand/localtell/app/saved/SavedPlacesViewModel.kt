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
    val cloudBackupOptions: Boolean = false,
)

/** One Activity-scoped owner shared by every bookmark entry point. IO never runs in composition. */
class SavedPlacesViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SavedPlacesRepository(app)
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
            val prepared = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val directory = java.io.File(app.cacheDir, "saved-place-backups")
                check(directory.isDirectory || directory.mkdirs())
                // Only disposable backups in this dedicated cache directory; never user documents.
                directory.listFiles()?.filter { it.isFile && it.name.endsWith(".bklt") &&
                    System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                val file = java.io.File(directory, BackupFileFormat.filename())
                try {
                    file.writeText(SavedPlacesBackupCodec.encode(repository.places.value), Charsets.UTF_8)
                    PreparedBackup(file, androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.backup-files", file))
                } catch (failure: Exception) { file.delete(); throw failure }
            }
            mutableState.value = mutableState.value.copy(preparedBackup = prepared, chooseBackupDestination = true, cloudBackupOptions = false)
        }
    }

    fun destinationPickerStarted() { mutableState.value = mutableState.value.copy(chooseBackupDestination = false, cloudBackupOptions = false) }
    fun cancelBackup() { mutableState.value = mutableState.value.copy(preparedBackup = null, chooseBackupDestination = false, cloudBackupOptions = false) }
    fun backupShareFallback() { mutableState.value = mutableState.value.copy(chooseBackupDestination = false, cloudBackupOptions = true) }
    fun backupSharingOpened() {
        cancelBackup()
        feedback(R.string.saved_backup_handoff) // Chooser launch is NOT proof of delivery or a save/upload.
    }

    fun export(uri: Uri): kotlinx.coroutines.Job? {
        val prepared = mutableState.value.preparedBackup ?: run { documentUnavailable(); return null }
        return operation(errorResource = R.string.saved_backup_write_error) {
            try {
                withContext(Dispatchers.IO) {
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
            catch (failure: Exception) { backupShareFallback(); throw failure }
        }
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
    fun backupDestinationUnavailable() { feedback(R.string.saved_backup_destination_unavailable) }
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
        // The mutex waits for any cancelled IO operation's transaction/finally block to finish.
        CoroutineScope(Dispatchers.IO).launch { repository.close() }
    }
}
