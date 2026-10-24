package com.actionanand.localtell.app.dropbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.actionanand.localtell.app.BuildConfig
import com.actionanand.localtell.app.backup.AppBackupCodec
import com.actionanand.localtell.app.backup.AppBackupData
import com.actionanand.localtell.app.backup.AppBackupRepository
import com.actionanand.localtell.app.saved.BackupFileFormat
import com.actionanand.localtell.app.saved.SavedPlacesOverflow
import com.dropbox.core.DbxException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import com.dropbox.core.v2.files.WriteMode
import java.io.FilterInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One-time manual runs and periodic work share one serial worker implementation. */
internal class DropboxBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = guardCloudBackupWork(
        enabled = CloudBackupFeature.enabled,
        onDisabled = {
            // Work queued by an earlier build: stop before any credential, network or file access.
            DropboxBackupScheduler.cancel(applicationContext)
            Result.failure()
        },
    ) { sync() }

    private suspend fun sync(): Result = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val account = inputData.getString(KEY_ACCOUNT_HASH) ?: return@withLock Result.failure()
            val generation = inputData.getString(KEY_SESSION) ?: return@withLock Result.failure()
            val sessions = DropboxBackupSessionStore(applicationContext)
            val queued = sessions.current() ?: return@withLock Result.failure()
            if (queued.account != account || queued.generation != generation) return@withLock Result.failure()
            val store = DropboxBackupStateStore(applicationContext)
            val repository = AppBackupRepository(applicationContext)
            val coroutine = currentCoroutineContext()
            var stage = DropboxBackupStage.VERIFY_ACCOUNT
            var uploadStarted = false
            val setStage: (DropboxBackupStage) -> Unit = {
                stage = it
                if (it == DropboxBackupStage.UPLOAD_BACKUP || it == DropboxBackupStage.VERIFY_UPLOAD) uploadStarted = true
            }
            fun stillCurrent(): Boolean {
                val current = sessions.current()
                return current == queued && store.frequency().syncAllowed &&
                    (!inputData.getBoolean(KEY_SCHEDULED, false) || store.frequency().periodDays != null)
            }
            fun checkActive() {
                coroutine.ensureActive()
                if (!stillCurrent()) throw CancellationException("Dropbox backup session ended")
            }
            fun handleFailure(error: Exception): Result {
                val failure = classifyDropboxFailure(error, stage)
                val retry = !uploadStarted && failure.shouldRetry(stage, runAttemptCount)
                fail(store, account, stage, error, failure.status, failure.error, retry, ::stillCurrent, failure.category)
                return if (retry) Result.retry() else Result.failure()
            }
            try {
                checkActive()
                store.selectAccount(account)
                val previousConflict = store.state.value
                store.beginAttempt(account)
                val stored = (DropboxCredentialStore(applicationContext).load() as? StoredCredentialResult.Available)?.credential
                    ?: throw DropboxBackupAuthorizationException("CREDENTIAL_UNAVAILABLE")
                if (!queued.accepts(account, generation, account, backupAccountHash(stored.refreshToken))) {
                    throw DropboxBackupAuthorizationException("CREDENTIAL_CHANGED")
                }
                checkActive()
                var verificationError: Exception? = null
                when (val verification = DropboxSdkGateway().verifyForBackup(stored) { verificationError = it }) {
                    is DropboxVerification.Connected -> {
                        checkActive()
                        if (!queued.accepts(account, generation, verification.accountIdHash, backupAccountHash(stored.refreshToken))) {
                            throw DropboxBackupAuthorizationException("ACCOUNT_MISMATCH")
                        }
                    }
                    DropboxVerification.CredentialsInvalid,
                    DropboxVerification.TemporarilyUnavailable ->
                        throw checkNotNull(verificationError) { "Dropbox account verification failed without an exception" }
                }
                setStage(DropboxBackupStage.RECORD_OWNERSHIP)
                val ownership = DropboxBackupOwnership(applicationContext)
                val ownershipRecords = ownership.read(account)
                    ?: throw IllegalStateException("Backup ownership ledger is invalid")
                val storedPending = ownership.pending(account)
                val client = DbxClientV2(
                    DbxRequestConfig("LocalTell/Dropbox"),
                    DbxCredential(stored.accessToken, stored.expiresAt, stored.refreshToken, BuildConfig.DROPBOX_APP_KEY),
                )
                val remote = DropboxSdkBackupRemote(client, ::checkActive, setStage)
                setStage(DropboxBackupStage.LIST_REMOTE)
                val initial = remote.snapshots()
                // An interrupted upload that no longer exists remotely cannot be resumed; start a fresh sync.
                val pendingUpload = storedPending?.takeIf { pending -> initial.any { it == pending.file } }
                if (storedPending != null && pendingUpload == null) {
                    setStage(DropboxBackupStage.RECORD_OWNERSHIP)
                    check(ownership.clearPending(account)) { "Cannot clear missing pending upload" }
                }
                val action = inputData.getString(KEY_ACTION) ?: ACTION_SYNC
                if (action == ACTION_RESTORE_REMOTE) {
                    val outcome = DropboxRemoteRestore(remote, repositoryRestoreTarget(repository, ::checkActive), ::checkActive, setStage)
                        .execute(
                            DropboxRestoreRequest(
                                inputData.getString(KEY_EXPECTED_PATH),
                                inputData.getString(KEY_EXPECTED_FINGERPRINT),
                                inputData.getString(KEY_EXPECTED_REMOTE_STATE),
                            ),
                            initial, ownershipRecords, store.baseline(account), pendingUpload?.file?.id,
                        )
                    setStage(DropboxBackupStage.COMPLETE)
                    completeDropboxRestore(store, repository, account, outcome, ::checkActive)
                    return@withLock Result.success()
                }
                val resolving = action == ACTION_USE_LOCAL || action == ACTION_MERGE_REMOTE
                val preferredPath = if (resolving) inputData.getString(KEY_EXPECTED_PATH) else previousConflict.conflictPath
                // A concurrent upload of our own must not hide the other device's unresolved snapshot.
                val candidates = initial.filter { it.id != pendingUpload?.file?.id }
                setStage(DropboxBackupStage.DOWNLOAD_REMOTE)
                val baseline = selectRemoteBaseline(candidates, preferredPath, ownershipRecords, remote::download)
                val latest = baseline.file
                val remoteData = baseline.data
                val remoteFingerprint = remoteData?.let(AppBackupCodec::fingerprint)
                checkActive()
                if (resolving) {
                    if (latest?.path != inputData.getString(KEY_EXPECTED_PATH) ||
                        remoteFingerprint.orEmpty() != inputData.getString(KEY_EXPECTED_FINGERPRINT) ||
                        DropboxRemoteState.token(initial) != inputData.getString(KEY_EXPECTED_REMOTE_STATE)) {
                        store.markConflict(account, latest?.path, remoteFingerprint.orEmpty(), remoteState = DropboxRemoteState.token(initial))
                        return@withLock Result.success()
                    }
                    // Check the entire remote set, not just whichever file sorted newest.
                    val beforeMerge = remote.snapshots()
                    if (!DropboxRemoteState.unchanged(initial, beforeMerge)) {
                        markConflict(store, account, remote, beforeMerge, ownershipRecords, ::checkActive)
                        return@withLock Result.success()
                    }
                    if (action == ACTION_MERGE_REMOTE && remoteData != null && remoteFingerprint != null) {
                        setStage(DropboxBackupStage.READ_LOCAL_DATA)
                        repository.mergeKeepingAllPlaces(remoteData, ::checkActive)
                        checkActive()
                        store.adoptBaseline(account, remoteFingerprint)
                    }
                }
                setStage(DropboxBackupStage.READ_LOCAL_DATA)
                val local = repository.snapshot()
                checkActive()
                if (SavedPlacesOverflow.excess(local.savedPlaces.size) > 0) {
                    store.markSavedLimitExceeded(account)
                    return@withLock Result.success()
                }
                val changedOwnedFile = latest?.let { file ->
                    ownershipRecords.any { it.file.id == file.id && !it.matches(file) }
                } == true
                val localFingerprint = AppBackupCodec.fingerprint(local)
                val protocolDecision = if (resolving || pendingUpload != null) DropboxSyncDecision.UPLOAD_LOCAL
                    else DropboxSyncProtocol.decide(
                        localFingerprint, remoteFingerprint, store.baseline(account),
                        localEmpty = local.isEmpty,
                        remoteEmpty = remoteData?.isEmpty == true,
                        requireReview = previousConflict.conflictFingerprint != null || changedOwnedFile,
                    )
                val decision = managedSyncDecision(protocolDecision, latest, ownershipRecords)
                when (decision) {
                    DropboxSyncDecision.CONFLICT, DropboxSyncDecision.RESTORE_REMOTE ->
                        store.markConflict(account, latest?.path, remoteFingerprint.orEmpty(),
                            remoteState = DropboxRemoteState.token(initial),
                            restore = decision == DropboxSyncDecision.RESTORE_REMOTE)
                    DropboxSyncDecision.UP_TO_DATE -> {
                        val current = remote.snapshots()
                        if (!DropboxRemoteState.unchanged(initial, current)) {
                            markConflict(store, account, remote, current, ownershipRecords, ::checkActive, restore = local.isEmpty)
                        } else {
                            setStage(DropboxBackupStage.COMPLETE)
                            completeDropboxBackup(store, repository, account, localFingerprint, ::checkActive)
                        }
                    }
                    DropboxSyncDecision.UPLOAD_LOCAL -> {
                        when (val uploaded = DropboxVerifiedUpload(
                            remote, ownership, setStage = setStage, checkActive = ::checkActive,
                        ).execute(account, local, initial)) {
                            is DropboxUploadResult.Conflict -> {
                                setStage(DropboxBackupStage.RECORD_OWNERSHIP)
                                val currentOwnership = checkNotNull(ownership.read(account)) { "Backup ownership ledger is invalid" }
                                markConflict(store, account, remote, uploaded.files, currentOwnership, ::checkActive,
                                    uploaded.ignoredUploadId, restore = local.isEmpty)
                            }
                            is DropboxUploadResult.Verified -> {
                                setStage(DropboxBackupStage.COMPLETE)
                                completeDropboxBackup(store, repository, account, uploaded.fingerprint, ::checkActive)
                            }
                        }
                    }
                }
                Result.success()
            } catch (cancelled: CancellationException) {
                if (sessions.current() == queued) store.markInterrupted(account)
                // Session ended (disconnect, account switch, backup turned off) while WorkManager still runs us.
                if (coroutine.isActive) return@withLock Result.failure()
                throw cancelled
            } catch (error: Exception) {
                // Every failure is classified and leaves a terminal status; nothing may stay "Syncing".
                handleFailure(error)
            } finally {
                withContext(NonCancellable) { repository.close() }
                store.close()
            }
        }
    }

    private fun markConflict(
        store: DropboxBackupStateStore,
        account: String,
        remote: DropboxBackupRemote,
        files: List<DropboxRemoteFile>,
        ownership: List<DropboxOwnedBackup>,
        checkActive: () -> Unit,
        ignoredUploadId: String? = null,
        restore: Boolean = false,
    ) {
        val baseline = selectRemoteBaseline(files.filter { it.id != ignoredUploadId }, null, ownership, remote::download)
        val fingerprint = baseline.data?.let(AppBackupCodec::fingerprint).orEmpty()
        checkActive()
        store.markConflict(account, baseline.file?.path, fingerprint, remoteState = DropboxRemoteState.token(files), restore = restore)
    }

    private fun fail(
        store: DropboxBackupStateStore,
        account: String,
        stage: DropboxBackupStage,
        error: Exception,
        status: DropboxBackupStatus,
        backupError: DropboxBackupError,
        retryable: Boolean,
        stillCurrent: () -> Boolean,
        category: String = backupError.name,
        requestId: String? = (error as? DbxException)?.requestId,
    ) {
        if (!stillCurrent()) return
        val diagnostic = DropboxBackupDiagnostic(
            stage = stage,
            exceptionClass = error.javaClass.simpleName.filter { it.isLetterOrDigit() }.take(80)
                .ifEmpty { "UnknownException" },
            category = category.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(80),
            requestId = requestId?.filter { it.isLetterOrDigit() || it == '-' || it == '_' }?.take(128),
            retryable = retryable,
            timestamp = System.currentTimeMillis(),
            detail = dropboxFailureDetail(error),
        )
        store.markFailure(account, status, backupError, diagnostic = diagnostic)
        Log.e(TAG, diagnostic.toSafeText())
    }

    companion object {
        private const val TAG = "DropboxBackupWorker"
        private val syncMutex = Mutex()
        const val MANUAL_WORK = "dropbox-manual-sync"
        const val ACTION_SYNC = "sync"
        const val ACTION_USE_LOCAL = "use_local"
        const val ACTION_MERGE_REMOTE = "merge_remote"
        const val ACTION_RESTORE_REMOTE = "restore_remote"
        const val KEY_ACTION = "action"
        const val KEY_ACCOUNT_HASH = "account_hash"
        const val KEY_SESSION = "session"
        const val KEY_SCHEDULED = "scheduled"
        const val KEY_EXPECTED_PATH = "expected_path"
        const val KEY_EXPECTED_FINGERPRINT = "expected_fingerprint"
        const val KEY_EXPECTED_REMOTE_STATE = "expected_remote_state"

        fun enqueue(
            context: Context, accountHash: String, action: String = ACTION_SYNC, path: String? = null,
            fingerprint: String? = null, remoteState: String? = null,
        ) {
            if (!CloudBackupFeature.enabled) return
            val session = DropboxBackupSessionStore(context).current() ?: return
            if (session.account != accountHash) return
            val data = workDataOf(
                KEY_ACCOUNT_HASH to accountHash,
                KEY_SESSION to session.generation,
                KEY_ACTION to action,
                KEY_EXPECTED_PATH to path,
                KEY_EXPECTED_FINGERPRINT to fingerprint,
                KEY_EXPECTED_REMOTE_STATE to remoteState,
            )
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                MANUAL_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DropboxBackupWorker>().setInputData(data).build(),
            )
        }
    }
}

