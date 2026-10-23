package com.actionanand.localtell.app.dropbox

import com.dropbox.core.*
import com.dropbox.core.v2.files.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DropboxBackupDiagnosticsTest {
    @Test fun pathAndPermissionFailuresAreNotNetworkErrorsOrRetryable() {
        val path = ListFolderErrorException("list", "req", null, ListFolderError.path(LookupError.NOT_FOUND))
        val permission = UploadErrorException("upload", "req", null,
            UploadError.path(UploadWriteFailed(WriteError.NO_WRITE_PERMISSION, "session")))
        val quota = CreateFolderErrorException("create", "req", null, CreateFolderError.path(WriteError.INSUFFICIENT_SPACE))
        assertEquals(DropboxBackupError.DROPBOX_PATH, classifyDropboxFailure(path, DropboxBackupStage.LIST_REMOTE).error)
        assertEquals(DropboxBackupError.DROPBOX_PERMISSION, classifyDropboxFailure(permission, DropboxBackupStage.UPLOAD_BACKUP).error)
        assertEquals(DropboxBackupError.DROPBOX_QUOTA, classifyDropboxFailure(quota, DropboxBackupStage.PREPARE_BACKUP_FOLDER).error)
        for (error in listOf(path, permission, quota)) {
            assertFalse(classifyDropboxFailure(error, DropboxBackupStage.LIST_REMOTE).retryable)
        }
    }

    @Test fun transportAndServerFailuresCanRetryButNotAfterUploadStarted() {
        val network = classifyDropboxFailure(NetworkIOException(IOException("sensitive payload")), DropboxBackupStage.LIST_REMOTE)
        assertEquals(DropboxBackupStatus.OFFLINE, network.status)
        assertTrue(network.shouldRetry(DropboxBackupStage.LIST_REMOTE, 0))
        assertFalse(network.shouldRetry(DropboxBackupStage.LIST_REMOTE, 3))
        assertFalse(network.shouldRetry(DropboxBackupStage.UPLOAD_BACKUP, 0))
        assertFalse(network.shouldRetry(DropboxBackupStage.VERIFY_UPLOAD, 0))
        assertFalse(network.shouldRetry(DropboxBackupStage.RETENTION_CLEANUP, 0))
        val interruptedStream = classifyDropboxFailure(IOException("stream reset"), DropboxBackupStage.DOWNLOAD_REMOTE)
        assertEquals(DropboxBackupError.NETWORK, interruptedStream.error)
        assertTrue(interruptedStream.retryable)
        assertTrue(classifyDropboxFailure(ServerException("req", "server"), DropboxBackupStage.LIST_REMOTE).retryable)
        assertTrue(classifyDropboxFailure(RetryException("req", "rate limit"), DropboxBackupStage.LIST_REMOTE).retryable)
        assertFalse(classifyDropboxFailure(DbxApiException("req", null, "permanent"), DropboxBackupStage.LIST_REMOTE).retryable)
        val badRequest = classifyDropboxFailure(BadRequestException("req", "bad"), DropboxBackupStage.UPLOAD_BACKUP)
        assertEquals(DropboxBackupError.DROPBOX_API, badRequest.error)
        assertEquals("BAD_REQUEST", badRequest.category)
        assertFalse(badRequest.retryable)
    }

    @Test fun permanentApiErrorsAreNeverRetriedAtAnyAttempt() {
        val path = classifyDropboxFailure(
            ListFolderErrorException("list", "req", null, ListFolderError.path(LookupError.NOT_FOLDER)),
            DropboxBackupStage.LIST_REMOTE,
        )
        assertNotEquals(DropboxBackupStatus.OFFLINE, path.status)
        for (attempt in 0..5) assertFalse(path.shouldRetry(DropboxBackupStage.LIST_REMOTE, attempt))
    }

    @Test fun nonPathDropboxErrorVariantsAreClassifiedWithoutThrowing() {
        val listOther = ListFolderErrorException("list", "req", null, ListFolderError.OTHER)
        val unsupported = DownloadErrorException("download", "req", null, DownloadError.UNSUPPORTED_FILE)
        val downloadOther = DownloadErrorException("download", "req", null, DownloadError.OTHER)
        val uploadOther = UploadErrorException("upload", "req", null, UploadError.OTHER)
        val deleteOther = DeleteErrorException("delete", "req", null, DeleteError.OTHER)
        for (error in listOf(listOther, unsupported, downloadOther, uploadOther, deleteOther)) {
            val failure = classifyDropboxFailure(error, DropboxBackupStage.DOWNLOAD_REMOTE)
            assertEquals(error.javaClass.simpleName, DropboxBackupError.DROPBOX_API, failure.error)
            assertFalse(failure.retryable)
        }
        assertEquals("UNSUPPORTED_FILE", classifyDropboxFailure(unsupported, DropboxBackupStage.DOWNLOAD_REMOTE).category)
        // A non-path listing error must surface as itself, not as an accessor IllegalStateException.
        val folder = DropboxBackupFolder({ true }, {})
        assertSame(listOther, assertThrows(ListFolderErrorException::class.java) { folder.discover { throw listOther } })
    }

    @Test fun changedRemoteDownloadIsRetriedOnlyBeforeUploadStarts() {
        val failure = classifyDropboxFailure(
            DropboxDownloadMismatchException("Dropbox download metadata changed"), DropboxBackupStage.DOWNLOAD_REMOTE,
        )
        assertEquals("DOWNLOAD_MISMATCH", failure.category)
        assertNotEquals(DropboxBackupError.NETWORK, failure.error)
        assertTrue(failure.shouldRetry(DropboxBackupStage.DOWNLOAD_REMOTE, 0))
        assertFalse(failure.shouldRetry(DropboxBackupStage.VERIFY_UPLOAD, 0))
        assertFalse(failure.shouldRetry(DropboxBackupStage.DOWNLOAD_REMOTE, 3))
    }

    @Test fun badRequestDetailIsRedactedAndRoundTrips() {
        val detail = sanitizeDropboxDetail(
            "Error in call to API function \"files/download\": path: 'rev:0123456789a' id:abc_DEF ns:12/x /secret/a.bklt\u0007",
        )
        assertNotNull(detail)
        for (secret in listOf("0123456789a", "abc_DEF", "ns:12/x", "/secret", "\u0007")) {
            assertFalse(detail, detail!!.contains(secret))
        }
        assertTrue(detail!!.contains("files/download"))
        assertTrue(sanitizeDropboxDetail("x".repeat(1000))!!.length <= 240)
        assertNull(dropboxFailureDetail(IOException("not a bad request")))
        val diagnostic = DropboxBackupDiagnostic(DropboxBackupStage.DOWNLOAD_REMOTE, "BadRequestException",
            "BAD_REQUEST", "req", false, 1L, detail)
        assertEquals(diagnostic, DropboxBackupDiagnostic.decode(diagnostic.encode()))
        assertTrue(diagnostic.toSafeText().contains("Detail: "))
        assertFalse(DropboxBackupDiagnostic(DropboxBackupStage.LIST_REMOTE, "E", "C", null, false, 1L)
            .toSafeText().contains("Detail"))
    }

    @Test fun validationIsPermanentAndDiagnosticContainsNoExceptionPayload() {
        val error = DropboxBackupValidationException("secret location content")
        assertEquals(DropboxBackupError.INVALID_BACKUP,
            classifyDropboxFailure(error, DropboxBackupStage.VERIFY_UPLOAD).error)
        val diagnostic = DropboxBackupDiagnostic(DropboxBackupStage.VERIFY_UPLOAD,
            error.javaClass.simpleName, "BACKUP_VALIDATION", "req", false, 123L)
        assertEquals(diagnostic, DropboxBackupDiagnostic.decode(diagnostic.encode()))
        assertFalse(diagnostic.toSafeText().contains("secret"))
        assertTrue(diagnostic.toSafeText().contains("VERIFY_UPLOAD"))
        assertTrue(diagnostic.toSafeText().contains("req"))
        assertNull(DropboxBackupDiagnostic.decode("bad json"))
    }
}
