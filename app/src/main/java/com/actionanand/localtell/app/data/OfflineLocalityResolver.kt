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
 * Runtime contract: `place` and `place_geometry` are required. Geographic packs may also contain
 * `place_geometry_rtree` for desktop/build-time validation, but Android does not query that virtual
 * table because some vendor SQLite builds do not include the `rtree` module.
 *
 * Geometry is stored as one closed ring per row using
 * `latitude,longitude;latitude,longitude;...`.
 */
class OfflineLocalityResolver(private val store: PackStore) {
    fun hasGeographicPack(): Boolean = store.all().any { pack ->
        runCatching {
            SQLiteDatabase.openDatabase(pack.filePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                schemaVersion(db) == 3 &&
                    hasTables(db, "place", "place_geometry") &&
                    hasColumn(db, "place", "admin_level")
            }
        }.getOrDefault(false)
    }

    fun resolve(latitude: Double, longitude: Double): LocalityMatch? {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "Invalid GNSS coordinate"
        }

        store.all().forEach { pack ->
            val match = SQLiteDatabase.openDatabase(
                pack.filePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                if (
                    schemaVersion(db) != 3 ||
                    !hasTables(db, "place", "place_geometry") ||
                    !hasColumn(db, "place", "admin_level")
                ) {
                    return@use null
                }

                if (!isInsidePackState(db, latitude, longitude)) return@use null

                resolvePolygon(db, pack, latitude, longitude)
                    ?: resolveNearestPlace(db, pack, latitude, longitude)
            }

            if (match != null) return match
        }

        return null
    }

    /**
     * Resolve against settlement polygons without depending on SQLite's optional RTree extension.
     *
     * State packs contain a relatively small number of polygon geometries (the first Tamil Nadu
     * pack contains under one thousand total geometry rows), so scanning the settlement geometry
     * rows on a background dispatcher is fast enough for the one-shot Home lookup and is portable
     * across Android vendor SQLite builds.
     */
    private fun resolvePolygon(
        db: SQLiteDatabase,
        pack: InstalledPack,
        latitude: Double,
        longitude: Double,
    ): LocalityMatch? =
        db.rawQuery(
            """SELECT p.id,g.id,p.name,p.place_type,p.sub_district,p.district,p.state,p.state_code,g.geometry
               FROM place_geometry g
               JOIN place p ON p.id=g.place_id
               WHERE lower(p.place_type) IN (${LocalityLookupRules.polygonPlaceTypes.joinToString(",") { "'$it'" }})
               ORDER BY p.id ASC, g.id ASC""".trimIndent(),
            null,
        ).use { cursor ->
            var bestMatch: LocalityMatch? = null
            var bestOrder: PolygonOrder? = null

            while (cursor.moveToNext()) {
                val geometry = cursor.getString(8)
                if (!PointInPolygon.contains(latitude, longitude, geometry)) continue

                val order = LocalityLookupRules.polygonOrder(
                    type = cursor.getString(3),
                    boundingBoxArea = PointInPolygon.boundingBoxArea(geometry),
                    stableGeometryId = cursor.getLong(1),
                    stablePlaceId = cursor.getLong(0),
                )

                if (bestOrder == null || order < bestOrder) {
                    bestOrder = order
                    bestMatch = cursor.toMatch(pack, "polygon", 2)
                }
            }

            bestMatch
        }

    /**
     * A schema-v3 pack may only resolve coordinates inside its level-4 state extent.
     *
     * State boundaries are few, so read their geometry directly instead of using RTree.
     */
    private fun isInsidePackState(
        db: SQLiteDatabase,
        latitude: Double,
        longitude: Double,
    ): Boolean =
        db.rawQuery(
            """SELECT g.geometry
               FROM place_geometry g
               JOIN place p ON p.id=g.place_id
               WHERE lower(p.place_type)='administrative_boundary' AND p.admin_level='4'
               ORDER BY g.id ASC""".trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (PointInPolygon.contains(latitude, longitude, cursor.getString(0))) {
                    return@use true
                }
            }
            false
        }

    private fun resolveNearestPlace(
        db: SQLiteDatabase,
        pack: InstalledPack,
        latitude: Double,
        longitude: Double,
    ): LocalityMatch? =
        db.rawQuery(
            """SELECT id,name,place_type,sub_district,district,state,state_code,latitude,longitude
               FROM place
               WHERE latitude IS NOT NULL AND longitude IS NOT NULL
                 AND lower(place_type) IN (${LocalityLookupRules.nearestPlaceTypes.joinToString(",") { "'$it'" }})
               ORDER BY ((latitude-?)*(latitude-?))+((longitude-?)*(longitude-?)), id
               LIMIT 16""".trimIndent(),
            arrayOf(
                latitude.toString(),
                latitude.toString(),
                longitude.toString(),
                longitude.toString(),
            ),
        ).use { cursor ->
            var nearest: LocalityMatch? = null
            var nearestDistance = Double.MAX_VALUE
            var nearestId = Long.MAX_VALUE

            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val distance = haversineMetres(
                    latitude,
                    longitude,
                    cursor.getDouble(7),
                    cursor.getDouble(8),
                )

                if (distance < nearestDistance || (distance == nearestDistance && id < nearestId)) {
                    nearestDistance = distance
                    nearestId = id
                    nearest = cursor.toMatch(pack, "nearest-place", 1)
                }
            }

            nearest
        }

    private fun android.database.Cursor.toMatch(
        pack: InstalledPack,
        quality: String,
        offset: Int = 0,
    ) = LocalityMatch(
        localityName = getString(offset),
        localityType = getString(offset + 1).takeIf { !it.isNullOrBlank() },
        subDistrict = getString(offset + 2).takeIf { !it.isNullOrBlank() },
        district = getString(offset + 3).takeIf { !it.isNullOrBlank() },
        state = getString(offset + 4).takeIf { !it.isNullOrBlank() },
        stateCode = getString(offset + 5).takeIf { !it.isNullOrBlank() },
        sourceQuality = quality,
        packId = pack.id,
        packVersion = pack.version,
    )

    private fun schemaVersion(db: SQLiteDatabase): Int? =
        db.rawQuery("SELECT value FROM pack_meta WHERE key='schema_version'", null).use {
            if (it.moveToFirst()) it.getString(0).toIntOrNull() else null
        }

    private fun hasTables(db: SQLiteDatabase, vararg tables: String): Boolean =
        tables.all { table ->
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type IN ('table','virtual table') AND name=?",
                arrayOf(table),
            ).use { it.moveToFirst() }
        }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            generateSequence {
                if (cursor.moveToNext()) cursor.getString(1) else null
            }.any { it == column }
        }

    private fun haversineMetres(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a =
            sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) *
                sin(dLng / 2) *
                sin(dLng / 2)
        return 6_371_000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Place-type rules shared by geographic lookup SQL and its unit tests. */
