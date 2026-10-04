package com.actionanand.localtell.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface AssistedLocationResult {
    data class Precise(val location: Location) : AssistedLocationResult
    data class Approximate(val location: Location) : AssistedLocationResult
    data object Unavailable : AssistedLocationResult
    data object PermissionMissing : AssistedLocationResult
    data class Error(val cause: Throwable? = null) : AssistedLocationResult
}

/** Explicit, foreground-only assisted fallback. It never resolves a locality online. */
class AssistedLocationLocator(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    suspend fun getLocation(): AssistedLocationResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return AssistedLocationResult.PermissionMissing
        }
        return getLocationWithPermission()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun getLocationWithPermission(): AssistedLocationResult {
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val tokenSource = CancellationTokenSource()
                continuation.invokeOnCancellation { tokenSource.cancel() }
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
                    .addOnSuccessListener { location ->
                        if (!continuation.isActive) return@addOnSuccessListener
                        val result = when (LocationAccuracy.classify(location?.hasAccuracy() == true, location?.accuracy ?: Float.MAX_VALUE)) {
                            LocationQuality.PRECISE -> AssistedLocationResult.Precise(Location(location!!))
                            LocationQuality.APPROXIMATE -> AssistedLocationResult.Approximate(Location(location!!))
                            null -> AssistedLocationResult.Unavailable
                        }
                        continuation.resume(result)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resume(AssistedLocationResult.Error(error))
                    }
            }
        } ?: AssistedLocationResult.Unavailable
    }

    private companion object { const val TIMEOUT_MS = 20_000L }
}

object NetworkAvailability {
    fun hasUsableNetwork(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }
}
