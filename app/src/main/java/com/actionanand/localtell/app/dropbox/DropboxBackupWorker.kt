package com.actionanand.localtell.app.dropbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.oauth.DbxOAuthException
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.FolderMetadata
import com.dropbox.core.v2.files.GetMetadataErrorException
import com.dropbox.core.v2.files.ListFolderErrorException
import com.dropbox.core.v2.files.WriteMode
import java.io.IOException
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
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val account = inputData.getString(KEY_ACCOUNT_HASH) ?: return@withLock Result.failure()
            val generation = inputData.getString(KEY_SESSION) ?: return@withLock Result.failure()
            val sessions = DropboxBackupSessionStore(applicationContext)
            val queued = sessions.current() ?: return@withLock Result.failure()
            if (queued.account != account || queued.generation != generation) return@withLock Result.failure()
            val store = DropboxBackupStateStore(applicationContext)
            val repository = AppBackupRepository(applicationContext)
            val coroutine = currentCoroutineContext()
            val stored = (DropboxCredentialStore(applicationContext).load() as? StoredCredentialResult.Available)?.credential
            fun stillCurrent(): Boolean {
                val current = sessions.current()
                return current == queued && store.frequency().syncAllowed &&
                    (!inputData.getBoolean(KEY_SCHEDULED, false) || store.frequency().periodDays != null)
            }
            fun checkActive() {
                coroutine.ensureActive()
                if (!stillCurrent()) throw CancellationException("Dropbox backup session ended")
            }
            try {
                if (stored == null) {
                    store.markFailure(account, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
                    return@withLock Result.failure()
                }
                if (!queued.accepts(account, generation, account, backupAccountHash(stored.refreshToken))) {
                    return@withLock Result.failure()
                }
                checkActive()
                when (val verification = DropboxSdkGateway().verify(stored)) {
                    is DropboxVerification.Connected -> {
                        checkActive()
                        if (!queued.accepts(account, generation, verification.accountIdHash, backupAccountHash(stored.refreshToken))) {
                            return@withLock Result.failure()
                        }
                    }
                    DropboxVerification.CredentialsInvalid -> {
                        checkActive()
                        store.markFailure(account, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
                        return@withLock Result.failure()
                    }
                    DropboxVerification.TemporarilyUnavailable -> {
                        checkActive()
                        store.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
                        return@withLock Result.retry()
                    }
                }
                store.selectAccount(account)
                val previousConflict = store.state.value
                store.beginAttempt(account)
                val client = DbxClientV2(
                    DbxRequestConfig("LocalTell/Dropbox"),
                    DbxCredential(stored.accessToken, stored.expiresAt, stored.refreshToken, BuildConfig.DROPBOX_APP_KEY),
                )
                val remote = DropboxSdkBackupRemote(client, ::checkActive)
                val initial = remote.snapshots()
                val action = inputData.getString(KEY_ACTION) ?: ACTION_SYNC
                val resolving = action == ACTION_USE_LOCAL || action == ACTION_MERGE_REMOTE
                val preferredPath = if (resolving) inputData.getString(KEY_EXPECTED_PATH) else previousConflict.conflictPath
                // A concurrent upload of our own must not hide the other device's unresolved snapshot.
                val latest = initial.firstOrNull { it.path == preferredPath } ?: newest(initial)
                val remoteData = latest?.let(remote::download)
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
                        markConflict(store, account, remote, beforeMerge, ::checkActive)
                        return@withLock Result.success()
                    }
                    if (action == ACTION_MERGE_REMOTE && remoteData != null && remoteFingerprint != null) {
                        repository.mergeKeepingAllPlaces(remoteData, ::checkActive)
                        checkActive()
                        store.adoptBaseline(account, remoteFingerprint)
                    }
                }
                val local = repository.snapshot()
                checkActive()
                if (SavedPlacesOverflow.excess(local.savedPlaces.size) > 0) {
                    store.markSavedLimitExceeded(account)
                    return@withLock Result.success()
                }
                if (!resolving && previousConflict.conflictFingerprint != null) {
                    store.markConflict(account, latest?.path, remoteFingerprint.orEmpty(), remoteState = DropboxRemoteState.token(initial))
                    return@withLock Result.success()
                }
                val localFingerprint = AppBackupCodec.fingerprint(local)
                val decision = if (resolving) DropboxSyncDecision.UPLOAD_LOCAL
                    else DropboxSyncProtocol.decide(localFingerprint, remoteFingerprint, store.baseline(account))
                when (decision) {
                    DropboxSyncDecision.CONFLICT ->
                        store.markConflict(account, latest?.path, remoteFingerprint.orEmpty(), remoteState = DropboxRemoteState.token(initial))
                    DropboxSyncDecision.UP_TO_DATE -> {
                        val current = remote.snapshots()
                        if (!DropboxRemoteState.unchanged(initial, current)) {
                            markConflict(store, account, remote, current, ::checkActive)
                        } else {
                            completeDropboxBackup(store, repository, account, localFingerprint, ::checkActive)
                        }
                    }
                    DropboxSyncDecision.UPLOAD_LOCAL -> {
                        when (val uploaded = DropboxVerifiedUpload(
                            remote, DropboxBackupOwnership(applicationContext), ::checkActive,
                        ).execute(account, local, initial)) {
                            is DropboxUploadResult.Conflict -> markConflict(
                                store, account, remote, uploaded.files, ::checkActive, uploaded.ignoredUploadId,
                            )
                            is DropboxUploadResult.Verified -> completeDropboxBackup(store, repository, account, uploaded.fingerprint, ::checkActive)
                        }
                    }
                }
                Result.success()
            } catch (cancelled: CancellationException) {
                if (sessions.current() == queued) store.markInterrupted(account)
                // Session ended (disconnect, account switch, backup turned off) while WorkManager still runs us.
                if (coroutine.isActive) return@withLock Result.failure()
                throw cancelled
            } catch (_: InvalidAccessTokenException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
                Result.failure()
            } catch (_: DbxOAuthException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
                Result.failure()
            } catch (_: IOException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
                Result.retry()
            } catch (_: DbxException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.NETWORK)
                Result.retry()
            } catch (_: IllegalArgumentException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE)
                Result.failure()
            } catch (_: IllegalStateException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE)
                Result.failure()
            } catch (_: org.json.JSONException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE)
                Result.failure()
            } catch (_: java.time.DateTimeException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE)
                Result.failure()
            } catch (_: android.database.sqlite.SQLiteException) {
                if (stillCurrent()) store.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE)
                Result.failure()
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
        checkActive: () -> Unit,
        ignoredUploadId: String? = null,
    ) {
        val latest = newest(files.filter { it.id != ignoredUploadId })
        val fingerprint = latest?.let { AppBackupCodec.fingerprint(remote.download(it)) }.orEmpty()
        checkActive()
        store.markConflict(account, latest?.path, fingerprint, remoteState = DropboxRemoteState.token(files))
    }

    companion object {
        private val syncMutex = Mutex()
        const val MANUAL_WORK = "dropbox-manual-sync"
        const val ACTION_SYNC = "sync"
        const val ACTION_USE_LOCAL = "use_local"
        const val ACTION_MERGE_REMOTE = "merge_remote"
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

        private fun newest(files: List<DropboxRemoteFile>): DropboxRemoteFile? =
            files.maxWithOrNull(compareBy<DropboxRemoteFile> { it.modifiedAtMillis }.thenBy { it.name })
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

internal class DropboxSdkBackupRemote(
    private val client: DbxClientV2,
    private val checkActive: () -> Unit,
) : DropboxBackupRemote {
    override fun snapshots(): List<DropboxRemoteFile> = list("") + list(DropboxBackupOwnership.MANAGED_DIRECTORY)

    private fun list(directory: String): List<DropboxRemoteFile> {
        checkActive()
        val files = mutableListOf<DropboxRemoteFile>()
        var result = try {
            client.files().listFolder(directory)
        } catch (missing: ListFolderErrorException) {
            if (directory.isNotEmpty() && missing.errorValue.isPath && missing.errorValue.pathValue.isNotFound) return emptyList()
            throw missing
        }
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
        checkActive()
        BackupFileFormat.requireFilename(file.name)
        val output = DropboxDownloadLimit(file.size, checkActive)
        // Pin the revision: a changed file cannot pass verification as the earlier snapshot.
        client.files().downloadBuilder(file.id).withRev(file.revision).start().use { download ->
            val actual = metadata(download.result)
            require(actual.id == file.id && actual.revision == file.revision && actual.size == file.size) {
                "Dropbox download metadata changed"
            }
            download.download(output)
        }
        checkActive()
        val bytes = output.bytes()
        require(bytes.size.toLong() == file.size) { "Dropbox download is incomplete" }
        return BackupFileFormat.decode(file.name, bytes)
    }

    override fun upload(data: AppBackupData): DropboxRemoteFile {
        val contents = AppBackupCodec.encode(data).toByteArray(Charsets.UTF_8)
        val directory = DropboxBackupOwnership.MANAGED_DIRECTORY
        checkActive()
        try {
            require(client.files().getMetadata(directory) is FolderMetadata) { "Backup destination is not a folder" }
        } catch (missing: GetMetadataErrorException) {
            if (!missing.errorValue.isPath || !missing.errorValue.pathValue.isNotFound) throw missing
            checkActive()
            client.files().createFolderV2(directory)
        }
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
            client.files().uploadBuilder("$directory/${BackupFileFormat.filename()}")
                .withMode(WriteMode.ADD).withAutorename(false).withStrictConflict(true)
                .uploadAndFinish(it)
        }
        checkActive()
        return metadata(result)
    }

    override fun deleteOwned(file: DropboxRemoteFile) {
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
