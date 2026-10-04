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
import com.actionanand.localtell.app.R
import com.actionanand.localtell.app.data.OfflineLocalityResolver
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.location.GnssFixResult
import com.actionanand.localtell.app.location.OneShotGnssLocator
import com.actionanand.localtell.app.model.RadioCell
import com.actionanand.localtell.app.telephony.CellReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
            JourneyTrackingState.stopped(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            JourneyTrackingState.update(this, JourneyTrackingStatus(JourneyTrackingMode.PERMISSION_REQUIRED, detail = "Fine location permission is required."))
            stopSelf(startId)
            return START_NOT_STICKY
        }

        JourneyTrackingState.update(this, JourneyTrackingStatus(JourneyTrackingMode.STARTING, detail = "Starting journey tracking…"))
        startAsForeground("Finding current locality…")
        if (loopJob?.isActive != true) loopJob = scope.launch { trackingLoop() }
        return START_STICKY
    }

    /** One sequential loop: read cellular state, acquire one GNSS fix, resolve locally, then wait. */
    private suspend fun trackingLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching { checkLocality() }
                .onFailure { publish(JourneyTrackingMode.WAITING_FOR_LOCALITY, "Waiting for locality…") }
            delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun checkLocality() {
        val subscriptions = reader.requestSubscriptionCells()
        val cells = subscriptions.flatMap { it.cells }
        val serving = preferredServingCell(cells)
        if (!resolver.hasGeographicPack()) {
            publish(JourneyTrackingMode.WAITING_FOR_LOCALITY, "Download a geographic offline data pack to track localities.")
            return
        }

        publish(JourneyTrackingMode.ACQUIRING_LOCALITY, "Acquiring locality…")
        when (val fix = gnssLocator.getLocation()) {
            is GnssFixResult.Success -> {
                val match = resolver.resolve(fix.location.latitude, fix.location.longitude)
                if (match == null) {
                    publish(JourneyTrackingMode.WAITING_FOR_LOCALITY, "Waiting for locality…")
                    return
                }

                currentLocality = match.localityName
                publish(JourneyTrackingMode.ACTIVE, null)
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
            GnssFixResult.Timeout -> publish(JourneyTrackingMode.WAITING_FOR_LOCALITY, getString(R.string.journey_waiting_precise_location))
            GnssFixResult.ProviderDisabled -> publish(JourneyTrackingMode.GPS_DISABLED, getString(R.string.journey_location_off_tracking))
            GnssFixResult.PermissionMissing -> publish(JourneyTrackingMode.PERMISSION_REQUIRED, "Fine location permission is required.")
            is GnssFixResult.Error -> publish(JourneyTrackingMode.WAITING_FOR_LOCALITY, "Waiting for locality…")
        }
    }

    private fun preferredServingCell(cells: List<RadioCell>): RadioCell? = cells
        .asSequence()
        .filter(RadioCell::registered)
        .sortedWith(compareByDescending<RadioCell> { it.radio == "NR" }.thenByDescending { it.dbm ?: -999 })
        .firstOrNull()

    private fun publish(mode: JourneyTrackingMode, detail: String?) {
        val status = JourneyTrackingStatus(mode, currentLocality, System.currentTimeMillis(), detail)
        JourneyTrackingState.update(this, status)
        updateNotification(currentLocality ?: detail ?: "Finding current locality…")
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, JourneyForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_localtell)
            .setContentTitle("LocalTell journey")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun startAsForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification(text))
    }

    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Journey tracking", NotificationManager.IMPORTANCE_LOW)) }

    override fun onDestroy() {
        loopJob?.cancel()
        JourneyTrackingState.stopped(this)
        journeyDb.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
