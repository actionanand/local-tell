package com.actionanand.localtell.app.backup

import com.actionanand.localtell.app.DataLimits
import com.actionanand.localtell.app.journey.JourneyPoint
import com.actionanand.localtell.app.saved.*
import java.security.MessageDigest
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Full app-data backup V1 only. Checksum detects corruption, not authenticity or encryption. */
object AppBackupCodec {
    // Includes UTF-8 and twice-escaped JSON at the absolute 200/1000 record ceilings.
    const val MAX_BYTES = 12 * 1024 * 1024
    private const val FORMAT = "localtell-backup"
    private const val VERSION = 1

    fun encode(data: AppBackupData): String {
        validate(data)
        val payload = JSONObject().put("exportedAt", Instant.now().toString())
            .put("savedPlaces", JSONArray().apply { data.savedPlaces.forEach { put(savedJson(it)) } })
            .put("journeys", JSONArray().apply { data.journeys.forEach { put(journeyJson(it)) } }).toString()
        return JSONObject().put("format", FORMAT).put("schemaVersion", VERSION)
            .put("payload", payload).put("sha256", sha256(payload)).toString()
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) }
    }

    /** Stable across exports of the same data; the export timestamp and database row IDs are excluded. */
    fun fingerprint(data: AppBackupData): String {
        validate(data)
        val canonical = data.copy(
            savedPlaces = data.savedPlaces.sortedBy { it.id },
            journeys = data.journeys.sortedWith(compareBy<JourneyPoint> { it.timestamp }.thenBy { journeySortKey(it) }),
        )
        val payload = JSONObject(JSONObject(encode(canonical)).getString("payload"))
        payload.remove("exportedAt")
        return sha256(payload.toString())
    }

    fun decode(json: String): AppBackupData {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        StrictBackupJson(json).validate()
        val root = JSONObject(json)
        require(root.get("format") == FORMAT && root.get("schemaVersion") is Int && root.getInt("schemaVersion") == VERSION)
        val payload = root.get("payload") as? String ?: error("Missing payload")
        require(root.get("sha256") == sha256(payload)) { "Backup checksum mismatch" }
        StrictBackupJson(payload).validate()
        val body = JSONObject(payload)
        Instant.parse(body.text("exportedAt"))
        val saved = body.getJSONArray("savedPlaces")
        val journey = body.getJSONArray("journeys")
        require(saved.length() <= DataLimits.MAX_SAVED_BOUND && journey.length() <= DataLimits.MAX_JOURNEY_BOUND)
        return AppBackupData(
            (0 until saved.length()).map { i -> saved.getJSONObject(i).let { item ->
                SavedPlace(item.text("id"), item.text("name"), item.optionalText("localityName"),
                    item.optionalText("subDistrict"), item.optionalText("district"), item.optionalText("state"),
                    item.optionalText("countryCode"), item.number("latitude"), item.number("longitude"),
                    Instant.parse(item.text("savedAt")).toEpochMilli(), SavedPlaceSource.valueOf(item.text("source")), item.optionalNumber("accuracyMetres")?.toFloat())
            } },
            (0 until journey.length()).map { i -> journey.getJSONObject(i).let { item ->
                JourneyPoint(0, Instant.parse(item.text("timestamp")).toEpochMilli(), item.text("areaName"),
                    item.optionalText("district"), item.optionalText("state"), item.text("radio"), item.text("plmn"),
                    item.integer("cellId"), item.integer("confidence").also { require(it in 0L..100L) }.toInt(),
                    item.optionalNumber("latitude"), item.optionalNumber("longitude"), item.optionalNumber("accuracyMetres")?.toFloat(),
                    item.optionalText("subDistrict"))
            } },
        ).also(::validate)
    }

    fun validate(data: AppBackupData) {
        // Preserve over-limit existing bookmarks after a configured limit reduction. The
        // configured capacity is enforced on additions in the merge plan, never by deleting data.
        require(data.savedPlaces.size <= DataLimits.MAX_SAVED_BOUND && data.savedPlaces.all(SavedPlaceIdentity::valid))
        require(data.savedPlaces.map { it.id }.distinct().size == data.savedPlaces.size && !SavedPlaceIdentity.hasDuplicates(data.savedPlaces))
        require(data.journeys.size <= DataLimits.MAX_JOURNEY_BOUND && data.journeys.all(::validJourney))
    }

    internal fun journeySortKey(point: JourneyPoint): String = listOf(point.areaName, point.district, point.state,
        point.subDistrict, point.radio, point.plmn, point.cellId, point.confidence, point.latitude, point.longitude,
        point.accuracyMetres).joinToString("\u0000") { it?.toString() ?: "\u0001" }

    private fun savedJson(place: SavedPlace) = JSONObject().apply {
        put("id", place.id); put("name", place.displayName); putNullable("localityName", place.localityName)
        putNullable("subDistrict", place.subDistrict); putNullable("district", place.district); putNullable("state", place.state)
        putNullable("countryCode", place.countryCode); put("latitude", place.latitude); put("longitude", place.longitude)
        put("savedAt", Instant.ofEpochMilli(place.savedAt).toString()); put("source", place.source.name)
        putNullable("accuracyMetres", place.accuracyMetres)
    }

    private fun journeyJson(point: JourneyPoint) = JSONObject().apply {
        put("timestamp", Instant.ofEpochMilli(point.timestamp).toString()); put("areaName", point.areaName)
        putNullable("district", point.district); putNullable("state", point.state); putNullable("subDistrict", point.subDistrict)
        put("radio", point.radio); put("plmn", point.plmn); put("cellId", point.cellId); put("confidence", point.confidence)
        putNullable("latitude", point.latitude); putNullable("longitude", point.longitude); putNullable("accuracyMetres", point.accuracyMetres)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.text(key: String): String = get(key) as? String ?: error("Invalid text: $key")
    private fun JSONObject.optionalText(key: String): String? = if (!has(key) || isNull(key)) null else text(key)
    private fun JSONObject.number(key: String): Double { require(get(key) is Number); return getDouble(key) }
    private fun JSONObject.optionalNumber(key: String): Double? = if (!has(key) || isNull(key)) null else number(key)
    private fun JSONObject.integer(key: String): Long {
        val value = get(key); require(value is Int || value is Long); return (value as Number).toLong()
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
