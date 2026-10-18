package com.actionanand.localtell.app.geography

import com.actionanand.localtell.app.data.*
import org.junit.Assert.*
import org.junit.Test

class OfflineDataGuidanceTest {
    private val india = CountryManifestSource("IN", "India", "state_ut", CountryLayout.REGIONS, "https://example.invalid/in", 0)
    private val sriLanka = CountryManifestSource("LK", "Sri Lanka", "province", CountryLayout.FLAT, "https://example.invalid/lk", 1)
    private val karnataka = DetectedRegion("IN", "India", "IN-KA", "Karnataka")
    private fun pack(id: String, source: CountryManifestSource = india) = RemotePack(id, id, 1,
        if (source == india) "south" else null, 0, "https://example.invalid/data", "a".repeat(64), null, null,
        source.countryCode, source.countryName, source.packType, if (source == sriLanka) id else null, source.layout, source.sourceOrder)
    private fun catalog(vararg packs: RemotePack) = RemoteManifest(3, null, packs.toList(), packs.firstOrNull()?.countryCode ?: "IN")
    private fun plan(region: DetectedRegion? = karnataka, manifest: RemoteManifest? = catalog(pack("KA"), pack("TN")),
        confirmed: Boolean = true, installed: Set<String> = emptySet(), covering: Set<String> = emptySet(), unreadable: Set<String> = emptySet()) =
        OfflineDataGuidancePolicy.plan(region, listOf(india, sriLanka), manifest, confirmed, installed, covering, unreadable)

    @Test fun `no installed packs or only TN installed still targets missing Karnataka by ID`() {
        for (ids in listOf(emptySet(), setOf("TN"))) {
            val guidance = plan(installed = ids)
            assertEquals(DataAvailability.DOWNLOADABLE, guidance.availability)
            assertEquals("KA", guidance.target?.packId)
            assertEquals("IN-KA", guidance.target?.adminCode)
        }
    }

    @Test fun `installed unreadable and installed unmatched data never looks like a missing pack`() {
        assertEquals(DataAvailability.INSTALLED_NO_MATCH, plan(installed = setOf("KA")).availability)
        assertEquals(DataAvailability.INSTALLED_NO_MATCH, plan(covering = setOf("custom-pack")).availability)
        assertEquals(DataAvailability.INSTALLED_ERROR, plan(installed = setOf("KA"), unreadable = setOf("KA")).availability)
    }

    @Test fun `configured country without a manifest is unknown and not unsupported`() {
        assertEquals(DataAvailability.CATALOG_UNKNOWN, plan(manifest = null).availability)
        assertEquals(DataAvailability.CATALOG_UNKNOWN, plan(confirmed = false).availability)
        assertEquals(DataAvailability.CATALOG_UNKNOWN, plan(manifest = catalog(pack("TN")), confirmed = false).availability)
        assertEquals(DataAvailability.UNPUBLISHED, plan(manifest = catalog(pack("TN")), confirmed = true).availability)
    }

    @Test fun `Sri Lanka province architecture handles missing partial and installed coverage`() {
        val region = DetectedRegion("LK", "Sri Lanka")
        val manifest = catalog(pack("LK-1", sriLanka), pack("LK-2", sriLanka))
        assertEquals(DataAvailability.DOWNLOADABLE, plan(region, manifest).availability)
        val partial = plan(region, manifest, installed = setOf("LK-1"))
        assertEquals(DataAvailability.DOWNLOADABLE, partial.availability)
        assertTrue(partial.partialCountryData)
        assertEquals("LK", partial.target?.countryCode)
        assertNull(partial.target?.packId)
        assertEquals(DataAvailability.INSTALLED_NO_MATCH, plan(region, manifest, covering = setOf("LK-2")).availability)
        assertEquals(DataAvailability.INSTALLED_NO_MATCH, plan(region, manifest, installed = setOf("LK-1", "LK-2")).availability)
    }

    @Test fun `unsupported country has no misleading download target and future configuration works generically`() {
        assertEquals(DataAvailability.UNSUPPORTED, plan(DetectedRegion("NP", "Nepal"), null).availability)
        assertNull(plan(DetectedRegion("NP", "Nepal"), null).target)
        val source = CountryManifestSource("DE", "Germany", "province", CountryLayout.FLAT, "https://example.invalid/de", 2)
        val manifest = catalog(pack("DE-BY", source))
        val future = OfflineDataGuidancePolicy.plan(DetectedRegion("DE", "Germany"), listOf(india, sriLanka, source), manifest,
            true, emptySet(), emptySet(), emptySet())
        assertEquals(DataAvailability.DOWNLOADABLE, future.availability)
        assertEquals("DE", future.target?.countryCode)
        assertEquals(DataAvailability.UNKNOWN_REGION, plan(null).availability)
    }

    @Test fun `selection uses codes not translated names`() {
        assertTrue(OfflineDataGuidancePolicy.matches(pack("KA").copy(name = "கர்நாடகா"), OfflineDataTarget("IN", adminCode = "IN-KA")))
        assertFalse(OfflineDataGuidancePolicy.matches(pack("TN"), OfflineDataTarget("IN", adminCode = "IN-KA")))
        assertFalse(OfflineDataGuidancePolicy.matches(pack("KA"), OfflineDataTarget("LK")))
    }
}
