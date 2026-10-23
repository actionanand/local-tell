package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData

internal interface DropboxBackupRemote {
    fun snapshots(): List<DropboxRemoteFile>
    fun download(file: DropboxRemoteFile): AppBackupData
    fun verify(file: DropboxRemoteFile): AppBackupData = download(file)
    fun upload(data: AppBackupData): DropboxRemoteFile
    fun deleteOwned(file: DropboxRemoteFile)
}

internal interface DropboxOwnershipRecords {
    fun read(account: String): List<DropboxOwnedBackup>?
    fun record(account: String, file: DropboxRemoteFile): Boolean
    fun forget(account: String, id: String): Boolean
    fun pending(account: String): DropboxPendingUpload?
    fun savePending(account: String, upload: DropboxPendingUpload): Boolean
    fun clearPending(account: String): Boolean
}

internal data class DropboxPendingUpload(val file: DropboxRemoteFile, val fingerprint: String, val beforeToken: String)

internal data class DropboxRemoteBaseline(val file: DropboxRemoteFile?, val data: AppBackupData?)

internal fun isInvalidRemoteBackup(error: Exception): Boolean =
    error is IllegalArgumentException || error is org.json.JSONException ||
        error is java.time.DateTimeException || error is java.nio.charset.CharacterCodingException

/**
 * Picks the newest readable backup in the managed folder. Backups from other devices stay visible so
 * conflicts are still detected; only an unreadable file of unknown origin is skipped (never deleted).
 */
internal fun selectRemoteBaseline(
    files: List<DropboxRemoteFile>,
    preferredPath: String?,
    ownership: List<DropboxOwnedBackup>,
    download: (DropboxRemoteFile) -> AppBackupData,
): DropboxRemoteBaseline {
    val ordered = files.sortedWith(
        compareByDescending<DropboxRemoteFile> { preferredPath != null && it.path == preferredPath }
            .thenByDescending { it.modifiedAtMillis }
            .thenByDescending { it.name },
    )
    for (file in ordered) {
        try {
            return DropboxRemoteBaseline(file, download(file))
        } catch (error: Exception) {
            if (!isInvalidRemoteBackup(error) || ownership.any { it.file.id == file.id }) throw error
        }
    }
    return DropboxRemoteBaseline(null, null)
}

/**
 * Identical content in a manual or other-device file is not a verified managed backup of this
 * installation, so it must not be reported as a successful sync; one owned copy is uploaded instead.
 */
internal fun managedSyncDecision(
    decision: DropboxSyncDecision,
    latest: DropboxRemoteFile?,
    ownership: List<DropboxOwnedBackup>,
): DropboxSyncDecision =
    if (decision == DropboxSyncDecision.UP_TO_DATE && latest != null && ownership.none { it.matches(latest) }) {
        DropboxSyncDecision.UPLOAD_LOCAL
    } else decision

internal sealed interface DropboxUploadResult {
    data class Verified(val fingerprint: String) : DropboxUploadResult
    data class Conflict(val files: List<DropboxRemoteFile>, val ignoredUploadId: String? = null) : DropboxUploadResult
}

/** Append-only uploads; no retention is permitted until both verification and race checks pass. */
internal class DropboxVerifiedUpload(
    private val remote: DropboxBackupRemote,
    private val ownership: DropboxOwnershipRecords,
    private val setStage: (DropboxBackupStage) -> Unit = {},
    private val checkActive: () -> Unit,
) {
    fun execute(account: String, data: AppBackupData, expected: List<DropboxRemoteFile>): DropboxUploadResult {
        checkActive()
        setStage(DropboxBackupStage.RECORD_OWNERSHIP)
        check(ownership.read(account) != null) { "Backup ownership ledger is invalid" }
        val pending = ownership.pending(account)
        setStage(DropboxBackupStage.LIST_REMOTE)
        val before = remote.snapshots()
        if (!DropboxRemoteState.unchanged(expected, before)) return DropboxUploadResult.Conflict(before)
        checkActive()
        setStage(DropboxBackupStage.PREPARE_BACKUP_FOLDER)
        val uploaded = pending?.file ?: remote.upload(data)
        checkActive()
        val fingerprint = pending?.fingerprint ?: AppBackupCodec.fingerprint(data)
        if (pending == null) {
            setStage(DropboxBackupStage.RECORD_OWNERSHIP)
            check(ownership.savePending(account, DropboxPendingUpload(uploaded, fingerprint, DropboxRemoteState.token(before)))) {
                "Cannot persist pending upload"
            }
        }
        setStage(DropboxBackupStage.VERIFY_UPLOAD)
        require(AppBackupCodec.fingerprint(remote.verify(uploaded)) == fingerprint) { "Dropbox verification mismatch" }
        checkActive()
        setStage(DropboxBackupStage.RECORD_OWNERSHIP)
        check(ownership.record(account, uploaded)) { "Cannot persist verified backup ownership" }
        fun conflict(files: List<DropboxRemoteFile>): DropboxUploadResult.Conflict {
            checkActive()
            setStage(DropboxBackupStage.RECORD_OWNERSHIP)
            check(ownership.clearPending(account)) { "Cannot clear conflicting pending upload" }
            return DropboxUploadResult.Conflict(files, uploaded.id)
        }
        setStage(DropboxBackupStage.LIST_REMOTE)
        val after = remote.snapshots()
        val expectedToken = pending?.beforeToken ?: DropboxRemoteState.token(before)
        if (after.none { it == uploaded } ||
            DropboxRemoteState.token(after.filter { it.id != uploaded.id }) != expectedToken) {
            return conflict(after)
        }
        setStage(DropboxBackupStage.RECORD_OWNERSHIP)
        checkActive()
        val records = checkNotNull(ownership.read(account)) { "Backup ownership ledger is invalid" }
        var expectedAfter = after
        DropboxBackupRetention.obsolete(after, records, uploaded).forEach { old ->
            checkActive()
            setStage(DropboxBackupStage.RETENTION_CLEANUP)
            setStage(DropboxBackupStage.LIST_REMOTE)
            val current = remote.snapshots()
            if (!DropboxRemoteState.unchanged(expectedAfter, current)) {
                return conflict(current)
            }
            // The transport uses the immutable file ID and a revision condition, never a path-only delete.
            setStage(DropboxBackupStage.RETENTION_CLEANUP)
            remote.deleteOwned(old)
            check(ownership.forget(account, old.id)) { "Cannot update backup ownership ledger" }
            expectedAfter = expectedAfter.filter { it.id != old.id }
            check(ownership.savePending(account, DropboxPendingUpload(
                uploaded, fingerprint, DropboxRemoteState.token(expectedAfter.filter { it.id != uploaded.id }),
            ))) { "Cannot update pending retention state" }
        }
        checkActive()
        setStage(DropboxBackupStage.LIST_REMOTE)
        val final = remote.snapshots()
        if (!DropboxRemoteState.unchanged(expectedAfter, final)) {
            return conflict(final)
        }
        setStage(DropboxBackupStage.RECORD_OWNERSHIP)
        check(ownership.clearPending(account)) { "Cannot clear verified pending upload" }
        setStage(DropboxBackupStage.COMPLETE)
        return DropboxUploadResult.Verified(fingerprint)
    }
}
