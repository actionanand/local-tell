package com.actionanand.localtell.app.location

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Registration is deliberately synchronous on IO, in the same coroutine as the finally block.
 * A callback can arrive during register(), but cannot cause cleanup to overtake that call.
 * Cancellation/timeout also waits for the Binder registration call to return before cleanup.
 * A wedged system Binder call cannot be interrupted safely by a coroutine timeout.
 */
internal suspend fun <T : Any> awaitRegisteredLocation(
    timeoutMillis: Long,
    register: (complete: (T) -> Unit) -> Unit,
    unregister: () -> Unit,
): T? = withContext(Dispatchers.IO) {
    val result = CompletableDeferred<T>()
    val accepting = AtomicBoolean(true)
    var registrationAttempted = false
    var failure: Throwable? = null
    try {
        withTimeoutOrNull(timeoutMillis) {
            registrationAttempted = true
            register { value -> if (accepting.get()) result.complete(value) }
            result.await()
        }
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        accepting.set(false)
        result.cancel()
        if (registrationAttempted) {
            try {
                withContext(NonCancellable + Dispatchers.IO) { unregister() }
            } catch (cleanupError: Throwable) {
                val primary = failure
                if (primary == null) throw cleanupError
                if (primary !== cleanupError) primary.addSuppressed(cleanupError)
            }
        }
    }
}