internal suspend fun completeDropboxBackup(
    store: DropboxBackupStateStore,
    repository: AppBackupRepository,
    account: String,
    fingerprint: String,
    checkActive: () -> Unit,
) {
    // Publish from a fresh transaction so a local edit during upload remains pending.
    repository.inspectSnapshot { current ->
        checkActive()
        store.markSuccess(account, fingerprint, pending = AppBackupCodec.fingerprint(current) != fingerprint)
        if (SavedPlacesOverflow.excess(current.savedPlaces.size) > 0) store.markSavedLimitExceeded(account)
    }
}

internal fun repositoryRestoreTarget(repository: AppBackupRepository, checkActive: () -> Unit) = object : DropboxRestoreTarget {
    override suspend fun snapshot(): AppBackupData = repository.snapshot()
    override suspend fun restoreIntoEmpty(data: AppBackupData): Boolean = repository.restoreIntoEmpty(data, checkActive)
}

/** Publishes a restore outcome; nothing here touches Dropbox or the last-successful-backup time. */
internal suspend fun completeDropboxRestore(
    store: DropboxBackupStateStore,
    repository: AppBackupRepository,
    account: String,
    outcome: DropboxRestoreOutcome,
    checkActive: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    when (outcome) {
        is DropboxRestoreOutcome.Review -> {
            checkActive()
            store.markConflict(account, outcome.path, outcome.fingerprint, remoteState = outcome.remoteState, restore = outcome.restore)
        }
        is DropboxRestoreOutcome.NothingToRestore -> {
            checkActive()
            store.clearReview(account, outcome.pending)
        }
        is DropboxRestoreOutcome.Restored -> repository.inspectSnapshot { current ->
            checkActive()
            store.markRestored(
                account, outcome.fingerprint,
                pending = AppBackupCodec.fingerprint(current) != outcome.fingerprint,
                restoredAt = now.takeIf { outcome.merged },
            )
            if (SavedPlacesOverflow.excess(current.savedPlaces.size) > 0) store.markSavedLimitExceeded(account)
        }
    }
}

