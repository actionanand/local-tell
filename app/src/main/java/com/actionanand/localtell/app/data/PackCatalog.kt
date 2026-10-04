package com.actionanand.localtell.app.data

import java.util.Locale

enum class IndiaRegion(val manifestKey: String, val displayName: String) {
    SOUTH("south", "South India"),
    WEST("west", "West India"),
    CENTRAL("central", "Central India"),
    NORTH("north", "North India"),
    EAST("east", "East India"),
    NORTHEAST("northeast", "North-East India"),
}

data class PackTotals(val compressedBytes: Long, val uncompressedBytes: Long)

data class RegionPacks(val region: IndiaRegion, val packs: List<RemotePack>) {
    val totals: PackTotals get() = PackCatalog.totals(packs)
}

/** Presentation-only catalog rules; membership and per-region ordering come from the manifest. */
object PackCatalog {
    fun regions(packs: List<RemotePack>): List<RegionPacks> = IndiaRegion.values().mapNotNull { region ->
        packs.filter { it.region == region.manifestKey }
            .sortedWith(compareBy<RemotePack> { it.displayOrder }.thenBy { it.name })
            .takeIf { it.isNotEmpty() }
            ?.let { RegionPacks(region, it) }
    }

    fun totals(packs: Collection<RemotePack>) = PackTotals(
        compressedBytes = packs.sumOf { it.compressedBytes ?: 0L },
        uncompressedBytes = packs.sumOf { it.uncompressedBytes ?: 0L },
    )

    fun needsDownload(pack: RemotePack, installed: Map<String, InstalledPack>): Boolean =
        installed[pack.id]?.version?.let { it < pack.version } ?: true

    fun requiredPacks(packs: Collection<RemotePack>, installed: Map<String, InstalledPack>): List<RemotePack> =
        packs.filter { needsDownload(it, installed) }

    fun installedPacks(packs: Collection<RemotePack>, installed: Map<String, InstalledPack>): List<RemotePack> =
        packs.filter { installed.containsKey(it.id) }

    /** Filters a display projection only; the authoritative region lists remain unchanged. */
    fun filterRegions(regions: List<RegionPacks>, query: String): List<RegionPacks> {
        val normalizedQuery = normalizeSearch(query)
        if (normalizedQuery.isEmpty()) return regions
        return regions.mapNotNull { regionPacks ->
            val regionMatches = matches(regionPacks.region.displayName, normalizedQuery) ||
                matches(regionPacks.region.manifestKey, normalizedQuery)
            val matchingPacks = if (regionMatches) {
                regionPacks.packs
            } else {
                regionPacks.packs.filter { pack ->
                    matches(pack.name, normalizedQuery) || matches(pack.id, normalizedQuery)
                }
            }
            matchingPacks.takeIf { it.isNotEmpty() }?.let { RegionPacks(regionPacks.region, it) }
        }
    }

    fun batchFailureMessage(failures: List<String>): String? = failures.takeIf { it.isNotEmpty() }?.let {
        "Unable to download ${it.joinToString()}. Successfully downloaded packs remain available offline."
    }

    private fun matches(value: String, normalizedQuery: String): Boolean =
        normalizeSearch(value).contains(normalizedQuery)

    private fun normalizeSearch(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("[\\s-]+"), "")
}

fun formatPackBytes(bytes: Long?): String? = bytes?.let {
    when {
        it >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", it / (1024.0 * 1024 * 1024))
        it >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", it / (1024.0 * 1024))
        it >= 1024L -> String.format(Locale.US, "%.1f KB", it / 1024.0)
        else -> "$it B"
    }
}
