package com.actionanand.localtell.app.geography

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException

class GeographyAssetException(cause: Exception) : IOException("Bundled geographic index unavailable", cause)

/** Keep loading failures distinct from a successful lookup with no confident match. */
internal object GeographyAssetLoader {
    fun load(open: () -> InputStream): BoundaryIndex = try {
        // Close the source even if GZIPInputStream's constructor rejects its header.
        open().use { BoundaryIndex.read(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw GeographyAssetException(error)
    }
}
