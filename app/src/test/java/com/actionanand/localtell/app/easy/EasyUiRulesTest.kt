package com.actionanand.localtell.app.easy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyUiRulesTest {
    @Test fun findIsDisabledForEmptyOrWhitespaceInput() {
        assertFalse(EasyFindInput.canSubmit("", locating = false))
        assertFalse(EasyFindInput.canSubmit("   \t ", locating = false))
        assertTrue(EasyFindInput.canSubmit("23G9B-KRPQQ-2CP83", locating = false))
        assertTrue(EasyFindInput.canSubmit(" x ", locating = false))
    }

    @Test fun findIsDisabledWhileLocating() {
        assertFalse(EasyFindInput.canSubmit("8.18, 77.41", locating = true))
    }

    @Test fun uberActionsShareOneRowWhenTheyFit() {
        assertTrue(UberChoiceLayout.sideBySide(actionContentWidthPx = 100, labelLineCounts = listOf(1, 1), hasTextOverflow = false))
        assertEquals(335, UberChoiceLayout.equalWidth(actionCount = 2, gapPx = 30, availableWidthPx = 700))
    }

    @Test fun uberActionsStaySideBySideWhenTranslationsNeedTwoLines() {
        listOf(listOf(2, 1), listOf(1, 2), listOf(2, 2)).forEach { lines ->
            assertTrue(UberChoiceLayout.sideBySide(actionContentWidthPx = 100, labelLineCounts = lines, hasTextOverflow = false))
        }
    }

    @Test fun uberActionsStackOnlyWhenEitherLabelNeedsMoreThanTwoLines() {
        listOf(listOf(3, 1), listOf(1, 3), listOf(3, 3)).forEach { lines ->
            assertFalse(UberChoiceLayout.sideBySide(actionContentWidthPx = 70, labelLineCounts = lines, hasTextOverflow = false))
        }
    }

    @Test fun uberActionsStackWhenTextOverflowsEvenWithinTwoLines() {
        assertFalse(UberChoiceLayout.sideBySide(actionContentWidthPx = 20, labelLineCounts = listOf(2, 2), hasTextOverflow = true))
    }

    @Test fun uberActionsStackRatherThanBreakAWordMidway() {
        assertTrue(UberChoiceLayout.sideBySide(actionContentWidthPx = 100, labelLineCounts = listOf(2, 1), hasTextOverflow = false, widestWordPx = 100))
        assertFalse(UberChoiceLayout.sideBySide(actionContentWidthPx = 100, labelLineCounts = listOf(2, 1), hasTextOverflow = false, widestWordPx = 101))
    }

    @Test fun uberLabelWordsSplitOnSpacesAndHyphensOnly() {
        assertEquals(listOf("Pickup", "location"), UberChoiceLayout.words("Pickup location"))
        assertEquals(listOf("ஏறும்", "இடம்"), UberChoiceLayout.words("ஏறும் இடம்"))
        assertEquals(listOf("ग्रहणस्थानम्"), UberChoiceLayout.words("ग्रहणस्थानम्"))
        assertEquals(listOf("Uber", "अनुप्रयोगे"), UberChoiceLayout.words("Uber-अनुप्रयोगे"))
    }

    @Test fun uberActionsStackWhenPaddingLeavesNoTextSpace() {
        assertFalse(UberChoiceLayout.sideBySide(actionContentWidthPx = 0, labelLineCounts = listOf(1, 1), hasTextOverflow = false))
        assertFalse(UberChoiceLayout.sideBySide(actionContentWidthPx = -5, labelLineCounts = listOf(1, 1), hasTextOverflow = false))
    }

    @Test fun equalWidthsAccountForSpacingAndNarrowConstraints() {
        assertEquals(126, UberChoiceLayout.equalWidth(actionCount = 2, gapPx = 12, availableWidthPx = 264))
        assertEquals(125, UberChoiceLayout.equalWidth(actionCount = 2, gapPx = 12, availableWidthPx = 263))
        assertEquals(0, UberChoiceLayout.equalWidth(actionCount = 2, gapPx = 12, availableWidthPx = 10))
    }
}
