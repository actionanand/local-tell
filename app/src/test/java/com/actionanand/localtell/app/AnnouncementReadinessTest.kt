package com.actionanand.localtell.app

import com.actionanand.localtell.app.easy.EasyError
import com.actionanand.localtell.app.easy.EasyLocation
import com.actionanand.localtell.app.easy.EasyUiState
import com.actionanand.localtell.app.data.AnnouncementSession
import com.actionanand.localtell.app.locationcode.EncodedLocation
import com.actionanand.localtell.app.locationcode.LocationInputResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementReadinessTest {
    private val location = EasyLocation(
        EncodedLocation(12.0, 77.0, "number", "short"),
        source = LocationInputResult.Source.COORDINATE,
    )

    @Test fun `loading phases and Easy errors do not invalidate root navigation`() = runBlocking {
        val home = Channel<HomeStatus>()
        val easy = Channel<EasyUiState>()
        val values = Channel<Boolean>(Channel.UNLIMITED)
        val collector = launch {
            announcementResultsLoaded(home.receiveAsFlow(), easy.receiveAsFlow()).collect { values.send(it) }
        }
        try {
            withTimeout(5_000L) {
                home.send(HomeStatus.Idle)
                easy.send(EasyUiState())
                assertEquals(false, values.receive())
                home.send(HomeStatus.Loading(LocalityState.READING_CELLULAR))
                home.send(HomeStatus.Loading(LocalityState.ACQUIRING_GNSS))
                easy.send(EasyUiState(locating = true))
                easy.send(EasyUiState(error = EasyError.LOCATION_UNAVAILABLE))
                // A successful Home response without a locality still suppresses notices.
                home.send(HomeStatus.Ready(emptyList(), null, LocalityState.NO_LOCALITY_MATCH))
                assertEquals(true, values.receive())
                assertFalse(values.tryReceive().isSuccess)
            }
        } finally {
            collector.cancel()
        }
    }

    @Test fun `Easy current and find results both count and repeated results emit only once`() = runBlocking {
        val home = Channel<HomeStatus>()
        val easy = Channel<EasyUiState>()
        val values = Channel<Boolean>(Channel.UNLIMITED)
        val collector = launch {
            announcementResultsLoaded(home.receiveAsFlow(), easy.receiveAsFlow()).collect { values.send(it) }
        }
        try {
            withTimeout(5_000L) {
                home.send(HomeStatus.Idle)
                easy.send(EasyUiState())
                assertEquals(false, values.receive())
                easy.send(EasyUiState(currentLocation = location))
                assertEquals(true, values.receive())
                easy.send(EasyUiState(currentLocation = location, error = EasyError.INVALID_INPUT))
                easy.send(EasyUiState(resolvedLocation = location))
                easy.send(EasyUiState())
                assertEquals(false, values.receive())
                easy.send(EasyUiState(resolvedLocation = location))
                assertEquals(true, values.receive())
                assertFalse(values.tryReceive().isSuccess)
            }
        } finally {
            collector.cancel()
        }
    }

    @Test fun `Home refresh after result cannot undo announcement session suppression`() = runBlocking {
        val home = Channel<HomeStatus>()
        val easy = Channel<EasyUiState>()
        val observed = Channel<Boolean>(Channel.UNLIMITED)
        val session = AnnouncementSession()
        val collector = launch {
            announcementResultsLoaded(home.receiveAsFlow(), easy.receiveAsFlow()).collect { ready ->
                session.observeResults(ready, false, false)
                observed.send(session.resultsLoaded)
            }
        }
        try {
            withTimeout(5_000L) {
                home.send(HomeStatus.Idle)
                easy.send(EasyUiState())
                assertEquals(false, observed.receive())
                home.send(HomeStatus.Ready(emptyList(), null, LocalityState.NO_LOCALITY_MATCH))
                assertEquals(true, observed.receive())
                home.send(HomeStatus.Loading(LocalityState.ACQUIRING_GNSS))
                assertEquals(true, observed.receive())
            }
        } finally { collector.cancel() }
    }

    @Test fun `already loaded Home seeds readiness before any UI collection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val suppressed = CompletableDeferred<Unit>()
        try {
            val home = MutableStateFlow<HomeStatus>(HomeStatus.Ready(emptyList(), null, LocalityState.NO_LOCALITY_MATCH))
            val readiness = announcementReadinessState(home, MutableStateFlow(EasyUiState()), scope) {
                suppressed.complete(Unit)
            }
            assertTrue(readiness.value)
            withTimeout(5_000L) { suppressed.await() }
        } finally { scope.cancel() }
    }

    @Test fun `already loaded Easy current and lookup results seed readiness immediately`() = runBlocking {
        listOf(EasyUiState(currentLocation = location), EasyUiState(resolvedLocation = location)).forEach { loaded ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val suppressed = CompletableDeferred<Unit>()
            try {
                val readiness = announcementReadinessState(MutableStateFlow(HomeStatus.Idle), MutableStateFlow(loaded), scope) {
                    suppressed.complete(Unit)
                }
                assertTrue(readiness.value)
                withTimeout(5_000L) { suppressed.await() }
            } finally { scope.cancel() }
        }
    }

    @Test fun `readiness latches without UI subscribers and survives refresh and resubscription`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val suppressed = CompletableDeferred<Unit>()
        val suppressions = AtomicInteger()
        val session = AnnouncementSession()
        val home = MutableStateFlow<HomeStatus>(HomeStatus.Idle)
        try {
            val readiness = announcementReadinessState(home, MutableStateFlow(EasyUiState()), scope) {
                session.observeResults(true, false, false)
                suppressions.incrementAndGet()
                suppressed.complete(Unit)
            }
            assertFalse(readiness.value)
            home.value = HomeStatus.Ready(emptyList(), null, LocalityState.NO_LOCALITY_MATCH)
            withTimeout(5_000L) {
                suppressed.await() // Suppress even if the Activity currently has no UI collector.
                assertTrue(readiness.first { it })
                home.value = HomeStatus.Loading(LocalityState.ACQUIRING_GNSS)
                assertTrue(readiness.first()) // New UI collector/configuration uses the retained value.
                home.value = HomeStatus.Idle
                assertTrue(readiness.first())
            }
            assertTrue(session.resultsLoaded)
            assertEquals(1, suppressions.get())
        } finally { scope.cancel() }
    }

    @Test fun `new app session with no results starts unsuppressed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = AnnouncementSession()
        try {
            val readiness = announcementReadinessState(MutableStateFlow(HomeStatus.Idle), MutableStateFlow(EasyUiState()), scope) {
                session.observeResults(true, false, false)
            }
            assertFalse(readiness.value)
            assertFalse(session.resultsLoaded)
        } finally { scope.cancel() }
    }
}
