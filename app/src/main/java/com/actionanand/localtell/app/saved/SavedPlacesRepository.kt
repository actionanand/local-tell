package com.actionanand.localtell.app.saved

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.actionanand.localtell.app.backup.SavedPlacesChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All database operations are serialized and executed away from the UI thread. */
class SavedPlacesRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db by lazy { SavedPlacesDb(appContext) }
    private val mutex = Mutex()
    private val mutablePlaces = MutableStateFlow<List<SavedPlace>>(emptyList())
    val places: StateFlow<List<SavedPlace>> = mutablePlaces.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) { mutex.withLock { publish() } }

    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { db.close() } }

    suspend fun save(place: SavedPlace): SaveResult = withContext(Dispatchers.IO) {
        require(SavedPlaceIdentity.valid(place))
        mutex.withLock {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                val existing = readAll(database)
                val result = classifySavedPlace(existing, place)
                if (result == SaveResult.Saved) insert(database, place)
                database.setTransactionSuccessful()
                result
            } finally {
                database.endTransaction()
                publish()
            }
        }
    }

    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val database = db.writableDatabase
            database.beginTransaction()
            try {
                ids.forEach { database.delete("saved_place", "id=?", arrayOf(it)) }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
                publish()
            }
        }
    }

    /** Recompute inside the write transaction; never remove more than the user confirmed. */
    internal suspend fun removeOldestOverflow(confirmedMaximum: Int) = withContext(Dispatchers.IO) {
        require(confirmedMaximum >= 0)
        mutex.withLock {
            val database = db.writableDatabase
            database.beginTransaction()
            var committed = false
            try {
                val extra = SavedPlacesOverflow.oldestToRemove(readAll(database)).take(confirmedMaximum)
                extra.forEach {
                    currentCoroutineContext().ensureActive()
                    database.delete("saved_place", "id=?", arrayOf(it.id))
                }
                currentCoroutineContext().ensureActive()
                database.setTransactionSuccessful()
                committed = true
            } finally {
                database.endTransaction()
                if (committed) SavedPlacesChanges.changed()
                publish()
            }
        }
    }

    private fun publish() { mutablePlaces.value = readAll(db.readableDatabase) }

    internal fun insert(database: SQLiteDatabase, place: SavedPlace) {
        database.insertOrThrow("saved_place", null, ContentValues().apply {
            put("id", place.id); put("identity", place.identity); put("name", place.displayName)
            put("locality", place.localityName); put("sub_district", place.subDistrict); put("district", place.district)
            put("state", place.state); put("country_code", place.countryCode); put("latitude", place.latitude)
            put("longitude", place.longitude); put("saved_at", place.savedAt); put("source", place.source.name)
            put("accuracy", place.accuracyMetres)
        })
    }

    internal fun readAll(database: SQLiteDatabase): List<SavedPlace> = database.rawQuery(
        "SELECT id,name,locality,sub_district,district,state,country_code,latitude,longitude,saved_at,source,accuracy FROM saved_place ORDER BY saved_at DESC,id DESC", null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(SavedPlace(
                id = cursor.getString(0), displayName = cursor.getString(1), localityName = cursor.getString(2),
                subDistrict = cursor.getString(3), district = cursor.getString(4), state = cursor.getString(5),
                countryCode = cursor.getString(6), latitude = cursor.getDouble(7), longitude = cursor.getDouble(8),
                savedAt = cursor.getLong(9), source = SavedPlaceSource.valueOf(cursor.getString(10)),
                accuracyMetres = if (cursor.isNull(11)) null else cursor.getFloat(11),
            ))
        }
    }
}

private class SavedPlacesDb(context: Context) : SQLiteOpenHelper(context, "saved_places.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE saved_place (
            id TEXT PRIMARY KEY NOT NULL, identity TEXT UNIQUE NOT NULL, name TEXT NOT NULL,
            locality TEXT, sub_district TEXT, district TEXT, state TEXT, country_code TEXT,
            latitude REAL NOT NULL, longitude REAL NOT NULL, saved_at INTEGER NOT NULL,
            source TEXT NOT NULL, accuracy REAL
        )""".trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
