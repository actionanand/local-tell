package com.actionanand.localtell.app.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManifestRepositoryTest {
    private val repository = ManifestRepository()
    private val india = CountryManifestSource("IN", "India", "state_ut", CountryLayout.REGIONS, "https://example.com/in.json", 0)
    private val sriLanka = CountryManifestSource("LK", "Sri Lanka", "province", CountryLayout.FLAT, "https://example.com/lk.json", 1)

    private fun manifest(source: CountryManifestSource, change: (JSONObject) -> Unit = {}): String {
        val regional = source.layout == CountryLayout.REGIONS
        val pack = JSONObject()
            .put("id", if (regional) "TN" else "LK-1")
            .put("name", if (regional) "Tamil Nadu" else "Western Province")
            .put("version", 3)
            .put("displayOrder", 7)
            .put("downloadUrl", "https://example.com/pack.db.gz")
            .put("sha256", "a".repeat(64))
        if (regional) pack.put("region", "south") else pack.put("adminCode", "LK-1")
        val root = JSONObject().put("schemaVersion", 1).put("packs", org.json.JSONArray().put(pack))
        if (!regional) root.put("countryCode", "LK").put("countryName", "Sri Lanka").put("packType", "province")
        change(root)
        return root.toString()
    }

    private fun parse(source: CountryManifestSource) = repository.parse(manifest(source), source)

    @Test fun `legacy India gets country and presentation metadata from configuration`() {
        val result = parse(india.copy(sourceOrder = 4))
        assertEquals("IN", result.countryCode)
        assertEquals("India", result.countryName)
        assertEquals("state_ut", result.packType)
        assertEquals(CountryLayout.REGIONS, result.layout)
        assertEquals(4, result.sourceOrder)
        val pack = result.packs.single()
        assertEquals("south", pack.region)
        assertEquals("India", pack.countryName)
        assertEquals(CountryLayout.REGIONS, pack.layout)
        assertEquals(4, pack.sourceOrder)
    }

    @Test fun `Sri Lanka accepts province metadata without a region`() {
        val result = parse(sriLanka)
        assertEquals("LK", result.countryCode)
        assertEquals("Sri Lanka", result.countryName)
        assertEquals("province", result.packType)
        assertEquals(CountryLayout.FLAT, result.layout)
        val pack = result.packs.single()
        assertNull(pack.region)
        assertEquals("LK-1", pack.adminCode)
        assertEquals(7, pack.displayOrder)
        assertEquals(CountryLayout.FLAT, pack.layout)
        assertEquals(1, pack.sourceOrder)
    }

    @Test fun `country code mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.parse(manifest(sriLanka) { it.put("countryCode", "IN") }, sriLanka)
        }
    }

    @Test fun `pack type mismatch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.parse(manifest(sriLanka) { it.put("packType", "state_ut") }, sriLanka)
        }
    }

    @Test fun `invalid checksum is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.parse(manifest(india) { it.getJSONArray("packs").getJSONObject(0).put("sha256", "invalid") }, india)
        }
    }

    @Test fun `HTTP download URL is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.parse(manifest(india) { it.getJSONArray("packs").getJSONObject(0).put("downloadUrl", "http://example.com/pack") }, india)
        }
    }

    @Test fun `flat country with a region is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.parse(manifest(sriLanka) { it.getJSONArray("packs").getJSONObject(0).put("region", "south") }, sriLanka)
        }
    }

    @Test fun `regional country must have a valid India region`() {
        for (region in listOf(null, "unknown")) {
            assertThrows(IllegalArgumentException::class.java) {
                repository.parse(manifest(india) {
                    val pack = it.getJSONArray("packs").getJSONObject(0)
                    if (region == null) pack.remove("region") else pack.put("region", region)
                }, india)
            }
        }
    }

    @Test fun `duplicate IDs across successful sources fail aggregation`() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.fetchSources(listOf(india, sriLanka)) { source ->
                repository.parse(manifest(source) { it.getJSONArray("packs").getJSONObject(0).put("id", "shared") }, source)
            }
        }
    }

    @Test fun `partial failure preserves successes in either direction and records failed source`() {
        for (failed in listOf(india, sriLanka)) {
            val result = repository.fetchSources(listOf(sriLanka, india)) { source ->
                if (source == failed) throw IOException("unavailable") else parse(source)
            }
            assertEquals(listOf(failed), result.failedSources)
            assertEquals(listOf(if (failed == india) "LK-1" else "TN"), result.packs.map(RemotePack::id))
        }
    }

    @Test fun `all failures fail without yielding an empty successful catalog`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            repository.fetchSources(listOf(india, sriLanka)) { throw IOException("unavailable") }
        }
        assertEquals("All country manifest requests failed", failure.message)
    }

    @Test fun `cancellation is not reported as a failed country`() {
        assertThrows(CancellationException::class.java) {
            repository.fetchSources(listOf(india, sriLanka)) { throw CancellationException() }
        }
    }

    @Test fun `update checks count installed India and Sri Lanka packs and never downgrade`() {
        val installed = mapOf(
            "TN" to InstalledPack("TN", "Tamil Nadu", 1, "/packs/TN.db", 0),
            "LK-1" to InstalledPack("LK-1", "Western Province", 2, "/packs/LK-1.db", 0),
        )
        val result = repository.fetchSources(listOf(india, sriLanka), ::parse)
        assertEquals(mapOf("IN" to 1, "LK" to 1), result.updateCounts(installed, emptyMap()))
        assertEquals(mapOf("IN" to 0, "LK" to 1), result.updateCounts(installed + ("TN" to installed.getValue("TN").copy(version = 4)), emptyMap()))
    }

    @Test fun `partial update checks retain failed source count and installed records`() {
        val installed = mapOf(
            "TN" to InstalledPack("TN", "Tamil Nadu", 1, "/packs/TN.db", 0),
            "LK-1" to InstalledPack("LK-1", "Western Province", 1, "/packs/LK-1.db", 0),
        )
        val before = installed.toMap()
        for (failed in listOf(india, sriLanka)) {
            val result = repository.fetchSources(listOf(india, sriLanka)) { source ->
                if (source == failed) throw IOException("unavailable") else parse(source)
            }
            val counts = result.updateCounts(installed, mapOf(failed.countryCode to 2))
            assertEquals(2, counts[failed.countryCode])
            assertEquals(3, counts.values.sum())
            assertEquals(before, installed)
        }
    }
}
