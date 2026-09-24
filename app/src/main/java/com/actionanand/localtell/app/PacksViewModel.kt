package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PackUiState(
    val loading: Boolean = false,
    val remote: List<RemotePack> = emptyList(),
    val installed: Map<String, InstalledPack> = emptyMap(),
    val progress: Map<String, Int> = emptyMap(),
    val error: String? = null,
)

class PacksViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PackStore(app)
    private val manifestRepository = ManifestRepository()
    private val downloader = PackDownloader(store)
    private val _state = MutableStateFlow(PackUiState(installed = store.all().associateBy { it.id }))
    val state: StateFlow<PackUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, installed = store.all().associateBy { it.id })
            runCatching { manifestRepository.fetch() }
                .onSuccess { _state.value = _state.value.copy(loading = false, remote = it.packs, installed = store.all().associateBy { p -> p.id }) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Unable to load pack list") }
        }
    }

    fun download(pack: RemotePack) {
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null)
            runCatching {
                downloader.download(pack) { p ->
                    _state.value = _state.value.copy(progress = _state.value.progress + (pack.id to p))
                }
            }.onSuccess {
                _state.value = _state.value.copy(
                    installed = store.all().associateBy { p -> p.id },
                    progress = _state.value.progress - pack.id,
                )
            }.onFailure {
                _state.value = _state.value.copy(error = it.message ?: "Download failed", progress = _state.value.progress - pack.id)
            }
        }
    }

    fun remove(id: String) {
        store.remove(id)
        _state.value = _state.value.copy(installed = store.all().associateBy { it.id })
    }
}
