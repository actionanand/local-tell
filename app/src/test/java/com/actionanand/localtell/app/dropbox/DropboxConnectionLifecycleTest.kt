package com.actionanand.localtell.app.dropbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropboxConnectionLifecycleTest {
    @Test fun `disconnect invalidates backup session before asynchronous revocation`() {
        var invalidated = false
        val lifecycle = DropboxConnectionLifecycle(FakeStore(credential), FakeGateway(DropboxVerification.Connected("A")),
            invalidateBackupSession = { invalidated = true })
        lifecycle.beginDisconnect()
        assertTrue(invalidated)
        assertEquals(DropboxConnectionStatus.DISCONNECTING, lifecycle.state.value.status)
    }

    @Test fun `account switch invalidates old session and activates only verified new account`() = runBlocking {
        val events = mutableListOf<String>()
        val lifecycle = DropboxConnectionLifecycle(FakeStore(), FakeGateway(DropboxVerification.Connected("B", "account-B")),
            invalidateBackupSession = { events += "invalidated" },
            activateBackupSession = { account, _ -> events += account })
        val operation = lifecycle.beginNewAuthorization()
        assertEquals(listOf("invalidated"), events)
        lifecycle.saveAndVerifyNew(operation, credential)
        assertEquals(listOf("invalidated", "account-B"), events)
    }

    @Test fun `session persistence failure is reported instead of claiming connected`() = runBlocking {
        val lifecycle = DropboxConnectionLifecycle(FakeStore(credential), FakeGateway(DropboxVerification.Connected("A")),
            activateBackupSession = { _, _ -> error("Storage failure") })
        lifecycle.verifyStored(lifecycle.beginStoredVerification())
        assertEquals(DropboxConnectionStatus.FAILED, lifecycle.state.value.status)
        assertEquals(DropboxConnectionFailure.CREDENTIAL_STORAGE, lifecycle.state.value.failure)
    }

    @Test fun `offline startup preserves stored credentials and offers retry`() = runBlocking {
        val store = FakeStore(credential)
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.TemporarilyUnavailable))

        lifecycle.verifyStored(lifecycle.beginStoredVerification())

        assertEquals(DropboxConnectionStatus.FAILED, lifecycle.state.value.status)
        assertEquals(DropboxConnectionFailure.TRANSPORT, lifecycle.state.value.failure)
        assertEquals(DropboxRetryAction.VERIFY_STORED, lifecycle.state.value.retryAction)
        assertEquals(0, store.clearCalls)
        assertEquals(credential, store.credential)
    }

    @Test fun `offline verification retry uses the encrypted existing credential`() = runBlocking {
        val store = FakeStore(credential)
        val gateway = FakeGateway(DropboxVerification.TemporarilyUnavailable)
        val lifecycle = DropboxConnectionLifecycle(store, gateway)

        lifecycle.verifyStored(lifecycle.beginStoredVerification())
        gateway.result = DropboxVerification.Connected("Recovered account")
        lifecycle.verifyStored(lifecycle.beginStoredVerification())

        assertEquals(DropboxConnectionStatus.CONNECTED, lifecycle.state.value.status)
        assertEquals("Recovered account", lifecycle.state.value.accountName)
        assertEquals(credential, store.credential)
    }

    @Test fun `network timeout while verifying a newly authorized account keeps refresh credential`() = runBlocking {
        val store = FakeStore()
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.TemporarilyUnavailable))

        lifecycle.saveAndVerifyNew(lifecycle.beginNewAuthorization(), credential)

        assertEquals(DropboxConnectionStatus.FAILED, lifecycle.state.value.status)
        assertEquals(credential, store.credential)
        assertEquals(0, store.clearCalls)
    }

    @Test fun `confirmed revoked credentials are cleared and require reauthorization`() = runBlocking {
        val store = FakeStore(credential)
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.CredentialsInvalid))

        lifecycle.verifyStored(lifecycle.beginStoredVerification())

        assertEquals(DropboxConnectionStatus.REAUTHORIZATION_REQUIRED, lifecycle.state.value.status)
        assertEquals(1, store.clearCalls)
        assertEquals(null, store.credential)
    }

    @Test fun `confirmed invalid access token is treated as an authorization error`() = runBlocking {
        val store = FakeStore(credential)
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.CredentialsInvalid))

        lifecycle.verifyStored(lifecycle.beginStoredVerification())

        assertEquals(DropboxConnectionStatus.REAUTHORIZATION_REQUIRED, lifecycle.state.value.status)
        assertEquals(1, store.clearCalls)
    }

    @Test fun `rate limit result preserves saved credentials`() = runBlocking {
        val store = FakeStore(credential)
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.TemporarilyUnavailable))

        lifecycle.verifyStored(lifecycle.beginStoredVerification())

        assertEquals(DropboxRetryAction.VERIFY_STORED, lifecycle.state.value.retryAction)
        assertEquals(credential, store.credential)
        assertEquals(0, store.clearCalls)
    }

    @Test fun `failed encrypted credential save never reports connected`() = runBlocking {
        val store = FakeStore(saveResult = false)
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.Connected("A")))

        lifecycle.saveAndVerifyNew(lifecycle.beginNewAuthorization(), credential)

        assertEquals(DropboxConnectionStatus.FAILED, lifecycle.state.value.status)
        assertEquals(DropboxConnectionFailure.CREDENTIAL_STORAGE, lifecycle.state.value.failure)
        assertEquals(0, store.clearCalls)
    }

    @Test fun `successful remote revocation with failed local removal reports incomplete local disconnect`() = runBlocking {
        val store = FakeStore(credential, clearResult = false)
        val gateway = FakeGateway(DropboxVerification.Connected("A"))
        val lifecycle = DropboxConnectionLifecycle(store, gateway)

        lifecycle.disconnect(lifecycle.beginDisconnect())

        assertEquals(DropboxConnectionStatus.FAILED, lifecycle.state.value.status)
        assertEquals(DropboxConnectionFailure.LOCAL_DISCONNECT, lifecycle.state.value.failure)
        assertEquals(1, gateway.revokeCalls)
        assertEquals(credential, store.credential)
    }

    @Test fun `unusable encrypted credentials require reauthorization only after their removal persists`() = runBlocking {
        val lifecycle = DropboxConnectionLifecycle(UnusableStore(removed = true), FakeGateway(DropboxVerification.Connected("A")))

        lifecycle.verifyStored(lifecycle.beginStoredVerification())

        assertEquals(DropboxConnectionStatus.REAUTHORIZATION_REQUIRED, lifecycle.state.value.status)
    }

    @Test fun `unusable encrypted credentials with failed cleanup surface a local storage error`() = runBlocking {
        val lifecycle = DropboxConnectionLifecycle(UnusableStore(removed = false), FakeGateway(DropboxVerification.Connected("A")))

        lifecycle.verifyStored(lifecycle.beginStoredVerification())

        assertEquals(DropboxConnectionFailure.CREDENTIAL_STORAGE, lifecycle.state.value.failure)
        assertEquals(DropboxRetryAction.AUTHORIZE, lifecycle.state.value.retryAction)
    }

    @Test fun `recoverable Keystore failure keeps credentials available for a later retry`() = runBlocking {
        val lifecycle = DropboxConnectionLifecycle(TemporaryStore(), FakeGateway(DropboxVerification.Connected("A")))

        lifecycle.verifyStored(lifecycle.beginStoredVerification())

        assertEquals(DropboxConnectionFailure.CREDENTIAL_STORAGE, lifecycle.state.value.failure)
        assertEquals(DropboxRetryAction.VERIFY_STORED, lifecycle.state.value.retryAction)
    }

    @Test fun `new authorization prevents obsolete initialization from overwriting its state`() = runBlocking {
        val store = FakeStore(credential)
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.Connected("Old account")))
        val initialization = lifecycle.beginStoredVerification()
        val authorization = lifecycle.beginNewAuthorization()

        lifecycle.verifyStored(initialization)
        lifecycle.saveAndVerifyNew(authorization, newCredential)

        assertEquals(DropboxConnectionStatus.CONNECTED, lifecycle.state.value.status)
        assertEquals("Old account", lifecycle.state.value.accountName)
        assertEquals(newCredential, store.credential)
    }

    @Test fun `generation changes for every operation and stale result cannot publish`() = runBlocking {
        val lifecycle = DropboxConnectionLifecycle(FakeStore(credential), FakeGateway(DropboxVerification.Connected("A")))
        val first = lifecycle.beginStoredVerification()
        val second = lifecycle.beginNewAuthorization()
        val third = lifecycle.beginDisconnect()

        assertEquals(first + 1, second)
        assertEquals(second + 1, third)
        lifecycle.authorizationFailed(first)
        assertEquals(DropboxConnectionStatus.DISCONNECTING, lifecycle.state.value.status)
    }

    @Test fun `disconnect during in-flight verification cannot be undone by the old operation`() = runBlocking {
        val store = FakeStore(credential)
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.Connected("Account")))
        val verification = lifecycle.beginStoredVerification()
        val disconnect = lifecycle.beginDisconnect()

        lifecycle.verifyStored(verification)
        lifecycle.disconnect(disconnect)

        assertEquals(DropboxConnectionStatus.NOT_CONNECTED, lifecycle.state.value.status)
        assertEquals(null, store.credential)
        assertEquals(1, store.clearCalls)
    }

    @Test fun `cancelled authorization leaves retryable failure and does not alter stored credentials`() = runBlocking {
        val store = FakeStore(credential)
        val lifecycle = DropboxConnectionLifecycle(store, FakeGateway(DropboxVerification.Connected("Account")))

        val authorization = lifecycle.beginNewAuthorization()
        lifecycle.authorizationFailed(authorization)

        assertEquals(DropboxConnectionStatus.FAILED, lifecycle.state.value.status)
        assertEquals(DropboxRetryAction.AUTHORIZE, lifecycle.state.value.retryAction)
        assertEquals(credential, store.credential)
        assertFalse(store.saveCalls > 0)
        assertTrue(store.clearCalls == 0)
    }

    private class FakeStore(
        initial: StoredDropboxCredential? = null,
        private val saveResult: Boolean = true,
        private val clearResult: Boolean = true,
    ) : DropboxCredentialPersistence {
        var credential = initial
        var saveCalls = 0
        var clearCalls = 0

        override fun load(): StoredCredentialResult = credential?.let(StoredCredentialResult::Available)
            ?: StoredCredentialResult.Missing

        override fun save(credential: StoredDropboxCredential): Boolean {
            saveCalls++
            if (saveResult) this.credential = credential
            return saveResult
        }

        override fun clear(): Boolean {
            clearCalls++
            if (clearResult) credential = null
            return clearResult
        }
    }

    private class FakeGateway(var result: DropboxVerification) : DropboxAccountGateway {
        var revokeCalls = 0
        override fun verify(credential: StoredDropboxCredential): DropboxVerification = result
        override fun revoke(credential: StoredDropboxCredential): Boolean {
            revokeCalls++
            return true
        }
    }

    private class UnusableStore(private val removed: Boolean) : DropboxCredentialPersistence {
        override fun load(): StoredCredentialResult = StoredCredentialResult.Unusable(removed)
        override fun save(credential: StoredDropboxCredential): Boolean = true
        override fun clear(): Boolean = removed
    }

    private class TemporaryStore : DropboxCredentialPersistence {
        override fun load(): StoredCredentialResult = StoredCredentialResult.TemporarilyUnavailable
        override fun save(credential: StoredDropboxCredential): Boolean = true
        override fun clear(): Boolean = true
    }

    private companion object {
        val credential = StoredDropboxCredential("access", 123L, "refresh")
        val newCredential = StoredDropboxCredential("new-access", 456L, "new-refresh")
    }
}
