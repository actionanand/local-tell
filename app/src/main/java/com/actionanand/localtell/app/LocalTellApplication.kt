package com.actionanand.localtell.app

import android.app.Application
import androidx.work.*
import com.actionanand.localtell.app.update.PackUpdateWorker
import java.util.concurrent.TimeUnit

class LocalTellApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val request = PeriodicWorkRequestBuilder<PackUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "offline-pack-update-check",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
