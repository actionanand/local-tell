package com.actionanand.localtell.app.geography

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BoundaryIndexTest {
    companion object {
        private val index by lazy {
            val file = File("src/main/assets/geography/boundaries.ltg.gz").takeIf { it.isFile }
                ?: File("app/src/main/assets/geography/boundaries.ltg.gz")
            BoundaryIndex.read(file.inputStream())
        }
    }
    private fun india(code: String, latitude: Double, longitude: Double) {
        val result = index.identify(latitude, longitude, 20.0)
        assertEquals("IN", result?.countryCode)
        assertEquals(code, result?.adminCode)
    }

    @Test fun `bundled India polygons identify states Delhi Puducherry and island territories without packs`() {
        india("IN-KA", 12.971598, 77.594566)
        india("IN-TN", 8.181910, 77.352330)
        india("IN-KL", 10.5276, 76.2144)
        india("IN-MH", 18.5204, 73.8567)
        india("IN-DL", 28.6139, 77.2090)
        india("IN-PY", 11.9340, 79.8120)
        india("IN-AN", 11.6234, 92.7265)
        india("IN-LD", 10.5667, 72.6417)
    }

    @Test fun `country identification covers continents independently of downloaded data`() {
        listOf(Triple("LK", 7.2906, 80.6337), Triple("NP", 27.7172, 85.3240),
            Triple("BD", 23.8103, 90.4125), Triple("DE", 52.5200, 13.4050),
            Triple("AU", -35.2809, 149.1300), Triple("JP", 35.6762, 139.6500),
            Triple("BR", -15.7939, -47.8828), Triple("ZA", -25.7479, 28.2293),
            Triple("US", 38.9072, -77.0369), Triple("LS", -29.5, 28.5)
        ).forEach { (code, lat, lng) -> assertEquals(code, index.identify(lat, lng, 20.0)?.countryCode) }
    }

    @Test fun `oceans international borders disputed land poles and unreliable fixes remain unknown`() {
        for ((lat, lng) in listOf(0.0 to 0.0, -30.0 to -30.0, 20.0 to 65.0,
            49.0 to -100.0, 34.08 to 74.79, -89.0 to 0.0, 90.0 to 0.0)) {
            assertNull("$lat,$lng", index.identify(lat, lng, 20.0))
        }
        assertNull(index.identify(52.52, 13.405, null))
        assertNull(index.identify(52.52, 13.405, 100_000.0))
        assertNull(index.identify(Double.NaN, 13.405, 20.0))
        assertNull(index.identify(12.97, 181.0, 20.0))
        assertNull(index.identify(12.97, 77.59, -1.0))
        // Country can remain confident while state is not, without guessing a neighbour.
        val uncertain = index.identify(28.6139, 77.2090, 25_000.0)
        assertEquals("IN", uncertain?.countryCode)
        assertNull(uncertain?.adminCode)
    }

    private fun polygon(outer: List<Pair<Double, Double>>, holes: List<List<Pair<Double, Double>>> = emptyList()): BoundaryPolygon {
        fun ring(points: List<Pair<Double, Double>>) = points.flatMap { listOf((it.first * 1e6).toInt(), (it.second * 1e6).toInt()) }.toIntArray()
        return BoundaryPolygon(0, "XX", "Test", 0, intArrayOf(
            (outer.minOf { it.second } * 1e6).toInt(), (outer.maxOf { it.second } * 1e6).toInt(),
            (outer.minOf { it.first } * 1e6).toInt(), (outer.maxOf { it.first } * 1e6).toInt()), listOf(ring(outer)) + holes.map(::ring))
    }

    @Test fun `ring tests respect holes enclaves and distance to their edges`() {
        val polygon = polygon(listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0, 0.0 to 0.0),
            listOf(listOf(4.0 to 4.0, 6.0 to 4.0, 6.0 to 6.0, 4.0 to 6.0, 4.0 to 4.0)))
        assertTrue(polygon.contains(3.0, 3.0) {})
        assertFalse(polygon.contains(5.0, 5.0) {})
        assertTrue(polygon.nearBoundary(3.999, 5.0, 200.0) {})
        assertFalse(polygon.nearBoundary(3.0, 3.0, 200.0) {})
    }

    @Test fun `unwrapped polygons work on both sides of longitude boundary without rectangular classification`() {
        val polygon = polygon(listOf(170.0 to 10.0, 190.0 to 10.0, 190.0 to 20.0, 170.0 to 20.0, 170.0 to 10.0))
        assertTrue(polygon.contains(15.0, 175.0) {})
        assertTrue(polygon.contains(15.0, -175.0) {})
        assertFalse(polygon.contains(15.0, 0.0) {})
    }

    @Test fun `CPU lookup propagates cancellation`() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            index.identify(12.971598, 77.594566, 20.0) { throw kotlinx.coroutines.CancellationException("cancel") }
        }
    }
}
