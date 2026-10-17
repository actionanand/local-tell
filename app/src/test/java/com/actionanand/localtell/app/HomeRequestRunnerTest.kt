package com.actionanand.localtell.app

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class HomeRequestRunnerTest {
    @Test fun `latest explicit refresh runs after cancelled GPS cleanup without overlap`() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val started = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val events = Collections.synchronizedList(mutableListOf<String>())
            val active = AtomicInteger()
            val failures = AtomicInteger()
            val runner = HomeRequestRunner(scope, { failures.incrementAndGet() })
            try {
                runner.submit {
                    assertEquals(1, active.incrementAndGet())
                    started.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            releaseCleanup.await()
                            active.decrementAndGet()
                            events.add("cleanup")
                        }
                    }
                }
                started.await()
                runner.submit { events.add("unexpected queued refresh") }
                cleaning.await()
                runner.submit {
                    assertEquals(1, active.incrementAndGet())
                    events.add("latest refresh")
                    active.decrementAndGet()
                    completed.complete(Unit)
                }
                releaseCleanup.complete(Unit)
                completed.await()
                assertEquals(listOf("cleanup", "latest refresh"), events.toList())
                assertEquals(0, failures.get())
            } finally {
                releaseCleanup.complete(Unit)
                scope.cancel()
            }
        }
    }

    @Test fun `choice submitted while producer is finishing is not discarded`() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val offered = CompletableDeferred<Unit>()
            val choice = CompletableDeferred<String>()
            val runner = HomeRequestRunner(scope, { choice.completeExceptionally(it) })
            try {
                runner.submit {
                    offered.complete(Unit) // Models publishing AwaitingLocationChoice.
                    awaitCancellation()
                }
                offered.await()
                runner.submit { choice.complete("approximate") }
                assertEquals("approximate", choice.await())
            } finally { scope.cancel() }
        }
    }

    @Test fun `resolver failures are reported and following assisted request still works`() = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val failure = CompletableDeferred<Exception>()
            val result = CompletableDeferred<String>()
            val runner = HomeRequestRunner(scope, { failure.complete(it) })
            try {
                runner.submit { throw IllegalStateException("resolver failed") }
                assertEquals("resolver failed", failure.await().message)
                runner.submit { result.complete("assisted") }
                assertEquals("assisted", result.await())
            } finally { scope.cancel() }
        }
    }
}
