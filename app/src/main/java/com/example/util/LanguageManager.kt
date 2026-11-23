package com.example.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object LanguageManager {
    private const val PREFS_NAME = "pin_wake_prefs"
    private const val KEY_LANG = "app_lang"

    fun getCurrentLanguageCode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANG, null)
        if (!saved.isNullOrBlank()) return saved
        val currentLocale = AppCompatDelegate.getApplicationLocales()[0]
        return currentLocale?.language ?: "en"
    }

    fun applySavedLanguage(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANG, null)
        if (!saved.isNullOrBlank()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(saved))
        }
    }

    fun setLanguage(context: Context, languageCode: String): String {
        val normalized = if (languageCode == "ar") "ar" else "en"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, normalized)
            .apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
        return normalized
    }

    fun toggleLanguage(context: Context): String {
        val current = getCurrentLanguageCode(context)
        val next = if (current == "ar") "en" else "ar"
        return setLanguage(context, next)
    }
}
