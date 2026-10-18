package com.actionanand.localtell.app.geography

import com.actionanand.localtell.app.data.CountryManifestSource
import com.actionanand.localtell.app.data.RemoteManifest
import com.actionanand.localtell.app.data.RemotePack

data class OfflineDataTarget(val countryCode: String, val packId: String? = null,
    val adminCode: String? = null, val label: String = countryCode)

enum class DataAvailability { UNKNOWN_REGION, UNSUPPORTED, CATALOG_UNKNOWN, UNPUBLISHED, DOWNLOADABLE, INSTALLED_NO_MATCH, INSTALLED_ERROR }
data class OfflineDataGuidance(val region: DetectedRegion?, val availability: DataAvailability,
    val target: OfflineDataTarget? = null, val partialCountryData: Boolean = false)

/** Pure policy. A configured source with no validated catalog is unknown, never unsupported. */
object OfflineDataGuidancePolicy {
    fun matches(pack: RemotePack, target: OfflineDataTarget): Boolean = pack.countryCode == target.countryCode &&
        when {
            target.packId != null -> pack.id == target.packId
            target.adminCode != null -> pack.adminCode == target.adminCode ||
                (target.countryCode == "IN" && pack.id == target.adminCode.removePrefix("IN-"))
            else -> true
        }

    fun plan(region: DetectedRegion?, sources: List<CountryManifestSource>, catalog: RemoteManifest?,
        catalogConfirmed: Boolean, installedIds: Set<String>, coveringIds: Set<String>, unreadableIds: Set<String>): OfflineDataGuidance {
        if (region == null) return OfflineDataGuidance(null, DataAvailability.UNKNOWN_REGION)
        val target = OfflineDataTarget(region.countryCode, adminCode = region.adminCode,
            label = region.adminName ?: region.countryName)
        if (coveringIds.isNotEmpty()) return OfflineDataGuidance(region, DataAvailability.INSTALLED_NO_MATCH, target)
        val candidates = catalog?.packs.orEmpty().filter { matches(it, target) }
        val expected = candidates.map { it.id }.toSet() +
            listOfNotNull(region.adminCode?.takeIf { region.countryCode == "IN" }?.removePrefix("IN-"))
        if (expected.any { it in unreadableIds }) return OfflineDataGuidance(region, DataAvailability.INSTALLED_ERROR, target)
        if (expected.any { it in installedIds } && (region.adminCode != null || candidates.all { it.id in installedIds })) {
            return OfflineDataGuidance(region, DataAvailability.INSTALLED_NO_MATCH, target)
        }
        if (sources.none { it.countryCode == region.countryCode }) return OfflineDataGuidance(region, DataAvailability.UNSUPPORTED)
        if (candidates.isNotEmpty()) {
            val selected = if (region.adminCode != null && candidates.size == 1) target.copy(packId = candidates.single().id) else target
            return OfflineDataGuidance(region, if (catalogConfirmed) DataAvailability.DOWNLOADABLE else DataAvailability.CATALOG_UNKNOWN, selected,
                candidates.any { it.id in installedIds })
        }
        return OfflineDataGuidance(region, if (catalog != null && catalogConfirmed) DataAvailability.UNPUBLISHED else DataAvailability.CATALOG_UNKNOWN, target)
    }
}
