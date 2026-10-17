package com.actionanand.localtell.app.saved

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.security.MessageDigest

/** Unencrypted, versioned backup of saved places only. */
object SavedPlacesBackupCodec {
    const val MAX_BYTES = 256 * 1024
    private const val FORMAT = "localtell-saved-places"
    private const val VERSION = 2

    fun encode(places: List<SavedPlace>): String {
        require(places.size <= MAX_SAVED_PLACES && places.all(SavedPlaceIdentity::valid))
        require(places.map { it.id }.distinct().size == places.size && !SavedPlaceIdentity.hasDuplicates(places))
        val array = JSONArray()
        places.forEach { place ->
            array.put(JSONObject().apply {
                put("id", place.id); put("name", place.displayName)
                put("localityName", place.localityName ?: JSONObject.NULL)
                put("subDistrict", place.subDistrict ?: JSONObject.NULL)
                put("district", place.district ?: JSONObject.NULL)
                put("state", place.state ?: JSONObject.NULL)
                put("countryCode", place.countryCode ?: JSONObject.NULL)
                put("latitude", place.latitude); put("longitude", place.longitude)
                put("savedAt", Instant.ofEpochMilli(place.savedAt).toString())
                put("source", place.source.name)
                put("accuracyMetres", place.accuracyMetres ?: JSONObject.NULL)
            })
        }
        // Hash the exact UTF-8 payload, not JSONObject's platform-dependent property ordering.
        val payload = JSONObject().put("exportedAt", Instant.now().toString()).put("places", array).toString()
        return JSONObject().put("format", FORMAT).put("schemaVersion", VERSION)
            .put("payload", payload).put("sha256", sha256(payload)).toString()
    }

    fun decode(json: String): List<SavedPlace> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        StrictBackupJson(json).validate()
        val tokener = JSONTokener(json)
        val root = tokener.nextValue() as? JSONObject ?: error("Expected backup object")
        require(tokener.nextClean() == '\u0000')
        require(root.get("format") == FORMAT && root.get("schemaVersion") is Int)
        val content = when (root.getInt("schemaVersion")) {
            1 -> root // Earlier experimental content remains readable, but only through a .bklt file.
            VERSION -> {
                val payload = root.get("payload") as? String ?: error("Missing payload")
                require(root.get("sha256") == sha256(payload)) { "Backup checksum mismatch" }
                StrictBackupJson(payload).validate()
                JSONObject(payload)
            }
            else -> error("Unsupported backup schema")
        }
        require(content.get("exportedAt") is String)
        Instant.parse(content.getString("exportedAt"))
        val array = content.getJSONArray("places")
        require(array.length() <= MAX_SAVED_PLACES)
        val places = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            fun requiredText(key: String): String = (item.get(key) as? String) ?: error("Invalid text field")
            fun optional(key: String): String? = if (!item.has(key) || item.isNull(key)) null else requiredText(key)
            require(item.get("latitude") is Number && item.get("longitude") is Number)
            require(!item.has("accuracyMetres") || item.isNull("accuracyMetres") || item.get("accuracyMetres") is Number)
            SavedPlace(
                id = requiredText("id"), displayName = requiredText("name"),
                localityName = optional("localityName"), subDistrict = optional("subDistrict"),
                district = optional("district"), state = optional("state"), countryCode = optional("countryCode"),
                latitude = item.getDouble("latitude"), longitude = item.getDouble("longitude"),
                savedAt = Instant.parse(requiredText("savedAt")).toEpochMilli(),
                source = SavedPlaceSource.valueOf(requiredText("source")),
                accuracyMetres = if (!item.has("accuracyMetres") || item.isNull("accuracyMetres")) null else item.getDouble("accuracyMetres").toFloat(),
            ).also { require(SavedPlaceIdentity.valid(it)) }
        }
        require(places.map { it.id }.distinct().size == places.size)
        require(!SavedPlaceIdentity.hasDuplicates(places))
        return places
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
