package com.actionanand.localtell.app

import com.actionanand.localtell.app.model.SubscriptionCells
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCellularVisibilityTest {
    private val serving = listOf(SubscriptionCells(null, emptyList()))

    @Test fun diagnosticsHiddenWithoutPhonePermissionEvenWhenCellsExist() {
        assertFalse(HomeCellularVisibility.showDiagnostics(phoneStateGranted = false, subscriptions = serving))
    }

    @Test fun diagnosticsShownWhenPhonePermissionGranted() {
        assertTrue(HomeCellularVisibility.showDiagnostics(phoneStateGranted = true, subscriptions = serving))
        assertFalse(HomeCellularVisibility.showDiagnostics(phoneStateGranted = true, subscriptions = emptyList()))
    }

    @Test fun grantingInSettingsTriggersOneRefreshOnResume() {
        assertTrue(HomeCellularVisibility.shouldRefreshAfterResume(wasGranted = false, grantedNow = true, locationPermissionGranted = true))
        assertFalse(HomeCellularVisibility.shouldRefreshAfterResume(wasGranted = true, grantedNow = true, locationPermissionGranted = true))
        assertFalse(HomeCellularVisibility.shouldRefreshAfterResume(wasGranted = true, grantedNow = false, locationPermissionGranted = true))
        assertFalse(HomeCellularVisibility.shouldRefreshAfterResume(wasGranted = false, grantedNow = true, locationPermissionGranted = false))
    }
}
