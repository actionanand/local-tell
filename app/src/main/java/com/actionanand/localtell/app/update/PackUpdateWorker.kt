package com.actionanand.localtell.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.actionanand.localtell.app.data.ManifestRepository
import com.actionanand.localtell.app.data.PackStore

class PackUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val installed = PackStore(applicationContext).all().associateBy { it.id }
            val remote = ManifestRepository().fetch()
            val updates = remote.packs.count { p -> installed[p.id]?.let { it.version < p.version } == true }
            applicationContext.getSharedPreferences("offline_packs", Context.MODE_PRIVATE)
                .edit()
                .putInt("updates_available", updates)
                .putLong("last_update_check", System.currentTimeMillis())
                .apply()
            Result.success()
        }.getOrElse { Result.success() }
    }
}
