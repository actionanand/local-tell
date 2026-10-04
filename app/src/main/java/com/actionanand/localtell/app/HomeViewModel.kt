package com.actionanand.localtell.app

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.data.LocalityCache
import com.actionanand.localtell.app.data.LocalityCachePolicy
import com.actionanand.localtell.app.data.OfflineAreaResolver
import com.actionanand.localtell.app.data.OfflineLocalityResolver
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.location.GnssFixResult
import com.actionanand.localtell.app.location.AssistedLocationLocator
import com.actionanand.localtell.app.location.AssistedLocationResult
import com.actionanand.localtell.app.location.LocationQuality
import com.actionanand.localtell.app.location.LocationSource
import com.actionanand.localtell.app.location.NetworkAvailability
import com.actionanand.localtell.app.location.OneShotGnssLocator
import com.actionanand.localtell.app.model.AreaMatch
import com.actionanand.localtell.app.model.CellularFingerprint
import com.actionanand.localtell.app.model.LocalityMatch
import com.actionanand.localtell.app.model.RadioCell
import com.actionanand.localtell.app.model.SubscriptionCells
import com.actionanand.localtell.app.telephony.CellReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LocalityState {
    READING_CELLULAR,
    USING_RECENT_OFFLINE_LOCALITY,
    ACQUIRING_GNSS,
    RESOLVING_OFFLINE_LOCALITY,
    LOCALITY_FOUND,
    NO_GEOGRAPHIC_PACK,
    NO_LOCALITY_MATCH,
    GNSS_TIMEOUT,
    GPS_DISABLED,
    PERMISSION_MISSING,
}

sealed interface HomeStatus {
    data object Idle : HomeStatus
    data class Loading(val state: LocalityState, val subscriptions: List<SubscriptionCells> = emptyList()) : HomeStatus
    data class Ready(
        val subscriptions: List<SubscriptionCells>,
        val locality: LocalityMatch?,
        val localityState: LocalityState,
        val legacyMatch: AreaMatch? = null,
        val accuracyMetres: Float? = null,
        val locationSource: LocationSource? = null,
        val locationQuality: LocationQuality? = null,
        val locationNotice: String? = null,
    ) : HomeStatus {
        val cells: List<RadioCell> get() = subscriptions.flatMap(SubscriptionCells::cells)
    }
    data class AwaitingLocationChoice(
        val subscriptions: List<SubscriptionCells>,
        val approximateLocation: Location?,
    ) : HomeStatus
    data class Error(val message: String) : HomeStatus
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = CellReader(app)
    private val packs = PackStore(app)
    private val legacyResolver = OfflineAreaResolver(packs)
    private val localityResolver = OfflineLocalityResolver(packs)
    private val localityCache = LocalityCache(app)
    private val gnssLocator = OneShotGnssLocator(app)
    private val assistedLocator = AssistedLocationLocator(app)
    private val assistedFallbackNotice = app.getString(R.string.location_assisted_fallback)
    private val _status = MutableStateFlow<HomeStatus>(HomeStatus.Idle)
    val status: StateFlow<HomeStatus> = _status.asStateFlow()

    fun refresh() {
        if (!reader.hasPermission()) {
            _status.value = HomeStatus.Error("Location permission is required by Android to expose cellular IDs and obtain an on-device GNSS fix when locality needs refreshing. Coordinates are processed locally and are not uploaded.")
            return
        }
        viewModelScope.launch {
            _status.value = HomeStatus.Loading(LocalityState.READING_CELLULAR)
            runCatching {
                val subscriptions = reader.requestSubscriptionCells()
                val cells = subscriptions.flatMap(SubscriptionCells::cells)
                val fingerprint = CellularFingerprint.from(cells)
                val cached = localityCache.read()
                val cachedPackInstalled = cached?.let { saved -> packs.all().any { it.id == saved.match.packId && it.version == saved.match.packVersion } } == true
                if (
                    cached?.locationQuality == LocationQuality.PRECISE &&
                    LocalityCachePolicy.canReuse(cached, fingerprint, cachedPackInstalled)
                ) {
                    val cachedLocality = cached!!
                    return@runCatching HomeStatus.Ready(
                        subscriptions = subscriptions,
                        locality = cachedLocality.match,
                        localityState = LocalityState.USING_RECENT_OFFLINE_LOCALITY,
                        accuracyMetres = cachedLocality.accuracyMetres,
                        locationSource = cachedLocality.locationSource,
                        locationQuality = cachedLocality.locationQuality,
                    )
                }

                if (!withContext(Dispatchers.IO) { localityResolver.hasGeographicPack() }) {
                    val legacy = withContext(Dispatchers.IO) { legacyResolver.resolveFirst(cells) }
                    return@runCatching HomeStatus.Ready(subscriptions, null, LocalityState.NO_GEOGRAPHIC_PACK, legacy)
                }

                _status.value = HomeStatus.Loading(LocalityState.ACQUIRING_GNSS, subscriptions)
                when (val fix = gnssLocator.getLocation()) {
                    is GnssFixResult.Precise -> resolveLocation(subscriptions, fingerprint, fix.location, LocationSource.GPS, LocationQuality.PRECISE)
                    is GnssFixResult.Approximate -> HomeStatus.AwaitingLocationChoice(subscriptions, fix.location)
                    GnssFixResult.Timeout -> HomeStatus.AwaitingLocationChoice(subscriptions, null)
                    GnssFixResult.ProviderDisabled -> HomeStatus.Ready(subscriptions, null, LocalityState.GPS_DISABLED)
                    GnssFixResult.PermissionMissing -> HomeStatus.Ready(subscriptions, null, LocalityState.PERMISSION_MISSING)
                    is GnssFixResult.Error -> HomeStatus.Error(fix.cause?.message ?: "Unable to acquire an on-device GNSS fix")
                }
            }.onSuccess { _status.value = it }
                .onFailure { _status.value = HomeStatus.Error(it.message ?: "Unable to read cellular diagnostics") }
        }
    }

