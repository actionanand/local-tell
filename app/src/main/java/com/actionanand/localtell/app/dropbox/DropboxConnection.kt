package com.actionanand.localtell.app.dropbox

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.actionanand.localtell.app.BuildConfig
import com.dropbox.core.DbxException
import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.oauth.DbxOAuthException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import java.io.IOException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal enum class DropboxConnectionStatus { NOT_CONNECTED, CONNECTING, DISCONNECTING, CONNECTED, FAILED, REAUTHORIZATION_REQUIRED }

internal enum class DropboxConnectionFailure { TRANSPORT, CREDENTIAL_STORAGE, LOCAL_DISCONNECT }

internal enum class DropboxRetryAction { VERIFY_STORED, AUTHORIZE }

internal enum class DropboxConnectionNotice { DISCONNECTED, REVOCATION_UNCONFIRMED }

internal data class DropboxConnectionUiState(
    val status: DropboxConnectionStatus = DropboxConnectionStatus.NOT_CONNECTED,
    val accountName: String? = null,
    val failure: DropboxConnectionFailure? = null,
    val retryAction: DropboxRetryAction? = null,
    val notice: DropboxConnectionNotice? = null,
)

internal data class StoredDropboxCredential(
    val accessToken: String,
    val expiresAt: Long?,
    val refreshToken: String,
)

internal sealed interface StoredCredentialResult {
    data object Missing : StoredCredentialResult
    data class Available(val credential: StoredDropboxCredential) : StoredCredentialResult
    /** The encrypted value could not be read; [removed] says whether its removal persisted. */
    data class Unusable(val removed: Boolean) : StoredCredentialResult
    /** A transient Keystore/storage problem; the encrypted credential has not been changed. */
    data object TemporarilyUnavailable : StoredCredentialResult
}

internal interface DropboxCredentialPersistence {
    fun load(): StoredCredentialResult
    fun save(credential: StoredDropboxCredential): Boolean
    fun clear(): Boolean
}

internal sealed interface DropboxVerification {
    data class Connected(val accountName: String) : DropboxVerification
    data object CredentialsInvalid : DropboxVerification
    data object TemporarilyUnavailable : DropboxVerification
}

internal interface DropboxAccountGateway {
    fun verify(credential: StoredDropboxCredential): DropboxVerification
    fun revoke(credential: StoredDropboxCredential): Boolean
}

/** Encrypted private storage for Dropbox credentials. No token enters app backup data. */
internal class DropboxCredentialStore(context: Context) : DropboxCredentialPersistence {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): StoredCredentialResult {
        val encoded = preferences.getString(CREDENTIAL, null) ?: return StoredCredentialResult.Missing
        return try {
            val json = JSONObject(decrypt(encoded))
            val credential = StoredDropboxCredential(
                accessToken = json.getString("accessToken"),
                expiresAt = json.optLong("expiresAt").takeIf { json.has("expiresAt") },
                refreshToken = json.getString("refreshToken"),
            )
            require(credential.accessToken.isNotBlank() && credential.refreshToken.isNotBlank())
            StoredCredentialResult.Available(credential)
        } catch (_: KeyPermanentlyInvalidatedException) {
            discardPermanentlyInvalidatedKey()
            StoredCredentialResult.Unusable(removed = clear())
        } catch (_: AEADBadTagException) {
            // Authentication-tag failure proves that this persisted ciphertext is not trustworthy.
            StoredCredentialResult.Unusable(removed = clear())
        } catch (_: org.json.JSONException) {
            StoredCredentialResult.Unusable(removed = clear())
        } catch (_: IllegalArgumentException) {
            // Includes malformed Base64/nonce data from the persisted value.
            StoredCredentialResult.Unusable(removed = clear())
        } catch (_: Exception) {
            // Keystore/provider failures can be temporary; do not destroy a valid refresh token.
            StoredCredentialResult.TemporarilyUnavailable
        }
    }

    override fun save(credential: StoredDropboxCredential): Boolean {
        require(credential.accessToken.isNotBlank() && credential.refreshToken.isNotBlank())
        return try {
            val json = JSONObject().put("accessToken", credential.accessToken)
                .put("refreshToken", credential.refreshToken)
            credential.expiresAt?.let { json.put("expiresAt", it) }
            preferences.edit().putString(CREDENTIAL, encrypt(json.toString())).commit()
        } catch (_: Exception) {
            false
        }
    }

    override fun clear(): Boolean = try {
        preferences.edit().remove(CREDENTIAL).commit()
    } catch (_: Exception) {
        false
    }

    private fun discardPermanentlyInvalidatedKey() {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // The encrypted preference is still cleared independently above.
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        }
        return cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)).toString(Charsets.UTF_8)
    }

    private companion object {
        const val PREFERENCES = "localtell_dropbox_credentials"
        const val CREDENTIAL = "credential"
        const val KEY_ALIAS = "localtell_dropbox_credentials_v1"
        const val IV_BYTES = 12
    }
}

