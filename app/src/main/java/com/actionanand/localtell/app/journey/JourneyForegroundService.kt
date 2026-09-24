package com.actionanand.localtell.app.journey

import android.app.*
import android.Manifest
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
import com.actionanand.localtell.app.data.OfflineAreaResolver
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.telephony.CellReader
import kotlinx.coroutines.*

class JourneyForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.actionanand.localtell.START_JOURNEY"
        const val ACTION_STOP = "com.actionanand.localtell.STOP_JOURNEY"
        private const val CHANNEL_ID = "journey"
        private const val NOTIFICATION_ID = 2201
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var reader: CellReader
    private lateinit var resolver: OfflineAreaResolver
    private lateinit var journeyDb: JourneyDbHelper
    private var loopJob: Job? = null
    private var lastArea: String? = null

    override fun onCreate() {
        super.onCreate()
        reader = CellReader(this)
        resolver = OfflineAreaResolver(PackStore(this))
        journeyDb = JourneyDbHelper(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startAsForeground("Finding approximate area…")
        if (loopJob == null) loopJob = scope.launch { trackingLoop() }
        return START_STICKY
    }

    private suspend fun trackingLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching {
                val cells = reader.requestServingCells()
                val match = resolver.resolveFirst(cells)
                if (match != null) {
                    updateNotification("Approx. area: ${match.areaName}")
                    if (lastArea != match.areaName) {
                        lastArea = match.areaName
                        journeyDb.add(
                            JourneyPoint(
                                id = 0,
                                timestamp = System.currentTimeMillis(),
                                areaName = match.areaName,
                                district = match.district,
                                state = match.state,
                                radio = match.matchedCell.radio,
                                plmn = match.matchedCell.plmn,
                                cellId = match.matchedCell.cellId,
                                confidence = match.confidence,
                            )
                        )
                    }
                } else {
                    updateNotification("Cell detected; locality not in installed offline packs")
                }
            }
            delay(20_000)
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, JourneyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Journey tracking", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        journeyDb.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
