package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.data.OfflineAreaResolver
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.model.AreaMatch
import com.actionanand.localtell.app.model.RadioCell
import com.actionanand.localtell.app.telephony.CellReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeStatus {
    data object Idle : HomeStatus
    data object Loading : HomeStatus
    data class Ready(val cells: List<RadioCell>, val match: AreaMatch?) : HomeStatus
    data class Error(val message: String) : HomeStatus
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = CellReader(app)
    private val resolver = OfflineAreaResolver(PackStore(app))
    private val _status = MutableStateFlow<HomeStatus>(HomeStatus.Idle)
    val status: StateFlow<HomeStatus> = _status.asStateFlow()

    fun refresh() {
        if (!reader.hasPermission()) {
            _status.value = HomeStatus.Error("Location permission is required by Android to expose cellular IDs. LocalTell does not request GPS coordinates.")
            return
        }
        viewModelScope.launch {
            _status.value = HomeStatus.Loading
            runCatching {
                val cells = reader.requestServingCells()
                HomeStatus.Ready(cells, resolver.resolveFirst(cells))
            }.onSuccess { _status.value = it }
                .onFailure { _status.value = HomeStatus.Error(it.message ?: "Unable to read serving cell") }
        }
    }
}
