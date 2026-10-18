package com.actionanand.localtell.app.geography

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.*

data class DetectedRegion(val countryCode: String, val countryName: String,
    val adminCode: String? = null, val adminName: String? = null)

/** Immutable, bundled polygons. Grid/bounds only prune; classification always uses rings. */
class BoundaryIndex private constructor(private val polygons: List<BoundaryPolygon>, private val grid: Map<Int, IntArray>) {
    private val countryNames = polygons.filter { it.kind == 0 }.associate { it.code to it.name }
    fun identify(latitude: Double, longitude: Double, accuracyMetres: Double?, checkCancelled: () -> Unit = {}): DetectedRegion? {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -85.0..85.0 || longitude !in -180.0..180.0 ||
            accuracyMetres == null || !accuracyMetres.isFinite() || accuracyMetres !in 0.0..25_000.0) return null
        val lng = if (longitude == 180.0) -180.0 else longitude
        val key = floor((latitude + 90) / 2).toInt() * 180 + floor((lng + 180) / 2).toInt()
        val candidates = grid[key]?.map { polygons[it] }.orEmpty()
        if (candidates.any { p ->
            checkCancelled()
            p.kind == 2 && (p.contains(latitude, lng, checkCancelled) || p.nearBoundary(latitude, lng, accuracyMetres + p.errorMetres, checkCancelled))
        }) return null
        val countries = candidates.filter { it.kind == 0 && it.contains(latitude, lng, checkCancelled) }
        val states = candidates.filter { it.kind == 1 && it.contains(latitude, lng, checkCancelled) }
        val state = states.singleOrNull()?.takeUnless { it.nearBoundary(latitude, lng, accuracyMetres + it.errorMetres, checkCancelled) }
        val codes = countries.map { it.code }.distinct()
        val adminCountry = state?.code?.substringBefore('-')
        if (codes.size > 1 || (adminCountry != null && codes.any { it != adminCountry })) return null
        // Detailed India polygons cover small islands/coasts omitted by the world cartography.
        if (state != null && adminCountry != null) return DetectedRegion(adminCountry, countryNames[adminCountry] ?: adminCountry, state.code, state.name)
        val country = countries.firstOrNull { !it.nearBoundary(latitude, lng, accuracyMetres + it.errorMetres, checkCancelled) } ?: return null
        return DetectedRegion(country.code, country.name)
    }

    companion object {
        fun read(input: InputStream): BoundaryIndex = DataInputStream(GZIPInputStream(input).buffered()).use { data ->
            fun count(maximum: Int): Int = data.readInt().also { require(it in 0..maximum) }
            fun text(): String = ByteArray(data.readUnsignedShort()).also(data::readFully).toString(Charsets.UTF_8)
            fun delta(): Int {
                var value = 0
                for (shift in 0..28 step 7) {
                    val byte = data.readUnsignedByte()
                    value = value or ((byte and 127) shl shift)
                    if (byte and 128 == 0) return (value ushr 1) xor -(value and 1)
                }
                error("Invalid boundary delta")
            }
            require(data.readInt() == 0x4c544731) // LTG1
            var vertices = 0
            val polygons = List(count(10_000)) {
                val kind = data.readUnsignedByte().also { require(it in 0..2) }
                val code = text(); val name = text(); val error = count(10_000)
                val bounds = IntArray(4) { data.readInt() }
                val rings = List(count(10_000)) {
                    val size = count(2_000_000)
                    vertices += size
                    require(vertices <= 2_000_000 && size >= 4)
                    var x = 0; var y = 0
                    IntArray(size * 2) { axis -> if (axis % 2 == 0) { x += delta(); x } else { y += delta(); y } }
                }
                require(rings.isNotEmpty())
                BoundaryPolygon(kind, code, name, error, bounds, rings)
            }
            val grid = buildMap {
                repeat(count(16_200)) {
                    val key = count(16_199)
                    val ids = IntArray(count(10_000)) { count(polygons.lastIndex) }
                    require(put(key, ids) == null)
                }
            }
            require(data.read() == -1)
            BoundaryIndex(polygons, grid)
        }
    }
}

internal class BoundaryPolygon(val kind: Int, val code: String, val name: String, val errorMetres: Int,
    private val bounds: IntArray, private val rings: List<IntArray>) {
    private fun longitude(lng: Double): Double {
        val centre = (bounds[2].toDouble() + bounds[3]) / (2 * SCALE)
        return lng + round((centre - lng) / 360) * 360
    }

    fun contains(lat: Double, lng: Double, cancelled: () -> Unit): Boolean {
        val x = longitude(lng) * SCALE; val y = lat * SCALE
        if (y < bounds[0] || y > bounds[1] || x < bounds[2] || x > bounds[3]) return false
        fun inside(ring: IntArray): Boolean {
            var result = false
            var previous = ring.size - 2
            for (i in ring.indices step 2) {
                if (i % 2048 == 0) cancelled()
                val x1 = ring[previous].toDouble(); val y1 = ring[previous + 1].toDouble()
                val x2 = ring[i].toDouble(); val y2 = ring[i + 1].toDouble()
                if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) result = !result
                previous = i
            }
            return result
        }
        return inside(rings.first()) && rings.drop(1).none(::inside)
    }

    fun nearBoundary(lat: Double, lng: Double, radius: Double, cancelled: () -> Unit): Boolean {
        val x = longitude(lng)
        val dy = (radius + 2) / 110_000.0
        val dx = dy / cos(Math.toRadians(lat)).coerceAtLeast(.08)
        for (ring in rings) {
            var previous = ring.size - 2
            for (i in ring.indices step 2) {
                if (i % 2048 == 0) cancelled()
                val ax = ring[previous] / SCALE; val ay = ring[previous + 1] / SCALE
                val bx = ring[i] / SCALE; val by = ring[i + 1] / SCALE
                previous = i
                // GeoJSON's artificial antimeridian cut is not a coastline/border.
                if (abs(ax - bx) < 1e-8 && abs(abs((ax + 180) % 360) - 0.0) < 1e-8) continue
                if (lat < min(ay, by) - dy || lat > max(ay, by) + dy || x < min(ax, bx) - dx || x > max(ax, bx) + dx) continue
                if (segmentDistance(lat, x, ay, ax, by, bx) <= radius + 2) return true
            }
        }
        return false
    }

    private fun segmentDistance(lat: Double, lon: Double, ay: Double, ax: Double, by: Double, bx: Double): Double {
        fun angle(a: Double, b: Double, c: Double, d: Double): Double {
            val h = sin(Math.toRadians(c - a) / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(Math.toRadians(d - b) / 2).pow(2)
            return 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
        fun bearing(a: Double, b: Double, c: Double, d: Double): Double {
            val p = Math.toRadians(a); val q = Math.toRadians(c); val x = Math.toRadians(d - b)
            return atan2(sin(x) * cos(q), cos(p) * sin(q) - sin(p) * cos(q) * cos(x))
        }
        val length = angle(ay, ax, by, bx)
        val toPoint = angle(ay, ax, lat, lon)
        if (length < 1e-12) return toPoint * EARTH
        val difference = bearing(ay, ax, lat, lon) - bearing(ay, ax, by, bx)
        val along = atan2(sin(toPoint) * cos(difference), cos(toPoint))
        return if (along < 0 || along > length) min(toPoint, angle(by, bx, lat, lon)) * EARTH
        else abs(asin((sin(toPoint) * sin(difference)).coerceIn(-1.0, 1.0))) * EARTH
    }

    companion object { private const val SCALE = 1_000_000.0; private const val EARTH = 6_371_000.0 }
}
