package com.actionanand.localtell.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
        const val RECENT_FIX_MAX_AGE_MS = 60_000L
        const val TIMEOUT_MS = 45_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val TAG = "OneShotGnssLocator"
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
        val lastKnown = try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            return GnssFixResult.PermissionMissing
        } catch (error: Throwable) {
            return GnssFixResult.Error(error)
        }
        lastKnown?.let { location ->
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val fixNanos = location.elapsedRealtimeNanos
            val ageMillis = if (nowNanos > 0L && fixNanos > 0L && nowNanos >= fixNanos) {
                (nowNanos - fixNanos) / NANOS_PER_MILLISECOND
            } else {
                null
            }
            if (GnssFixEligibility.isRecentPreciseFix(
                    ageMillis = ageMillis,
                    hasAccuracy = location.hasAccuracy(),
                    accuracyMetres = location.accuracy,
                    maxAccuracyMetres = MAX_ACCURACY_METRES,
                    maxAgeMillis = RECENT_FIX_MAX_AGE_MS,
                )
            ) {
                Log.d(TAG, "Recent GPS fix age=${ageMillis}ms accuracy=${location.accuracy}m; reused")
                return GnssFixResult.Success(Location(location))
            }
            Log.d(TAG, "Last-known GPS fix not reusable; age=${ageMillis ?: "invalid"}ms accuracy=${if (location.hasAccuracy()) "${location.accuracy}m" else "unavailable"}")
        }

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
                            Log.d(TAG, "GPS update accuracy=${location.accuracy}m; accepted")
                            finish(GnssFixResult.Success(Location(location)))
                        } else {
                            Log.d(TAG, "GPS update accuracy=${if (location.hasAccuracy()) "${location.accuracy}m" else "unavailable"}; waiting for <=${MAX_ACCURACY_METRES}m")
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
        } ?: run {
            Log.d(TAG, "GPS acquisition timed out after ${TIMEOUT_MS / 1_000L}s")
            GnssFixResult.Timeout
        }
    }
}
