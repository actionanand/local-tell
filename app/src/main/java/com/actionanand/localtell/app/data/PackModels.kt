package com.actionanand.localtell.app.data

data class RemotePack(
    val id: String,
    val name: String,
    val version: Long,
    val region: String?,
    val displayOrder: Int,
    val downloadUrl: String,
    val sha256: String,
    val compressedBytes: Long?,
    val uncompressedBytes: Long?,
    val countryCode: String = "IN",
    val countryName: String = "India",
    val packType: String = "state_ut",
    val adminCode: String? = null,
    val layout: CountryLayout = CountryLayout.REGIONS,
    val sourceOrder: Int = 0,
)

data class RemoteManifest(
    val schemaVersion: Int,
    val generatedAt: String?,
    val packs: List<RemotePack>,
    val countryCode: String = "IN",
    val countryName: String = "India",
    val packType: String = "state_ut",
    val layout: CountryLayout = CountryLayout.REGIONS,
    val sourceOrder: Int = 0,
)

data class ManifestFetchResult(
    val manifests: List<RemoteManifest>,
    val failedSources: List<CountryManifestSource>,
) {
    val packs: List<RemotePack> get() = manifests.flatMap(RemoteManifest::packs)

    init {
        require(manifests.isNotEmpty()) { "All country manifest requests failed" }
        require(packs.map(RemotePack::id).distinct().size == packs.size) { "Duplicate pack ID across country manifests" }
    }

    /** Only successful sources contribute new counts; failed sources retain their last known count. */
    fun updateCounts(installed: Map<String, InstalledPack>, previous: Map<String, Int>): Map<String, Int> =
        failedSources.associate { it.countryCode to (previous[it.countryCode] ?: 0) } +
            manifests.associate { manifest ->
                manifest.countryCode to manifest.packs.count { pack ->
                    installed[pack.id]?.let { it.version < pack.version } == true
                }
            }
}

enum class CountryLayout { REGIONS, FLAT }

data class CountryManifestSource(
    val countryCode: String,
    val countryName: String,
    val packType: String,
    val layout: CountryLayout,
    val manifestUrl: String,
    val sourceOrder: Int,
)

data class InstalledPack(
    val id: String,
    val name: String,
    val version: Long,
    val filePath: String,
    val installedAt: Long,
)
