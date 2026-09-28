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