    fun useApproximateLocation() {
        val pending = _status.value as? HomeStatus.AwaitingLocationChoice ?: return
        val location = pending.approximateLocation
        if (location == null) {
            _status.value = HomeStatus.Ready(pending.subscriptions, null, LocalityState.GNSS_TIMEOUT)
            return
        }
        viewModelScope.launch {
            _status.value = HomeStatus.Loading(LocalityState.RESOLVING_OFFLINE_LOCALITY, pending.subscriptions)
            _status.value = resolveLocation(pending.subscriptions, CellularFingerprint.from(pending.subscriptions.flatMap(SubscriptionCells::cells)), location, LocationSource.GPS, LocationQuality.APPROXIMATE)
        }
    }

    fun useAssistedLocation() {
        val pending = _status.value as? HomeStatus.AwaitingLocationChoice ?: return
        val approximateLocation = pending.approximateLocation
        viewModelScope.launch {
            val fingerprint = CellularFingerprint.from(pending.subscriptions.flatMap(SubscriptionCells::cells))
            if (!NetworkAvailability.hasUsableNetwork(getApplication())) {
                _status.value = if (approximateLocation != null) {
                    resolveLocation(pending.subscriptions, fingerprint, approximateLocation, LocationSource.GPS, LocationQuality.APPROXIMATE, assistedFallbackNotice)
                } else {
                    HomeStatus.Ready(
                        subscriptions = pending.subscriptions,
                        locality = null,
                        localityState = LocalityState.GNSS_TIMEOUT,
                        locationNotice = getApplication<Application>().getString(R.string.location_connect_network),
                    )
                }
                return@launch
            }
            _status.value = HomeStatus.Loading(LocalityState.ACQUIRING_GNSS, pending.subscriptions)
            when (val assisted = assistedLocator.getLocation()) {
                is AssistedLocationResult.Precise -> _status.value = resolveLocation(pending.subscriptions, fingerprint, assisted.location, LocationSource.ASSISTED, LocationQuality.PRECISE)
                is AssistedLocationResult.Approximate -> _status.value = resolveLocation(pending.subscriptions, fingerprint, assisted.location, LocationSource.ASSISTED, LocationQuality.APPROXIMATE)
                else -> _status.value = if (approximateLocation != null) {
                    resolveLocation(pending.subscriptions, fingerprint, approximateLocation, LocationSource.GPS, LocationQuality.APPROXIMATE, assistedFallbackNotice)
                } else {
                    HomeStatus.Ready(pending.subscriptions, null, LocalityState.GNSS_TIMEOUT)
                }
            }
        }
    }

    private suspend fun resolveLocation(
        subscriptions: List<SubscriptionCells>,
        fingerprint: CellularFingerprint?,
        location: Location,
        source: LocationSource,
        quality: LocationQuality,
        notice: String? = null,
    ): HomeStatus.Ready {
        _status.value = HomeStatus.Loading(LocalityState.RESOLVING_OFFLINE_LOCALITY, subscriptions)
        val locality = withContext(Dispatchers.IO) { localityResolver.resolve(location.latitude, location.longitude) }
        if (locality != null && fingerprint != null) localityCache.save(locality, location.accuracy, fingerprint, source, quality)
        return HomeStatus.Ready(
            subscriptions = subscriptions,
            locality = locality,
            localityState = if (locality != null) LocalityState.LOCALITY_FOUND else LocalityState.NO_LOCALITY_MATCH,
            accuracyMetres = location.accuracy,
            locationSource = source,
            locationQuality = quality,
            locationNotice = notice,
        )
    }
}