/** Dropbox SDK calls live behind this boundary so credential decisions remain deterministic and testable. */
internal class DropboxSdkGateway : DropboxAccountGateway {
    override fun verify(credential: StoredDropboxCredential): DropboxVerification = try {
        val account = client(credential).users().currentAccount
        DropboxVerification.Connected(account.name.displayName)
    } catch (_: InvalidAccessTokenException) {
        DropboxVerification.CredentialsInvalid
    } catch (_: DbxOAuthException) {
        // The SDK documents this for a revoked or invalid refresh credential.
        DropboxVerification.CredentialsInvalid
    } catch (_: DbxException) {
        DropboxVerification.TemporarilyUnavailable
    } catch (_: IOException) {
        DropboxVerification.TemporarilyUnavailable
    } catch (_: RuntimeException) {
        // Do not mistake an SDK/transport failure for a revoked credential.
        DropboxVerification.TemporarilyUnavailable
    }

    override fun revoke(credential: StoredDropboxCredential): Boolean = try {
        client(credential).auth().tokenRevoke()
        true
    } catch (_: Exception) {
        false
    }

    private fun client(stored: StoredDropboxCredential): DbxClientV2 = DbxClientV2(
        DbxRequestConfig("LocalTell/Dropbox"),
        DbxCredential(stored.accessToken, stored.expiresAt, stored.refreshToken, BuildConfig.DROPBOX_APP_KEY),
    )
}

/**
 * Generation IDs make stale startup/OAuth results harmless. The mutex serializes credential
 * mutations without blocking Compose; callers run these suspend functions on Dispatchers.IO.
 */
internal class DropboxConnectionLifecycle(
    private val credentials: DropboxCredentialPersistence,
    private val gateway: DropboxAccountGateway,
) {
    private val stateLock = Any()
    private val credentialOperations = Mutex()
    private var generation = 0L
    private val mutableState = MutableStateFlow(DropboxConnectionUiState())
    val state = mutableState.asStateFlow()

    fun beginStoredVerification(): Long = begin(DropboxConnectionStatus.CONNECTING)

    fun beginNewAuthorization(): Long = begin(DropboxConnectionStatus.CONNECTING)

    fun beginDisconnect(): Long = begin(DropboxConnectionStatus.DISCONNECTING)

    fun authorizationFailed(operation: Long) = publishIfCurrent(
        operation,
        DropboxConnectionUiState(
            DropboxConnectionStatus.FAILED,
            failure = DropboxConnectionFailure.TRANSPORT,
            retryAction = DropboxRetryAction.AUTHORIZE,
        ),
    )

    suspend fun verifyStored(operation: Long) = credentialOperations.withLock {
        when (val stored = credentials.load()) {
            StoredCredentialResult.Missing -> publishIfCurrent(operation, DropboxConnectionUiState())
            is StoredCredentialResult.Unusable -> publishIfCurrent(
                operation,
                if (stored.removed) {
                    DropboxConnectionUiState(DropboxConnectionStatus.REAUTHORIZATION_REQUIRED)
                } else {
                    DropboxConnectionUiState(
                        DropboxConnectionStatus.FAILED,
                        failure = DropboxConnectionFailure.CREDENTIAL_STORAGE,
                        retryAction = DropboxRetryAction.AUTHORIZE,
                    )
                },
            )
            StoredCredentialResult.TemporarilyUnavailable -> publishIfCurrent(
                operation,
                DropboxConnectionUiState(
                    DropboxConnectionStatus.FAILED,
                    failure = DropboxConnectionFailure.CREDENTIAL_STORAGE,
                    retryAction = DropboxRetryAction.VERIFY_STORED,
                ),
            )
            is StoredCredentialResult.Available -> verifyExisting(operation, stored.credential)
        }
    }

    suspend fun saveAndVerifyNew(operation: Long, credential: StoredDropboxCredential) = credentialOperations.withLock {
        if (!isCurrent(operation)) return@withLock
        if (!credentials.save(credential)) {
            publishIfCurrent(
                operation,
                DropboxConnectionUiState(
                    DropboxConnectionStatus.FAILED,
                    failure = DropboxConnectionFailure.CREDENTIAL_STORAGE,
                    retryAction = DropboxRetryAction.AUTHORIZE,
                ),
            )
            return@withLock
        }
        // A disconnect/new authorization begun while persistence ran wins over this operation.
        if (!isCurrent(operation)) {
            credentials.clear()
            return@withLock
        }
        verifyExisting(operation, credential)
    }

    suspend fun disconnect(operation: Long) = credentialOperations.withLock {
        val credential = (credentials.load() as? StoredCredentialResult.Available)?.credential
        val revoked = credential?.let(gateway::revoke) ?: true
        if (!isCurrent(operation)) return@withLock
        if (!credentials.clear()) {
            publishIfCurrent(
                operation,
                DropboxConnectionUiState(
                    DropboxConnectionStatus.FAILED,
                    failure = DropboxConnectionFailure.LOCAL_DISCONNECT,
                    retryAction = DropboxRetryAction.VERIFY_STORED,
                ),
            )
            return@withLock
        }
        publishIfCurrent(
            operation,
            DropboxConnectionUiState(
                notice = if (revoked) DropboxConnectionNotice.DISCONNECTED else DropboxConnectionNotice.REVOCATION_UNCONFIRMED,
            ),
        )
    }

    private fun verifyExisting(operation: Long, credential: StoredDropboxCredential) {
        if (!isCurrent(operation)) return
        when (val result = gateway.verify(credential)) {
            is DropboxVerification.Connected -> publishIfCurrent(
                operation,
                DropboxConnectionUiState(DropboxConnectionStatus.CONNECTED, accountName = result.accountName),
            )
            DropboxVerification.TemporarilyUnavailable -> publishIfCurrent(
                operation,
                DropboxConnectionUiState(
                    DropboxConnectionStatus.FAILED,
                    failure = DropboxConnectionFailure.TRANSPORT,
                    retryAction = DropboxRetryAction.VERIFY_STORED,
                ),
            )
            DropboxVerification.CredentialsInvalid -> {
                if (!isCurrent(operation)) return
                if (credentials.clear()) {
                    publishIfCurrent(operation, DropboxConnectionUiState(DropboxConnectionStatus.REAUTHORIZATION_REQUIRED))
                } else {
                    publishIfCurrent(
                        operation,
                        DropboxConnectionUiState(
                            DropboxConnectionStatus.FAILED,
                            failure = DropboxConnectionFailure.CREDENTIAL_STORAGE,
                            retryAction = DropboxRetryAction.VERIFY_STORED,
                        ),
                    )
                }
            }
        }
    }

    private fun begin(status: DropboxConnectionStatus): Long = synchronized(stateLock) {
        generation += 1
        mutableState.value = DropboxConnectionUiState(status)
        generation
    }

    private fun isCurrent(operation: Long): Boolean = synchronized(stateLock) { operation == generation }

    private fun publishIfCurrent(operation: Long, state: DropboxConnectionUiState) {
        synchronized(stateLock) { if (operation == generation) mutableState.value = state }
    }
}

