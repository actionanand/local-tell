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
import com.actionanand.localtell.app.backup.AppBackupCodec
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
import com.dropbox.core.v2.files.WriteMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class DropboxRemoteSnapshot(
    val path: String,
    val name: String,
    val modifiedAt: Date,
)

private object DropboxSyncLock {
    val mutex = Mutex()
}

/** One-time manual runs and periodic work share one serial worker implementation. */
internal class DropboxBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = DropboxSyncLock.mutex.withLock {
        val store = DropboxBackupStateStore(applicationContext)
        val repository = AppBackupRepository(applicationContext)
        try {
            DropboxBackupRunner(applicationContext, store, repository).run(
                action = inputData.getString(KEY_ACTION) ?: ACTION_SYNC,
                expectedPath = inputData.getString(KEY_EXPECTED_PATH),
                expectedFingerprint = inputData.getString(KEY_EXPECTED_FINGERPRINT),
                accountHint = inputData.getString(KEY_ACCOUNT_HASH),
            )
        } catch (cancelled: CancellationException) {
            inputData.getString(KEY_ACCOUNT_HASH)?.let(store::markInterrupted)
            throw cancelled
        } catch (_: InvalidAccessTokenException) {
            val account = inputData.getString(KEY_ACCOUNT_HASH)
            if (account != null) store.markFailure(account, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
            Result.failure()
        } catch (_: DbxOAuthException) {
            val account = inputData.getString(KEY_ACCOUNT_HASH)
            if (account != null) store.markFailure(account, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
            Result.failure()
        } catch (_: IOException) {
            currentAccountHash()?.let { store.markFailure(it, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK) }
            Result.retry()
        } catch (_: DbxException) {
            currentAccountHash()?.let { store.markFailure(it, DropboxBackupStatus.FAILED, DropboxBackupError.NETWORK) }
            Result.retry()
        } catch (_: IllegalArgumentException) {
            currentAccountHash()?.let { store.markFailure(it, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE) }
            Result.failure()
        } finally {
            repository.close()
            store.close()
        }
    }

    private fun currentAccountHash(): String? = inputData.getString(KEY_ACCOUNT_HASH)

    companion object {
        const val MANUAL_WORK = "dropbox-manual-sync"
        const val ACTION_SYNC = "sync"
        const val ACTION_USE_LOCAL = "use_local"
        const val ACTION_MERGE_REMOTE = "merge_remote"
        const val KEY_ACTION = "action"
        const val KEY_ACCOUNT_HASH = "account_hash"
        const val KEY_EXPECTED_PATH = "expected_path"
        const val KEY_EXPECTED_FINGERPRINT = "expected_fingerprint"

        fun enqueue(context: Context, accountHash: String, action: String = ACTION_SYNC, path: String? = null, fingerprint: String? = null) {
            val data = workDataOf(
                KEY_ACCOUNT_HASH to accountHash,
                KEY_ACTION to action,
                KEY_EXPECTED_PATH to path,
                KEY_EXPECTED_FINGERPRINT to fingerprint,
            )
            val request = OneTimeWorkRequestBuilder<DropboxBackupWorker>()
                .setInputData(data)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                MANUAL_WORK,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}

private class DropboxBackupRunner(
    private val context: Context,
    private val state: DropboxBackupStateStore,
    private val repository: AppBackupRepository,
) {
    suspend fun run(
        action: String,
        expectedPath: String?,
        expectedFingerprint: String?,
        accountHint: String?,
    ): androidx.work.ListenableWorker.Result {
        val stored = (DropboxCredentialStore(context).load() as? StoredCredentialResult.Available)?.credential
            ?: run {
                accountHint?.let {
                    state.markFailure(it, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
                }
                return androidx.work.ListenableWorker.Result.failure()
            }
        val verification = DropboxSdkGateway().verify(stored)
        if (verification !is DropboxVerification.Connected) {
            when (verification) {
                DropboxVerification.CredentialsInvalid -> {
                    accountHint?.let { state.markFailure(it, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION) }
                    return androidx.work.ListenableWorker.Result.failure()
                }
                DropboxVerification.TemporarilyUnavailable -> {
                    accountHint?.let { state.markFailure(it, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK) }
                    return androidx.work.ListenableWorker.Result.retry()
                }
            }
        }
        val connected = verification as DropboxVerification.Connected
        val account = connected.accountIdHash
        // Backup turned Off after this work was queued: do nothing and leave status unchanged.
        if (!state.frequency().syncAllowed) return androidx.work.ListenableWorker.Result.success()
        state.beginAttempt(account)

        try {
            val client = DbxClientV2(
                DbxRequestConfig("LocalTell/Dropbox"),
                DbxCredential(stored.accessToken, stored.expiresAt, stored.refreshToken, com.actionanand.localtell.app.BuildConfig.DROPBOX_APP_KEY),
            )
            val remote = latest(client)
            val remoteData = remote?.let { download(client, it) }
            val remoteFingerprint = remoteData?.let(AppBackupCodec::fingerprint)
            val baseline = state.baseline(account)
            val actionIsResolution = action == DropboxBackupWorker.ACTION_USE_LOCAL ||
                action == DropboxBackupWorker.ACTION_MERGE_REMOTE

            if (actionIsResolution) {
                if (remote?.path != expectedPath || (remoteFingerprint ?: "") != expectedFingerprint) {
                    state.markConflict(account, remote?.path, remoteFingerprint ?: "")
                    return androidx.work.ListenableWorker.Result.success()
                }
                if (action == DropboxBackupWorker.ACTION_MERGE_REMOTE && remoteData != null && remoteFingerprint != null) {
                    repository.mergeKeepingAllPlaces(remoteData)
                    // This device now contains the Dropbox snapshot, so later trimming is a local change, not a new conflict.
                    state.adoptBaseline(account, remoteFingerprint)
                }
                val localData = repository.snapshot()
                if (pauseForSavedPlacesLimit(localData, account)) return androidx.work.ListenableWorker.Result.success()
                uploadAndVerify(client, localData, account)
                return androidx.work.ListenableWorker.Result.success()
            }

            val localData = repository.snapshot()
            if (pauseForSavedPlacesLimit(localData, account)) return androidx.work.ListenableWorker.Result.success()
            val localFingerprint = AppBackupCodec.fingerprint(localData)
            val decision = DropboxSyncProtocol.decide(localFingerprint, remoteFingerprint, baseline)
            when (decision) {
                DropboxSyncDecision.UP_TO_DATE -> state.markSuccess(account, localFingerprint)
                DropboxSyncDecision.UPLOAD_LOCAL -> {
                    val latestBeforeUpload = latest(client)
                    val latestData = latestBeforeUpload?.let { download(client, it) }
                    val latestFingerprint = latestData?.let(AppBackupCodec::fingerprint)
                    if (latestBeforeUpload?.path != remote?.path || latestFingerprint != remoteFingerprint) {
                        state.markConflict(account, latestBeforeUpload?.path, latestFingerprint ?: "")
                    } else {
                        uploadAndVerify(client, localData, account)
                    }
                }
                DropboxSyncDecision.CONFLICT ->
                    state.markConflict(account, remote?.path, remoteFingerprint ?: "")
            }
            return androidx.work.ListenableWorker.Result.success()
        } catch (cancelled: CancellationException) {
            state.markInterrupted(account)
            throw cancelled
        } catch (_: InvalidAccessTokenException) {
            state.markFailure(account, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
            return androidx.work.ListenableWorker.Result.failure()
        } catch (_: DbxOAuthException) {
            state.markFailure(account, DropboxBackupStatus.REAUTHORIZATION_REQUIRED, DropboxBackupError.AUTHORIZATION)
            return androidx.work.ListenableWorker.Result.failure()
        } catch (_: IOException) {
            state.markFailure(account, DropboxBackupStatus.OFFLINE, DropboxBackupError.NETWORK)
            return androidx.work.ListenableWorker.Result.retry()
        } catch (_: DbxException) {
            val online = isDropboxNetworkAvailable(context)
            state.markFailure(
                account,
                if (online) DropboxBackupStatus.FAILED else DropboxBackupStatus.OFFLINE,
                DropboxBackupError.NETWORK,
            )
            return androidx.work.ListenableWorker.Result.retry()
        } catch (_: IllegalArgumentException) {
            state.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE)
            return androidx.work.ListenableWorker.Result.failure()
        } catch (_: IllegalStateException) {
            state.markFailure(account, DropboxBackupStatus.FAILED, DropboxBackupError.STORAGE)
            return androidx.work.ListenableWorker.Result.failure()
        }
    }

    private fun pauseForSavedPlacesLimit(data: com.actionanand.localtell.app.backup.AppBackupData, account: String): Boolean {
        if (SavedPlacesOverflow.excess(data.savedPlaces.size) == 0) return false
        state.markSavedLimitExceeded(account)
        return true
    }

    private fun uploadAndVerify(client: DbxClientV2, data: com.actionanand.localtell.app.backup.AppBackupData, account: String) {
        val contents = AppBackupCodec.encode(data).toByteArray(Charsets.UTF_8)
        val name = BackupFileFormat.filename()
        val path = "/$name"
        client.files().uploadBuilder(path).withMode(WriteMode.ADD).uploadAndFinish(contents.inputStream())
        val saved = download(client, DropboxRemoteSnapshot(path, name, Date()))
        require(AppBackupCodec.fingerprint(saved) == AppBackupCodec.fingerprint(data)) {
            "Dropbox upload verification did not match the local backup"
        }
        state.markSuccess(account, AppBackupCodec.fingerprint(data))
        pruneOldSnapshots(client, path)
    }

    /** Runs only after the new snapshot was verified. Failures are harmless and retried after the next backup. */
    private fun pruneOldSnapshots(client: DbxClientV2, justUploadedPath: String) {
        try {
            DropboxBackupRetention.obsolete(snapshots(client), justUploadedPath).forEach { old ->
                client.files().deleteV2(old.path)
            }
        } catch (_: DbxException) {
        } catch (_: IOException) {
        }
    }

    private fun latest(client: DbxClientV2): DropboxRemoteSnapshot? =
        snapshots(client).maxWithOrNull(compareBy<DropboxRemoteFile> { it.modifiedAtMillis }.thenBy { it.name })
            ?.let { DropboxRemoteSnapshot(it.path, it.name, Date(it.modifiedAtMillis)) }

    /** Only LocalTell-managed snapshot files in the app folder root; anything else is never read or deleted. */
    private fun snapshots(client: DbxClientV2): List<DropboxRemoteFile> {
        val snapshots = mutableListOf<DropboxRemoteFile>()
        var result = client.files().listFolder("")
        while (true) {
            result.entries.filterIsInstance<FileMetadata>()
                .filter { file ->
                    file.name.startsWith("LocalTell-") && runCatching { BackupFileFormat.requireFilename(file.name) }.isSuccess
                }
                .forEach { file -> snapshots += DropboxRemoteFile(file.pathLower, file.name, file.serverModified.time) }
            if (!result.hasMore) break
            result = client.files().listFolderContinue(result.cursor)
        }
        return snapshots
    }

    private fun download(client: DbxClientV2, snapshot: DropboxRemoteSnapshot): com.actionanand.localtell.app.backup.AppBackupData {
        val output = ByteArrayOutputStream()
        client.files().download(snapshot.path).download(output)
        val bytes = output.toByteArray()
        return BackupFileFormat.decode(snapshot.name, bytes)
    }
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
