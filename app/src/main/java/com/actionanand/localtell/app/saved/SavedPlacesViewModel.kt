package com.actionanand.localtell.app.saved

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.R
import com.actionanand.localtell.app.backup.SavedPlacesChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SavedPlacesUiState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val message: Int? = null,
    val messageSequence: Long = 0,
    val limitReached: Boolean = false,
)

/** Activity-scoped bookmark owner; backups live in the independent app-data component. */
class SavedPlacesViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SavedPlacesRepository(app)
    val places = repository.places
    private val mutableState = MutableStateFlow(SavedPlacesUiState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            SavedPlacesChanges.version.collect {
                try { repository.refresh(); mutableState.value = mutableState.value.copy(loaded = true) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { feedback(R.string.saved_error) }
            }
        }
    }

    private fun feedback(resource: Int) {
        mutableState.value = mutableState.value.copy(message = resource, messageSequence = mutableState.value.messageSequence + 1)
    }
    private fun operation(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback(R.string.saved_error) }
            finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }
    fun refresh() = operation { repository.refresh(); mutableState.value = mutableState.value.copy(loaded = true) }
    fun save(place: SavedPlace) = operation {
        when (repository.save(place.copy(id = java.util.UUID.randomUUID().toString(), savedAt = System.currentTimeMillis()))) {
            SaveResult.Saved -> { SavedPlacesChanges.changed(); feedback(R.string.saved_success) }
            SaveResult.Duplicate -> feedback(R.string.saved_duplicate)
            SaveResult.Full -> mutableState.value = mutableState.value.copy(limitReached = true)
        }
    }
    fun dismissLimit() { mutableState.value = mutableState.value.copy(limitReached = false) }
    fun delete(ids: Set<String>) = operation { repository.delete(ids); SavedPlacesChanges.changed(); feedback(R.string.saved_removed) }
    override fun onCleared() { super.onCleared(); CoroutineScope(Dispatchers.IO).launch { repository.close() } }
}
