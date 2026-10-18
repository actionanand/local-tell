package com.actionanand.localtell.app.geography

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Load once on IO, independently of geographic packs. Never performs network requests. */
object BuiltInGeography {
    private val mutex = Mutex()
    private var index: BoundaryIndex? = null
    suspend fun identify(context: Context, latitude: Double, longitude: Double, accuracy: Float?): DetectedRegion? = withContext(Dispatchers.IO) {
        val loaded = mutex.withLock {
            index ?: BoundaryIndex.read(context.assets.open("geography/boundaries.ltg.gz")).also { index = it }
        }
        val coroutine = currentCoroutineContext()
        loaded.identify(latitude, longitude, accuracy?.toDouble()) { coroutine.ensureActive() }
    }
}
