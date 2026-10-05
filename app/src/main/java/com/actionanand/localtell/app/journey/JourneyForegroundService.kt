package com.actionanand.localtell.app.journey

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.actionanand.localtell.app.MainActivity
import com.actionanand.localtell.app.AppLanguageManager
import com.actionanand.localtell.app.R
import com.actionanand.localtell.app.data.OfflineLocalityResolver
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.location.GnssFixResult
import com.actionanand.localtell.app.location.OneShotGnssLocator
import com.actionanand.localtell.app.model.RadioCell
import com.actionanand.localtell.app.telephony.CellReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class JourneyForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.actionanand.localtell.START_JOURNEY"
        const val ACTION_STOP = "com.actionanand.localtell.STOP_JOURNEY"
        private const val CHANNEL_ID = "journey"
        private const val NOTIFICATION_ID = 2201
        private const val CHECK_INTERVAL_MS = 20_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var reader: CellReader
    private lateinit var resolver: OfflineLocalityResolver
    private lateinit var gnssLocator: OneShotGnssLocator
    private lateinit var journeyDb: JourneyDbHelper
    private val runGuard = JourneyRunGuard()
    private var loopJob: Job? = null
    private var lastLocalityKey: String? = null
    private var currentLocality: String? = null

    override fun onCreate() {
        super.onCreate()
        reader = CellReader(this)
        resolver = OfflineLocalityResolver(PackStore(this))
        gnssLocator = OneShotGnssLocator(this)
        journeyDb = JourneyDbHelper(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent != null && intent.action != ACTION_START) return START_NOT_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopTracking()
            JourneyTrackingState.update(this, JourneyTrackingStatus(JourneyTrackingMode.PERMISSION_REQUIRED, detail = AppLanguageManager.getString(this, R.string.journey_fine_location_needed)))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (loopJob?.isActive == true) return START_STICKY
        val generation = runGuard.begin()
        lastLocalityKey = null
        currentLocality = null
        JourneyTrackingState.update(this, JourneyTrackingStatus(JourneyTrackingMode.STARTING, detail = AppLanguageManager.getString(this, R.string.journey_starting)))
        startAsForeground(AppLanguageManager.getString(this, R.string.journey_finding_locality))
        loopJob = scope.launch { trackingLoop(generation) }
        return START_STICKY
    }

    private fun stopTracking() {
        runGuard.stop {
            loopJob?.cancel()
            loopJob = null
            JourneyTrackingState.stopped(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    /** One sequential loop: read cellular state, acquire one GNSS fix, resolve locally, then wait. */
    private suspend fun trackingLoop(generation: Long) {
        while (currentCoroutineContext().isActive && runGuard.isActive(generation)) {
            try {
                checkLocality(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish(generation, JourneyTrackingMode.WAITING_FOR_LOCALITY, AppLanguageManager.getString(this, R.string.journey_waiting_locality))
            }
            ensureTracking(generation)
            delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun ensureTracking(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!runGuard.isActive(generation)) throw CancellationException("Journey stopped")
    }

    private suspend fun checkLocality(generation: Long) {
        ensureTracking(generation)
        val runContext = currentCoroutineContext()
        val subscriptions = reader.requestSubscriptionCells()
        ensureTracking(generation)
        val cells = subscriptions.flatMap { it.cells }
        val serving = preferredServingCell(cells)
        if (!resolver.hasGeographicPack()) {
            publish(generation, JourneyTrackingMode.WAITING_FOR_LOCALITY, AppLanguageManager.getString(this, R.string.journey_download_geographic_pack))
            return
        }

        ensureTracking(generation)
        publish(generation, JourneyTrackingMode.ACQUIRING_LOCALITY, AppLanguageManager.getString(this, R.string.journey_acquiring_locality))
        when (val fix = gnssLocator.getLocation()) {
            is GnssFixResult.Precise -> {
                val match = resolver.resolve(fix.location.latitude, fix.location.longitude)
                if (match == null) {
                    publish(generation, JourneyTrackingMode.WAITING_FOR_LOCALITY, AppLanguageManager.getString(this, R.string.journey_waiting_locality))
                    return
                }

                runGuard.runIfActive(generation) {
                    runContext.ensureActive()
                    currentLocality = match.localityName
                    publish(generation, JourneyTrackingMode.ACTIVE, null)
                    val localityKey = journeyLocalityKey(match.localityName, match.subDistrict, match.district, match.state)
                    if (localityKey != lastLocalityKey) {
                        lastLocalityKey = localityKey
                        journeyDb.add(
                            JourneyPoint(
                                id = 0,
                                timestamp = System.currentTimeMillis(),
                                areaName = match.localityName,
                                district = match.district ?: match.subDistrict,
                                state = match.state,
                                radio = serving?.radio ?: "Unknown",
                                plmn = serving?.plmn ?: "Unknown",
                                cellId = serving?.cellId ?: 0L,
                                confidence = if (match.sourceQuality == "polygon") 100 else 70,
                            ),
                        )
                        JourneyHistoryChanges.changed()
                    }
                }
            }
            is GnssFixResult.Approximate -> {
                val match = resolver.resolve(fix.location.latitude, fix.location.longitude)
                if (match == null) {
                    publish(generation, JourneyTrackingMode.WAITING_FOR_LOCALITY, AppLanguageManager.getString(this, R.string.journey_waiting_locality))
                    return
                }

                runGuard.runIfActive(generation) {
                    runContext.ensureActive()
                    currentLocality = match.localityName
                    publish(generation, JourneyTrackingMode.ACTIVE, AppLanguageManager.getString(this, R.string.journey_approximate_location))
                    val localityKey = journeyLocalityKey(match.localityName, match.subDistrict, match.district, match.state)
                    if (localityKey != lastLocalityKey) {
                        lastLocalityKey = localityKey
                        journeyDb.add(
                            JourneyPoint(
                                id = 0,
                                timestamp = System.currentTimeMillis(),
                                areaName = match.localityName,
                                district = match.district ?: match.subDistrict,
                                state = match.state,
                                radio = serving?.radio ?: "Unknown",
                                plmn = serving?.plmn ?: "Unknown",
                                cellId = serving?.cellId ?: 0L,
                                confidence = if (match.sourceQuality == "polygon") 70 else 50,
                            ),
                        )
                        JourneyHistoryChanges.changed()
                    }
                }
            }
            GnssFixResult.Timeout -> publish(generation, JourneyTrackingMode.WAITING_FOR_LOCALITY, AppLanguageManager.getString(this, R.string.journey_waiting_precise_location))
            GnssFixResult.ProviderDisabled -> publish(generation, JourneyTrackingMode.GPS_DISABLED, AppLanguageManager.getString(this, R.string.journey_location_off_tracking))
            GnssFixResult.PermissionMissing -> publish(generation, JourneyTrackingMode.PERMISSION_REQUIRED, AppLanguageManager.getString(this, R.string.journey_fine_location_needed))
            is GnssFixResult.Error -> publish(generation, JourneyTrackingMode.WAITING_FOR_LOCALITY, AppLanguageManager.getString(this, R.string.journey_waiting_locality))
        }
    }

    private fun preferredServingCell(cells: List<RadioCell>): RadioCell? = cells
        .asSequence()
        .filter(RadioCell::registered)
        .sortedWith(compareByDescending<RadioCell> { it.radio == "NR" }.thenByDescending { it.dbm ?: -999 })
        .firstOrNull()

    private fun publish(generation: Long, mode: JourneyTrackingMode, detail: String?) {
        runGuard.runIfActive(generation) {
            val status = JourneyTrackingStatus(mode, currentLocality, System.currentTimeMillis(), detail)
            JourneyTrackingState.update(this, status)
            updateNotification(currentLocality ?: detail ?: AppLanguageManager.getString(this, R.string.journey_finding_locality))
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, JourneyForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_localtell)
            .setContentTitle(AppLanguageManager.getString(this, R.string.journey_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, AppLanguageManager.getString(this, R.string.notification_stop), stopIntent)
            .build()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun startAsForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification(text))
    }

    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, AppLanguageManager.getString(this, R.string.journey_notification_channel), NotificationManager.IMPORTANCE_LOW)) }

    override fun onDestroy() {
        stopTracking()
        scope.cancel()
        journeyDb.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
