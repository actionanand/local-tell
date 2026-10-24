package com.actionanand.localtell.app.dropbox

import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData

internal interface DropboxRestoreTarget {
    suspend fun snapshot(): AppBackupData

    /** Writes [data] only if this device still has no records, checked inside the write transaction. */
    suspend fun restoreIntoEmpty(data: AppBackupData): Boolean
}

/** What the user saw in the Restore dialog. */
internal data class DropboxRestoreRequest(val path: String?, val fingerprint: String?, val remoteState: String?)

internal sealed interface DropboxRestoreOutcome {
    /** [merged] is false when this device already held exactly the Dropbox data. */
    data class Restored(val fingerprint: String, val merged: Boolean) : DropboxRestoreOutcome

    /** Local or remote data changed before the restore ran; the user must choose again. */
    data class Review(val path: String?, val fingerprint: String, val remoteState: String, val restore: Boolean) :
        DropboxRestoreOutcome

    /** No Dropbox data remains to restore; [pending] is true when local records still need a backup. */
    data class NothingToRestore(val pending: Boolean) : DropboxRestoreOutcome
}

/** Read-only toward Dropbox: it lists and downloads, but never uploads, records ownership or deletes. */
internal class DropboxRemoteRestore(
    private val remote: DropboxBackupRemote,
    private val local: DropboxRestoreTarget,
    private val checkActive: () -> Unit,
    private val setStage: (DropboxBackupStage) -> Unit = {},
) {
    suspend fun execute(
        request: DropboxRestoreRequest,
        files: List<DropboxRemoteFile>,
        ownership: List<DropboxOwnedBackup>,
        baseline: String?,
        ignoredUploadId: String? = null,
    ): DropboxRestoreOutcome {
        setStage(DropboxBackupStage.DOWNLOAD_REMOTE)
        val remoteState = DropboxRemoteState.token(files)
        // A changed remote set voids the confirmed choice; the newest valid backup must be confirmed afresh.
        val preferredPath = request.path.takeIf { remoteState == request.remoteState }
        val selected = selectRemoteBaseline(files.filter { it.id != ignoredUploadId }, preferredPath, ownership, remote::download)
        val remoteData = selected.data
        val remoteFingerprint = remoteData?.let(AppBackupCodec::fingerprint)
        checkActive()
        setStage(DropboxBackupStage.READ_LOCAL_DATA)
        val current = local.snapshot()
        checkActive()
        if (remoteData == null || remoteFingerprint == null || remoteData.isEmpty || !current.isEmpty ||
            selected.file?.path != request.path || remoteFingerprint != request.fingerprint ||
            remoteState != request.remoteState) {
            return reconsider(current, selected.file?.path, remoteData, remoteState, baseline)
        }
        val beforeRestore = remote.snapshots()
        checkActive()
        if (!DropboxRemoteState.unchanged(files, beforeRestore)) {
            setStage(DropboxBackupStage.DOWNLOAD_REMOTE)
            val changed = selectRemoteBaseline(beforeRestore.filter { it.id != ignoredUploadId }, null, ownership, remote::download)
            checkActive()
            return reconsider(current, changed.file?.path, changed.data, DropboxRemoteState.token(beforeRestore), baseline)
        }
        setStage(DropboxBackupStage.READ_LOCAL_DATA)
        if (!local.restoreIntoEmpty(remoteData)) {
            val added = local.snapshot()
            checkActive()
            return reconsider(added, selected.file?.path, remoteData, remoteState, baseline)
        }
        return DropboxRestoreOutcome.Restored(remoteFingerprint, merged = true)
    }

    // requireReview: differing data on both sides is always the user's choice here, never an implicit upload.
    private fun reconsider(
        local: AppBackupData,
        path: String?,
        remoteData: AppBackupData?,
        remoteState: String,
        baseline: String?,
    ): DropboxRestoreOutcome {
        val remoteFingerprint = remoteData?.let(AppBackupCodec::fingerprint)
        return when (DropboxSyncProtocol.decide(
            AppBackupCodec.fingerprint(local), remoteFingerprint, baseline,
            localEmpty = local.isEmpty, remoteEmpty = remoteData?.isEmpty == true, requireReview = true,
        )) {
            DropboxSyncDecision.UP_TO_DATE -> DropboxRestoreOutcome.Restored(checkNotNull(remoteFingerprint), merged = false)
            DropboxSyncDecision.UPLOAD_LOCAL -> DropboxRestoreOutcome.NothingToRestore(pending = !local.isEmpty)
            DropboxSyncDecision.RESTORE_REMOTE ->
                DropboxRestoreOutcome.Review(path, remoteFingerprint.orEmpty(), remoteState, restore = true)
            DropboxSyncDecision.CONFLICT ->
                DropboxRestoreOutcome.Review(path, remoteFingerprint.orEmpty(), remoteState, restore = false)
        }
    }
}
