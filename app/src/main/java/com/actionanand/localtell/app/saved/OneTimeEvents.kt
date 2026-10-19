package com.actionanand.localtell.app.saved

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Non-replaying, single-consumer event stream. Each sent event is delivered to exactly one
 * collector exactly once; a collector that starts later (for example after Activity recreation
 * for a language or theme change) never receives events that were already consumed. Events sent
 * while no collector is active are buffered and delivered once when collection resumes.
 */
internal class OneTimeEvents<T> {
    private val channel = Channel<T>(Channel.BUFFERED)
    val events: Flow<T> = channel.receiveAsFlow()

    fun send(event: T) {
        channel.trySend(event)
    }
}
