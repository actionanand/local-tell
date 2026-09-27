package com.actionanand.localtell.app.journey

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class JourneyTrackingMode { STOPPED, STARTING, ACQUIRING_LOCALITY, ACTIVE, WAITING_FOR_LOCALITY, GPS_DISABLED, PERMISSION_REQUIRED }

data class JourneyTrackingStatus(
    val mode: JourneyTrackingMode = JourneyTrackingMode.STOPPED,
    val localityName: String? = null,
    val lastCheckedAt: Long? = null,
    val detail: String? = null,
)

/** Lightweight process-local observation plus persisted state for a foreground Journey service. */
object JourneyTrackingState {
    private const val PREFS = "journey_tracking"
    private const val MODE = "mode"
    private const val LOCALITY = "locality"
    private const val LAST_CHECKED = "last_checked"
    private const val DETAIL = "detail"
    private val mutableStatus = MutableStateFlow(JourneyTrackingStatus())
    val status: StateFlow<JourneyTrackingStatus> = mutableStatus

    fun initialize(context: Context) { mutableStatus.value = read(context) }

    fun update(context: Context, status: JourneyTrackingStatus) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MODE, status.mode.name)
            .putString(LOCALITY, status.localityName)
            .putLong(LAST_CHECKED, status.lastCheckedAt ?: 0L)
            .putString(DETAIL, status.detail)
            .apply()
        mutableStatus.value = status
    }

    fun stopped(context: Context) = update(context, JourneyTrackingStatus())

    private fun read(context: Context): JourneyTrackingStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(MODE, null)?.let { runCatching { JourneyTrackingMode.valueOf(it) }.getOrNull() }
            ?: JourneyTrackingMode.STOPPED
        return JourneyTrackingStatus(
            mode = mode,
            localityName = prefs.getString(LOCALITY, null),
            lastCheckedAt = prefs.getLong(LAST_CHECKED, 0L).takeIf { it > 0L },
            detail = prefs.getString(DETAIL, null),
        )
    }
}

/** Notifies active UI consumers after Journey history is changed without polling SQLite. */
object JourneyHistoryChanges {
    private val mutableVersion = MutableStateFlow(0L)
    val version: StateFlow<Long> = mutableVersion
    fun changed() { mutableVersion.value += 1L }
}

/** Identifies a human locality across repeated periodic checks without relying on radio details. */
fun journeyLocalityKey(localityName: String, subDistrict: String?, district: String?, state: String?): String =
    listOf(localityName, subDistrict, district, state).joinToString("|")
