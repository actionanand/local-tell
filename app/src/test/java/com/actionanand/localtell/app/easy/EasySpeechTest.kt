package com.actionanand.localtell.app.easy

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EasySpeechTest {
    @Test fun `first speech lazily initializes once and preserves digit speech`() = runBlocking {
        withTimeout(5_000L) {
            val created = AtomicInteger()
            val stopped = CompletableDeferred<Unit>()
            val text = Collections.synchronizedList(mutableListOf<String>())
            val speech = EasySpeech(createEngine = { ready ->
                created.incrementAndGet()
                ready(true) // Include the synchronous initialization callback case.
                object : SpeechEngine {
                    override fun speak(value: String): Boolean { text.add(value); return true }
                    override fun shutdown() { stopped.complete(Unit) }
                }
            })
            try {
                assertEquals(0, created.get())
                assertTrue(speech.speakNumber("1 234-56"))
                assertTrue(speech.speakNumber("789"))
                assertEquals(1, created.get())
                assertEquals(listOf("1 2 3 4 5 6", "7 8 9"), text.toList())
            } finally { speech.close() }
            stopped.await()
            assertFalse(speech.speakNumber("1"))
            assertEquals(1, created.get())
        }
    }

    @Test fun `dispose wakes initialization and ignores late ready callback`() = runBlocking {
        withTimeout(5_000L) {
            val callback = CompletableDeferred<(Boolean) -> Unit>()
            val stopped = CompletableDeferred<Unit>()
            val spoken = AtomicInteger()
            val speech = EasySpeech(createEngine = { ready ->
                callback.complete(ready)
                object : SpeechEngine {
                    override fun speak(text: String): Boolean { spoken.incrementAndGet(); return true }
                    override fun shutdown() { stopped.complete(Unit) }
                }
            })
            try {
                val request = async { speech.speakNumber("123") }
                val ready = callback.await()
                speech.close()
                assertFalse(request.await())
                stopped.await()
                ready(true)
                assertFalse(speech.speakNumber("456"))
                assertEquals(0, spoken.get())
            } finally { speech.close() }
        }
    }

    @Test fun `caller cancellation during init closes engine without later speech`() = runBlocking {
        withTimeout(5_000L) {
            val callback = CompletableDeferred<(Boolean) -> Unit>()
            val stopped = CompletableDeferred<Unit>()
            val speech = EasySpeech(createEngine = { ready ->
                callback.complete(ready)
                object : SpeechEngine {
                    override fun speak(text: String): Boolean = error("Cancelled speech")
                    override fun shutdown() { stopped.complete(Unit) }
                }
            })
            try {
                val request = async { speech.speakNumber("123") }
                val ready = callback.await()
                request.cancelAndJoin()
                stopped.await()
                ready(true)
            } finally { speech.close() }
        }
    }

    @Test fun `disposal does not block caller or shut down during in-flight speech`() = runBlocking {
        withTimeout(5_000L) {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val inSpeech = AtomicBoolean(false)
            val stopped = CompletableDeferred<Boolean>()
            val speech = EasySpeech(createEngine = { ready ->
                ready(true)
                object : SpeechEngine {
                    override fun speak(text: String): Boolean {
                        inSpeech.set(true)
                        entered.complete(Unit)
                        check(release.await(3, TimeUnit.SECONDS))
                        inSpeech.set(false)
                        return true
                    }
                    override fun shutdown() { stopped.complete(!inSpeech.get()) }
                }
            })
            val request = async { speech.speakNumber("123") }
            try {
                entered.await()
                speech.close() // Must return even while the fake Binder call is blocked.
                assertFalse(stopped.isCompleted)
            } finally {
                release.countDown()
                speech.close()
            }
            request.await()
            assertTrue(stopped.await())
            assertFalse(speech.speakNumber("456"))
        }
    }
}