/** OAuth result ownership stays in the Activity; account and keystore work stays off the UI thread. */
internal class DropboxConnectionViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val lifecycle = DropboxConnectionLifecycle(DropboxCredentialStore(app), DropboxSdkGateway())
    val state = lifecycle.state
    private var awaitingAuthorization: Boolean
        get() = savedState[AUTHORIZATION_PENDING] ?: false
        set(value) { savedState[AUTHORIZATION_PENDING] = value }

    init {
        if (awaitingAuthorization) {
            savedState[AUTHORIZATION_OPERATION] = lifecycle.beginNewAuthorization()
        } else {
            val operation = lifecycle.beginStoredVerification()
            launchIo { lifecycle.verifyStored(operation) }
        }
    }

    fun beginAuthorization(): Boolean {
        if (awaitingAuthorization || state.value.status == DropboxConnectionStatus.DISCONNECTING) return false
        savedState[AUTHORIZATION_OPERATION] = lifecycle.beginNewAuthorization()
        awaitingAuthorization = true
        return true
    }

    fun authorizationLaunchFailed() {
        if (!awaitingAuthorization) return
        awaitingAuthorization = false
        authorizationOperation()?.let(lifecycle::authorizationFailed)
        savedState.remove<Long>(AUTHORIZATION_OPERATION)
    }

    fun consumeAuthorizationResult(): Boolean = awaitingAuthorization.also { awaitingAuthorization = false }

    fun completeAuthorization(credential: DbxCredential?, grantedScope: String?) {
        val operation = authorizationOperation() ?: return
        savedState.remove<Long>(AUTHORIZATION_OPERATION)
        if (!DropboxAuthorization.canAcceptOAuthResult(credential != null, credential?.refreshToken, grantedScope)) {
            lifecycle.authorizationFailed(operation)
            return
        }
        val accepted = requireNotNull(credential)
        launchIo {
            lifecycle.saveAndVerifyNew(
                operation,
                StoredDropboxCredential(accepted.accessToken, accepted.expiresAt, requireNotNull(accepted.refreshToken)),
            )
        }
    }

    fun retry() {
        if (state.value.status != DropboxConnectionStatus.FAILED) return
        val operation = lifecycle.beginStoredVerification()
        launchIo { lifecycle.verifyStored(operation) }
    }

    fun disconnect() {
        awaitingAuthorization = false
        savedState.remove<Long>(AUTHORIZATION_OPERATION)
        val operation = lifecycle.beginDisconnect()
        launchIo { lifecycle.disconnect(operation) }
    }

    private fun launchIo(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun authorizationOperation(): Long? = savedState[AUTHORIZATION_OPERATION]

    private companion object {
        const val AUTHORIZATION_PENDING = "dropbox_authorization_pending"
        const val AUTHORIZATION_OPERATION = "dropbox_authorization_operation"
    }
}