internal object LocalityLookupRules {
    val nearestPlaceTypes =
        listOf("neighbourhood", "suburb", "locality", "hamlet", "village", "town", "city")
    val polygonPlaceTypes = nearestPlaceTypes

    fun polygonPriority(type: String?): Int =
        polygonPlaceTypes.indexOf(type?.lowercase()).let { index ->
            if (index < 0) polygonPlaceTypes.size + 1 else index + 1
        }

    fun polygonPrioritySql(column: String): String = buildString {
        append("CASE lower(coalesce($column,''))")
        polygonPlaceTypes.forEachIndexed { index, type ->
            append(" WHEN '$type' THEN ${index + 1}")
        }
        append(" ELSE ${polygonPlaceTypes.size + 1} END")
    }

    fun polygonOrder(
        type: String?,
        boundingBoxArea: Double,
        stableGeometryId: Long,
        stablePlaceId: Long,
    ) = PolygonOrder(
        polygonPriority(type),
        boundingBoxArea,
        stableGeometryId,
        stablePlaceId,
    )
}

internal data class PolygonOrder(
    val priority: Int,
    val boundingBoxArea: Double,
    val geometryId: Long,
    val placeId: Long,
) : Comparable<PolygonOrder> {
    override fun compareTo(other: PolygonOrder): Int =
        compareValuesBy(
            this,
            other,
            PolygonOrder::priority,
            PolygonOrder::boundingBoxArea,
            PolygonOrder::geometryId,
            PolygonOrder::placeId,
        )
}

object PointInPolygon {
    private fun points(encodedRing: String?): List<Pair<Double, Double>> =
        encodedRing.orEmpty().split(';').mapNotNull { pair ->
            pair.split(',').takeIf { it.size == 2 }?.let {
                it[0].trim().toDoubleOrNull()?.let { lat ->
                    it[1].trim().toDoubleOrNull()?.let { lng -> lat to lng }
                }
            }
        }

    fun contains(latitude: Double, longitude: Double, encodedRing: String?): Boolean {
        val points = points(encodedRing)
        if (points.size < 3) return false

        // Keep this ray-casting form aligned with
        // localtell-data/scripts/test_locality_lookup.py, which validates release packs.
        var inside = false
        var previous = points.last()

        points.forEach { current ->
            val lat1 = previous.first
            val lon1 = previous.second
            val lat2 = current.first
            val lon2 = current.second

            if ((lat1 > latitude) != (lat2 > latitude)) {
                val crossingLongitude =
                    (lon2 - lon1) * (latitude - lat1) / (lat2 - lat1) + lon1
                if (longitude < crossingLongitude) inside = !inside
            }

            previous = current
        }

        return inside
    }

    fun boundingBoxArea(encodedRing: String?): Double {
        val points = points(encodedRing)
        if (points.isEmpty()) return Double.MAX_VALUE

        val minLat = points.minOf { it.first }
        val maxLat = points.maxOf { it.first }
        val minLng = points.minOf { it.second }
        val maxLng = points.maxOf { it.second }

        return (maxLat - minLat) * (maxLng - minLng)
    }
}
