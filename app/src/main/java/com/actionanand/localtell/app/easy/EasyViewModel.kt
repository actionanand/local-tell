package com.actionanand.localtell.app.easy

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.data.OfflineLocalityResolver
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.R
import com.actionanand.localtell.app.location.GnssFixResult
import com.actionanand.localtell.app.location.AssistedLocationLocator
import com.actionanand.localtell.app.location.AssistedLocationResult
import com.actionanand.localtell.app.location.LocationQuality
import com.actionanand.localtell.app.location.LocationSource
import com.actionanand.localtell.app.location.NetworkAvailability
import com.actionanand.localtell.app.location.OneShotGnssLocator
import com.actionanand.localtell.app.locationcode.EncodedLocation
import com.actionanand.localtell.app.locationcode.LocationInputParser
import com.actionanand.localtell.app.locationcode.LocationInputResult
import com.actionanand.localtell.app.locationcode.LocalTellLocationCode
import com.actionanand.localtell.app.model.LocalityMatch
import com.actionanand.localtell.app.saved.SavedPlace
import com.actionanand.localtell.app.saved.toEasyLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EasyLocation(
    val encoded: EncodedLocation,
    val accuracyMetres: Float? = null,
    val locality: LocalityMatch? = null,
    val source: LocationInputResult.Source,
    val locationSource: LocationSource? = null,
    val locationQuality: LocationQuality? = null,
    val locationNotice: String? = null,
    val originalLatitude: Double = encoded.latitude,
    val originalLongitude: Double = encoded.longitude,
    val savedPlace: SavedPlace? = null,
)

data class PendingLocationChoice(val approximateLocation: Location?)

data class EasyUiState(
    val locating: Boolean = false,
    val currentLocation: EasyLocation? = null,
    val resolvedLocation: EasyLocation? = null,
    val error: EasyError? = null,
    val pendingLocationChoice: PendingLocationChoice? = null,
    val savedOpenSequence: Long = 0,
)

enum class EasyError {
    TIMEOUT,
    LOCATION_DISABLED,
    PERMISSION_MISSING,
    LOCATION_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    INVALID_INPUT,
}

class EasyViewModel(app: Application) : AndroidViewModel(app) {
    private val locator = OneShotGnssLocator(app)
    private val assistedLocator = AssistedLocationLocator(app)
    private val assistedFallbackNotice = app.getString(R.string.location_assisted_fallback)
    private val resolver = OfflineLocalityResolver(PackStore(app))
    private val _state = MutableStateFlow(EasyUiState())
    val state: StateFlow<EasyUiState> = _state.asStateFlow()
    private var lookupRevision = 0L
    internal val locationRequestFlow = EasyLocationRequestFlow()

    /** Shows why a pending Get my location request could not continue (permission or Location declined). */
    fun reportLocationPrerequisite(error: EasyError) {
        if (_state.value.locating) return
        _state.value = _state.value.copy(error = error)
    }
    fun getMyLocation() {
        if (_state.value.locating) return
        viewModelScope.launch {
            _state.value = _state.value.copy(locating = true, error = null, pendingLocationChoice = null)
            when (val result = locator.getLocation()) {
                is GnssFixResult.Precise -> setCurrent(result.location, LocationSource.GPS, LocationQuality.PRECISE)
                is GnssFixResult.Approximate -> _state.value = _state.value.copy(
                    locating = false,
                    pendingLocationChoice = PendingLocationChoice(result.location),
                )
                GnssFixResult.Timeout -> _state.value = _state.value.copy(
                    locating = false,
                    pendingLocationChoice = PendingLocationChoice(null),
                )
                GnssFixResult.ProviderDisabled -> fail(EasyError.LOCATION_DISABLED)
                GnssFixResult.PermissionMissing -> fail(EasyError.PERMISSION_MISSING)
                is GnssFixResult.Error -> fail(EasyError.LOCATION_UNAVAILABLE)
            }
        }
    }

