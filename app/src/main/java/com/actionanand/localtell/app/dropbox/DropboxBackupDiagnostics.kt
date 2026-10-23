package com.actionanand.localtell.app.dropbox

import com.dropbox.core.*
import com.dropbox.core.oauth.DbxOAuthException
import com.dropbox.core.v2.files.*
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

internal enum class DropboxBackupStage {
    VERIFY_ACCOUNT,
    LIST_REMOTE,
    PREPARE_BACKUP_FOLDER,
    DOWNLOAD_REMOTE,
    READ_LOCAL_DATA,
    UPLOAD_BACKUP,
    VERIFY_UPLOAD,
    RECORD_OWNERSHIP,
    RETENTION_CLEANUP,
    COMPLETE,
}

internal data class DropboxBackupFailure(
    val error: DropboxBackupError,
    val category: String,
    val retryable: Boolean = false,
) {
    val status: DropboxBackupStatus get() = when (error) {
        DropboxBackupError.AUTHORIZATION -> DropboxBackupStatus.REAUTHORIZATION_REQUIRED
        DropboxBackupError.NETWORK -> DropboxBackupStatus.OFFLINE
        else -> DropboxBackupStatus.FAILED
    }
}

internal class DropboxBackupAuthorizationException(val category: String) : IllegalStateException(category)

internal fun classifyDropboxFailure(error: Exception, stage: DropboxBackupStage): DropboxBackupFailure {
    fun api(tag: String) = DropboxBackupFailure(DropboxBackupError.DROPBOX_API, tag)
    fun lookup(value: LookupError): DropboxBackupFailure = when {
        value.isRestrictedContent -> DropboxBackupFailure(DropboxBackupError.DROPBOX_PERMISSION, value.tag().name)
        else -> DropboxBackupFailure(DropboxBackupError.DROPBOX_PATH, value.tag().name)
    }
    fun write(value: WriteError): DropboxBackupFailure = when {
        value.isInsufficientSpace -> DropboxBackupFailure(DropboxBackupError.DROPBOX_QUOTA, value.tag().name)
        value.isNoWritePermission || value.isAccessRestricted ->
            DropboxBackupFailure(DropboxBackupError.DROPBOX_PERMISSION, value.tag().name)
        value.isTooManyWriteOperations ->
            DropboxBackupFailure(DropboxBackupError.DROPBOX_TEMPORARY, value.tag().name, true)
        else -> DropboxBackupFailure(DropboxBackupError.DROPBOX_PATH, value.tag().name)
    }
    val invalidData = stage == DropboxBackupStage.DOWNLOAD_REMOTE || stage == DropboxBackupStage.VERIFY_UPLOAD
    return when (error) {
        is DropboxBackupAuthorizationException -> DropboxBackupFailure(DropboxBackupError.AUTHORIZATION, error.category)
        is DropboxBackupFolderException -> DropboxBackupFailure(DropboxBackupError.DROPBOX_PATH, "NOT_FOLDER")
        is InvalidAccessTokenException, is DbxOAuthException ->
            DropboxBackupFailure(DropboxBackupError.AUTHORIZATION, "AUTHORIZATION")
        is AccessErrorException -> DropboxBackupFailure(DropboxBackupError.DROPBOX_PERMISSION, error.accessError.tag().name)
        is NetworkIOException -> DropboxBackupFailure(DropboxBackupError.NETWORK, "TRANSPORT", true)
        is RetryException -> DropboxBackupFailure(DropboxBackupError.DROPBOX_TEMPORARY, "RATE_LIMIT_OR_RETRY", true)
        is ServerException -> DropboxBackupFailure(DropboxBackupError.DROPBOX_TEMPORARY, "SERVER", true)
        is BadResponseCodeException -> if (error.statusCode == 429 || error.statusCode in 500..599) {
            DropboxBackupFailure(DropboxBackupError.DROPBOX_TEMPORARY, "HTTP_${error.statusCode}", true)
        } else api("HTTP_${error.statusCode}")
        is ListFolderErrorException -> error.errorValue.let { if (it.isPath) lookup(it.pathValue) else api(it.tag().name) }
        is GetMetadataErrorException -> lookup(error.errorValue.pathValue)
        is CreateFolderErrorException -> write(error.errorValue.pathValue)
        is UploadErrorException -> error.errorValue.let { if (it.isPath) write(it.pathValue.reason) else api(it.tag().name) }
        is DownloadErrorException -> error.errorValue.let { if (it.isPath) lookup(it.pathValue) else api(it.tag().name) }
        is DeleteErrorException -> error.errorValue.let {
            when {
                it.isPathLookup -> lookup(it.pathLookupValue)
                it.isPathWrite -> write(it.pathWriteValue)
                it.isTooManyWriteOperations -> DropboxBackupFailure(DropboxBackupError.DROPBOX_TEMPORARY, it.tag().name, true)
                else -> api(it.tag().name)
            }
        }
        is BadRequestException -> api("BAD_REQUEST")
        is DbxException -> api("UNCLASSIFIED_API")
        is DropboxBackupValidationException -> DropboxBackupFailure(DropboxBackupError.INVALID_BACKUP, "BACKUP_VALIDATION")
        is IllegalArgumentException, is org.json.JSONException, is java.time.DateTimeException,
        is java.nio.charset.CharacterCodingException ->
            DropboxBackupFailure(if (invalidData) DropboxBackupError.INVALID_BACKUP else DropboxBackupError.STORAGE, "VALIDATION")
        // Dropbox transfers surface interrupted streams as plain IOExceptions.
        is IOException -> DropboxBackupFailure(DropboxBackupError.NETWORK, "TRANSPORT_IO", true)
        is IllegalStateException, is android.database.sqlite.SQLiteException ->
            DropboxBackupFailure(DropboxBackupError.STORAGE, "LOCAL_STORAGE")
        else -> DropboxBackupFailure(DropboxBackupError.UNKNOWN, "UNEXPECTED")
    }
}

