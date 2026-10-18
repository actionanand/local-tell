package com.actionanand.localtell.app.saved

import java.util.Locale
import java.util.UUID
import kotlin.math.*
import com.actionanand.localtell.app.AppLimits
import com.actionanand.localtell.app.DataLimits

val MAX_SAVED_PLACES: Int get() = AppLimits.current.maxSavedPlaces

enum class SavedPlaceSource { HOME, EASY, LOOKUP, JOURNEY }

data class SavedPlace(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val localityName: String?,
    val subDistrict: String?,
    val district: String?,
    val state: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val savedAt: Long = System.currentTimeMillis(),
    val source: SavedPlaceSource,
    val accuracyMetres: Float? = null,
) {
    val identity: String get() = SavedPlaceIdentity.of(this)
}

data class RestorePreview(val added: Int, val duplicates: Int, val total: Int, val fits: Boolean)

sealed interface SaveResult {
    data object Saved : SaveResult
    data object Duplicate : SaveResult
    data object Full : SaveResult
}

internal fun classifySavedPlace(current: List<SavedPlace>, place: SavedPlace, limit: Int = MAX_SAVED_PLACES): SaveResult {
    require(SavedPlaceIdentity.valid(place))
    return when {
        current.any { SavedPlaceIdentity.matches(it, place) || it.id == place.id } -> SaveResult.Duplicate
        current.size >= limit -> SaveResult.Full
        else -> SaveResult.Saved
    }
}

/** Existing identities win. An ID collision with a different place is an invalid backup. */
internal fun planSavedPlacesMerge(current: List<SavedPlace>, incoming: List<SavedPlace>, limit: Int = MAX_SAVED_PLACES): RestorePreview {
    require(incoming.size <= DataLimits.MAX_SAVED_BOUND && incoming.all(SavedPlaceIdentity::valid))
    require(incoming.map { it.id }.distinct().size == incoming.size)
    require(!SavedPlaceIdentity.hasDuplicates(incoming))
    val existingById = current.associateBy { it.id }
    require(incoming.none { candidate -> existingById[candidate.id]?.let { !SavedPlaceIdentity.matches(it, candidate) } == true })
    val additions = incoming.count { candidate -> current.none { SavedPlaceIdentity.matches(it, candidate) } }
    return RestorePreview(additions, incoming.size - additions, current.size + additions, additions == 0 || current.size + additions <= limit)
}

/** Identity is a storage key; duplicate decisions also consider distance, including grid edges. */
object SavedPlaceIdentity {
    private val countryCodes = Locale.getISOCountries().toSet()
    private fun normalize(value: String?): String = value.orEmpty().trim()
        .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun validText(value: String): Boolean = value.isNotBlank() && value.length <= 200 &&
        value.none(Char::isISOControl) && validBackupUnicode(value)

    fun of(place: SavedPlace): String {
        val name = normalize(place.localityName)
        if (name.isEmpty()) {
            return String.format(Locale.ROOT, "coordinates:%.4f:%.4f", place.latitude, place.longitude)
        }
        return listOf(name, place.subDistrict, place.district, place.state, place.countryCode)
            .map { normalize(it) }.joinToString("|") { "${it.length}:$it" }
            .let { "locality:$it:${String.format(Locale.ROOT, "%.6f:%.6f", place.latitude, place.longitude)}" }
    }

    fun matches(a: SavedPlace, b: SavedPlace): Boolean {
        val named = !a.localityName.isNullOrBlank() && !b.localityName.isNullOrBlank()
        if (named && normalize(a.localityName) != normalize(b.localityName)) return false
        val dLat = Math.toRadians(a.latitude - b.latitude)
        val dLon = Math.toRadians(a.longitude - b.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        val metres = 12_742_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        // The same coordinate can have less hierarchy after packs are removed, or an older
        // Journey district fallback. Missing/changed metadata must not hide its saved status.
        if (metres <= 20.0) return true
        if (!named) return false
        val hasContext = listOf(a.subDistrict, a.district, a.state).any { !it.isNullOrBlank() }
        return hasContext && metres <= 5_000.0 &&
            listOf(a.subDistrict, a.district, a.state, a.countryCode).map(::normalize) ==
            listOf(b.subDistrict, b.district, b.state, b.countryCode).map(::normalize)
    }

    fun hasDuplicates(places: List<SavedPlace>): Boolean = places.indices.any { i ->
        (0 until i).any { j -> matches(places[i], places[j]) }
    }

    fun valid(place: SavedPlace): Boolean =
        place.latitude.isFinite() && place.latitude in -90.0..90.0 &&
            place.longitude.isFinite() && place.longitude in -180.0..180.0 &&
            place.id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && validText(place.displayName) &&
            listOf(place.localityName, place.subDistrict, place.district, place.state).all { it == null || validText(it) } &&
            (place.countryCode == null || place.countryCode in countryCodes) &&
            place.savedAt > 0 && (place.accuracyMetres == null || (place.accuracyMetres.isFinite() && place.accuracyMetres >= 0))
}
