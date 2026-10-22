package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import java.io.ByteArrayOutputStream
import java.io.OutputStream

internal class DropboxBackupValidationException(message: String) : IllegalArgumentException(message)

/** Metadata is untrusted: enforce the same ceiling while receiving the response body. */
internal class DropboxDownloadLimit(
    metadataSize: Long,
    private val checkActive: () -> Unit = {},
    private val limit: Int = AppBackupCodec.MAX_BYTES,
) : OutputStream() {
    private val output = ByteArrayOutputStream()

    init {
        if (metadataSize !in 0L..limit.toLong()) throw DropboxBackupValidationException("Backup exceeds download limit")
    }

    override fun write(value: Int) {
        reserve(1)
        output.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        reserve(length)
        output.write(bytes, offset, length)
    }

    private fun reserve(length: Int) {
        checkActive()
        if (length > limit - output.size()) throw DropboxBackupValidationException("Backup exceeds download limit")
    }

    fun bytes(): ByteArray = output.toByteArray()
}
