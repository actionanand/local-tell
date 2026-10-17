package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.actionanand.localtell.app.data.AnnouncementSession
import com.actionanand.localtell.app.data.HomeAnnouncement
import com.actionanand.localtell.app.data.HomeAnnouncementRepository
import com.actionanand.localtell.app.data.HomeAnnouncementUiState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class HomeAnnouncementViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = HomeAnnouncementRepository(app)
    private val _state = MutableStateFlow(HomeAnnouncementUiState())
    val state: StateFlow<HomeAnnouncementUiState> = _state.asStateFlow()
    private var announcements: List<HomeAnnouncement> = emptyList()
    private var session: AnnouncementSession? = null
    private var lastRefreshNanos: Long? = null

    /** Called inside repeatOnLifecycle: no network polling survives STOPPED. */
    suspend fun whileForeground() {
        if (session == null) {
            val cached = withContext(Dispatchers.IO) {
                repository.dismissedRevisions() to repository.loadCached()
            }
            session = AnnouncementSession(cached.first).also {
                if (_state.value.sessionSuppressed) it.observeResults(true, false, false)
            }
            cached.second?.let { announcements = it }
        }
        updateSelection()
        refreshIfDue(60_000_000_000L)
        var ticks = 0
        while (true) {
            delay(30_000)
            updateSelection() // Local expiry/scheduling still applies offline.
            if (++ticks >= 10) {
                ticks = 0
                if (!_state.value.sessionSuppressed) refreshIfDue(300_000_000_000L)
            }
        }
    }

    private suspend fun refreshIfDue(intervalNanos: Long) {
        if (_state.value.sessionSuppressed) return
        val now = System.nanoTime()
        if (lastRefreshNanos?.let { now - it < intervalNanos } == true) return
        lastRefreshNanos = now
        try {
            announcements = repository.refresh()
            updateSelection()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the last validated document; announcements never surface network errors.
            updateSelection()
        }
    }

    fun dismissCurrent() {
        val current = _state.value.visibleAnnouncement ?: return
        session?.dismiss(current)
        repository.dismiss(current)
        updateSelection()
    }

    fun suppressForSession() {
        session?.observeResults(true, false, false)
        _state.value = _state.value.suppressForSession()
    }

    private fun updateSelection() {
        val selected = session?.select(announcements, Instant.now(), BuildConfig.VERSION_CODE)
        _state.value = _state.value.withAnnouncement(selected)
    }
}
