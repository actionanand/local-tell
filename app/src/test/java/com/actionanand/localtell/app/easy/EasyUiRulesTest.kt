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
        assertTrue(UberChoiceLayout.sideBySide(widestActionPx = 300, actionCount = 2, gapPx = 30, availableWidthPx = 700))
        assertEquals(335, UberChoiceLayout.equalWidth(actionCount = 2, gapPx = 30, availableWidthPx = 700))
    }

    @Test fun uberActionsStackForLargeFontsOrNarrowScreens() {
        assertFalse(UberChoiceLayout.sideBySide(widestActionPx = 400, actionCount = 2, gapPx = 30, availableWidthPx = 700))
    }

    @Test fun singleActionAlwaysFits() {
        assertTrue(UberChoiceLayout.sideBySide(widestActionPx = 900, actionCount = 1, gapPx = 30, availableWidthPx = 100))
    }
}
