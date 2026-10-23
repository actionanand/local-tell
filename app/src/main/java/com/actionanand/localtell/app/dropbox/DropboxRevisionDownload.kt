package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupData
import com.actionanand.localtell.app.saved.BackupFileFormat
import java.io.Closeable
import java.io.OutputStream
import java.security.MessageDigest

/** Server metadata returned with a download; the path is optional for historical revisions. */
internal data class DropboxDownloadedMetadata(
    val id: String,
    val revision: String,
    val size: Long,
    val contentHash: String?,
)

internal interface DropboxOpenedDownload : Closeable {
    val metadata: DropboxDownloadedMetadata
    fun copyTo(output: OutputStream)
}

/** Transport seam: [path] is the complete Dropbox `path` argument; no other download argument is sent. */
internal fun interface DropboxDownloadSource {
    fun open(path: String): DropboxOpenedDownload
}

/** The listed file no longer matches what Dropbox returned; abort instead of accepting other content. */
internal class DropboxDownloadMismatchException(message: String) : IllegalStateException(message)

private val dropboxRevisionPattern = Regex("[0-9a-f]{9,}")

/**
 * Dropbox requires a specific revision to be addressed through the `path` argument as `rev:<revision>`.
 * The deprecated separate `rev` argument (SDK `withRev`) must not be combined with it.
 */
internal fun dropboxRevisionPath(file: DropboxRemoteFile): String {
    if (!dropboxRevisionPattern.matches(file.revision)) {
        throw DropboxBackupValidationException("Dropbox revision is invalid")
    }
    return "rev:${file.revision}"
}

/** Shared by baseline downloads and post-upload verification. */
internal class DropboxRevisionDownload(
    private val source: DropboxDownloadSource,
    private val checkActive: () -> Unit = {},
) {
    fun fetch(file: DropboxRemoteFile): AppBackupData {
        checkActive()
        BackupFileFormat.requireFilename(file.name)
        val path = dropboxRevisionPath(file)
        val output = DropboxDownloadLimit(file.size, checkActive)
        source.open(path).use { download ->
            val actual = download.metadata
            if (actual.id != file.id || actual.revision != file.revision || actual.size != file.size ||
                (file.contentHash != null && actual.contentHash != null && actual.contentHash != file.contentHash)) {
                throw DropboxDownloadMismatchException("Dropbox download metadata changed")
            }
            download.copyTo(output)
        }
        checkActive()
        val bytes = output.bytes()
        if (bytes.size.toLong() != file.size) throw DropboxDownloadMismatchException("Dropbox download is incomplete")
        if (file.contentHash != null && DropboxContentHash.of(bytes) != file.contentHash) {
            throw DropboxDownloadMismatchException("Dropbox download content hash mismatch")
        }
        return BackupFileFormat.decode(file.name, bytes)
    }
}

/** Dropbox content hash: SHA-256 over the concatenated SHA-256 digests of each 4 MiB block. */
internal object DropboxContentHash {
    private const val BLOCK_SIZE = 4 * 1024 * 1024

    fun of(bytes: ByteArray): String {
        val overall = MessageDigest.getInstance("SHA-256")
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(BLOCK_SIZE, bytes.size - offset)
            overall.update(MessageDigest.getInstance("SHA-256").apply { update(bytes, offset, length) }.digest())
            offset += length
        }
        return overall.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
