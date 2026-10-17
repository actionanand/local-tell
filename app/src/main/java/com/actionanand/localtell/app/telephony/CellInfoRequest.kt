package com.actionanand.localtell.app.telephony

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** The caller owns the IO dispatcher. Late callbacks cannot convert data or read the cache. */
internal suspend fun <T : Any> cellInfoOrCached(
    timeoutMillis: Long,
    request: (complete: (Result<T>) -> Unit) -> Unit,
    cached: () -> T,
): T {
    val updated = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine<Result<T>> { continuation ->
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation { completed.set(true) }
            val complete: (Result<T>) -> Unit = { value ->
                if (completed.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(value)
                }
            }
            try {
                request(complete)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                complete(Result.failure(error))
            }
        }
    }
    currentCoroutineContext().ensureActive()
    val value = updated?.getOrNull() ?: cached()
    currentCoroutineContext().ensureActive()
    return value
}
