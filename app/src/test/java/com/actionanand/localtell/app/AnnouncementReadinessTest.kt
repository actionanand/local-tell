package com.actionanand.localtell.app

import com.actionanand.localtell.app.easy.EasyError
import com.actionanand.localtell.app.easy.EasyLocation
import com.actionanand.localtell.app.easy.EasyUiState
import com.actionanand.localtell.app.data.AnnouncementSession
import com.actionanand.localtell.app.locationcode.EncodedLocation
import com.actionanand.localtell.app.locationcode.LocationInputResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
