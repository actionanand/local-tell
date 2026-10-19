package com.actionanand.localtell.app

import android.app.Activity
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Uses Google Play services solely to show Android's Location-settings resolution. It never
 * creates a location client, starts updates, or receives coordinates.
 */
class LocationEnablement(private val activity: ComponentActivity, savedState: Bundle? = null) {
    private val settingsClient = LocationServices.getSettingsClient(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = PendingLocationEnablement(
        isEnabled = ::isEnabled,
        scheduleRecheck = { action -> handler.postDelayed({ action() }, 200L) },
        cancelRechecks = { handler.removeCallbacksAndMessages(null) },
    )
    private var waitingForUiResult = savedState?.getBoolean("waiting_for_result") == true
    private var requestGeneration = 0

    private val resolutionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (waitingForUiResult) {
            waitingForUiResult = false
            if (result.resultCode == Activity.RESULT_OK) pending.userResponded() else pending.decline()
        }
    }

    private val settingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (waitingForUiResult) {
            waitingForUiResult = false
            pending.userResponded()
        }
    }

    init {
        if (savedState?.getBoolean("pending") == true) {
            val result = if (savedState.containsKey("accepted")) savedState.getBoolean("accepted") else null
            pending.restore(result)
        }
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) cancelPending()
        })
    }

    fun saveState(): Bundle = Bundle().apply {
        putBoolean("waiting_for_result", waitingForUiResult)
        putBoolean("pending", pending.hasPendingAction || pending.hasRestorableAction)
        pending.response?.let { putBoolean("accepted", it) }
    }

    val hasPendingAction: Boolean get() = pending.hasPendingAction

    /** Rebinds restored results to the new UI without opening a second system prompt. */
    fun reconnect(onDeclined: () -> Unit, onEnabled: () -> Unit) {
        pending.reconnect(onDeclined, onEnabled)
    }

    fun isEnabled(): Boolean {
        val manager = activity.getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }

    /**
     * [onEnabled] runs at most once when Location becomes ON; [onDeclined] runs when the user
     * cancels the system dialog or Location stays OFF after returning. Superseded or destroyed
     * requests are discarded silently.
     */
    fun requestEnable(onDeclined: () -> Unit = {}, onEnabled: () -> Unit) {
        cancelPending()
        if (isEnabled()) {
            onEnabled()
            return
        }
        pending.begin(onDeclined = onDeclined, onEnabled = onEnabled)
        // The settings check is asynchronous; no expiry countdown may run until the user answers.
        pending.awaitUser()
        val generation = requestGeneration
        val request = LocationSettingsRequest.Builder()
            // Balanced power is sufficient to ask Android to enable the Location setting; no
            // location request is ever started by LocalTell.
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L).build())
            .setAlwaysShow(true)
            .build()
        settingsClient.checkLocationSettings(request)
            .addOnSuccessListener {
                if (generation == requestGeneration && pending.hasPendingAction) pending.userResponded()
            }
            .addOnFailureListener { error ->
                if (generation == requestGeneration && pending.hasPendingAction) {
                    waitingForUiResult = true
                    val resolvable = error as? ResolvableApiException
                    if (resolvable != null) {
                        resolutionLauncher.launch(IntentSenderRequest.Builder(resolvable.resolution).build())
                    } else {
                        settingsLauncher.launch(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                }
            }
    }

    fun onResume() {
        // While awaiting the user this only completes if Location is already ON; it never expires the request.
        pending.completeIfEnabled()
    }

    private fun cancelPending() {
        requestGeneration++
        waitingForUiResult = false
        pending.cancel()
    }
}

internal class PendingLocationEnablement(
    private val isEnabled: () -> Boolean,
    private val scheduleRecheck: (() -> Unit) -> Unit,
    private val cancelRechecks: () -> Unit,
) {
    private var enabledAction: (() -> Unit)? = null
    private var declinedAction: (() -> Unit)? = null
    private var awaitingUser = false
    private var remainingRechecks = 0
    private var recheckScheduled = false
    private var generation = 0
    private var restored = false
    var response: Boolean? = null
        private set

    val hasPendingAction: Boolean get() = enabledAction != null
    val hasRestorableAction: Boolean get() = restored

    fun restore(accepted: Boolean?) {
        cancel()
        restored = true
        response = accepted
    }

    fun reconnect(onDeclined: () -> Unit, onEnabled: () -> Unit) {
        if (hasPendingAction) {
            declinedAction = onDeclined
            enabledAction = onEnabled
        } else if (restored) {
            val accepted = response
            begin(onDeclined = onDeclined, onEnabled = onEnabled)
            when (accepted) {
                true -> userResponded()
                false -> decline()
                null -> awaitUser()
            }
        }
    }

    fun begin(onDeclined: () -> Unit = {}, onEnabled: () -> Unit) {
        cancel()
        enabledAction = onEnabled
        declinedAction = onDeclined
        remainingRechecks = 10
    }

    /**
     * Suspends the bounded recheck countdown while a settings check or system dialog is
     * outstanding. Previously, an Activity resume during this window started the countdown,
     * which could expire and silently drop the action before the user accepted the dialog.
     */
    fun awaitUser() {
        if (enabledAction == null) return
        awaitingUser = true
        stopRechecks()
    }

    /** The user answered (or the check succeeded); Location may take a moment to report ON. */
    fun userResponded() {
        if (restored) {
            response = true
            return
        }
        if (enabledAction == null) return
        response = true
        awaitingUser = false
        stopRechecks()
        remainingRechecks = 10
        completeIfEnabled()
    }

    fun decline() {
        if (restored) {
            response = false
            return
        }
        if (enabledAction == null) return
        val declined = declinedAction
        cancel()
        declined?.invoke()
    }

    fun completeIfEnabled() {
        val action = enabledAction ?: return
        if (isEnabled()) {
            cancel()
            action()
        } else if (!awaitingUser && !recheckScheduled) {
            if (remainingRechecks == 0) {
                decline()
                return
            }
            remainingRechecks--
            recheckScheduled = true
            val scheduledGeneration = generation
            scheduleRecheck {
                if (scheduledGeneration == generation) {
                    recheckScheduled = false
                    completeIfEnabled()
                }
            }
        }
    }

    fun cancel() {
        stopRechecks()
        enabledAction = null
        declinedAction = null
        restored = false
        response = null
        awaitingUser = false
        remainingRechecks = 0
    }

    private fun stopRechecks() {
        generation++
        cancelRechecks()
        recheckScheduled = false
    }
}
