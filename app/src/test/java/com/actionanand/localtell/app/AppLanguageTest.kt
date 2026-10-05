package com.actionanand.localtell.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun persistedSystemMapsToSystem() = assertEquals(AppLanguage.SYSTEM, AppLanguage.fromPreference("system"))
    @Test fun persistedEnglishMapsToEnglish() = assertEquals(AppLanguage.ENGLISH, AppLanguage.fromPreference("en"))
    @Test fun persistedTamilMapsToTamil() = assertEquals(AppLanguage.TAMIL, AppLanguage.fromPreference("ta"))
    @Test fun unknownPreferenceFallsBackToSystem() = assertEquals(AppLanguage.SYSTEM, AppLanguage.fromPreference("unknown"))
    @Test fun languageTagsMapToSupportedLanguages() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromLanguageTag("en-IN"))
        assertEquals(AppLanguage.TAMIL, AppLanguage.fromLanguageTag("ta-IN"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLanguageTag("ml"))
    }
}
