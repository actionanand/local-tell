package com.actionanand.localtell.app.saved

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData

/** Stream boundary shared by local, remote-provider and external backups. Call on Dispatchers.IO.
 * Provider calls may block; cancellation is checked between calls and all opened streams close.
 */
internal object BackupDocumentIO {
    /** GET_CONTENT may return arbitrary URI types. Never turn a supplied path into a File. */
    fun requireContentUri(scheme: String?, authority: String?) {
        require(scheme == "content" && !authority.isNullOrBlank()) { "Expected a content provider URI" }
    }

    suspend fun write(
        displayName: () -> String?,
        openInput: () -> InputStream,
        openOutput: () -> OutputStream,
    ) {
        currentCoroutineContext().ensureActive()
        BackupFileFormat.requireFilename(displayName())
        openInput().use { input ->
            openOutput().use { output ->
                val buffer = ByteArray(8_192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    currentCoroutineContext().ensureActive()
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                currentCoroutineContext().ensureActive()
                output.flush()
            }
        }
        currentCoroutineContext().ensureActive()
        // Some providers rename on close. Successful close alone is not sufficient.
        BackupFileFormat.requireFilename(displayName())
    }

    suspend fun read(displayName: () -> String?, openInput: () -> InputStream): AppBackupData {
        currentCoroutineContext().ensureActive()
        val filename = displayName()
        BackupFileFormat.requireFilename(filename)
        val bytes = openInput().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                currentCoroutineContext().ensureActive()
                if (count < 0) break
                require(output.size() + count <= AppBackupCodec.MAX_BYTES)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        currentCoroutineContext().ensureActive()
        return BackupFileFormat.decode(filename, bytes)
    }
}
