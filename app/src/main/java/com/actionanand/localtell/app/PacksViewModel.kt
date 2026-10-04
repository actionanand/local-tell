package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.data.InstalledPack
import com.actionanand.localtell.app.data.ManifestRepository
import com.actionanand.localtell.app.data.PackCatalog
import com.actionanand.localtell.app.data.PackDownloader
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.data.RemotePack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BatchDownloadProgress(val label: String, val completed: Int, val total: Int, val currentPackName: String, val currentPackId: String)

data class PackUiState(
    val loading: Boolean = false,
    val remote: List<RemotePack> = emptyList(),
    val installed: Map<String, InstalledPack> = emptyMap(),
    val progress: Map<String, Int> = emptyMap(),
    val activeDownloads: Set<String> = emptySet(),
    val batch: BatchDownloadProgress? = null,
    val error: String? = null,
)

class PacksViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PackStore(app)
    private val manifestRepository = ManifestRepository()
    private val downloader = PackDownloader(store)
    private val _state = MutableStateFlow(PackUiState(installed = store.all().associateBy { it.id }))
    val state: StateFlow<PackUiState> = _state.asStateFlow()
    private val individualDownloads = mutableMapOf<String, Job>()
    private var batchDownload: Job? = null

    fun refresh() {
        if (_state.value.batch != null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, installed = installed())
            runCatching { manifestRepository.fetch() }
                .onSuccess { _state.value = _state.value.copy(loading = false, remote = it.packs, installed = installed()) }
                .onFailure { _state.value = _state.value.copy(loading = false, error = "Unable to refresh offline data right now. Installed packs remain available offline.") }
        }
    }

    fun download(pack: RemotePack) {
        if (batchDownload?.isActive == true || individualDownloads.containsKey(pack.id) || _state.value.progress.containsKey(pack.id)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            downloadOne(pack)
        }
        individualDownloads[pack.id] = job
        _state.value = _state.value.copy(activeDownloads = _state.value.activeDownloads + pack.id)
        job.invokeOnCompletion {
            viewModelScope.launch {
                if (individualDownloads[pack.id] === job) {
                    individualDownloads.remove(pack.id)
                    _state.value = _state.value.copy(activeDownloads = _state.value.activeDownloads - pack.id)
                }
            }
        }
        job.start()
    }

    fun cancelDownload(id: String) {
        individualDownloads[id]?.cancel()
        downloader.cancel(id)
    }

    fun downloadAll() = downloadBatch("India", _state.value.remote)

    fun downloadRegion(regionPacks: Collection<RemotePack>, regionName: String) = downloadBatch(regionName, regionPacks)

    private fun downloadBatch(label: String, packs: Collection<RemotePack>) {
        if (batchDownload?.isActive == true || individualDownloads.isNotEmpty() || _state.value.progress.isNotEmpty()) return
        val job = viewModelScope.launch {
            val required = PackCatalog.requiredPacks(packs, installed())
            if (required.isEmpty()) return@launch
            val failures = mutableListOf<String>()
            var cancelled = false
            try {
                required.forEachIndexed { index, pack ->
                    _state.value = _state.value.copy(batch = BatchDownloadProgress(label, index, required.size, pack.name, pack.id), error = null)
                    if (!downloadOne(pack, reportFailure = false)) failures += pack.name
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                _state.value = _state.value.copy(
                    batch = null,
                    installed = installed(),
                    error = if (cancelled) null else PackCatalog.batchFailureMessage(failures),
                )
                batchDownload = null
            }
        }
        batchDownload = job
    }

    fun cancelBatchDownload() {
        val currentPackId = _state.value.batch?.currentPackId
        batchDownload?.cancel()
        currentPackId?.let(downloader::cancel)
    }

    /** Reuses the authoritative checksum and atomic-install downloader for every individual pack. */
    private suspend fun downloadOne(pack: RemotePack, reportFailure: Boolean = true): Boolean {
        _state.value = _state.value.copy(error = null)
        return try {
            downloader.download(pack) { percent ->
                _state.value = _state.value.copy(progress = _state.value.progress + (pack.id to percent))
            }
            _state.value = _state.value.copy(installed = installed(), progress = _state.value.progress - pack.id)
            true
        } catch (e: CancellationException) {
            _state.value = _state.value.copy(progress = _state.value.progress - pack.id, error = null)
            throw e
        } catch (_: Exception) {
            _state.value = _state.value.copy(
                progress = _state.value.progress - pack.id,
                error = if (reportFailure) "Unable to download this offline data pack. Check your internet connection and try again." else _state.value.error,
            )
            false
        }
    }

    fun remove(id: String) {
        if (batchDownload?.isActive == true || _state.value.activeDownloads.contains(id) || _state.value.progress.containsKey(id)) return
        store.remove(id)
        _state.value = _state.value.copy(installed = installed())
    }

    fun removeRegion(packs: Collection<RemotePack>) {
        if (batchDownload?.isActive == true || packs.any { _state.value.activeDownloads.contains(it.id) || _state.value.progress.containsKey(it.id) }) return
        PackCatalog.installedPacks(packs, _state.value.installed).forEach { store.remove(it.id) }
        _state.value = _state.value.copy(installed = installed())
    }

    private fun installed() = store.all().associateBy { it.id }
}
