package com.actionanand.localtell.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.actionanand.localtell.app.data.ManifestRepository
import com.actionanand.localtell.app.data.PackStore
import kotlinx.coroutines.CancellationException

class PackUpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return runCatching {
            val installed = PackStore(applicationContext).all().associateBy { it.id }
            val remote = ManifestRepository().fetch()
            val preferences = applicationContext.getSharedPreferences("offline_packs", Context.MODE_PRIVATE)
            val previous = remote.failedSources.associate { source ->
                source.countryCode to preferences.getInt("updates_available:${source.countryCode}", 0)
            }
            val counts = remote.updateCounts(installed, previous)
            // Keep the legacy total conservatively on the first partial check, before per-country counts exist.
            val missingPreviousCount = remote.failedSources.any { !preferences.contains("updates_available:${it.countryCode}") }
            val total = if (missingPreviousCount) maxOf(counts.values.sum(), preferences.getInt("updates_available", 0))
                else counts.values.sum()
            preferences.edit().apply {
                remote.manifests.forEach { manifest ->
                    putInt("updates_available:${manifest.countryCode}", counts.getValue(manifest.countryCode))
                }
                putInt("updates_available", total)
                putLong("last_update_check", System.currentTimeMillis())
            }.apply()
            Result.success()
        }.getOrElse {
            if (it is CancellationException) throw it
            Result.success()
        }
    }
}
