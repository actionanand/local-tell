package com.actionanand.localtell.app.data

import android.database.sqlite.SQLiteDatabase
import com.actionanand.localtell.app.model.AreaMatch
import com.actionanand.localtell.app.model.RadioCell

class OfflineAreaResolver(private val store: PackStore) {
    fun resolve(cell: RadioCell): AreaMatch? {
        for (pack in store.all()) {
            val match = runCatching { query(pack, cell) }.getOrNull()
            if (match != null) return match
        }
        return null
    }

    fun resolveFirst(cells: List<RadioCell>): AreaMatch? {
        cells.sortedWith(compareByDescending<RadioCell> { it.radio == "NR" }.thenByDescending { it.dbm ?: -999 })
            .forEach { cell -> resolve(cell)?.let { return it } }
        return null
    }

    private fun query(pack: InstalledPack, cell: RadioCell): AreaMatch? {
        val db = SQLiteDatabase.openDatabase(pack.filePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val exact = if (cell.areaCode != null) {
                queryOne(
                    db,
                    """SELECT a.area_name,a.district,a.state,c.confidence FROM cell_lookup c JOIN area a ON a.id=c.area_id
                       WHERE c.mcc=? AND c.mnc=? AND c.radio=? AND c.area_code=? AND c.cell_id=? LIMIT 1""".trimIndent(),
                    arrayOf(cell.mcc, cell.mnc, cell.radio, cell.areaCode.toString(), cell.cellId.toString()),
                    pack,
                    cell,
                )
            } else null
            if (exact != null) return exact

            // LTE ECI and 5G NCI are PLMN-scoped identities; allow a TAC-independent fallback.
            if (cell.radio == "LTE" || cell.radio == "NR") {
                return queryOne(
                    db,
                    """SELECT a.area_name,a.district,a.state,c.confidence FROM cell_lookup c JOIN area a ON a.id=c.area_id
                       WHERE c.mcc=? AND c.mnc=? AND c.radio=? AND c.cell_id=?
                       ORDER BY c.confidence DESC LIMIT 1""".trimIndent(),
                    arrayOf(cell.mcc, cell.mnc, cell.radio, cell.cellId.toString()),
                    pack,
                    cell,
                )
            }
            return null
        } finally {
            db.close()
        }
    }

    private fun queryOne(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>,
        pack: InstalledPack,
        cell: RadioCell,
    ): AreaMatch? = db.rawQuery(sql, args).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        AreaMatch(
            areaName = cursor.getString(0),
            district = cursor.getString(1).takeIf { it.isNotBlank() },
            state = cursor.getString(2).takeIf { it.isNotBlank() },
            confidence = cursor.getInt(3).coerceIn(0, 100),
            packId = pack.id,
            packVersion = pack.version,
            matchedCell = cell,
        )
    }
}
