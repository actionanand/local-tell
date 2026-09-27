package com.actionanand.localtell.app.data

import android.database.sqlite.SQLiteDatabase
import com.actionanand.localtell.app.model.LocalityMatch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Resolves coordinates entirely from schema-v3 geographic packs.
 *
 * Contract: `place`, `place_geometry`, and `place_geometry_rtree` are required. Geometry is
 * stored as one closed ring per row using `latitude,longitude;latitude,longitude;...`.
 */
class OfflineLocalityResolver(private val store: PackStore) {
    fun hasGeographicPack(): Boolean = store.all().any { pack ->
        runCatching {
            SQLiteDatabase.openDatabase(pack.filePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                schemaVersion(db) == 3 && hasTables(db, "place", "place_geometry", "place_geometry_rtree")
            }
        }.getOrDefault(false)
    }

    fun resolve(latitude: Double, longitude: Double): LocalityMatch? {
        store.all().forEach { pack ->
            runCatching {
                SQLiteDatabase.openDatabase(pack.filePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    if (schemaVersion(db) != 3 || !hasTables(db, "place", "place_geometry", "place_geometry_rtree")) return@use null
                    resolvePolygon(db, pack, latitude, longitude) ?: resolveNearestPlace(db, pack, latitude, longitude)
                }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun resolvePolygon(db: SQLiteDatabase, pack: InstalledPack, latitude: Double, longitude: Double): LocalityMatch? =
        db.rawQuery(
            """SELECT p.name,p.place_type,p.sub_district,p.district,p.state,p.state_code,g.geometry
               FROM place_geometry_rtree r
               JOIN place_geometry g ON g.id=r.id
               JOIN place p ON p.id=g.place_id
               WHERE r.min_lat<=? AND r.max_lat>=? AND r.min_lng<=? AND r.max_lng>=?
               ORDER BY ${LocalityLookupRules.polygonPrioritySql("p.place_type")},
                   ((r.max_lat-r.min_lat)*(r.max_lng-r.min_lng)) ASC,
                   r.id ASC, p.id ASC""".trimIndent(),
            arrayOf(latitude.toString(), latitude.toString(), longitude.toString(), longitude.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (PointInPolygon.contains(latitude, longitude, cursor.getString(6))) {
                    return@use cursor.toMatch(pack, "polygon")
                }
            }
            null
        }

    private fun resolveNearestPlace(db: SQLiteDatabase, pack: InstalledPack, latitude: Double, longitude: Double): LocalityMatch? =
        db.rawQuery(
            """SELECT id,name,place_type,sub_district,district,state,state_code,latitude,longitude
               FROM place WHERE latitude IS NOT NULL AND longitude IS NOT NULL
                   AND lower(place_type) IN (${LocalityLookupRules.nearestPlaceTypes.joinToString(",") { "'$it'" }})
               ORDER BY ((latitude-?)*(latitude-?))+((longitude-?)*(longitude-?)), id LIMIT 16""".trimIndent(),
            arrayOf(latitude.toString(), latitude.toString(), longitude.toString(), longitude.toString()),
        ).use { cursor ->
            var nearest: LocalityMatch? = null
            var nearestDistance = Double.MAX_VALUE
            var nearestId = Long.MAX_VALUE
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val distance = haversineMetres(latitude, longitude, cursor.getDouble(7), cursor.getDouble(8))
                if (distance < nearestDistance || (distance == nearestDistance && id < nearestId)) {
                    nearestDistance = distance
                    nearestId = id
                    nearest = cursor.toMatch(pack, "nearest-place", 1)
                }
            }
            nearest
        }

    private fun android.database.Cursor.toMatch(pack: InstalledPack, quality: String, offset: Int = 0) = LocalityMatch(
        localityName = getString(offset), localityType = getString(offset + 1).takeIf { !it.isNullOrBlank() },
        subDistrict = getString(offset + 2).takeIf { !it.isNullOrBlank() }, district = getString(offset + 3).takeIf { !it.isNullOrBlank() },
        state = getString(offset + 4).takeIf { !it.isNullOrBlank() }, stateCode = getString(offset + 5).takeIf { !it.isNullOrBlank() },
        sourceQuality = quality, packId = pack.id, packVersion = pack.version,
    )

    private fun schemaVersion(db: SQLiteDatabase): Int? = db.rawQuery("SELECT value FROM pack_meta WHERE key='schema_version'", null).use {
        if (it.moveToFirst()) it.getString(0).toIntOrNull() else null
    }

    private fun hasTables(db: SQLiteDatabase, vararg tables: String): Boolean = tables.all { table ->
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type IN ('table','virtual table') AND name=?", arrayOf(table)).use { it.moveToFirst() }
    }

    private fun haversineMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Place-type rules shared by geographic lookup SQL and its unit tests. */
internal object LocalityLookupRules {
    val nearestPlaceTypes = listOf("neighbourhood", "suburb", "locality", "hamlet", "village", "town", "city")
    private val polygonPlaceTypes = nearestPlaceTypes + "administrative_boundary"

    fun polygonPriority(type: String?): Int = polygonPlaceTypes.indexOf(type?.lowercase()).let { index -> if (index < 0) polygonPlaceTypes.size + 1 else index + 1 }

    fun polygonPrioritySql(column: String): String = buildString {
        append("CASE lower(coalesce($column,''))")
        polygonPlaceTypes.forEachIndexed { index, type -> append(" WHEN '$type' THEN ${index + 1}") }
        append(" ELSE ${polygonPlaceTypes.size + 1} END")
    }

    fun polygonOrder(type: String?, boundingBoxArea: Double, stableGeometryId: Long, stablePlaceId: Long) =
        PolygonOrder(polygonPriority(type), boundingBoxArea, stableGeometryId, stablePlaceId)
}

internal data class PolygonOrder(
    val priority: Int,
    val boundingBoxArea: Double,
    val geometryId: Long,
    val placeId: Long,
) : Comparable<PolygonOrder> {
    override fun compareTo(other: PolygonOrder): Int = compareValuesBy(this, other, PolygonOrder::priority, PolygonOrder::boundingBoxArea, PolygonOrder::geometryId, PolygonOrder::placeId)
}

object PointInPolygon {
    fun contains(latitude: Double, longitude: Double, encodedRing: String?): Boolean {
        val points = encodedRing.orEmpty().split(';').mapNotNull { pair ->
            pair.split(',').takeIf { it.size == 2 }?.let { it[0].trim().toDoubleOrNull()?.let { lat -> it[1].trim().toDoubleOrNull()?.let { lng -> lat to lng } } }
        }
        if (points.size < 3) return false
        var inside = false
        var previous = points.last()
        points.forEach { current ->
            if ((current.second > longitude) != (previous.second > longitude) &&
                latitude < (previous.first - current.first) * (longitude - current.second) / (previous.second - current.second) + current.first
            ) inside = !inside
            previous = current
        }
        return inside
    }
}
