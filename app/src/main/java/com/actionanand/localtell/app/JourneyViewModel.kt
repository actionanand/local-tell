package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.actionanand.localtell.app.journey.JourneyDbHelper
import com.actionanand.localtell.app.journey.JourneyPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class JourneyViewModel(app: Application) : AndroidViewModel(app) {
    private val db = JourneyDbHelper(app)
    private val _points = MutableStateFlow<List<JourneyPoint>>(emptyList())
    val points: StateFlow<List<JourneyPoint>> = _points.asStateFlow()

    fun refresh() { _points.value = db.latest() }
    fun clear() { db.clear(); refresh() }
    override fun onCleared() { db.close(); super.onCleared() }
}
