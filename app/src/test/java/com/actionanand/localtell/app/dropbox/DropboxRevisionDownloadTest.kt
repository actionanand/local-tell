package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData
import com.dropbox.core.BadRequestException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.http.HttpRequestor
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DropboxRevisionDownloadTest {
    private val data = AppBackupData(emptyList(), emptyList())
    private val bytes = AppBackupCodec.encode(data).toByteArray(Charsets.UTF_8)
    private val file = DropboxRemoteFile(
        "/localtell-managed-backups/localtell-1.bklt", "LocalTell-1.bklt", 1L,
        "id:a4ayc_80_OEAAAAAAAAAYa", "015f9b4a1c8e2d70", bytes.size.toLong(), sha256(sha256(bytes)).hex(),
    )

    @Test fun sdkDownloadAndVerificationSendOnlyTheRevisionAddressedPath() {
        val http = FakeDropboxHttp { _, _ -> fileResponse(file, bytes) }
        val stages = mutableListOf<DropboxBackupStage>()
        val remote = DropboxSdkBackupRemote(client(http), {}, { stages += it })

        assertEquals(data, remote.download(file))
        assertEquals(data, remote.verify(file))

        assertEquals(listOf(DropboxBackupStage.DOWNLOAD_REMOTE, DropboxBackupStage.VERIFY_UPLOAD), stages)
        assertEquals(2, http.requests.size)
        for ((url, argument) in http.requests) {
            assertTrue(url, url.endsWith("/2/files/download"))
            assertEquals("rev:${file.revision}", argument.getString("path"))
            // The deprecated separate argument (SDK withRev) must never be sent with a revision path.
            assertFalse(argument.toString(), argument.has("rev"))
            assertEquals(setOf("path"), argument.keySet())
        }
    }

    @Test fun deprecatedWithRevIsNotUsedAnywhereInDropboxSources() {
        val directory = listOf(File("src/main/java/com/actionanand/localtell/app/dropbox"),
            File("app/src/main/java/com/actionanand/localtell/app/dropbox")).first { it.isDirectory }
        directory.listFiles { source -> source.extension == "kt" }!!.forEach {
            assertFalse("${it.name} uses deprecated withRev", it.readText().contains(".withRev("))
        }
    }

    @Test fun revisionPathFollowsDropboxReadPathFormatAndRejectsInvalidRevisions() {
        assertEquals("rev:015f9b4a1c8e2d70", dropboxRevisionPath(file))
        for (invalid in listOf("", "12345678", "revision-1", "015F9B4A1C8E", "id:abc", "rev:015f9b4a1c")) {
            assertThrows(DropboxBackupValidationException::class.java) {
                dropboxRevisionPath(file.copy(revision = invalid))
            }
        }
    }

    @Test fun mismatchedIdRevisionSizeOrHashRejectsTheDownloadedFile() {
        val mismatches = listOf(
            DropboxDownloadedMetadata("id:other", file.revision, file.size, file.contentHash),
            DropboxDownloadedMetadata(file.id, "015f9b4a1c8e2d71", file.size, file.contentHash),
            DropboxDownloadedMetadata(file.id, file.revision, file.size + 1, file.contentHash),
            DropboxDownloadedMetadata(file.id, file.revision, file.size, "b".repeat(64)),
        )
        for (metadata in mismatches) {
            var copied = false
            val download = DropboxRevisionDownload({ opened(metadata, bytes) { copied = true } })
            assertThrows(DropboxDownloadMismatchException::class.java) { download.fetch(file) }
            assertFalse("Body must not be accepted after a metadata mismatch", copied)
        }
    }

    @Test fun bodyThatDoesNotMatchTheListedContentHashIsRejected() {
        val tampered = bytes.copyOf().also { it[it.size - 2] = ' '.code.toByte() }
        val download = DropboxRevisionDownload({
            opened(DropboxDownloadedMetadata(file.id, file.revision, file.size, file.contentHash), tampered)
        })
        assertThrows(DropboxDownloadMismatchException::class.java) { download.fetch(file) }
        // Without a listed hash, size and backup-format validation still apply.
        assertEquals(data, DropboxRevisionDownload({
            opened(DropboxDownloadedMetadata(file.id, file.revision, file.size, null), bytes)
        }).fetch(file.copy(contentHash = null)))
    }

    @Test fun truncatedOrOversizedBodiesAreRejected() {
        val metadata = DropboxDownloadedMetadata(file.id, file.revision, file.size, file.contentHash)
        assertThrows(DropboxDownloadMismatchException::class.java) {
            DropboxRevisionDownload({ opened(metadata, bytes.copyOf(bytes.size - 1)) }).fetch(file)
        }
        assertThrows(DropboxDownloadMismatchException::class.java) {
            DropboxRevisionDownload({ opened(metadata, bytes + ' '.code.toByte()) }).fetch(file)
        }
    }

    @Test fun twelveMebibyteLimitIsEnforcedBeforeAndDuringDownload() {
        var requests = 0
        val oversized = file.copy(size = AppBackupCodec.MAX_BYTES + 1L)
        assertThrows(DropboxBackupValidationException::class.java) {
            DropboxRevisionDownload({ requests++; error("must not be requested") }).fetch(oversized)
        }
        assertEquals(0, requests)
        val stream = DropboxRevisionDownload({
            object : DropboxOpenedDownload {
                override val metadata = DropboxDownloadedMetadata(file.id, file.revision, file.size, file.contentHash)
                override fun copyTo(output: OutputStream) {
                    val chunk = ByteArray(1024 * 1024)
                    repeat(13) { output.write(chunk) }
                }
                override fun close() = Unit
            }
        })
        assertThrows(DropboxBackupValidationException::class.java) { stream.fetch(file) }
    }

    @Test fun malformedFilenameIsRejectedWithoutAnyRequest() {
        var requests = 0
        val download = DropboxRevisionDownload({ requests++; error("must not be requested") })
        for (name in listOf("LocalTell-1.json", "../LocalTell-1.bklt", "nested/LocalTell-1.bklt")) {
            assertThrows(IllegalArgumentException::class.java) { download.fetch(file.copy(name = name)) }
        }
        assertEquals(0, requests)
    }

    @Test fun dropboxContentHashMatchesTheDocumentedBlockAlgorithm() {
        assertEquals(sha256(sha256(bytes)).hex(), DropboxContentHash.of(bytes))
        assertEquals(sha256(ByteArray(0)).hex(), DropboxContentHash.of(ByteArray(0)))
        val block = 4 * 1024 * 1024
        val large = ByteArray(block + 3) { (it % 251).toByte() }
        val expected = sha256(sha256(large.copyOfRange(0, block)) + sha256(large.copyOfRange(block, large.size))).hex()
        assertEquals(expected, DropboxContentHash.of(large))
    }

    @Test fun httpBadRequestIsPermanentAndDiagnosticRedactsEchoedArguments() {
        val http = FakeDropboxHttp { _, _ ->
            HttpRequestor.Response(400, ByteArrayInputStream(
                ("Error in call to API function \"files/download\": HTTP header \"Dropbox-API-Arg\": " +
                    "path: 'rev:015f9b4a1c8e2d70' id:a4ayc_80_OEAAAAAAAAAYa /private/LocalTell-1.bklt").toByteArray(),
            ), mapOf(
                "X-Dropbox-Request-Id" to listOf("02d4d93632bf4a648c0f9bb26adb795e"),
                "Content-Type" to listOf("text/plain; charset=utf-8"),
            ))
        }
        val remote = DropboxSdkBackupRemote(client(http), {})
        val error = assertThrows(BadRequestException::class.java) { remote.download(file) }
        val failure = classifyDropboxFailure(error, DropboxBackupStage.DOWNLOAD_REMOTE)
        assertEquals("BAD_REQUEST", failure.category)
        assertFalse(failure.shouldRetry(DropboxBackupStage.DOWNLOAD_REMOTE, 0))
        assertEquals("02d4d93632bf4a648c0f9bb26adb795e", error.requestId)
        val detail = checkNotNull(dropboxFailureDetail(error))
        for (secret in listOf("015f9b4a1c8e2d70", "a4ayc_80_OEAAAAAAAAAYa", "/private")) {
            assertFalse(detail, detail.contains(secret))
        }
    }

    @Test fun downloadFailuresNeverDeleteOrUploadAnything() {
        val http = FakeDropboxHttp { url, _ ->
            assertTrue("Only downloads are permitted", url.endsWith("/2/files/download"))
            fileResponse(file, bytes, revision = "015f9b4a1c8e2d71")
        }
        val remote = DropboxSdkBackupRemote(client(http), {})
        assertThrows(DropboxDownloadMismatchException::class.java) {
            selectRemoteBaseline(listOf(file), null, emptyList(), remote::download)
        }
        assertEquals(1, http.requests.size)
    }

    private fun client(http: HttpRequestor) = DbxClientV2(
        DbxRequestConfig.newBuilder("LocalTell-test").withHttpRequestor(http).build(),
        DbxCredential("test-access-token"),
    )

    private fun fileResponse(file: DropboxRemoteFile, body: ByteArray, revision: String = file.revision) =
        HttpRequestor.Response(200, ByteArrayInputStream(body), mapOf(
            "Content-Type" to listOf("application/octet-stream"),
            "dropbox-api-result" to listOf(
                """{"name":"${file.name}","id":"${file.id}","client_modified":"2026-10-18T15:00:00Z",""" +
                    """"server_modified":"2026-10-18T15:00:00Z","rev":"$revision","size":${file.size},""" +
                    """"path_lower":"${file.path}","content_hash":"${file.contentHash}"}""",
            ),
        ))

    private fun opened(received: DropboxDownloadedMetadata, body: ByteArray, onCopy: () -> Unit = {}) =
        object : DropboxOpenedDownload {
            override val metadata = received
            override fun copyTo(output: OutputStream) {
                onCopy()
                output.write(body)
            }
            override fun close() = Unit
        }

    private class FakeDropboxHttp(private val respond: (String, JSONObject) -> HttpRequestor.Response) : HttpRequestor() {
        val requests = mutableListOf<Pair<String, JSONObject>>()

        override fun doGet(url: String, headers: Iterable<HttpRequestor.Header>): HttpRequestor.Response = throw AssertionError("Unexpected GET $url")

        override fun startPut(url: String, headers: Iterable<HttpRequestor.Header>): HttpRequestor.Uploader = throw AssertionError("Unexpected PUT $url")

        override fun startPost(url: String, headers: Iterable<HttpRequestor.Header>): HttpRequestor.Uploader {
            val argument = JSONObject(headers.first { it.key == "Dropbox-API-Arg" }.value)
            requests += url to argument
            return object : HttpRequestor.Uploader() {
                private val body = ByteArrayOutputStream()
                override fun getBody(): OutputStream = body
                override fun close() = Unit
                override fun abort() = Unit
                override fun finish(): HttpRequestor.Response = respond(url, argument)
            }
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
