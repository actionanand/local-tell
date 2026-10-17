package com.actionanand.localtell.app.location

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RegisteredLocationRequestTest {
    @Test fun `synchronous callback cannot remove before registration returns`() = runBlocking {
        var returnedFromRegistration = false
        var removed = false
        val result = awaitRegisteredLocation<String>(1_000L, register = { complete ->
            complete("fix")
            complete("duplicate")
            assertFalse(removed)
            returnedFromRegistration = true
        }, unregister = {
            assertTrue(returnedFromRegistration)
            removed = true
        })
        assertEquals("fix", result)
        assertTrue(removed)
    }

    @Test fun `cancel during blocked registration waits before removing exactly once`() = runBlocking {
        withTimeout(5_000L) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val registrationReturned = AtomicBoolean(false)
            val removals = AtomicInteger()
            val request = async(Dispatchers.IO) {
                awaitRegisteredLocation<String>(1_000L, register = {
                    entered.complete(Unit)
                    check(release.await(3, TimeUnit.SECONDS))
                    registrationReturned.set(true)
                }, unregister = {
                    check(registrationReturned.get())
                    removals.incrementAndGet()
                })
            }
            try {
                entered.await()
                request.cancel()
                delay(30L)
                assertFalse(request.isCompleted)
                assertEquals(0, removals.get())
            } finally {
                release.countDown()
            }
            request.join()
            assertEquals(1, removals.get())
        }
    }

    @Test fun `timeout removes listener and ignores later callbacks`() = runBlocking {
        lateinit var deliver: (String) -> Unit
        var removals = 0
        val result = awaitRegisteredLocation<String>(30L, register = { deliver = it }, unregister = { removals++ })
        assertNull(result)
        assertEquals(1, removals)
        deliver("late")
        assertEquals(1, removals)
    }

    @Test fun `timeout during registration does not run cleanup early`() = runBlocking {
        withTimeout(5_000L) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val removals = AtomicInteger()
            val request = async(Dispatchers.IO) {
                awaitRegisteredLocation<String>(30L, register = {
                    entered.complete(Unit)
                    check(release.await(3, TimeUnit.SECONDS))
                }, unregister = { removals.incrementAndGet() })
            }
            try {
                entered.await()
                delay(80L)
                assertEquals(0, removals.get())
                assertFalse(request.isCompleted)
            } finally { release.countDown() }
            assertNull(request.await())
            assertEquals(1, removals.get())
        }
    }

    @Test fun `cancel while awaiting fix removes listener and ignores callback`() = runBlocking {
        val entered = CompletableDeferred<(String) -> Unit>()
        val removals = AtomicInteger()
        val request = async {
            awaitRegisteredLocation<String>(1_000L, register = { entered.complete(it) }, unregister = { removals.incrementAndGet() })
        }
        val deliver = withTimeout(5_000L) { entered.await() }
        request.cancelAndJoin()
        deliver("late")
        assertEquals(1, removals.get())
    }

    @Test fun `partial registration failure still cleans up`() = runBlocking {
        var removals = 0
        try {
            awaitRegisteredLocation<String>(1_000L, register = { throw IllegalStateException("service failed") }, unregister = { removals++ })
            fail("Expected registration error")
        } catch (_: IllegalStateException) {
            assertEquals(1, removals)
        }
    }

    @Test fun `cleanup error is surfaced and does not replace registration error`() = runBlocking {
        val registrationError = IllegalStateException("registration")
        val removalError = IllegalArgumentException("removal")
        val removals = AtomicInteger()
        try {
            awaitRegisteredLocation<String>(1_000L, register = { throw registrationError }, unregister = {
                removals.incrementAndGet()
                throw removalError
            })
            fail("Expected error")
        } catch (error: IllegalStateException) {
            // Coroutine stack-trace recovery may copy an exception and put its original in
            // cause. Throwable identity and the outer copy's suppressed array are not stable.
            assertEquals(registrationError.javaClass, error.javaClass)
            assertEquals(registrationError.message, error.message)
            val primaryChain = generateSequence<Throwable>(error) { it.cause }
                .takeWhile { it.javaClass == registrationError.javaClass && it.message == registrationError.message }
                .toList()
            val cleanupErrors = primaryChain.flatMap { it.suppressed.toList() }
            assertTrue("Cleanup failure must remain suppressed on the observable primary chain", cleanupErrors.isNotEmpty())
            cleanupErrors.forEach { cleanup ->
                assertEquals(removalError.javaClass, cleanup.javaClass)
                assertEquals(removalError.message, cleanup.message)
            }
            assertEquals("Cleanup must be attempted exactly once", 1, removals.get())
            // Also guards against attaching suppression only to an intermediate recovery copy
            // that the next coroutine boundary can discard.
            assertEquals(1, registrationError.suppressed.size)
            assertEquals(removalError.javaClass, registrationError.suppressed.single().javaClass)
            assertEquals(removalError.message, registrationError.suppressed.single().message)
        }
    }
}
