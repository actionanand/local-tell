package com.actionanand.localtell.app.easy

/**
 * Layout rule for the Uber choice dialog: Pickup and Destination share one row with equal widths
 * when both labels fit; otherwise (large fonts, narrow screens) they stack at full width.
 * Cancel is always rendered separately below them, aligned to the end.
 */
internal object UberChoiceLayout {
    fun sideBySide(widestActionPx: Int, actionCount: Int, gapPx: Int, availableWidthPx: Int): Boolean {
        if (actionCount <= 1) return true
        return widestActionPx.toLong() * actionCount + gapPx.toLong() * (actionCount - 1) <= availableWidthPx
    }

    fun equalWidth(actionCount: Int, gapPx: Int, availableWidthPx: Int): Int =
        ((availableWidthPx - gapPx * (actionCount - 1)) / actionCount).coerceAtLeast(0)
}
