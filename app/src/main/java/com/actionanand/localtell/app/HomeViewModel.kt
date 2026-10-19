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
import com.actionanand.localtell.app.geography.BuiltInGeography
import com.actionanand.localtell.app.geography.GeographyAssetException
import com.actionanand.localtell.app.geography.DataAvailability
import com.actionanand.localtell.app.geography.OfflineDataGuidance
import com.actionanand.localtell.app.geography.OfflineGuidanceResolver
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val latitude: Double? = null,
        val longitude: Double? = null,
        val offlineGuidance: OfflineDataGuidance? = null,
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
    private val guidanceResolver = OfflineGuidanceResolver(app, packs)
    private val localityCache = LocalityCache(app)
    private val gnssLocator = OneShotGnssLocator(app)
    private val assistedLocator = AssistedLocationLocator(app)
    private val assistedFallbackNotice = app.getString(R.string.location_assisted_fallback)
    private val _status = MutableStateFlow<HomeStatus>(HomeStatus.Idle)
    val status: StateFlow<HomeStatus> = _status.asStateFlow()
    private val requests = HomeRequestRunner(viewModelScope, onFailure = {
        _status.value = HomeStatus.Error(AppLanguageManager.getString(getApplication(), R.string.home_error_gnss))
    })

    fun refresh() {
        requests.submit {
            if (!reader.hasPermission()) {
                _status.value = HomeStatus.Error(AppLanguageManager.getString(getApplication(), R.string.home_error_permission))
                return@submit
            }
            _status.value = HomeStatus.Loading(LocalityState.READING_CELLULAR)
            runCatching {
                val subscriptions = try { reader.requestSubscriptionCells() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { listOf(SubscriptionCells(null, emptyList())) }
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
                        latitude = cachedLocality.latitude,
                        longitude = cachedLocality.longitude,
                    )
                }

                _status.value = HomeStatus.Loading(LocalityState.ACQUIRING_GNSS, subscriptions)
                when (val fix = gnssLocator.getLocation()) {
                    is GnssFixResult.Precise -> resolveLocation(subscriptions, fingerprint, fix.location, LocationSource.GPS, LocationQuality.PRECISE)
                    is GnssFixResult.Approximate -> HomeStatus.AwaitingLocationChoice(subscriptions, fix.location)
                    GnssFixResult.Timeout -> HomeStatus.AwaitingLocationChoice(subscriptions, null)
                    GnssFixResult.ProviderDisabled -> HomeStatus.Ready(subscriptions, null, LocalityState.GPS_DISABLED)
                    GnssFixResult.PermissionMissing -> HomeStatus.Ready(subscriptions, null, LocalityState.PERMISSION_MISSING)
                    is GnssFixResult.Error -> HomeStatus.Error(AppLanguageManager.getString(getApplication(), R.string.home_error_gnss))
                }
            }.onSuccess {
                currentCoroutineContext().ensureActive()
                _status.value = it
            }
                .onFailure {
                    if (it is CancellationException) throw it
                    currentCoroutineContext().ensureActive()
                    _status.value = HomeStatus.Error(AppLanguageManager.getString(getApplication(), R.string.home_error_cellular))
                }
        }
    }

    fun useApproximateLocation() {
        val pending = _status.value as? HomeStatus.AwaitingLocationChoice ?: return
        val location = pending.approximateLocation
        requests.submit {
            if (location == null) {
                _status.value = HomeStatus.Ready(pending.subscriptions, null, LocalityState.GNSS_TIMEOUT)
                return@submit
            }
            _status.value = HomeStatus.Loading(LocalityState.RESOLVING_OFFLINE_LOCALITY, pending.subscriptions)
            _status.value = resolveLocation(pending.subscriptions, CellularFingerprint.from(pending.subscriptions.flatMap(SubscriptionCells::cells)), location, LocationSource.GPS, LocationQuality.APPROXIMATE)
        }
    }

    fun useAssistedLocation() {
        val pending = _status.value as? HomeStatus.AwaitingLocationChoice ?: return
        val approximateLocation = pending.approximateLocation
        requests.submit {
            val fingerprint = CellularFingerprint.from(pending.subscriptions.flatMap(SubscriptionCells::cells))
            if (!NetworkAvailability.hasUsableNetwork(getApplication())) {
                _status.value = if (approximateLocation != null) {
                    resolveLocation(pending.subscriptions, fingerprint, approximateLocation, LocationSource.GPS, LocationQuality.APPROXIMATE, assistedFallbackNotice)
                } else {
                    HomeStatus.Ready(
                        subscriptions = pending.subscriptions,
                        locality = null,
                        localityState = LocalityState.GNSS_TIMEOUT,
                        locationNotice = AppLanguageManager.getString(getApplication(), R.string.location_connect_network),
                    )
                }
                return@submit
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
        val locality = withContext(Dispatchers.IO) {
            try { localityResolver.resolve(location.latitude, location.longitude) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } // The coverage inspection distinguishes unreadable packs.
        }
        val guidanceAndCoverage: Pair<OfflineDataGuidance?, Boolean> = if (locality == null) withContext(Dispatchers.IO) {
            var assetUnavailable = false
            val region = try {
                BuiltInGeography.identify(getApplication(), location.latitude, location.longitude,
                    location.accuracy.takeIf { location.hasAccuracy() })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: GeographyAssetException) { assetUnavailable = true; null }
            val coroutine = currentCoroutineContext()
            val coverage = localityResolver.coverage(location.latitude, location.longitude) { coroutine.ensureActive() }
            val guidance = if (assetUnavailable) OfflineDataGuidance(null, DataAvailability.ASSET_UNAVAILABLE)
                else guidanceResolver.resolve(region, coverage)
            guidance to coverage.geographicIds.isNotEmpty()
        } else (null to true)
        val guidance = guidanceAndCoverage.first
        val hasGeographicPack = guidanceAndCoverage.second
        val legacy = if (!hasGeographicPack) withContext(Dispatchers.IO) {
            try { legacyResolver.resolveFirst(subscriptions.flatMap(SubscriptionCells::cells)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        } else null
        currentCoroutineContext().ensureActive()
        if (locality != null && fingerprint != null) localityCache.save(locality, location.accuracy, fingerprint, source, quality, location.latitude, location.longitude)
        return HomeStatus.Ready(
            subscriptions = subscriptions,
            locality = locality,
            localityState = when { locality != null -> LocalityState.LOCALITY_FOUND; !hasGeographicPack -> LocalityState.NO_GEOGRAPHIC_PACK; else -> LocalityState.NO_LOCALITY_MATCH },
            legacyMatch = legacy,
            accuracyMetres = location.accuracy,
            locationSource = source,
            locationQuality = quality,
            locationNotice = notice,
            latitude = location.latitude,
            longitude = location.longitude,
            offlineGuidance = guidance,
        )
    }
}
