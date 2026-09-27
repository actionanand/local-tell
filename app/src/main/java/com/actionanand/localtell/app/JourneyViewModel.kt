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

class JourneyViewModel(app: Application) : AndroidViewModel(app) {
    private val db = JourneyDbHelper(app)
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

    fun refresh() { _points.value = db.latest() }
    fun clear() { db.clear(); JourneyHistoryChanges.changed() }
    fun markStarting() = JourneyTrackingState.update(
        getApplication(),
        JourneyTrackingStatus(
            mode = com.actionanand.localtell.app.journey.JourneyTrackingMode.STARTING,
            detail = "Starting journey tracking…",
        ),
    )
    fun markStopped() = JourneyTrackingState.stopped(getApplication())

    override fun onCleared() { db.close(); super.onCleared() }
}
