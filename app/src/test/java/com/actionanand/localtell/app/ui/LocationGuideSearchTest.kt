package com.actionanand.localtell.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationGuideSearchTest {
    @Test fun searchesQuestionAnswerAndHiddenKeywordsTogether() {
        assertTrue(matchesLocationGuideQuery("GPS window offline", "Why does GPS work?", "Move near a window.", "offline satellite"))
    }

    @Test fun normalizesCaseAndWhitespace() {
        assertTrue(matchesLocationGuideQuery("  gPs\t WINDOW\n", "GPS", "near   a\nwindow", ""))
    }

    @Test fun requiresEveryWord() {
        assertFalse(matchesLocationGuideQuery("GPS window missing", "GPS window", "", ""))
    }

    @Test fun supportsTamilAndSanskrit() {
        assertTrue(matchesLocationGuideQuery("GPS சாளரம்", "GPS", "", "சாளரம் செயற்கைக்கோள்"))
        assertTrue(matchesLocationGuideQuery("GPS उपग्रह", "GPS", "", "उपग्रह वातायन"))
    }

    @Test fun clearingQueryRestoresAllItems() {
        val questions = listOf("GPS window", "train travel", "offline packs")
        assertEquals(1, questions.count { matchesLocationGuideQuery("window", it, "", "") })
        assertEquals(3, questions.count { matchesLocationGuideQuery("", it, "", "") })
        assertEquals(3, questions.count { matchesLocationGuideQuery(" \n\t ", it, "", "") })
    }

    @Test fun unmatchedQueryProducesNoResults() {
        assertFalse(matchesLocationGuideQuery("unmatched", "GPS", "satellite", "window"))
    }
}