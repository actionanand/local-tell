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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

sealed interface GnssFixResult {
    data class Precise(val location: Location) : GnssFixResult
    data class Approximate(val location: Location) : GnssFixResult
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

    private val manager by lazy { context.getSystemService(LocationManager::class.java) }

    suspend fun getLocation(): GnssFixResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return@withContext GnssFixResult.PermissionMissing
        }
        try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return@withContext GnssFixResult.ProviderDisabled
            requestFixWithPermission()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            currentCoroutineContext().ensureActive()
            GnssFixResult.PermissionMissing
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            GnssFixResult.Error(error)
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun requestFixWithPermission(): GnssFixResult {
        val lastKnown = try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return GnssFixResult.PermissionMissing
        } catch (error: Exception) {
            return GnssFixResult.Error(error)
        }
        var recentApproximate: Location? = null
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
                return GnssFixResult.Precise(Location(location))
            }
            if (
                ageMillis != null && ageMillis in 0..RECENT_FIX_MAX_AGE_MS &&
                LocationAccuracy.classify(location.hasAccuracy(), location.accuracy) == LocationQuality.APPROXIMATE
            ) {
                recentApproximate = Location(location)
                Log.d(TAG, "Recent GPS fix age=${ageMillis}ms accuracy=${location.accuracy}m; retained as approximate candidate")
            }
            Log.d(TAG, "Last-known GPS fix not reusable; age=${ageMillis ?: "invalid"}ms accuracy=${if (location.hasAccuracy()) "${location.accuracy}m" else "unavailable"}")
        }

        val bestApproximate = AtomicReference(recentApproximate)
        var listener: LocationListener? = null
        return awaitRegisteredLocation<GnssFixResult>(
            timeoutMillis = TIMEOUT_MS,
            register = { finish ->
                val callback = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        when (LocationAccuracy.classify(location.hasAccuracy(), location.accuracy)) {
                            LocationQuality.PRECISE -> {
                                Log.d(TAG, "GPS update accuracy=${location.accuracy}m; precise location accepted")
                                finish(GnssFixResult.Precise(Location(location)))
                            }
                            LocationQuality.APPROXIMATE -> {
                                val current = bestApproximate.get()
                                if (LocationAccuracy.isBetterApproximate(location.accuracy, current?.accuracy)) {
                                    bestApproximate.set(Location(location))
                                    Log.d(TAG, "GPS update accuracy=${location.accuracy}m; retained as approximate candidate")
                                }
                            }
                            null -> Log.d(TAG, "GPS update accuracy=${if (location.hasAccuracy()) "${location.accuracy}m" else "unavailable"}; rejected")
                        }
                    }

                    override fun onProviderDisabled(provider: String) = finish(GnssFixResult.ProviderDisabled)
                }
                // API 26-compatible overload. Register is synchronous on IO; updates use
                // the explicit main looper and never remove a listener from a callback.
                listener = callback
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, callback, Looper.getMainLooper())
            },
            unregister = {
                // Do not silently swallow system-service cleanup failures.
                try {
                    listener?.let { manager.removeUpdates(it) }
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to remove GPS listener", error)
                    throw error
                }
            },
        ) ?: run {
            Log.d(TAG, "GPS acquisition timed out after ${TIMEOUT_MS / 1_000L}s")
            bestApproximate.get()?.let(GnssFixResult::Approximate) ?: GnssFixResult.Timeout
        }
    }
}
