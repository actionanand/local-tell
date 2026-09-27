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
               WHERE r.min_lat<=? AND r.max_lat>=? AND r.min_lng<=? AND r.max_lng>=?""".trimIndent(),
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
            """SELECT name,place_type,sub_district,district,state,state_code,latitude,longitude
               FROM place WHERE latitude IS NOT NULL AND longitude IS NOT NULL
               ORDER BY ((latitude-?)*(latitude-?))+((longitude-?)*(longitude-?)) LIMIT 16""".trimIndent(),
            arrayOf(latitude.toString(), latitude.toString(), longitude.toString(), longitude.toString()),
        ).use { cursor ->
            var nearest: LocalityMatch? = null
            var nearestDistance = Double.MAX_VALUE
            while (cursor.moveToNext()) {
                val distance = haversineMetres(latitude, longitude, cursor.getDouble(6), cursor.getDouble(7))
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearest = cursor.toMatch(pack, "nearest-place")
                }
            }
            nearest
        }

    private fun android.database.Cursor.toMatch(pack: InstalledPack, quality: String) = LocalityMatch(
        localityName = getString(0), localityType = getString(1).takeIf { !it.isNullOrBlank() },
        subDistrict = getString(2).takeIf { !it.isNullOrBlank() }, district = getString(3).takeIf { !it.isNullOrBlank() },
        state = getString(4).takeIf { !it.isNullOrBlank() }, stateCode = getString(5).takeIf { !it.isNullOrBlank() },
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
