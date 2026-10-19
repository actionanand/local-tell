package com.actionanand.localtell.app.geography

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class GeographyAssetLoaderTest {
    @Test fun `missing asset is a load error not uncertain coordinates`() {
        val missing = FileNotFoundException("missing test asset")
        val error = assertThrows(GeographyAssetException::class.java) {
            GeographyAssetLoader.load { throw missing }
        }
        assertSame(missing, error.cause)
    }

    @Test fun `bad gzip is a load error and closes the input`() {
        var closed = false
        val stream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) {
            override fun close() { closed = true; super.close() }
        }
        assertThrows(GeographyAssetException::class.java) { GeographyAssetLoader.load { stream } }
        assertTrue(closed)
    }

    @Test fun `load cancellation propagates unchanged`() {
        val cancelled = CancellationException("cancel")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            GeographyAssetLoader.load { throw cancelled }
        })
    }

    @Test fun `successful load supports matches and genuinely uncertain coordinates`() {
        val file = File("src/main/assets/geography/boundaries.ltgz").takeIf { it.isFile }
            ?: File("app/src/main/assets/geography/boundaries.ltgz")
        val index = GeographyAssetLoader.load { file.inputStream() }
        assertEquals("IN-KA", index.identify(12.971598, 77.594566, 20.0)?.adminCode)
        assertEquals("IN-TN", index.identify(8.1833, 77.4119, 20.0)?.adminCode)
        assertEquals("LK", index.identify(7.2906, 80.6337, 20.0)?.countryCode)
        assertNull(index.identify(0.0, 0.0, 20.0))
    }
}
