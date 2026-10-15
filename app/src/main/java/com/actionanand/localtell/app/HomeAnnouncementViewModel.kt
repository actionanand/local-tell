package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.data.HomeAnnouncement
import com.actionanand.localtell.app.data.HomeAnnouncementRepository
import com.actionanand.localtell.app.data.HomeAnnouncementSelector
import com.actionanand.localtell.app.data.HomeAnnouncementUiState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeAnnouncementViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = HomeAnnouncementRepository(app)
    private val _state = MutableStateFlow(HomeAnnouncementUiState())
    val state: StateFlow<HomeAnnouncementUiState> = _state.asStateFlow()
    private var announcements: List<HomeAnnouncement> = emptyList()

    init {
        viewModelScope.launch {
            repository.loadCached()?.let(::applyDocument)
            try {
                applyDocument(repository.refresh())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Announcements are best-effort; a valid cached document remains active.
            }
        }
    }

    fun dismissCurrent() {
        val current = _state.value.visibleAnnouncement ?: return
        repository.dismiss(current.id)
        updateSelection()
    }

    fun suppressForSession() {
        _state.value = _state.value.suppressForSession()
    }

    private fun applyDocument(next: List<HomeAnnouncement>) {
        announcements = next
        updateSelection()
    }

    private fun updateSelection() {
        val selected = HomeAnnouncementSelector.select(
            announcements = announcements,
            now = Instant.now(),
            versionCode = BuildConfig.VERSION_CODE,
            dismissedIds = repository.dismissedIds(),
        )
        _state.value = _state.value.withAnnouncement(selected)
    }
}