internal class DropboxSdkBackupRemote(
    private val client: DbxClientV2,
    private val checkActive: () -> Unit,
    private val setStage: (DropboxBackupStage) -> Unit = {},
) : DropboxBackupRemote {
    private val folder = DropboxBackupFolder(
        isFolder = {
            checkActive()
            client.files().getMetadata(DropboxBackupOwnership.MANAGED_DIRECTORY) is FolderMetadata
        },
        create = {
            checkActive()
            client.files().createFolderV2(DropboxBackupOwnership.MANAGED_DIRECTORY)
        },
    )

    override fun snapshots(): List<DropboxRemoteFile> =
        folder.discover { list(DropboxBackupOwnership.MANAGED_DIRECTORY) }

    private fun list(directory: String): List<DropboxRemoteFile> {
        checkActive()
        setStage(DropboxBackupStage.LIST_REMOTE)
        val files = mutableListOf<DropboxRemoteFile>()
        var result = client.files().listFolder(directory)
        while (true) {
            checkActive()
            result.entries.filterIsInstance<FileMetadata>().filter {
                it.name.startsWith("LocalTell-") && it.name.endsWith(".bklt", ignoreCase = true)
            }.forEach { file -> files += metadata(file) }
            if (!result.hasMore) break
            result = client.files().listFolderContinue(result.cursor)
        }
        return files
    }

    override fun download(file: DropboxRemoteFile): AppBackupData {
        setStage(DropboxBackupStage.DOWNLOAD_REMOTE)
        return downloadFile(file)
    }

    override fun verify(file: DropboxRemoteFile): AppBackupData {
        setStage(DropboxBackupStage.VERIFY_UPLOAD)
        return downloadFile(file)
    }

    // Pin the revision via the path argument ("rev:<rev>"); a changed file cannot pass as the earlier snapshot.
    private val revisionDownload = DropboxRevisionDownload(
        source = { path ->
            val download = client.files().downloadBuilder(path).start()
            try {
                val result = download.result
                val received = DropboxDownloadedMetadata(result.id, result.rev, result.size, result.contentHash)
                object : DropboxOpenedDownload {
                    override val metadata = received
                    override fun copyTo(output: java.io.OutputStream) {
                        download.download(output)
                    }
                    override fun close() = download.close()
                }
            } catch (error: Throwable) {
                download.close()
                throw error
            }
        },
        checkActive = checkActive,
    )

    private fun downloadFile(file: DropboxRemoteFile): AppBackupData = revisionDownload.fetch(file)

    override fun upload(data: AppBackupData): DropboxRemoteFile {
        val contents = AppBackupCodec.encode(data).toByteArray(Charsets.UTF_8)
        setStage(DropboxBackupStage.PREPARE_BACKUP_FOLDER)
        checkActive()
        folder.prepare()
        setStage(DropboxBackupStage.UPLOAD_BACKUP)
        checkActive()
        val input = object : FilterInputStream(contents.inputStream()) {
            override fun read(): Int {
                checkActive()
                return super.read()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                checkActive()
                return super.read(buffer, offset, length)
            }
        }
        val result = input.use {
            client.files().uploadBuilder("${DropboxBackupOwnership.MANAGED_DIRECTORY}/${BackupFileFormat.filename()}")
                .withMode(WriteMode.ADD).withAutorename(false).withStrictConflict(true)
                .uploadAndFinish(it)
        }
        checkActive()
        return metadata(result)
    }

    override fun deleteOwned(file: DropboxRemoteFile) {
        setStage(DropboxBackupStage.RETENTION_CLEANUP)
        checkActive()
        val current = client.files().getMetadata(file.id) as? FileMetadata
            ?: throw DropboxBackupValidationException("Managed backup is no longer a file")
        require(DropboxOwnedBackup(file).matches(metadata(current))) { "Managed backup changed; preserve it" }
        checkActive()
        client.files().deleteV2(file.id, file.revision)
    }

    private fun metadata(file: FileMetadata): DropboxRemoteFile = DropboxRemoteFile(
        path = file.pathLower ?: throw DropboxBackupValidationException("Dropbox file path is unavailable"),
        name = file.name,
        modifiedAtMillis = file.serverModified.time,
        id = file.id,
        revision = file.rev,
        size = file.size,
        contentHash = file.contentHash,
    )
}

internal fun backupAccountHash(accountId: String): String =
    MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun isDropboxNetworkAvailable(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
