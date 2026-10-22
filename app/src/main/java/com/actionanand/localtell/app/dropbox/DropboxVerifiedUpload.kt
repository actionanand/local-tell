package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData

internal interface DropboxBackupRemote {
    fun snapshots(): List<DropboxRemoteFile>
    fun download(file: DropboxRemoteFile): AppBackupData
    fun upload(data: AppBackupData): DropboxRemoteFile
    fun deleteOwned(file: DropboxRemoteFile)
}

internal interface DropboxOwnershipRecords {
    fun read(account: String): List<DropboxOwnedBackup>?
    fun record(account: String, file: DropboxRemoteFile): Boolean
    fun forget(account: String, id: String): Boolean
}

internal sealed interface DropboxUploadResult {
    data class Verified(val fingerprint: String) : DropboxUploadResult
    data class Conflict(val files: List<DropboxRemoteFile>, val ignoredUploadId: String? = null) : DropboxUploadResult
}

/** Append-only uploads; no retention is permitted until both verification and race checks pass. */
internal class DropboxVerifiedUpload(
    private val remote: DropboxBackupRemote,
    private val ownership: DropboxOwnershipRecords,
    private val checkActive: () -> Unit,
) {
    fun execute(account: String, data: AppBackupData, expected: List<DropboxRemoteFile>): DropboxUploadResult {
        checkActive()
        val before = remote.snapshots()
        if (!DropboxRemoteState.unchanged(expected, before)) return DropboxUploadResult.Conflict(before)
        checkActive()
        val uploaded = remote.upload(data)
        checkActive()
        val fingerprint = AppBackupCodec.fingerprint(data)
        require(AppBackupCodec.fingerprint(remote.download(uploaded)) == fingerprint) { "Dropbox verification mismatch" }
        checkActive()
        val after = remote.snapshots()
        if (!DropboxRemoteState.unchanged(before, after, uploaded.id)) {
            return DropboxUploadResult.Conflict(after, uploaded.id)
        }
        check(ownership.record(account, uploaded)) { "Cannot persist verified backup ownership" }
        checkActive()
        val records = checkNotNull(ownership.read(account)) { "Backup ownership ledger is invalid" }
        var expectedAfter = after
        DropboxBackupRetention.obsolete(after, records, uploaded).forEach { old ->
            checkActive()
            val current = remote.snapshots()
            if (!DropboxRemoteState.unchanged(expectedAfter, current)) {
                return DropboxUploadResult.Conflict(current, uploaded.id)
            }
            // The transport uses the immutable file ID and a revision condition, never a path-only delete.
            remote.deleteOwned(old)
            check(ownership.forget(account, old.id)) { "Cannot update backup ownership ledger" }
            expectedAfter = expectedAfter.filter { it.id != old.id }
        }
        checkActive()
        val final = remote.snapshots()
        if (!DropboxRemoteState.unchanged(expectedAfter, final)) {
            return DropboxUploadResult.Conflict(final, uploaded.id)
        }
        return DropboxUploadResult.Verified(fingerprint)
    }
}
