package com.actionanand.localtell.app.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.actionanand.localtell.app.AppLimits
import com.actionanand.localtell.app.journey.JourneyDbHelper
import com.actionanand.localtell.app.journey.JourneyHistoryChanges
import com.actionanand.localtell.app.saved.SavedPlacesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object SavedPlacesChanges {
    private val mutableVersion = MutableStateFlow(0L)
    val version = mutableVersion.asStateFlow()
    fun changed() { mutableVersion.update { it + 1L } }
}

class BackupStorageModeException : IllegalStateException("Attached backup requires rollback journals")

/** A dedicated attached, rollback-journal SQLite transaction coordinates both existing files.
 * No table/schema replacement. Refuse WAL/OFF/MEMORY instead of claiming cross-file atomicity.
 */
class AppBackupRepository(context: Context) {
    private val context = context.applicationContext
    private val saved = SavedPlacesRepository(this.context)
    private val journey = JourneyDbHelper(this.context, trimOnOpen = false)
    private val mutex = Mutex()

    private suspend fun <T> transaction(notifyChanges: Boolean = false, action: (SQLiteDatabase, AppBackupData) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Existing migrations are additive. Do not trim Journey before a restore is accepted.
            saved.refresh()
            journey.readableDatabase
            SQLiteDatabase.openDatabase(context.getDatabasePath("saved_places.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                fun requireJournal(schema: String) {
                    val mode = db.rawQuery("PRAGMA $schema.journal_mode", null).use { it.moveToFirst(); it.getString(0) }
                    if (mode.lowercase() !in setOf("delete", "truncate", "persist")) throw BackupStorageModeException()
                    db.execSQL("PRAGMA $schema.synchronous=FULL")
                }
                requireJournal("main")
                db.execSQL("ATTACH DATABASE ? AS journey_data", arrayOf(context.getDatabasePath("journeys.db").path))
                requireJournal("journey_data")
                db.beginTransaction()
                try {
                    currentCoroutineContext().ensureActive()
                    val current = AppBackupData(saved.readAll(db), journey.readFrom(db, Int.MAX_VALUE))
                    val result = action(db, current)
                    currentCoroutineContext().ensureActive()
                    db.setTransactionSuccessful()
                    result
                } finally { db.endTransaction() }
            }.also {
                // Notify after commit, before cancellation can prevent delivery back to Main.
                if (notifyChanges) {
                    SavedPlacesChanges.changed()
                    JourneyHistoryChanges.changed()
                }
            }
        }
    }

    suspend fun snapshot(): AppBackupData = transaction { _, current ->
        current.copy(journeys = current.journeys.take(AppLimits.current.maxJourneyPoints))
    }

    suspend fun preview(incoming: AppBackupData): AppRestorePlan = withContext(Dispatchers.IO) {
        AppBackupCodec.validate(incoming)
        transaction { _, current -> planAppRestore(current, incoming) }
    }

    suspend fun restore(incoming: AppBackupData): AppRestorePlan = withContext(Dispatchers.IO) {
        AppBackupCodec.validate(incoming) // No database access at all for malformed content.
        transaction(notifyChanges = true) { db, current ->
            val plan = planAppRestore(current, incoming)
            require(plan.saved.fits) { "Saved Places capacity exceeded" }
            incoming.savedPlaces.filter { candidate -> current.savedPlaces.none {
                com.actionanand.localtell.app.saved.SavedPlaceIdentity.matches(it, candidate)
            } }.forEach { saved.insert(db, it) }
            plan.journey.insertions.forEach { journey.insert(db, it) }
            journey.trimHistory(db)
            plan
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { saved.close(); journey.close() } }
}
