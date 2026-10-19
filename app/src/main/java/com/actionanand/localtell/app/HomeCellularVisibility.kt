package com.actionanand.localtell.app

import com.actionanand.localtell.app.model.SubscriptionCells

/**
 * Home presentation rule only. Cellular collection keeps running unchanged; Home simply keeps
 * serving-cell diagnostics hidden until the user allows SIM details (READ_PHONE_STATE).
 */
internal object HomeCellularVisibility {
    fun showDiagnostics(phoneStateGranted: Boolean, subscriptions: List<SubscriptionCells>): Boolean =
        phoneStateGranted && subscriptions.isNotEmpty()

    /** Returns true when Home should reload so newly permitted SIM details appear. */
    fun shouldRefreshAfterResume(wasGranted: Boolean, grantedNow: Boolean, locationPermissionGranted: Boolean): Boolean =
        !wasGranted && grantedNow && locationPermissionGranted
}
