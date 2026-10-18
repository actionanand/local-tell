package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.journey.JourneyDbHelper
import com.actionanand.localtell.app.journey.JourneyHistoryChanges
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.journey.JourneyTrackingState
import com.actionanand.localtell.app.journey.JourneyTrackingStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JourneyViewModel(app: Application) : AndroidViewModel(app) {
    private val db = JourneyDbHelper(app)
    private val dbMutex = Mutex()
    private val _points = MutableStateFlow<List<JourneyPoint>>(emptyList())
    val points: StateFlow<List<JourneyPoint>> = _points.asStateFlow()
    val tracking = JourneyTrackingState.status

    init {
        JourneyTrackingState.initialize(app)
        refresh()
        viewModelScope.launch {
            JourneyHistoryChanges.version.collect { refresh() }
        }
    }

    fun refresh() { viewModelScope.launch { _points.value = withContext(Dispatchers.IO) { dbMutex.withLock { db.latest() } } } }
    fun clear() { viewModelScope.launch { withContext(Dispatchers.IO) { dbMutex.withLock { db.clear() } }; JourneyHistoryChanges.changed() } }
    fun markStarting() = JourneyTrackingState.update(
        getApplication(),
        JourneyTrackingStatus(
            mode = com.actionanand.localtell.app.journey.JourneyTrackingMode.STARTING,
            detail = "Starting journey tracking…",
        ),
    )
    fun markStopped() = JourneyTrackingState.stopped(getApplication())

    override fun onCleared() { super.onCleared(); CoroutineScope(Dispatchers.IO).launch { dbMutex.withLock { db.close() } } }
}
