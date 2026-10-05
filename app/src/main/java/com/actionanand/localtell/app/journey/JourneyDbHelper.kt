package com.actionanand.localtell.app.journey

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val MAX_JOURNEY_POINTS = 100

data class JourneyPoint(
    val id: Long,
    val timestamp: Long,
    val areaName: String,
    val district: String?,
    val state: String?,
    val radio: String,
    val plmn: String,
    val cellId: Long,
    val confidence: Int,
)

class JourneyDbHelper(context: Context) : SQLiteOpenHelper(context, "journeys.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE journey_point (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                area_name TEXT NOT NULL,
                district TEXT,
                state TEXT,
                radio TEXT NOT NULL,
                plmn TEXT NOT NULL,
                cell_id INTEGER NOT NULL,
                confidence INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_journey_point_timestamp ON journey_point(timestamp DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) trimHistory(db)
    }

    fun add(point: JourneyPoint) {
        val values = ContentValues().apply {
            put("timestamp", point.timestamp)
            put("area_name", point.areaName)
            put("district", point.district)
            put("state", point.state)
            put("radio", point.radio)
            put("plmn", point.plmn)
            put("cell_id", point.cellId)
            put("confidence", point.confidence)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("journey_point", null, values)
            trimHistory(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun trimHistory(db: SQLiteDatabase) {
        db.execSQL(
            """DELETE FROM journey_point WHERE id IN (
                SELECT id FROM journey_point
                ORDER BY timestamp DESC, id DESC LIMIT -1 OFFSET ?
            )""".trimIndent(),
            arrayOf(MAX_JOURNEY_POINTS),
        )
    }

    fun latest(limit: Int = MAX_JOURNEY_POINTS): List<JourneyPoint> = readableDatabase.rawQuery(
        """SELECT id,timestamp,area_name,district,state,radio,plmn,cell_id,confidence
           FROM journey_point ORDER BY timestamp DESC, id DESC LIMIT ?""".trimIndent(),
        arrayOf(limit.coerceIn(1, MAX_JOURNEY_POINTS).toString())
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    JourneyPoint(
                        id = c.getLong(0),
                        timestamp = c.getLong(1),
                        areaName = c.getString(2),
                        district = c.getString(3),
                        state = c.getString(4),
                        radio = c.getString(5),
                        plmn = c.getString(6),
                        cellId = c.getLong(7),
                        confidence = c.getInt(8),
                    )
                )
            }
        }
    }

    fun clear() = writableDatabase.delete("journey_point", null, null)
}
