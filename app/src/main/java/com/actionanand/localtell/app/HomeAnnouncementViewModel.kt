package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.easy.EasyUiState
import com.actionanand.localtell.app.data.AnnouncementSession
import com.actionanand.localtell.app.data.HomeAnnouncement
import com.actionanand.localtell.app.data.HomeAnnouncementRepository
import com.actionanand.localtell.app.data.HomeAnnouncementUiState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeAnnouncementViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = HomeAnnouncementRepository(app)
    private val _state = MutableStateFlow(HomeAnnouncementUiState())
    val state: StateFlow<HomeAnnouncementUiState> = _state.asStateFlow()
    private var announcements: List<HomeAnnouncement> = emptyList()
    private var session: AnnouncementSession? = null
    private var lastRefreshNanos: Long? = null
    private var readiness: StateFlow<Boolean>? = null

    /** Bind once to this Activity's retained Home/Easy ViewModels; no work is started on rebinding. */
    fun resultReadiness(home: StateFlow<HomeStatus>, easy: StateFlow<EasyUiState>): StateFlow<Boolean> =
        readiness ?: announcementReadinessState(home, easy, viewModelScope, ::suppressForSession)
            .also { readiness = it }

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
        updateSelection()
        // Session dismissal is immediate; preference loading/writing must not delay the tap.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.dismiss(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A persistence failure must not undo the in-memory/session dismissal.
            }
        }
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

/** Navigation only needs readiness, not every loading phase, cell list or Easy error update. */
internal fun announcementResultsLoaded(home: Flow<HomeStatus>, easy: Flow<EasyUiState>): Flow<Boolean> =
    combine(
        home.map { it is HomeStatus.Ready }.distinctUntilChanged(),
        easy.map { it.currentLocation != null || it.resolvedLocation != null }.distinctUntilChanged(),
    ) { homeReady, easyReady -> homeReady || easyReady }.distinctUntilChanged()

/** Model-side snapshot seeds Compose's StateFlow overload without a false first frame.
 * Eager collection keeps the session latch current while UI collection is stopped. Only true
 * passes through after initialization, so refreshes cannot reset it or cause recompositions.
 */
internal fun announcementReadinessState(
    home: StateFlow<HomeStatus>,
    easy: StateFlow<EasyUiState>,
    scope: CoroutineScope,
    onResultsLoaded: () -> Unit,
): StateFlow<Boolean> {
    val initial = home.value is HomeStatus.Ready ||
        easy.value.currentLocation != null || easy.value.resolvedLocation != null
    return announcementResultsLoaded(home, easy)
        .onStart { emit(initial) }
        .filter { it }
        .distinctUntilChanged()
        .onEach { onResultsLoaded() }
        .stateIn(scope, SharingStarted.Eagerly, initial)
}
