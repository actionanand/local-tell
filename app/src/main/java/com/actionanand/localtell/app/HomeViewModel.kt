package com.actionanand.localtell.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.data.LocalityCache
import com.actionanand.localtell.app.data.LocalityCachePolicy
import com.actionanand.localtell.app.data.OfflineAreaResolver
import com.actionanand.localtell.app.data.OfflineLocalityResolver
import com.actionanand.localtell.app.data.PackStore
import com.actionanand.localtell.app.location.GnssFixResult
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
    ) : HomeStatus {
        val cells: List<RadioCell> get() = subscriptions.flatMap(SubscriptionCells::cells)
    }
    data class Error(val message: String) : HomeStatus
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val reader = CellReader(app)
    private val packs = PackStore(app)
    private val legacyResolver = OfflineAreaResolver(packs)
    private val localityResolver = OfflineLocalityResolver(packs)
    private val localityCache = LocalityCache(app)
    private val gnssLocator = OneShotGnssLocator(app)
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
                if (LocalityCachePolicy.canReuse(cached, fingerprint, cachedPackInstalled)) {
                    return@runCatching HomeStatus.Ready(subscriptions, cached!!.match, LocalityState.USING_RECENT_OFFLINE_LOCALITY)
                }

                if (!withContext(Dispatchers.IO) { localityResolver.hasGeographicPack() }) {
                    val legacy = withContext(Dispatchers.IO) { legacyResolver.resolveFirst(cells) }
                    return@runCatching HomeStatus.Ready(subscriptions, null, LocalityState.NO_GEOGRAPHIC_PACK, legacy)
                }

                _status.value = HomeStatus.Loading(LocalityState.ACQUIRING_GNSS, subscriptions)
                when (val fix = gnssLocator.getLocation()) {
                    is GnssFixResult.Success -> {
                        _status.value = HomeStatus.Loading(LocalityState.RESOLVING_OFFLINE_LOCALITY, subscriptions)
                        val locality = withContext(Dispatchers.IO) { localityResolver.resolve(fix.location.latitude, fix.location.longitude) }
                        if (locality != null && fingerprint != null) localityCache.save(locality, fix.location.accuracy, fingerprint)
                        HomeStatus.Ready(subscriptions, locality, if (locality != null) LocalityState.LOCALITY_FOUND else LocalityState.NO_LOCALITY_MATCH)
                    }
                    GnssFixResult.Timeout -> HomeStatus.Ready(subscriptions, null, LocalityState.GNSS_TIMEOUT)
                    GnssFixResult.ProviderDisabled -> HomeStatus.Ready(subscriptions, null, LocalityState.GPS_DISABLED)
                    GnssFixResult.PermissionMissing -> HomeStatus.Ready(subscriptions, null, LocalityState.PERMISSION_MISSING)
                    is GnssFixResult.Error -> HomeStatus.Error(fix.cause?.message ?: "Unable to acquire an on-device GNSS fix")
                }
            }.onSuccess { _status.value = it }
                .onFailure { _status.value = HomeStatus.Error(it.message ?: "Unable to read cellular diagnostics") }
        }
    }
}
