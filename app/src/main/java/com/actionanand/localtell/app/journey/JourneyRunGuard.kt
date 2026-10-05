package com.actionanand.localtell.app.journey

internal class JourneyRunGuard {
    @Volatile
    var stopRequested = true
        private set

    @Volatile
    private var generation = 0L

    @Synchronized
    fun begin(): Long {
        generation++
        stopRequested = false
        return generation
    }

    fun isActive(runGeneration: Long): Boolean =
        !stopRequested && generation == runGeneration

    @Synchronized
    fun runIfActive(runGeneration: Long, action: () -> Unit): Boolean {
        if (!isActive(runGeneration)) return false
        action()
        return true
    }

    @Synchronized
    fun stop(onStopped: () -> Unit) {
        stopRequested = true
        onStopped()
    }
}