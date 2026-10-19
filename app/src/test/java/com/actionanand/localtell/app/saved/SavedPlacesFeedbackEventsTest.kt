package com.actionanand.localtell.app.saved

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedPlacesFeedbackEventsTest {
    private val savedMessage = 1
    private val duplicateMessage = 2
    private val removedMessage = 3

    private suspend fun OneTimeEvents<Int>.nextOrNull(): Int? = withTimeoutOrNull(100) { events.first() }

    @Test fun oneSaveIsNotReplayedAfterRepeatedLanguageSwitches() = runBlocking {
        val events = OneTimeEvents<Int>()
        events.send(savedMessage)
        assertEquals(savedMessage, events.nextOrNull())
        // Each language/theme change recreates the Activity, starting a fresh collector.
        repeat(5) { assertNull(events.nextOrNull()) }
    }

    @Test fun eventSentDuringRecreationIsDeliveredOnceToTheNextCollector() = runBlocking {
        val events = OneTimeEvents<Int>()
        events.send(removedMessage)
        assertEquals(removedMessage, events.nextOrNull())
        assertNull(events.nextOrNull())
    }

    @Test fun consecutiveLegitimateActionsEachProduceFeedback() = runBlocking {
        val events = OneTimeEvents<Int>()
        events.send(savedMessage)
        events.send(savedMessage)
        events.send(duplicateMessage)
        events.send(removedMessage)
        assertEquals(listOf(savedMessage, savedMessage, duplicateMessage, removedMessage), events.events.take(4).toList())
        assertNull(events.nextOrNull())
    }

    @Test fun newSaveAfterLanguageSwitchStillShowsFeedback() = runBlocking {
        val events = OneTimeEvents<Int>()
        events.send(savedMessage)
        assertEquals(savedMessage, events.nextOrNull())
        assertNull(events.nextOrNull())
        events.send(duplicateMessage)
        assertEquals(duplicateMessage, events.nextOrNull())
    }
}
