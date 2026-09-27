package com.actionanand.localtell.app.data

import android.content.Context
import com.actionanand.localtell.app.model.CellularFingerprint
import com.actionanand.localtell.app.model.LocalityMatch
import org.json.JSONObject

data class CachedLocality(
    val match: LocalityMatch,
    val resolvedAt: Long,
    val accuracyMetres: Float,
    val fingerprint: CellularFingerprint,
)

/** Central policy for the small, local battery-saving cache; it is not a cell-location database. */
object LocalityCachePolicy {
    const val MAX_AGE_MS = 15L * 60L * 1_000L

    fun canReuse(
        cached: CachedLocality?,
        current: CellularFingerprint?,
        packStillInstalled: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean = cached != null && current != null && packStillInstalled &&
        cached.fingerprint == current && now - cached.resolvedAt in 0..MAX_AGE_MS
}

class LocalityCache(context: Context) {
    private val preferences = context.getSharedPreferences("locality_cache", Context.MODE_PRIVATE)

    fun read(): CachedLocality? = runCatching {
        val value = preferences.getString("recent", null) ?: return null
        val json = JSONObject(value)
        val match = LocalityMatch(
            localityName = json.getString("name"), localityType = json.optString("type").ifBlank { null },
            subDistrict = json.optString("subDistrict").ifBlank { null }, district = json.optString("district").ifBlank { null },
            state = json.optString("state").ifBlank { null }, stateCode = json.optString("stateCode").ifBlank { null },
            sourceQuality = json.getString("quality"), packId = json.getString("packId"), packVersion = json.getLong("packVersion"),
        )
        CachedLocality(
            match, json.getLong("resolvedAt"), json.getDouble("accuracy").toFloat(),
            CellularFingerprint(json.optInt("subscription", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }, json.getString("mcc"), json.getString("mnc"), json.getString("radio"), json.optLong("area", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }, json.getLong("cell")),
        )
    }.getOrNull()

    fun save(match: LocalityMatch, accuracyMetres: Float, fingerprint: CellularFingerprint) {
        val json = JSONObject().apply {
            put("name", match.localityName); put("type", match.localityType); put("subDistrict", match.subDistrict)
            put("district", match.district); put("state", match.state); put("stateCode", match.stateCode)
            put("quality", match.sourceQuality); put("packId", match.packId); put("packVersion", match.packVersion)
            put("resolvedAt", System.currentTimeMillis()); put("accuracy", accuracyMetres)
            put("subscription", fingerprint.subscriptionId ?: Int.MIN_VALUE); put("mcc", fingerprint.mcc); put("mnc", fingerprint.mnc)
            put("radio", fingerprint.radio); put("area", fingerprint.areaCode ?: Long.MIN_VALUE); put("cell", fingerprint.cellId)
        }
        preferences.edit().putString("recent", json.toString()).apply()
    }
}
