package com.actionanand.localtell.app.survey

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.net.Uri
import com.actionanand.localtell.app.model.RadioCell
import java.io.OutputStreamWriter

data class TowerSurveySummary(
    val active: Boolean,
    val startedAt: Long?,
    val acceptedFixes: Int,
    val observations: Int,
    val accuracy: Float?,
    val registeredSummary: String?,
)

class TowerSurveyDbHelper(context: Context) : SQLiteOpenHelper(context, "tower-survey.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE survey_session (id INTEGER PRIMARY KEY, started_at INTEGER NOT NULL, ended_at INTEGER)""")
        db.execSQL("""CREATE TABLE survey_fix (
            id INTEGER PRIMARY KEY, session_id INTEGER NOT NULL, timestamp INTEGER NOT NULL,
            latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy REAL NOT NULL,
            altitude REAL, speed REAL, bearing REAL, provider TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE survey_cell (
            id INTEGER PRIMARY KEY, fix_id INTEGER NOT NULL, subscription_id INTEGER, sim_slot INTEGER,
            carrier TEXT, mcc TEXT NOT NULL, mnc TEXT NOT NULL, radio TEXT NOT NULL,
            area_code INTEGER, cell_id INTEGER NOT NULL, registered INTEGER NOT NULL,
            pci INTEGER, channel_number INTEGER, modem_bands TEXT, derived_band TEXT,
            dbm INTEGER, rsrp INTEGER, rsrq INTEGER, sinr INTEGER, timing_advance INTEGER,
            timing_advance_usable INTEGER NOT NULL, lte_enb_id INTEGER, lte_sector_id INTEGER)""")
        db.execSQL("CREATE INDEX idx_survey_fix_session ON survey_fix(session_id)")
        db.execSQL("CREATE INDEX idx_survey_cell_identity ON survey_cell(mcc,mnc,radio,area_code,cell_id)")
        db.execSQL("CREATE INDEX idx_survey_cell_registered ON survey_cell(registered)")
        db.execSQL("CREATE INDEX idx_survey_cell_lte_enb ON survey_cell(lte_enb_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun startSession(): Long {
        val values = ContentValues().apply { put("started_at", System.currentTimeMillis()) }
        return writableDatabase.insertOrThrow("survey_session", null, values)
    }

    fun endSession(sessionId: Long) {
        writableDatabase.update("survey_session", ContentValues().apply { put("ended_at", System.currentTimeMillis()) }, "id=?", arrayOf(sessionId.toString()))
    }

    fun insertFix(sessionId: Long, location: Location, cells: List<RadioCell>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val fixId = db.insertOrThrow("survey_fix", null, ContentValues().apply {
                put("session_id", sessionId); put("timestamp", location.time)
                put("latitude", location.latitude); put("longitude", location.longitude); put("accuracy", location.accuracy)
                if (location.hasAltitude()) put("altitude", location.altitude)
                if (location.hasSpeed()) put("speed", location.speed)
                if (location.hasBearing()) put("bearing", location.bearing)
                put("provider", location.provider ?: "gps")
            })
            cells.forEach { cell ->
                val grouping = if (cell.radio == "LTE") lteCellGrouping(cell.cellId) else null
                db.insertOrThrow("survey_cell", null, ContentValues().apply {
                    put("fix_id", fixId); cell.subscriptionId?.let { put("subscription_id", it) }; cell.simSlotIndex?.let { put("sim_slot", it) }
                    cell.carrierName?.let { put("carrier", it) }; put("mcc", cell.mcc); put("mnc", cell.mnc); put("radio", cell.radio)
                    cell.areaCode?.let { put("area_code", it) }; put("cell_id", cell.cellId); put("registered", if (cell.registered) 1 else 0)
                    cell.pci?.let { put("pci", it) }; cell.channelNumber?.let { put("channel_number", it) }
                    put("modem_bands", cell.bands.joinToString("|")); RadioBandResolver.derivedBand(cell.radio, cell.channelNumber)?.let { put("derived_band", it) }
                    cell.dbm?.let { put("dbm", it) }; cell.rsrp?.let { put("rsrp", it) }; cell.rsrq?.let { put("rsrq", it) }; cell.sinr?.let { put("sinr", it) }
                    cell.timingAdvance?.let { put("timing_advance", it) }
                    put("timing_advance_usable", if (if (cell.radio == "LTE") isUsableLteTimingAdvance(cell.timingAdvance) else cell.timingAdvance != null) 1 else 0)
                    grouping?.let { put("lte_enb_id", it.enbId); put("lte_sector_id", it.sectorId) }
                })
            }
            db.setTransactionSuccessful()
            return fixId
        } finally { db.endTransaction() }
    }

    fun counts(sessionId: Long): Pair<Int, Int> {
        val db = readableDatabase
        val fixes = db.rawQuery("SELECT COUNT(*) FROM survey_fix WHERE session_id=?", arrayOf(sessionId.toString())).use { it.moveToFirst(); it.getInt(0) }
        val cells = db.rawQuery("SELECT COUNT(*) FROM survey_cell c JOIN survey_fix f ON f.id=c.fix_id WHERE f.session_id=?", arrayOf(sessionId.toString())).use { it.moveToFirst(); it.getInt(0) }
        return fixes to cells
    }

    fun clear() { writableDatabase.delete("survey_cell", null, null); writableDatabase.delete("survey_fix", null, null); writableDatabase.delete("survey_session", null, null) }

    fun export(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream, Charsets.UTF_8).buffered().use { out ->
                out.appendLine("session_id,timestamp,latitude,longitude,location_accuracy,altitude,speed,bearing,provider,subscription_id,sim_slot,carrier,mcc,mnc,radio,area_code,cell_id,registered,pci,channel_number,modem_bands,derived_band,dbm,rsrp,rsrq,sinr,timing_advance,timing_advance_usable,lte_enb_id,lte_sector_id")
                readableDatabase.rawQuery("""SELECT f.session_id,f.timestamp,f.latitude,f.longitude,f.accuracy,f.altitude,f.speed,f.bearing,f.provider,c.subscription_id,c.sim_slot,c.carrier,c.mcc,c.mnc,c.radio,c.area_code,c.cell_id,c.registered,c.pci,c.channel_number,c.modem_bands,c.derived_band,c.dbm,c.rsrp,c.rsrq,c.sinr,c.timing_advance,c.timing_advance_usable,c.lte_enb_id,c.lte_sector_id FROM survey_fix f JOIN survey_cell c ON c.fix_id=f.id ORDER BY f.id,c.id""", null).use { cursor ->
                    while (cursor.moveToNext()) out.appendLine((0 until cursor.columnCount).joinToString(",") { csv(cursor.getString(it)) })
                }
            }
        } ?: error("Unable to create survey export")
    }

    private fun csv(value: String?): String = "\"${(value ?: "").replace("\"", "\"\"")}\""
}
