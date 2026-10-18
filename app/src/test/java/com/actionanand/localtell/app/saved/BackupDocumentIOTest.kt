package com.actionanand.localtell.app.saved

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupDocumentIOTest {
    private fun backupBytes(): ByteArray = SavedPlacesBackupCodec.encode(
        SavedPlacesBackupCodec.decode(legacyBackupFixture().toString()),
    ).toByteArray(Charsets.UTF_8)

    @Test fun `content provider URIs work with opaque document IDs rather than path filenames`() {
        for (text in listOf("content://cloud.provider/document/123", "content://files.provider/item/a%2Fb")) {
            val uri = java.net.URI(text)
            BackupDocumentIO.requireContentUri(uri.scheme, uri.authority)
        }
    }

    @Test fun `unsupported and malformed URI components fail before querying names or opening streams`() {
        val invalid = listOf(null to null, "" to "", "file" to null, "https" to "example.com",
            "content" to null, "content" to "", "content" to " ")
        for ((scheme, authority) in invalid) {
            var providerAccessed = false
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    BackupDocumentIO.requireContentUri(scheme, authority)
                    BackupDocumentIO.read({ providerAccessed = true; "backup.bklt" }, { providerAccessed = true; Input(backupBytes()) })
                }
            }
            assertFalse(providerAccessed)
        }
    }

    @Test fun `inaccessible provider filename fails without opening a document`() {
        var streamOpened = false
        assertThrows(SecurityException::class.java) {
            runBlocking {
                BackupDocumentIO.read({ throw SecurityException("Grant unavailable") }, { streamOpened = true; Input(backupBytes()) })
            }
        }
        assertFalse(streamOpened)
    }

    @Test fun `local and remote document streams roundtrip the identical backup without changing metadata`() = runBlocking {
        val bytes = backupBytes()
        for (name in listOf("LocalTell-local.bklt", "தமிழ்-स्थानम्-cloud.bklt")) {
            val input = Input(bytes)
            val output = Output()
            var nameChecks = 0
            BackupDocumentIO.write(
                displayName = {
                    if (++nameChecks == 2) { assertEquals(1, input.closes); assertEquals(1, output.closes) }
                    name
                },
                openInput = { input }, openOutput = { output },
            )
            assertEquals(2, nameChecks)
            assertArrayEquals(bytes, output.toByteArray())
            val restored = BackupDocumentIO.read({ name }, { Input(output.toByteArray()) })
            assertEquals(SavedPlacesBackupCodec.decode(legacyBackupFixture().toString()), restored)
        }
    }

    @Test fun `unsupported names and missing names are rejected before opening any stream`() {
        for (name in listOf(null, "backup.json", "backup.txt", "backup.zip")) {
            var opened = false
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    BackupDocumentIO.write({ name }, { opened = true; Input(backupBytes()) }, { opened = true; Output() })
                }
            }
            assertFalse(opened)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { BackupDocumentIO.read({ name }, { opened = true; Input(backupBytes()) }) }
            }
            assertFalse(opened)
        }
    }

    @Test fun `provider renaming on close is rejected even after all bytes were written`() {
        val input = Input(backupBytes())
        val output = Output()
        var checks = 0
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                BackupDocumentIO.write({ if (++checks == 1) "backup.bklt" else "backup.json" }, { input }, { output })
            }
        }
        assertEquals(1, input.closes)
        assertEquals(1, output.closes)
    }

    @Test fun `write flush close and post-write provider failures never return success and close streams`() {
        for (failure in listOf("write", "flush", "close", "name")) {
            val input = Input(backupBytes())
            val output = Output(failure)
            var checks = 0
            val error = assertThrows(IOException::class.java) {
                runBlocking {
                    BackupDocumentIO.write(
                        { if (++checks == 2 && failure == "name") throw IOException("name"); "backup.bklt" },
                        { input }, { output },
                    )
                }
            }
            assertEquals(failure, error.message)
            assertEquals(1, input.closes)
            assertEquals(1, output.closes)
        }
    }

    @Test fun `provider open and read failures propagate and close already-opened streams`() {
        val input = Input(backupBytes())
        assertThrows(IOException::class.java) {
            runBlocking { BackupDocumentIO.write({ "backup.bklt" }, { input }, { throw IOException("open") }) }
        }
        assertEquals(1, input.closes)
        assertThrows(IOException::class.java) {
            runBlocking { BackupDocumentIO.read({ "backup.bklt" }, { throw IOException("open") }) }
        }
        val brokenInput = Input(backupBytes(), onRead = { throw IOException("read") })
        assertThrows(IOException::class.java) {
            runBlocking { BackupDocumentIO.read({ "backup.bklt" }, { brokenInput }) }
        }
        assertEquals(1, brokenInput.closes)
    }

    @Test fun `cancellation during a write closes both streams and cannot return success`() = runBlocking {
        var completed = false
        val input = Input(backupBytes())
        lateinit var output: Output
        val transfer = launch(start = CoroutineStart.LAZY) {
            val job = currentCoroutineContext().job
            output = Output(onWrite = { job.cancel() })
            BackupDocumentIO.write({ "backup.bklt" }, { input }, { output })
            completed = true
        }
        transfer.start()
        transfer.join()
        assertTrue(transfer.isCancelled)
        assertFalse(completed)
        assertEquals(1, input.closes)
        assertEquals(1, output.closes)
    }

    @Test fun `cancellation during a read closes the document before any restore result is returned`() = runBlocking {
        var restored = false
        lateinit var input: Input
        val transfer = launch {
            val job = currentCoroutineContext().job
            input = Input(backupBytes(), onRead = { job.cancel() })
            BackupDocumentIO.read({ "backup.bklt" }, { input })
            restored = true
        }
        transfer.join()
        assertTrue(transfer.isCancelled)
        assertFalse(restored)
        assertEquals(1, input.closes)
    }

    @Test fun `corruption truncation invalid UTF8 and oversized remote reads fail after closing the document`() {
        for (bytes in listOf("{".toByteArray(), "not a backup".toByteArray(), byteArrayOf(0xc3.toByte(), 0x28),
            ByteArray(SavedPlacesBackupCodec.MAX_BYTES + 1))) {
            val input = Input(bytes)
            assertThrows(Exception::class.java) {
                runBlocking { BackupDocumentIO.read({ "renamed.bklt" }, { input }) }
            }
            assertEquals(1, input.closes)
        }
    }

    private class Input(bytes: ByteArray, private val onRead: () -> Unit = {}) : ByteArrayInputStream(bytes) {
        var closes = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            onRead()
            return super.read(buffer, offset, length)
        }
        override fun close() { closes++; super.close() }
    }

    private class Output(private val failure: String? = null, private val onWrite: () -> Unit = {}) : ByteArrayOutputStream() {
        var closes = 0
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (failure == "write") throw IOException("write")
            super.write(buffer, offset, length)
            onWrite()
        }
        override fun flush() { if (failure == "flush") throw IOException("flush"); super.flush() }
        override fun close() { closes++; if (failure == "close") throw IOException("close"); super.close() }
    }
}
