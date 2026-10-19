package com.actionanand.localtell.app.geography

import android.content.Context
import android.util.Log
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
            index ?: try {
                GeographyAssetLoader.load { context.assets.open("geography/boundaries.ltgz") }.also { index = it }
            } catch (error: GeographyAssetException) {
                // Do not log coordinates. A broken/missing bundled asset is not an uncertain fix.
                Log.e("BuiltInGeography", "Bundled geographic index could not be loaded", error)
                throw error
            }
        }
        val coroutine = currentCoroutineContext()
        loaded.identify(latitude, longitude, accuracy?.toDouble()) { coroutine.ensureActive() }
    }
}
