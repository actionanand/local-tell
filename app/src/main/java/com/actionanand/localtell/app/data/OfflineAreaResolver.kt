package com.actionanand.localtell.app.data

import android.database.sqlite.SQLiteDatabase
import com.actionanand.localtell.app.model.AreaMatch
import com.actionanand.localtell.app.model.RadioCell

class OfflineAreaResolver(private val store: PackStore) {
    fun resolve(cell: RadioCell): AreaMatch? {
        if (!cell.registered) return null
        for (pack in store.all()) {
            val match = runCatching { query(pack, cell) }.getOrNull()
            if (match != null) return match
        }
        return null
    }

    fun resolveFirst(cells: List<RadioCell>): AreaMatch? {
        cells.filter(RadioCell::registered)
            .sortedWith(compareByDescending<RadioCell> { it.radio == "NR" }.thenByDescending { it.dbm ?: -999 })
            .forEach { cell -> resolve(cell)?.let { return it } }
        return null
    }

    private fun query(pack: InstalledPack, cell: RadioCell): AreaMatch? {
        val db = SQLiteDatabase.openDatabase(pack.filePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return when (schemaVersion(db)) {
                2 -> queryV2(db, pack, cell)
                1 -> queryV1(db, pack, cell)
                else -> null
            }
        } finally {
            db.close()
        }
    }

    private fun schemaVersion(db: SQLiteDatabase): Int? = db.rawQuery(
        "SELECT value FROM pack_meta WHERE key='schema_version'",
        null,
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).toIntOrNull() else null
    }

    private fun queryV2(db: SQLiteDatabase, pack: InstalledPack, cell: RadioCell): AreaMatch? {
        val exact = if (cell.areaCode != null) {
            queryV2One(
                db,
                """SELECT t.source_site_id,t.latitude,t.longitude,t.area_name,t.district,t.state,t.source,c.confidence
                   FROM cell_lookup c JOIN tower_site t ON t.id=c.tower_site_id
                   WHERE c.mcc=? AND c.mnc=? AND c.radio=? AND c.area_code=? AND c.cell_id=? LIMIT 1""".trimIndent(),
                arrayOf(cell.mcc, cell.mnc, cell.radio, cell.areaCode.toString(), cell.cellId.toString()),
                pack,
                cell,
            )
        } else null
        if (exact != null) return exact

        // LTE ECI and 5G NCI are PLMN-scoped identities; PCI/ARFCN remain diagnostics only.
        return if (cell.radio == "LTE" || cell.radio == "NR") {
            queryV2One(
                db,
                """SELECT t.source_site_id,t.latitude,t.longitude,t.area_name,t.district,t.state,t.source,c.confidence
                   FROM cell_lookup c JOIN tower_site t ON t.id=c.tower_site_id
                   WHERE c.mcc=? AND c.mnc=? AND c.radio=? AND c.cell_id=?
                   ORDER BY c.confidence DESC LIMIT 1""".trimIndent(),
                arrayOf(cell.mcc, cell.mnc, cell.radio, cell.cellId.toString()),
                pack,
                cell,
            )
        } else null
    }

    private fun queryV1(db: SQLiteDatabase, pack: InstalledPack, cell: RadioCell): AreaMatch? {
        val exact = if (cell.areaCode != null) {
            queryV1One(
                db,
                """SELECT a.area_name,a.district,a.state,c.confidence FROM cell_lookup c JOIN area a ON a.id=c.area_id
                   WHERE c.mcc=? AND c.mnc=? AND c.radio=? AND c.area_code=? AND c.cell_id=? LIMIT 1""".trimIndent(),
                arrayOf(cell.mcc, cell.mnc, cell.radio, cell.areaCode.toString(), cell.cellId.toString()),
                pack,
                cell,
            )
        } else null
        if (exact != null) return exact

        return if (cell.radio == "LTE" || cell.radio == "NR") {
            queryV1One(
                db,
                """SELECT a.area_name,a.district,a.state,c.confidence FROM cell_lookup c JOIN area a ON a.id=c.area_id
                   WHERE c.mcc=? AND c.mnc=? AND c.radio=? AND c.cell_id=?
                   ORDER BY c.confidence DESC LIMIT 1""".trimIndent(),
                arrayOf(cell.mcc, cell.mnc, cell.radio, cell.cellId.toString()),
                pack,
                cell,
            )
        } else null
    }

    private fun queryV2One(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
        pack: InstalledPack,
        cell: RadioCell,
    ): AreaMatch? = db.rawQuery(sql, args).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        AreaMatch(
            sourceSiteId = cursor.getString(0).takeIf { it.isNotBlank() },
            latitude = cursor.getDouble(1).takeUnless { cursor.isNull(1) },
            longitude = cursor.getDouble(2).takeUnless { cursor.isNull(2) },
            areaName = cursor.getString(3),
            district = cursor.getString(4).takeIf { it.isNotBlank() },
            state = cursor.getString(5).takeIf { it.isNotBlank() },
            source = cursor.getString(6).takeIf { it.isNotBlank() },
            confidence = cursor.getInt(7).coerceIn(0, 100),
            packId = pack.id,
            packVersion = pack.version,
            matchedCell = cell,
        )
    }

    private fun queryV1One(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
        pack: InstalledPack,
        cell: RadioCell,
    ): AreaMatch? = db.rawQuery(sql, args).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        AreaMatch(
            sourceSiteId = null,
            latitude = null,
            longitude = null,
            areaName = cursor.getString(0),
            district = cursor.getString(1).takeIf { it.isNotBlank() },
            state = cursor.getString(2).takeIf { it.isNotBlank() },
            source = null,
            confidence = cursor.getInt(3).coerceIn(0, 100),
            packId = pack.id,
            packVersion = pack.version,
            matchedCell = cell,
        )
    }
}
