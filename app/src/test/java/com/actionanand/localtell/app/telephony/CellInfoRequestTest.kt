package com.actionanand.localtell.app.telephony

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CellInfoRequestTest {
    @Test fun `missing callback returns cache on bounded timeout and ignores late success`() = runBlocking {
        withTimeout(5_000L) {
            lateinit var deliver: (Result<List<Int>>) -> Unit
            var cacheReads = 0
            val value = cellInfoOrCached<List<Int>>(30L, request = { deliver = it }, cached = { cacheReads++; listOf(7) })
            assertEquals(listOf(7), value)
            deliver(Result.success(listOf(9)))
            deliver(Result.failure(IllegalStateException()))
            assertEquals(1, cacheReads)
        }
    }

    @Test fun `error callback and thrown request both use cache once`() = runBlocking {
        var cacheReads = 0
        assertEquals(listOf(7), cellInfoOrCached<List<Int>>(1_000L, request = {
            it(Result.failure(SecurityException()))
            it(Result.success(listOf(9)))
        }, cached = { cacheReads++; listOf(7) }))
        assertEquals(listOf(7), cellInfoOrCached<List<Int>>(1_000L, request = {
            throw IllegalStateException("Binder failed")
        }, cached = { cacheReads++; listOf(7) }))
        assertEquals(2, cacheReads)
    }

    @Test fun `successful empty cell list is valid and does not fall back`() = runBlocking {
        assertEquals(emptyList<Int>(), cellInfoOrCached<List<Int>>(1_000L, request = {
            it(Result.success(emptyList()))
            it(Result.failure(IllegalStateException()))
        }, cached = { error("No cache read on successful callback") }))
    }

    @Test fun `external cancellation does not read cache or accept late callbacks`() = runBlocking {
        withTimeout(5_000L) {
            val entered = CompletableDeferred<(Result<List<Int>>) -> Unit>()
            val cacheReads = AtomicInteger()
            val request = async {
                cellInfoOrCached<List<Int>>(1_000L, request = { entered.complete(it) }, cached = { cacheReads.incrementAndGet(); emptyList() })
            }
            val deliver = entered.await()
            request.cancelAndJoin()
            deliver(Result.success(listOf(1)))
            assertEquals(0, cacheReads.get())
        }
    }
}
