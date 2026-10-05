package com.actionanand.localtell.app

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
}