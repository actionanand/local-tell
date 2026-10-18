package com.actionanand.localtell.app.data

import com.actionanand.localtell.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class ManifestRepository(private val cache: ManifestCatalogCache? = null) {
    suspend fun fetch(): ManifestFetchResult = withContext(Dispatchers.IO) {
        fetchSources(configuredSources(), ::fetch)
    }

    internal fun fetchSources(
        sources: List<CountryManifestSource>,
        load: (CountryManifestSource) -> RemoteManifest,
    ): ManifestFetchResult {
        val successes = mutableListOf<RemoteManifest>()
        val failures = mutableListOf<CountryManifestSource>()
        sources.sortedBy { it.sourceOrder }.forEach { source ->
            try {
                successes += load(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures += source
            }
        }
        return ManifestFetchResult(successes, failures)
    }

    internal fun configuredSources(): List<CountryManifestSource> {
        val sources = JSONArray(BuildConfig.DATA_MANIFEST_SOURCES_JSON)
        return List(sources.length()) { index ->
            val source = sources.getJSONObject(index)
            CountryManifestSource(
                source.getString("countryCode").uppercase(Locale.ROOT), source.getString("countryName").trim(),
                source.getString("packType").trim(), CountryLayout.valueOf(source.getString("layout").uppercase(Locale.ROOT)),
                source.getString("manifestUrl"), index,
            )
        }
    }

    private fun fetch(source: CountryManifestSource): RemoteManifest {
        val connection = (URL(source.manifestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LocalTell/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(connection.responseCode in 200..299) { "Manifest request failed: HTTP ${connection.responseCode}" }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val manifest = parse(text, source)
            cache?.save(source, text)
            return manifest
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            cache?.failed(source)
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(text: String, source: CountryManifestSource = indiaSource()): RemoteManifest {
        val root = JSONObject(text)
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion in 1..3) { "Unsupported manifest schema: $schemaVersion" }
        val countryCode = root.optString("countryCode").takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: source.countryCode
        val countryName = root.optString("countryName").takeIf { it.isNotBlank() } ?: source.countryName
        val packType = root.optString("packType").takeIf { it.isNotBlank() } ?: source.packType
        require(countryCode == source.countryCode) { "Manifest country code does not match ${source.countryCode}" }
        require(packType == source.packType) { "Manifest pack type does not match ${source.countryCode}" }
        val packsJson = root.getJSONArray("packs")
        val packs = buildList {
            for (i in 0 until packsJson.length()) {
                val p = packsJson.getJSONObject(i)
                val id = p.getString("id")
                val name = p.getString("name").trim()
                val version = p.getLong("version")
                val region = p.optString("region").trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
                val displayOrder = p.getInt("displayOrder")
                val downloadUrl = p.getString("downloadUrl")
                val sha256 = p.getString("sha256").lowercase(Locale.ROOT)
                require(id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid pack id: $id" }
                require(name.isNotBlank() && name.length <= 120) { "Invalid pack name for $id" }
                require(version >= 1) { "Invalid pack version for $id" }
                if (source.layout == CountryLayout.REGIONS) {
                    require(source.countryCode == "IN") { "No regional catalog configured for ${source.countryCode}" }
                    require(region != null && IndiaRegion.values().any { it.manifestKey == region }) { "Unknown region for $id: $region" }
                } else require(region == null) { "Flat-country pack must not define a region: $id" }
                require(displayOrder >= 0) { "Invalid display order for $id" }
                require(URL(downloadUrl).protocol.equals("https", ignoreCase = true)) { "Pack URL must use HTTPS: $id" }
                require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $id" }
                add(
                    RemotePack(
                        id = id,
                        name = name,
                        version = version,
                        region = region,
                        displayOrder = displayOrder,
                        downloadUrl = downloadUrl,
                        sha256 = sha256,
                        compressedBytes = p.optLong("compressedBytes").takeIf { it > 0 },
                        uncompressedBytes = p.optLong("uncompressedBytes").takeIf { it > 0 },
                        countryCode = source.countryCode,
                        countryName = countryName,
                        packType = packType,
                        adminCode = p.optString("adminCode").takeIf { it.isNotBlank() },
                        layout = source.layout,
                        sourceOrder = source.sourceOrder,
                    )
                )
            }
        }
        require(packs.map(RemotePack::id).distinct().size == packs.size) { "Duplicate pack ID within ${source.countryCode}" }
        return RemoteManifest(schemaVersion, root.optString("generatedAt").takeIf { it.isNotBlank() }, packs, source.countryCode, countryName, packType, source.layout, source.sourceOrder)
    }

    private fun indiaSource() = CountryManifestSource("IN", "India", "state_ut", CountryLayout.REGIONS, "https://example.invalid", 0)
}
