package com.actionanand.localtell.app.geography

import android.content.Context
import com.actionanand.localtell.app.data.ManifestCatalogCache
import com.actionanand.localtell.app.data.ManifestRepository
import com.actionanand.localtell.app.data.PackCoverage
import com.actionanand.localtell.app.data.PackStore

/** Only installed metadata + validated cached catalogs. Does not fetch a manifest. Call on IO. */
class OfflineGuidanceResolver(context: Context, private val packs: PackStore) {
    private val cache = ManifestCatalogCache(context)
    private val manifests = ManifestRepository()
    fun resolve(region: DetectedRegion?, coverage: PackCoverage): OfflineDataGuidance {
        val sources = manifests.configuredSources()
        val source = sources.firstOrNull { it.countryCode == region?.countryCode }
        val catalog = source?.let { s -> runCatching { cache.read(s)?.let { manifests.parse(it, s) } }.getOrNull() }
        val confirmed = source?.let { runCatching { cache.confirmed(it) }.getOrDefault(false) } == true
        return OfflineDataGuidancePolicy.plan(region, sources, catalog, confirmed,
            packs.all().map { it.id }.toSet(), coverage.coveringIds, coverage.unreadableIds)
    }
}
