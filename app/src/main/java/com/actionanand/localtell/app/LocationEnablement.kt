package com.actionanand.localtell.app

import android.content.Intent
import android.location.LocationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
    private var enabledAction: (() -> Unit)? = null
    private var waitingForSettings = false

    private val resolutionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { completeIfEnabled() }

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
        if (isEnabled()) {
            onEnabled()
            return
        }
        enabledAction = onEnabled
        val request = LocationSettingsRequest.Builder()
            // Balanced power is sufficient to ask Android to enable the Location setting; no
            // location request is ever started by LocalTell.
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L).build())
            .setAlwaysShow(true)
            .build()
        settingsClient.checkLocationSettings(request)
            .addOnSuccessListener { completeIfEnabled() }
            .addOnFailureListener { error ->
                val resolvable = error as? ResolvableApiException
                if (resolvable != null) {
                    resolutionLauncher.launch(IntentSenderRequest.Builder(resolvable.resolution).build())
                } else {
                    waitingForSettings = true
                    activity.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
    }

    fun onResume() {
        if (waitingForSettings || enabledAction != null) completeIfEnabled()
    }

    private fun completeIfEnabled() {
        if (!isEnabled()) return
        waitingForSettings = false
        enabledAction?.also { action ->
            enabledAction = null
            action()
        }
    }
}
