package com.actionanand.localtell.app.easy

/**
 * Layout rule for the Uber choice dialog: Pickup and Destination share one row with equal widths
 * when both labels fit in up to two lines at their allocated content widths without splitting a word.
 * Otherwise (large fonts, narrow screens) they stack at full width without a line limit.
 * Cancel is always rendered separately below them, aligned to the end.
 */
internal object UberChoiceLayout {
    fun sideBySide(
        actionContentWidthPx: Int,
        labelLineCounts: List<Int>,
        hasTextOverflow: Boolean,
        widestWordPx: Int = 0,
    ): Boolean =
        actionContentWidthPx > 0 && !hasTextOverflow && widestWordPx <= actionContentWidthPx &&
            labelLineCounts.all { it in 1..2 }

    /** Splits a label into the words a line breaker may wrap between (spaces and hyphens). */
    fun words(label: String): List<String> = label.split(Regex("[\\s-]+")).filter(String::isNotEmpty)

    fun equalWidth(actionCount: Int, gapPx: Int, availableWidthPx: Int): Int =
        ((availableWidthPx - gapPx * (actionCount - 1)) / actionCount).coerceAtLeast(0)
}
