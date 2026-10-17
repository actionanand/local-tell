package com.actionanand.localtell.app.easy

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface SpeechEngine {
    fun speak(text: String): Boolean
    fun shutdown()
}

/** All engine operations, including cleanup, are serialized on a worker dispatcher. */
internal class EasySpeech(
    private val createEngine: (onReady: (Boolean) -> Unit) -> SpeechEngine,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(createEngine = { onReady ->
        val tts = TextToSpeech(context) { onReady(it == TextToSpeech.SUCCESS) }
        object : SpeechEngine {
            override fun speak(text: String): Boolean =
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "localtell-number") != TextToSpeech.ERROR

            override fun shutdown() { tts.shutdown() }
        }
    })

    private data class Engine(val speech: SpeechEngine, val ready: CompletableDeferred<Boolean>)
    private val operations = Mutex()
    private val closed = AtomicBoolean(false)
    private val disposed = CompletableDeferred<Unit>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatcher)
    // Accessed only under operations; close() never takes that lock on the UI thread.
    private var engine: Engine? = null

    suspend fun speakNumber(number: String): Boolean = withContext(dispatcher) {
        operations.withLock {
            if (closed.get()) return@withLock false
            var initialized = false
            try {
                val current = engine ?: run {
                    val ready = CompletableDeferred<Boolean>()
                    val created = Engine(createEngine { success ->
                        // Android can call onInit after shutdown/disposal. No UI callback or
                        // speech is scheduled from onInit, and late results are ignored.
                        if (!closed.get()) ready.complete(success)
                    }, ready)
                    engine = created
                    created
                }
                initialized = withTimeoutOrNull(10_000L) {
                    select<Boolean> {
                        current.ready.onAwait { it }
                        disposed.onAwait { false }
                    }
                } == true
                if (!initialized || closed.get()) return@withLock false
                // An already in-flight Binder call cannot be interrupted by close(). Cleanup
                // waits for it and shuts down before any further engine operation is allowed.
                current.speech.speak(number.filter(Char::isDigit).map { it.toString() }.joinToString(" "))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                initialized = false
                false
            } finally {
                if (!initialized || closed.get()) releaseEngine()
            }
        }
    }

    private fun releaseEngine() {
        val discarded = engine
        engine = null
        discarded?.ready?.cancel()
        runCatching { discarded?.speech?.shutdown() }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        disposed.complete(Unit) // Wake initialization immediately; do not wait ten seconds.
        cleanupScope.launch {
            try {
                operations.withLock { releaseEngine() }
            } finally {
                cleanupScope.cancel()
            }
        }
    }
}