internal fun DropboxBackupFailure.shouldRetry(stage: DropboxBackupStage, attempt: Int): Boolean =
    retryable && attempt < 3 && stage in setOf(
        DropboxBackupStage.VERIFY_ACCOUNT, DropboxBackupStage.LIST_REMOTE,
        DropboxBackupStage.PREPARE_BACKUP_FOLDER, DropboxBackupStage.DOWNLOAD_REMOTE,
        DropboxBackupStage.READ_LOCAL_DATA,
    )

internal data class DropboxBackupDiagnostic(
    val stage: DropboxBackupStage,
    val exceptionClass: String,
    val category: String,
    val requestId: String?,
    val retryable: Boolean,
    val timestamp: Long,
) {
    fun toSafeText(): String = buildString {
        appendLine("Dropbox backup diagnostic")
        appendLine("Stage: $stage")
        appendLine("Exception: $exceptionClass")
        appendLine("Category: $category")
        appendLine("Request ID: ${requestId ?: "unavailable"}")
        appendLine("Retryable: $retryable")
        append("Timestamp (UTC epoch ms): $timestamp")
    }

    fun encode(): String = JSONObject()
        .put("stage", stage.name)
        .put("exception", exceptionClass)
        .put("category", category)
        .put("requestId", requestId)
        .put("retryable", retryable)
        .put("timestamp", timestamp)
        .toString()

    companion object {
        fun decode(value: String?): DropboxBackupDiagnostic? {
            if (value == null) return null
            return try {
                val json = JSONObject(value)
                DropboxBackupDiagnostic(
                    stage = DropboxBackupStage.valueOf(json.getString("stage")),
                    exceptionClass = json.getString("exception"),
                    category = json.getString("category"),
                    requestId = json.optString("requestId").takeIf(String::isNotEmpty),
                    retryable = json.getBoolean("retryable"),
                    timestamp = json.getLong("timestamp"),
                )
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
