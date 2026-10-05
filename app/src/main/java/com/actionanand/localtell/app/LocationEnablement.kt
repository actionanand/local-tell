package com.actionanand.localtell.app

import android.app.Activity
import android.content.Intent
import android.location.LocationManager
import android.os.Build
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
class LocationEnablement(private val activity: ComponentActivity) {
    private val settingsClient = LocationServices.getSettingsClient(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val pending = PendingLocationEnablement(
        isEnabled = ::isEnabled,
        scheduleRecheck = { action -> handler.postDelayed({ action() }, 200L) },
        cancelRechecks = { handler.removeCallbacksAndMessages(null) },
    )
    private var waitingForUiResult = false
    private var requestGeneration = 0

    private val resolutionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (waitingForUiResult) {
            waitingForUiResult = false
            if (result.resultCode == Activity.RESULT_OK) pending.completeIfEnabled() else cancelPending()
        }
    }

    private val settingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (waitingForUiResult) {
            waitingForUiResult = false
            pending.completeIfEnabled()
        }
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) cancelPending()
        })
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

    fun requestEnable(onEnabled: () -> Unit) {
        cancelPending()
        if (isEnabled()) {
            onEnabled()
            return
        }
        pending.begin(onEnabled)
        val generation = requestGeneration
        val request = LocationSettingsRequest.Builder()
            // Balanced power is sufficient to ask Android to enable the Location setting; no
            // location request is ever started by LocalTell.
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L).build())
            .setAlwaysShow(true)
            .build()
        settingsClient.checkLocationSettings(request)
            .addOnSuccessListener {
                if (generation == requestGeneration && pending.hasPendingAction) pending.completeIfEnabled()
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
        if (!waitingForUiResult) pending.completeIfEnabled()
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
    private var remainingRechecks = 0
    private var recheckScheduled = false
    private var generation = 0

    val hasPendingAction: Boolean get() = enabledAction != null

    fun begin(onEnabled: () -> Unit) {
        cancel()
        enabledAction = onEnabled
        remainingRechecks = 10
    }

    fun completeIfEnabled() {
        val action = enabledAction ?: return
        if (isEnabled()) {
            cancel()
            action()
        } else if (!recheckScheduled) {
            if (remainingRechecks == 0) {
                cancel()
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
        generation++
        cancelRechecks()
        enabledAction = null
        remainingRechecks = 0
        recheckScheduled = false
    }
}