    fun useApproximateLocation() {
        val pending = _state.value.pendingLocationChoice ?: return
        val location = pending.approximateLocation
        if (location == null) {
            fail(EasyError.LOCATION_UNAVAILABLE)
            return
        }
        _state.value = _state.value.copy(locating = true, pendingLocationChoice = null)
        viewModelScope.launch { setCurrent(location, LocationSource.GPS, LocationQuality.APPROXIMATE) }
    }

    fun useAssistedLocation() {
        val pending = _state.value.pendingLocationChoice ?: return
        val approximate = pending.approximateLocation
        _state.value = _state.value.copy(locating = true, pendingLocationChoice = null)
        viewModelScope.launch {
            if (!withContext(Dispatchers.IO) { NetworkAvailability.hasUsableNetwork(getApplication()) }) {
                if (approximate != null) {
                    setCurrent(approximate, LocationSource.GPS, LocationQuality.APPROXIMATE, assistedFallbackNotice)
                } else {
                    fail(EasyError.NETWORK_UNAVAILABLE)
                }
                return@launch
            }
            when (val assisted = assistedLocator.getLocation()) {
                is AssistedLocationResult.Precise -> setCurrent(assisted.location, LocationSource.ASSISTED, LocationQuality.PRECISE)
                is AssistedLocationResult.Approximate -> setCurrent(assisted.location, LocationSource.ASSISTED, LocationQuality.APPROXIMATE)
                else -> if (approximate != null) {
                    setCurrent(approximate, LocationSource.GPS, LocationQuality.APPROXIMATE, assistedFallbackNotice)
                } else {
                    fail(EasyError.LOCATION_UNAVAILABLE)
                }
            }
        }
    }

    fun find(input: String) {
        if (_state.value.locating) return
        val revision = ++lookupRevision
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null)
            when (val parsed = LocationInputParser.parse(input)) {
                is LocationInputResult.Coordinate -> setResolved(parsed.latitude, parsed.longitude, parsed.source, revision)
                is LocationInputResult.Error -> _state.value = _state.value.copy(error = EasyError.INVALID_INPUT)
            }
        }
    }

    fun openSaved(place: SavedPlace) {
        lookupRevision++ // An older, still-running offline lookup must not overwrite this selection.
        // Merely presents an existing coordinate. Do not start acquisition or require a pack.
        _state.value = _state.value.copy(
            resolvedLocation = place.toEasyLocation(), error = null,
            savedOpenSequence = _state.value.savedOpenSequence + 1,
        )
    }

    fun savedLocationShown(sequence: Long) {
        if (_state.value.savedOpenSequence == sequence) {
            _state.value = _state.value.copy(savedOpenSequence = 0)
        }
    }

    private suspend fun setCurrent(location: Location, locationSource: LocationSource, locationQuality: LocationQuality, notice: String? = null) {
        val encoded = runCatching { LocalTellLocationCode.encode(location.latitude, location.longitude) }.getOrElse { return fail(EasyError.LOCATION_UNAVAILABLE) }
        val locality = withContext(Dispatchers.IO) { runCatching { resolver.resolve(encoded.latitude, encoded.longitude) }.getOrNull() }
        _state.value = _state.value.copy(locating = false, currentLocation = EasyLocation(encoded, location.accuracy, locality, LocationInputResult.Source.COORDINATE, locationSource, locationQuality, notice, location.latitude, location.longitude), pendingLocationChoice = null)
    }

    private suspend fun setResolved(latitude: Double, longitude: Double, source: LocationInputResult.Source, revision: Long) {
        val encoded = runCatching { LocalTellLocationCode.encode(latitude, longitude) }.getOrElse { return fail(EasyError.INVALID_INPUT) }
        val locality = withContext(Dispatchers.IO) { runCatching { resolver.resolve(encoded.latitude, encoded.longitude) }.getOrNull() }
        if (revision == lookupRevision) _state.value = _state.value.copy(resolvedLocation = EasyLocation(
            encoded, null, locality, source, originalLatitude = latitude, originalLongitude = longitude,
        ))
    }

    private fun fail(error: EasyError) {
        _state.value = _state.value.copy(locating = false, error = error, pendingLocationChoice = null)
    }
}
