package com.actionanand.localtell.app.saved

import com.actionanand.localtell.app.easy.EasyLocation
import com.actionanand.localtell.app.locationcode.LocalTellLocationCode
import com.actionanand.localtell.app.locationcode.LocationInputResult
import com.actionanand.localtell.app.model.LocalityMatch

fun savedPlaceFor(
    latitude: Double?, longitude: Double?, accuracy: Float?, locality: LocalityMatch?,
    source: SavedPlaceSource, name: String? = locality?.localityName,
    district: String? = locality?.district, state: String? = locality?.state,
    countryCode: String? = null,
): SavedPlace? {
    if (latitude == null || longitude == null) return null
    val place = SavedPlace(
        displayName = name?.takeIf { it.isNotBlank() }
            ?: String.format(java.util.Locale.ROOT, "%.6f, %.6f", latitude, longitude),
        localityName = name?.takeIf { it.isNotBlank() }, subDistrict = locality?.subDistrict,
        district = district, state = state, countryCode = countryCode,
        latitude = latitude, longitude = longitude, source = source, accuracyMetres = accuracy,
    )
    return place.takeIf(SavedPlaceIdentity::valid)
}

fun EasyLocation.bookmark(source: SavedPlaceSource): SavedPlace? = savedPlace ?: savedPlaceFor(
    originalLatitude, originalLongitude, accuracyMetres, locality, source,
)

/** No pack lookup, Android Location object, network request or GPS acquisition. */
fun SavedPlace.toEasyLocation(): EasyLocation = EasyLocation(
    encoded = LocalTellLocationCode.encode(latitude, longitude),
    accuracyMetres = accuracyMetres, source = LocationInputResult.Source.COORDINATE,
    originalLatitude = latitude, originalLongitude = longitude, savedPlace = this,
)
