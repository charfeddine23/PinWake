package com.example.util

import android.content.Context

enum class OpenFreeMapStyle(val styleUrl: String, val isDark: Boolean) {
    GOOGLE_DARK(OpenFreeMapStyles.OPENFREEMAP_LIBERTY_URL, true),
    GOOGLE_LIGHT(OpenFreeMapStyles.OPENFREEMAP_LIBERTY_URL, false)
}

object OpenFreeMapStyles {

    const val OPENFREEMAP_LIBERTY_URL = "https://tiles.openfreemap.org/styles/liberty"

    private const val PREFS_NAME = "pin_wake_prefs"
    private const val KEY_VECTOR_STYLE = "openfreemap_vector_style"
    private const val KEY_3D_BUILDINGS = "openfreemap_3d_buildings"

    fun getSavedStyle(context: Context): OpenFreeMapStyle {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VECTOR_STYLE, OpenFreeMapStyle.GOOGLE_DARK.name)
        return when (raw) {
            OpenFreeMapStyle.GOOGLE_DARK.name, "DARK", "FIORD" -> OpenFreeMapStyle.GOOGLE_DARK
            OpenFreeMapStyle.GOOGLE_LIGHT.name, "LIBERTY", "POSITRON" -> OpenFreeMapStyle.GOOGLE_LIGHT
            else -> OpenFreeMapStyle.GOOGLE_DARK
        }
    }

    fun saveStyle(context: Context, style: OpenFreeMapStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VECTOR_STYLE, style.name)
            .apply()
    }

    fun is3dBuildingsEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_3D_BUILDINGS, true)
    }

    fun set3dBuildingsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_3D_BUILDINGS, enabled)
            .apply()
    }
}
