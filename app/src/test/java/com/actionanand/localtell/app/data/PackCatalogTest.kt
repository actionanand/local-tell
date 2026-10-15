package com.actionanand.localtell.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackCatalogTest {
    @Test
    fun `regions use the fixed India order and manifest display order`() {
        val packs = listOf(
            remote("goa", "Goa", "west", 2),
            remote("tn", "Tamil Nadu", "south", 4),
            remote("kl", "Kerala", "south", 1),
            remote("ar", "Arunachal Pradesh", "northeast", 1),
        )

        val regions = PackCatalog.regions(packs)

        assertEquals(listOf("South India", "West India", "North-East India"), regions.map { it.region.displayName })
        assertEquals(listOf("Kerala", "Tamil Nadu"), regions.first().packs.map(RemotePack::name))
    }

    @Test
    fun `totals include download and device sizes from each state pack`() {
        val totals = PackCatalog.totals(listOf(
            remote("tn", "Tamil Nadu", "south", 1, compressed = 1024L, uncompressed = 4096L),
            remote("kl", "Kerala", "south", 2, compressed = 2048L, uncompressed = 8192L),
        ))

        assertEquals(3072L, totals.compressedBytes)
        assertEquals(12288L, totals.uncompressedBytes)
    }

    @Test
    fun `only missing or stale packs are required for a batch`() {
        val current = remote("tn", "Tamil Nadu", "south", 1, version = 3)
        val stale = remote("kl", "Kerala", "south", 2, version = 3)
        val missing = remote("ka", "Karnataka", "south", 3, version = 3)
        val installed = mapOf(
            "tn" to installed("tn", "Tamil Nadu", 3),
            "kl" to installed("kl", "Kerala", 2),
        )

        assertEquals(listOf("kl", "ka"), PackCatalog.requiredPacks(listOf(current, stale, missing), installed).map(RemotePack::id))
        assertFalse(PackCatalog.needsDownload(current, installed))
        assertTrue(PackCatalog.needsDownload(stale, installed))
    }

    @Test
    fun `a partially installed region queues only its missing state packs`() {
        val tamilNadu = remote("tn", "Tamil Nadu", "south", 1, version = 3)
        val kerala = remote("kl", "Kerala", "south", 2, version = 3)
        val installed = mapOf("tn" to installed("tn", "Tamil Nadu", 3))

        assertEquals(listOf("kl"), PackCatalog.requiredPacks(listOf(tamilNadu, kerala), installed).map(RemotePack::id))
    }

    @Test
    fun `a fully current region has no packs left to download`() {
        val tamilNadu = remote("tn", "Tamil Nadu", "south", 1, version = 3)
        val kerala = remote("kl", "Kerala", "south", 2, version = 3)
        val installed = mapOf(
            "tn" to installed("tn", "Tamil Nadu", 3),
            "kl" to installed("kl", "Kerala", 3),
        )

        assertTrue(PackCatalog.requiredPacks(listOf(tamilNadu, kerala), installed).isEmpty())
    }

    @Test
    fun `region removal selects only currently installed packs`() {
        val tamilNadu = remote("tn", "Tamil Nadu", "south", 1)
        val kerala = remote("kl", "Kerala", "south", 2)
        val installed = mapOf("tn" to installed("tn", "Tamil Nadu", 1))

        assertEquals(listOf("tn"), PackCatalog.installedPacks(listOf(tamilNadu, kerala), installed).map(RemotePack::id))
        assertTrue(PackCatalog.installedPacks(listOf(tamilNadu, kerala), emptyMap()).isEmpty())
    }

    @Test
    fun `fully installed region removal selects every region pack`() {
        val tamilNadu = remote("tn", "Tamil Nadu", "south", 1)
        val kerala = remote("kl", "Kerala", "south", 2)
        val installed = mapOf(
            "tn" to installed("tn", "Tamil Nadu", 1),
            "kl" to installed("kl", "Kerala", 1),
        )

        assertEquals(listOf("tn", "kl"), PackCatalog.installedPacks(listOf(tamilNadu, kerala), installed).map(RemotePack::id))
    }

    @Test
    fun `a newer installed pack is not downgraded by a batch`() {
        val remote = remote("tn", "Tamil Nadu", "south", 1, version = 3)
        val installed = mapOf("tn" to installed("tn", "Tamil Nadu", 4))

        assertFalse(PackCatalog.needsDownload(remote, installed))
    }

    @Test
    fun `byte formatting is compact and human readable`() {
        assertEquals("512 B", formatPackBytes(512))
        assertEquals("1.5 KB", formatPackBytes(1536))
        assertEquals("2.0 MB", formatPackBytes(2L * 1024 * 1024))
    }

    @Test
    fun `batch failure feedback names only packs that failed`() {
        assertEquals(
            "Unable to download Kerala, Goa. Successfully downloaded packs remain available offline.",
            PackCatalog.batchFailureMessage(listOf("Kerala", "Goa")),
        )
        assertNull(PackCatalog.batchFailureMessage(emptyList()))
    }

    @Test
    fun `blank search returns the original complete regional grouping`() {
        val regions = catalogRegions()

        assertEquals(regions, PackCatalog.filterRegions(regions, "  "))
    }

    @Test
    fun `state searches are partial and case insensitive`() {
        val regions = catalogRegions()

        assertEquals(listOf("Tamil Nadu"), PackCatalog.filterRegions(regions, "TAMIL NADU").single().packs.map(RemotePack::name))
        assertEquals(listOf("Karnataka"), PackCatalog.filterRegions(regions, "kar").single().packs.map(RemotePack::name))
        assertEquals(listOf("Tamil Nadu"), PackCatalog.filterRegions(regions, "tn").single().packs.map(RemotePack::name))
    }

    @Test
    fun `region display name and key return every pack in that region`() {
        val regions = catalogRegions()

        assertEquals(listOf("Kerala", "Tamil Nadu", "Karnataka"), PackCatalog.filterRegions(regions, "South").single().packs.map(RemotePack::name))
        assertEquals(listOf("Kerala", "Tamil Nadu", "Karnataka"), PackCatalog.filterRegions(regions, "south").single().packs.map(RemotePack::name))
    }

    @Test
    fun `north east spelling variants match the North-East region`() {
        val regions = catalogRegions()

        listOf("North East", "north-east", "northeast").forEach { query ->
            assertEquals("North-East India", PackCatalog.filterRegions(regions, query).single().region.displayName)
        }
    }

    @Test
    fun `search can return matching packs in fixed region order without mutating the source`() {
        val regions = catalogRegions()
        val sourceNames = regions.map { it.packs.map(RemotePack::name) }

        val results = PackCatalog.filterRegions(regions, "pradesh")

        assertEquals(listOf("Central India", "North India", "North-East India"), results.map { it.region.displayName })
        assertEquals(listOf("Madhya Pradesh", "Uttar Pradesh"), results[0].packs.map(RemotePack::name))
        assertEquals(sourceNames, regions.map { it.packs.map(RemotePack::name) })
        assertTrue(PackCatalog.filterRegions(regions, "no such place").isEmpty())
    }

    @Test
    fun `countries follow source order and retain configured layout`() {
        val india = remote("tn", "Tamil Nadu", "south", 1)
        val sriLanka = RemotePack("LK-1", "Western Province", 1, null, 1, "https://example.com/LK-1", "b".repeat(64), 100, 200,
            countryCode = "LK", countryName = "Sri Lanka", packType = "province", adminCode = "LK-1",
            layout = CountryLayout.FLAT, sourceOrder = 1)

        val countries = PackCatalog.countries(listOf(sriLanka, india))

        assertEquals(listOf("India", "Sri Lanka"), countries.map(CountryPacks::countryName))
        assertEquals(listOf(CountryLayout.REGIONS, CountryLayout.FLAT), countries.map(CountryPacks::layout))
        assertEquals(listOf(0, 1), countries.map(CountryPacks::sourceOrder))
        assertEquals(listOf("LK", "IN"), PackCatalog.countries(listOf(india.copy(sourceOrder = 3), sriLanka.copy(sourceOrder = 0))).map(CountryPacks::countryCode))
        assertEquals(listOf("LK-1"), PackCatalog.filterPacks(countries[1].packs, "lk-1").map(RemotePack::id))
        assertEquals(listOf("LK-1"), PackCatalog.filterPacks(countries[1].packs, "western").map(RemotePack::id))
    }

    @Test
    fun `country search handles flat names IDs and configured regional presentation without changing sources`() {
        val west = RemotePack("LK-1", "Western Province", 1, null, 1, "https://example.com/LK-1", "b".repeat(64), 100, 200,
            countryCode = "LK", countryName = "Sri Lanka", packType = "province", layout = CountryLayout.FLAT, sourceOrder = 1)
        val central = west.copy(id = "LK-2", name = "Central Province", displayOrder = 2)
        val source = PackCatalog.countries(listOf(central, remote("TN", "Tamil Nadu", "south", 1), west))
        assertEquals(listOf("LK-1", "LK-2"), source[1].packs.map(RemotePack::id))
        assertEquals(listOf("LK-1", "LK-2"), PackCatalog.filterCountries(source, "Sri Lanka").single().packs.map(RemotePack::id))
        assertEquals(listOf("LK-1"), PackCatalog.filterCountries(source, "Western").single().packs.map(RemotePack::id))
        assertEquals(listOf("LK-2"), PackCatalog.filterCountries(source, "lk-2").single().packs.map(RemotePack::id))
        assertEquals(listOf("TN"), PackCatalog.filterCountries(source, "India").single().packs.map(RemotePack::id))
        assertEquals(listOf("TN"), PackCatalog.filterCountries(source, "South").single().packs.map(RemotePack::id))
        assertEquals(listOf("TN"), PackCatalog.filterCountries(source, "Tamil").single().packs.map(RemotePack::id))
        assertEquals(listOf("LK-1", "LK-2"), source[1].packs.map(RemotePack::id))
        assertTrue(PackCatalog.filterCountries(source, "no such pack").isEmpty())
    }

    @Test
    fun `a future flat country works from metadata without a country code branch`() {
        val pack = RemotePack("ZZ-1", "Example Division", 1, null, 1, "https://example.com/ZZ-1", "c".repeat(64), 100, 200,
            countryCode = "ZZ", countryName = "Example Country", packType = "division", layout = CountryLayout.FLAT, sourceOrder = 0)
        val countries = PackCatalog.countries(listOf(pack))
        assertEquals(CountryLayout.FLAT, countries.single().layout)
        assertEquals(listOf(pack), PackCatalog.filterCountries(countries, "Example Country").single().packs)
        assertEquals(listOf(pack), PackCatalog.filterCountries(countries, "ZZ-1").single().packs)
        assertTrue(PackCatalog.regions(listOf(pack)).isEmpty())
    }

    private fun catalogRegions() = PackCatalog.regions(
        listOf(
            remote("tn", "Tamil Nadu", "south", 2),
            remote("kl", "Kerala", "south", 1),
            remote("ka", "Karnataka", "south", 3),
            remote("mp", "Madhya Pradesh", "central", 1),
            remote("up", "Uttar Pradesh", "central", 2),
            remote("hp", "Himachal Pradesh", "north", 1),
            remote("ar", "Arunachal Pradesh", "northeast", 1),
        ),
    )

    private fun remote(
        id: String,
        name: String,
        region: String,
        displayOrder: Int,
        version: Long = 1,
        compressed: Long = 100L,
        uncompressed: Long = 200L,
    ) = RemotePack(id, name, version, region, displayOrder, "https://example.com/$id", "a".repeat(64), compressed, uncompressed)

    private fun installed(id: String, name: String, version: Long) =
        InstalledPack(id, name, version, "/tmp/$id.db", 0)
}
