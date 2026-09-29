package com.actionanand.localtell.app.easy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.data.OfflineLocalityResolver
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.location.GnssFixResult
import com.actionanand.localtell.app.location.OneShotGnssLocator
import com.actionanand.localtell.app.locationcode.EncodedLocation
import com.actionanand.localtell.app.locationcode.LocationInputParser
import com.actionanand.localtell.app.locationcode.LocationInputResult
import com.actionanand.localtell.app.locationcode.LocalTellLocationCode
import com.actionanand.localtell.app.model.LocalityMatch
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
)

data class EasyUiState(
    val locating: Boolean = false,
    val currentLocation: EasyLocation? = null,
    val resolvedLocation: EasyLocation? = null,
    val error: EasyError? = null,
)

enum class EasyError { TIMEOUT, LOCATION_DISABLED, PERMISSION_MISSING, LOCATION_UNAVAILABLE, INVALID_INPUT }

class EasyViewModel(app: Application) : AndroidViewModel(app) {
    private val locator = OneShotGnssLocator(app)
    private val resolver = OfflineLocalityResolver(PackStore(app))
    private val _state = MutableStateFlow(EasyUiState())
    val state: StateFlow<EasyUiState> = _state.asStateFlow()

    fun getMyLocation() {
        if (_state.value.locating) return
        viewModelScope.launch {
            _state.value = _state.value.copy(locating = true, error = null)
            when (val result = locator.getLocation()) {
                is GnssFixResult.Success -> setCurrent(result.location.latitude, result.location.longitude, result.location.accuracy)
                GnssFixResult.Timeout -> fail(EasyError.TIMEOUT)
                GnssFixResult.ProviderDisabled -> fail(EasyError.LOCATION_DISABLED)
                GnssFixResult.PermissionMissing -> fail(EasyError.PERMISSION_MISSING)
                is GnssFixResult.Error -> fail(EasyError.LOCATION_UNAVAILABLE)
            }
        }
    }

    fun find(input: String) {
        if (_state.value.locating) return
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null)
            when (val parsed = LocationInputParser.parse(input)) {
                is LocationInputResult.Coordinate -> setResolved(parsed.latitude, parsed.longitude, parsed.source)
                is LocationInputResult.Error -> _state.value = _state.value.copy(error = EasyError.INVALID_INPUT)
            }
        }
    }

    private suspend fun setCurrent(latitude: Double, longitude: Double, accuracy: Float) {
        val encoded = runCatching { LocalTellLocationCode.encode(latitude, longitude) }.getOrElse { return fail(EasyError.LOCATION_UNAVAILABLE) }
        val locality = withContext(Dispatchers.IO) { runCatching { resolver.resolve(encoded.latitude, encoded.longitude) }.getOrNull() }
        _state.value = _state.value.copy(locating = false, currentLocation = EasyLocation(encoded, accuracy, locality, LocationInputResult.Source.COORDINATE))
    }

    private suspend fun setResolved(latitude: Double, longitude: Double, source: LocationInputResult.Source) {
        val encoded = runCatching { LocalTellLocationCode.encode(latitude, longitude) }.getOrElse { return fail(EasyError.INVALID_INPUT) }
        val locality = withContext(Dispatchers.IO) { runCatching { resolver.resolve(encoded.latitude, encoded.longitude) }.getOrNull() }
        _state.value = _state.value.copy(resolvedLocation = EasyLocation(encoded, null, locality, source))
    }

    private fun fail(error: EasyError) {
        _state.value = _state.value.copy(locating = false, error = error)
    }
}
