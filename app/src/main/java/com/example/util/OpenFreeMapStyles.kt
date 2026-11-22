package com.example.util

import android.content.Context

enum class OpenFreeMapStyle(val styleUrl: String, val isDark: Boolean) {
    DARK("https://tiles.openfreemap.org/styles/dark", true),
    FIORD("https://tiles.openfreemap.org/styles/fiord", true),
    LIBERTY("https://tiles.openfreemap.org/styles/liberty", false),
    POSITRON("https://tiles.openfreemap.org/styles/positron", false)
}

object OpenFreeMapStyles {

    private const val PREFS_NAME = "pin_wake_prefs"
    private const val KEY_VECTOR_STYLE = "openfreemap_vector_style"

    fun getSavedStyle(context: Context): OpenFreeMapStyle {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VECTOR_STYLE, OpenFreeMapStyle.DARK.name)
        return runCatching { OpenFreeMapStyle.valueOf(raw ?: OpenFreeMapStyle.DARK.name) }
            .getOrDefault(OpenFreeMapStyle.DARK)
    }

    fun saveStyle(context: Context, style: OpenFreeMapStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VECTOR_STYLE, style.name)
            .apply()
    }
}
