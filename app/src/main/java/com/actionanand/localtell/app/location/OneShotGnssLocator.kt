package com.actionanand.localtell.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

sealed interface GnssFixResult {
    data class Success(val location: Location) : GnssFixResult
    data object Timeout : GnssFixResult
    data object ProviderDisabled : GnssFixResult
    data object PermissionMissing : GnssFixResult
    data class Error(val cause: Throwable? = null) : GnssFixResult
}

/** Requests one short, on-device GNSS fix and always removes its listener before returning. */
class OneShotGnssLocator(private val context: Context) {
    companion object {
        const val MAX_ACCURACY_METRES = 50f
        const val TIMEOUT_MS = 25_000L
    }

    private val manager = context.getSystemService(LocationManager::class.java)

    suspend fun getLocation(): GnssFixResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return GnssFixResult.PermissionMissing
        }
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return GnssFixResult.ProviderDisabled
        return requestFixWithPermission()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun requestFixWithPermission(): GnssFixResult {
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                lateinit var listener: LocationListener
                fun finish(result: GnssFixResult) {
                    runCatching { manager.removeUpdates(listener) }
                    if (continuation.isActive) continuation.resume(result)
                }
                listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (location.hasAccuracy() && location.accuracy <= MAX_ACCURACY_METRES) {
                            finish(GnssFixResult.Success(Location(location)))
                        }
                    }

                    override fun onProviderDisabled(provider: String) = finish(GnssFixResult.ProviderDisabled)
                }
                continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                try {
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
                } catch (error: SecurityException) {
                    finish(GnssFixResult.PermissionMissing)
                } catch (error: Throwable) {
                    finish(GnssFixResult.Error(error))
                }
            }
        } ?: GnssFixResult.Timeout
    }
}
