package com.actionanand.localtell.app

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/** The small, stable set of app languages. Add future languages here and in resources. */
enum class AppLanguage(val preferenceValue: String, val languageTag: String?) {
    SYSTEM("system", null),
    TAMIL("ta", "ta"),
    ENGLISH("en", "en"),
    SANSKRIT("sa", "sa"),
    ;

    companion object {
        fun fromPreference(value: String?): AppLanguage =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM

        fun fromLanguageTag(value: String?): AppLanguage = when (value?.substringBefore('-')?.lowercase()) {
            "en" -> ENGLISH
            "ta" -> TAMIL
            "sa" -> SANSKRIT
            else -> SYSTEM
        }
    }
}

/**
 * Keeps the selected app locale in one place while using Android's own per-app
 * locale feature when it is available.
 */
object AppLanguageManager {
    private const val PREFERENCES = "localtell_preferences"
    private const val LANGUAGE_KEY = "app_language"

    fun current(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.fromLanguageTag(locales[0]?.toLanguageTag())
        }
        return AppLanguage.fromPreference(preferences(context).getString(LANGUAGE_KEY, null))
    }

    fun apply(context: Context, language: AppLanguage) {
        preferences(context).edit().putString(LANGUAGE_KEY, language.preferenceValue).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language.languageTag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.languageTag)
        }
    }

    /** Applies the persisted override before activities inflate their resources on API 26–32. */
    fun localizedContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = AppLanguage.fromPreference(preferences(base).getString(LANGUAGE_KEY, null))
        val tag = language.languageTag ?: return base
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return base.createConfigurationContext(configuration)
    }

    fun getString(context: Context, @StringRes resId: Int, vararg args: Any): String =
        localizedContext(context).getString(resId, *args)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
