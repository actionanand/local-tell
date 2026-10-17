package com.actionanand.localtell.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Submit from Main. A new explicit request replaces the old one, after its cleanup completes. */
internal class HomeRequestRunner(
    private val scope: CoroutineScope,
    private val onFailure: (Exception) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val serial = Mutex()
    private var job: Job? = null

    fun submit(request: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            // Even a replacement that is itself cancelled cannot release the previous owner's
            // lock. The next request waits for that owner's non-cancellable listener cleanup.
            serial.withLock {
                try {
                    withContext(dispatcher) {
                        request()
                        ensureActive()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    ensureActive()
                    onFailure(error)
                }
            }
        }
    }
}
