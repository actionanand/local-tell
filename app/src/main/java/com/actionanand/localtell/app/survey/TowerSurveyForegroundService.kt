package com.actionanand.localtell.app.survey

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.actionanand.localtell.app.BuildConfig
import com.actionanand.localtell.app.MainActivity
import com.actionanand.localtell.app.R
import com.actionanand.localtell.app.model.RadioCell
import com.actionanand.localtell.app.telephony.CellReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object TowerSurveyState {
    private const val PREFS = "tower_survey_state"
    private const val ACTIVE = "active"
    private const val STARTED_AT = "started_at"
    private const val FIXES = "fixes"
    private const val OBSERVATIONS = "observations"
    private const val ACCURACY = "accuracy"
    private const val REGISTERED = "registered"

    fun summary(context: Context): TowerSurveySummary {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TowerSurveySummary(
            active = prefs.getBoolean(ACTIVE, false),
            startedAt = prefs.getLong(STARTED_AT, 0L).takeIf { it > 0 },
            acceptedFixes = prefs.getInt(FIXES, 0), observations = prefs.getInt(OBSERVATIONS, 0),
            accuracy = prefs.getFloat(ACCURACY, -1f).takeIf { it >= 0f }, registeredSummary = prefs.getString(REGISTERED, null),
        )
    }

    fun started(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(ACTIVE, true).putLong(STARTED_AT, System.currentTimeMillis()).putInt(FIXES, 0).putInt(OBSERVATIONS, 0).apply()
    fun stopped(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ACTIVE, false).apply()
    fun update(context: Context, fixes: Int, observations: Int, accuracy: Float, registered: String?) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(FIXES, fixes).putInt(OBSERVATIONS, observations).putFloat(ACCURACY, accuracy).putString(REGISTERED, registered).apply()
    fun cleared(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(FIXES).remove(OBSERVATIONS).remove(ACCURACY).remove(REGISTERED).apply()
}

class TowerSurveyForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.actionanand.localtell.START_TOWER_SURVEY"
        const val ACTION_STOP = "com.actionanand.localtell.STOP_TOWER_SURVEY"
        private const val CHANNEL_ID = "tower-survey"
        private const val NOTIFICATION_ID = 2202
        private const val MAX_ACCURACY_METRES = 50f
        private const val MIN_DISTANCE_METRES = 45f
        private const val MIN_INTERVAL_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var locationManager: LocationManager
    private lateinit var reader: CellReader
    private lateinit var db: TowerSurveyDbHelper
    private var sessionId = 0L
    private var lastStoredLocation: Location? = null
    private var lastServingKey: String? = null
    private val listener = LocationListener { location -> scope.launch { handleLocation(location) } }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        reader = CellReader(this)
        db = TowerSurveyDbHelper(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.ENABLE_TOWER_SURVEY || intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (sessionId == 0L) {
            sessionId = db.startSession()
            TowerSurveyState.started(this)
            startAsForeground("Tower Survey active · 0 observations")
            startSurveyUpdates()
        } else {
            startAsForeground("Tower Survey active · 0 observations")
        }
        return START_NOT_STICKY
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun startSurveyUpdates() {
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5_000L, 0f, listener, Looper.getMainLooper())
    }

    private suspend fun handleLocation(location: Location) {
        if (location.provider != LocationManager.GPS_PROVIDER || !location.hasAccuracy() || location.accuracy > MAX_ACCURACY_METRES) return
        val cells = reader.requestSubscriptionCells().flatMap { it.cells }
        if (cells.isEmpty()) return
        val registered = cells.filter(RadioCell::registered)
        val servingKey = registered.joinToString("|") { "${it.subscriptionId}:${it.mcc}:${it.mnc}:${it.radio}:${it.areaCode}:${it.cellId}" }
        val previous = lastStoredLocation
        val distance = previous?.distanceTo(location) ?: Float.MAX_VALUE
        val elapsed = previous?.let { location.time - it.time } ?: Long.MAX_VALUE
        val moving = distance >= 5f || (location.hasSpeed() && location.speed >= 0.5f)
        val shouldStore = previous == null || servingKey != lastServingKey || distance >= MIN_DISTANCE_METRES || (moving && elapsed >= MIN_INTERVAL_MS)
        if (!shouldStore) return

        db.insertFix(sessionId, location, cells)
        lastStoredLocation = Location(location)
        lastServingKey = servingKey
        val (fixes, observations) = db.counts(sessionId)
        val summary = registered.joinToString(" · ") { "${it.carrierName ?: "Cellular"} ${it.radio} ${it.cellId}" }.takeIf { it.isNotBlank() }
        TowerSurveyState.update(this, fixes, observations, location.accuracy, summary)
        updateNotification("Tower Survey active · $observations observations")
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, TowerSurveyForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_localtell).setContentTitle("LocalTell Tower Survey")
            .setContentText(text).setContentIntent(openIntent).setOngoing(true).setOnlyAlertOnce(true).addAction(0, "Stop", stopIntent).build()
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun startAsForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification(text))
    }

    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    private fun createChannel() { getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tower Survey", NotificationManager.IMPORTANCE_LOW)) }

    override fun onDestroy() {
        runCatching { locationManager.removeUpdates(listener) }
        if (sessionId != 0L) db.endSession(sessionId)
        TowerSurveyState.stopped(this)
        db.close(); scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
