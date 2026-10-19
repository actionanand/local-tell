package com.actionanand.localtell.app

import com.actionanand.localtell.app.easy.EasyLocationRequestFlow
import com.actionanand.localtell.app.easy.EasyLocationRequestFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationEnablementTest {
    private var enabled = false
    private var completions = 0
    private val rechecks = ArrayDeque<() -> Unit>()
    private val pending = PendingLocationEnablement(
        isEnabled = { enabled },
        scheduleRecheck = { rechecks.addLast(it) },
        cancelRechecks = { rechecks.clear() },
    )

    @Test fun alreadyEnabledCompletesOnlyOnce() {
        enabled = true
        pending.begin { completions++ }
        pending.completeIfEnabled()
        pending.completeIfEnabled()
        assertEquals(1, completions)
        assertFalse(pending.hasPendingAction)
        assertTrue(rechecks.isEmpty())
    }

    @Test fun delayedEnablementContinuesWithoutAnotherRequest() {
        pending.begin { completions++ }
        pending.completeIfEnabled()
        rechecks.removeFirst().invoke()
        assertEquals(0, completions)
        enabled = true
        rechecks.removeFirst().invoke()
        assertEquals(1, completions)
        assertFalse(pending.hasPendingAction)
        assertTrue(rechecks.isEmpty())
    }

    @Test fun repeatedResumeChecksDoNotDuplicateRetries() {
        pending.begin { completions++ }
        repeat(3) { pending.completeIfEnabled() }
        assertEquals(1, rechecks.size)
    }

    @Test fun disabledLocationExpiresAfterTenRechecks() {
        pending.begin { completions++ }
        pending.completeIfEnabled()
        repeat(10) { rechecks.removeFirst().invoke() }
        assertTrue(rechecks.isEmpty())
        assertFalse(pending.hasPendingAction)
        enabled = true
        pending.completeIfEnabled()
        assertEquals(0, completions)
    }

    @Test fun cancellationDiscardsPendingAndScheduledActions() {
        pending.begin { completions++ }
        pending.completeIfEnabled()
        val staleRecheck = rechecks.first()
        pending.cancel()
        enabled = true
        staleRecheck()
        pending.completeIfEnabled()
        assertEquals(0, completions)
        assertFalse(pending.hasPendingAction)
        assertTrue(rechecks.isEmpty())
    }

    @Test fun freshRequestAfterCancellationIgnoresOldRecheck() {
        var cancelledCompletions = 0
        pending.begin { cancelledCompletions++ }
        pending.completeIfEnabled()
        val staleRecheck = rechecks.first()
        pending.cancel()
        pending.begin { completions++ }
        pending.completeIfEnabled()
        enabled = true
        staleRecheck()
        assertEquals(0, completions)
        rechecks.removeFirst().invoke()
        assertEquals(1, completions)
        assertEquals(0, cancelledCompletions)
    }
    @Test fun resumeDuringSystemDialogDoesNotExpireThePendingAction() {
        var declines = 0
        pending.begin(onDeclined = { declines++ }) { completions++ }
        pending.awaitUser()
        // Activity resumes repeatedly (permission dialog closed) while the enable dialog is pending.
        repeat(20) { pending.completeIfEnabled() }
        assertTrue(rechecks.isEmpty())
        assertTrue(pending.hasPendingAction)
        enabled = true
        pending.userResponded()
        assertEquals(1, completions)
        assertEquals(0, declines)
        assertFalse(pending.hasPendingAction)
    }

    @Test fun userAcceptanceWaitsForDelayedProviderStateThenCompletesOnce() {
        pending.begin { completions++ }
        pending.awaitUser()
        pending.userResponded()
        assertEquals(1, rechecks.size)
        enabled = true
        rechecks.removeFirst().invoke()
        pending.completeIfEnabled()
        assertEquals(1, completions)
    }

    @Test fun locationAlreadyOnWhenResumedWhileAwaitingCompletesOnce() {
        pending.begin { completions++ }
        pending.awaitUser()
        enabled = true
        pending.completeIfEnabled()
        pending.userResponded()
        assertEquals(1, completions)
    }

    @Test fun cancelledSystemDialogReportsDeclineOnce() {
        var declines = 0
        pending.begin(onDeclined = { declines++ }) { completions++ }
        pending.awaitUser()
        pending.decline()
        pending.decline()
        assertEquals(0, completions)
        assertEquals(1, declines)
        assertFalse(pending.hasPendingAction)
    }

    @Test fun locationStillOffAfterReturningReportsDeclineAfterBoundedRechecks() {
        var declines = 0
        pending.begin(onDeclined = { declines++ }) { completions++ }
        pending.awaitUser()
        pending.userResponded()
        repeat(10) { rechecks.removeFirst().invoke() }
        assertEquals(1, declines)
        assertEquals(0, completions)
        assertTrue(rechecks.isEmpty())
    }

    @Test fun supersededRequestIsDiscardedSilently() {
        var declines = 0
        pending.begin(onDeclined = { declines++ }) { completions++ }
        pending.awaitUser()
        pending.cancel()
        assertEquals(0, declines)
        assertFalse(pending.hasPendingAction)
    }

    @Test fun recreatedActivityReattachesToPendingPromptWithoutStartingRechecks() {
        pending.restore(null)
        pending.reconnect(onDeclined = { throw AssertionError("Unexpected decline") }, onEnabled = { completions++ })
        repeat(5) { pending.completeIfEnabled() }
        assertTrue(pending.hasPendingAction)
        assertFalse(pending.hasRestorableAction)
        assertTrue(rechecks.isEmpty())
        pending.userResponded()
        enabled = true
        rechecks.removeFirst().invoke()
        assertEquals(1, completions)
    }

    @Test fun acceptedResultBeforeUiReattachesIsBufferedUntilReconnection() {
        pending.restore(null)
        pending.userResponded()
        assertTrue(pending.response == true)
        enabled = true
        pending.reconnect(onDeclined = {}, onEnabled = { completions++ })
        pending.completeIfEnabled()
        assertEquals(1, completions)
    }

    @Test fun declinedResultBeforeUiReattachesIsDeliveredExactlyOnce() {
        var declines = 0
        pending.restore(null)
        pending.decline()
        assertTrue(pending.response == false)
        pending.reconnect(onDeclined = { declines++ }, onEnabled = { completions++ })
        pending.reconnect(onDeclined = { declines++ }, onEnabled = { completions++ })
        enabled = true
        pending.completeIfEnabled()
        assertEquals(1, declines)
        assertEquals(0, completions)
    }

    @Test fun acceptedResultSurvivesAnotherRecreationBeforeProviderReportsEnabled() {
        pending.restore(true)
        pending.reconnect(onDeclined = {}, onEnabled = { completions++ })
        val savedResult = pending.response
        val oldRecheck = rechecks.first()
        pending.cancel()
        pending.restore(savedResult)
        pending.reconnect(onDeclined = {}, onEnabled = { completions++ })
        enabled = true
        oldRecheck()
        rechecks.removeFirst().invoke()
        assertEquals(1, completions)
    }

    @Test fun resultAfterReattachmentReportsDeclineOnce() {
        var declines = 0
        pending.restore(null)
        pending.reconnect(onDeclined = { declines++ }, onEnabled = { completions++ })
        pending.decline()
        pending.decline()
        assertEquals(1, declines)
        assertEquals(0, completions)
    }

    @Test fun reattachmentReplacesOldCallbacksWithoutResettingDelayedChecks() {
        pending.begin { throw AssertionError("Old Activity callback invoked") }
        pending.userResponded()
        pending.reconnect(onDeclined = {}, onEnabled = { completions++ })
        assertEquals(1, rechecks.size)
        enabled = true
        rechecks.removeFirst().invoke()
        assertEquals(1, completions)
    }

    @Test fun retainedUserIntentSurvivesOldActivityDestructionAndDelayedEnablement() {
        val flow = EasyLocationRequestFlow()
        val steps = mutableListOf<Step>()
        assertEquals(Step.REQUEST_LOCATION_ENABLE, flow.onTap(true, false, false))
        pending.begin { throw AssertionError("Destroyed Activity callback invoked") }
        pending.awaitUser()
        pending.cancel() // Old Activity's ON_DESTROY.

        pending.restore(null) // The new Activity restores the in-flight system prompt.
        assertEquals(Step.RECONNECT_LOCATION_ENABLE, flow.onHostAttached())
        pending.reconnect(
            onDeclined = { steps.add(flow.onLocationDeclined()) },
            onEnabled = { steps.add(flow.onLocationEnabled()) },
        )
        repeat(5) { assertEquals(Step.NONE, flow.onResume(true, false, false)) }
        pending.userResponded()
        enabled = true
        rechecks.removeFirst().invoke()
        repeat(5) { assertEquals(Step.NONE, flow.onResume(true, true, false)) }
        assertEquals(listOf(Step.START_LOCATING), steps)
        assertFalse(flow.isPending)
    }

    @Test fun restoredDeclineConsumesRetainedIntentBeforeLaterResumeCanStartGps() {
        val flow = EasyLocationRequestFlow()
        flow.onTap(true, false, false)
        pending.restore(false)
        assertEquals(Step.RECONNECT_LOCATION_ENABLE, flow.onHostAttached())
        pending.reconnect(
            onDeclined = { assertEquals(Step.LOCATION_DECLINED, flow.onLocationDeclined()) },
            onEnabled = { throw AssertionError("Declined request started GPS") },
        )
        repeat(5) { assertEquals(Step.NONE, flow.onResume(true, true, false)) }
        assertFalse(flow.isPending)
    }
}
