package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.R
import java.util.Locale

object NavigationAssistHelper {

    fun buildGoogleNavigationIntent(latitude: Double, longitude: Double): Intent {
        val coords = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        return Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$coords"))
    }

    fun buildGeoFallbackIntent(
        latitude: Double,
        longitude: Double,
        label: String?
    ): Intent {
        val coords = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        val cleanLabel = label
            ?.let { com.example.data.remote.dto.NominatimPlaceDto.stripUnsupportedGlyphs(it) }
            ?.trim()
            ?.take(80)
        val query = if (!cleanLabel.isNullOrBlank()) {
            "$coords(${Uri.encode(cleanLabel)})"
        } else {
            coords
        }
        return Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coords?q=$query"))
    }

    fun buildWebDirectionsIntent(latitude: Double, longitude: Double): Intent {
        val coords = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$coords")
        return Intent(Intent.ACTION_VIEW, uri)
    }

    fun launchTurnByTurnNavigation(
        context: Context,
        latitude: Double,
        longitude: Double,
        label: String?
    ): Boolean {
        val candidates = listOf(
            buildGoogleNavigationIntent(latitude, longitude),
            buildGeoFallbackIntent(latitude, longitude, label),
            buildWebDirectionsIntent(latitude, longitude)
        )
        for (intent in candidates) {
            val launched = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        Toast.makeText(context, R.string.nav_assist_no_nav_app, Toast.LENGTH_SHORT).show()
        return false
    }
}
