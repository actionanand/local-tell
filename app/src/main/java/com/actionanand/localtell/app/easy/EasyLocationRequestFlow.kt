package com.actionanand.localtell.app.easy

/**
 * Holds the single pending "Get my location" intent across permission and Location-enable
 * prompts so one tap completes the whole flow. Owned by [EasyViewModel] so it survives
 * Activity recreation. Every continuation is idempotent: at most one [Step.START_LOCATING] is
 * emitted per pending intent.
 */
internal class EasyLocationRequestFlow {
    enum class Step { NONE, REQUEST_PERMISSION, REQUEST_LOCATION_ENABLE, RECONNECT_LOCATION_ENABLE, START_LOCATING, PERMISSION_DENIED, LOCATION_DECLINED }

    private enum class Stage { IDLE, AWAITING_PERMISSION, AWAITING_LOCATION_ENABLE }

    private var stage = Stage.IDLE
    private var promptRetryAllowed = false

    val isPending: Boolean get() = stage != Stage.IDLE

    fun onTap(fineGranted: Boolean, locationEnabled: Boolean, locating: Boolean): Step {
        if (locating) return Step.NONE
        if (stage != Stage.IDLE) {
            if (fineGranted && locationEnabled) return start()
            if (!promptRetryAllowed) return Step.NONE
        }
        return advance(fineGranted, locationEnabled)
    }

    /**
     * Reconnects callbacks without launching another dialog. If Android lost the prompt, a
     * deliberate tap may re-establish it once; ordinary resume callbacks never re-arm retries.
     */
    fun onHostAttached(enablementRequestActive: Boolean = false): Step {
        if (stage == Stage.AWAITING_PERMISSION) {
            // A lost permission result must not leave the request stuck; only a tap re-asks.
            promptRetryAllowed = true
            return Step.NONE
        }
        if (stage != Stage.AWAITING_LOCATION_ENABLE) return Step.NONE
        // A restored permission result may already have launched a fresh prompt in this host.
        if (!enablementRequestActive) promptRetryAllowed = true
        return Step.RECONNECT_LOCATION_ENABLE
    }

    fun onPermissionResult(fineGranted: Boolean, locationEnabled: Boolean): Step {
        if (stage != Stage.AWAITING_PERMISSION) return Step.NONE
        if (!fineGranted) {
            reset()
            return Step.PERMISSION_DENIED
        }
        return advance(fineGranted = true, locationEnabled = locationEnabled)
    }

    fun onLocationEnabled(): Step = if (stage == Stage.AWAITING_LOCATION_ENABLE) start() else Step.NONE

    fun onLocationDeclined(): Step {
        if (stage != Stage.AWAITING_LOCATION_ENABLE) return Step.NONE
        reset()
        return Step.LOCATION_DECLINED
    }

    /** Continues after returning from Settings or recreation, when a system callback was lost. */
    fun onResume(fineGranted: Boolean, locationEnabled: Boolean, locating: Boolean): Step {
        if (stage == Stage.IDLE) return Step.NONE
        if (locating) return Step.NONE
        if (fineGranted && locationEnabled) return start()
        // The grant callback can be lost or delayed across recreation. Continue the original
        // user intent, but never launch a permission request from a lifecycle callback.
        return if (stage == Stage.AWAITING_PERMISSION && fineGranted) {
            advance(fineGranted = true, locationEnabled = false)
        } else Step.NONE
    }

    private fun advance(fineGranted: Boolean, locationEnabled: Boolean): Step = when {
        !fineGranted -> awaiting(Stage.AWAITING_PERMISSION, Step.REQUEST_PERMISSION)
        !locationEnabled -> awaiting(Stage.AWAITING_LOCATION_ENABLE, Step.REQUEST_LOCATION_ENABLE)
        else -> start()
    }

    private fun awaiting(next: Stage, step: Step): Step {
        stage = next
        promptRetryAllowed = false
        return step
    }

    private fun start(): Step {
        reset()
        return Step.START_LOCATING
    }

    private fun reset() {
        stage = Stage.IDLE
        promptRetryAllowed = false
    }
}
