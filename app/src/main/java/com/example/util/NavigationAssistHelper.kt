package com.example.util

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import com.example.R
import com.example.data.remote.dto.NominatimPlaceDto
import com.example.ui.nav.InAppNavigationActivity

object NavigationAssistHelper {

    fun buildInAppNavigationIntent(
        context: Context,
        latitude: Double,
        longitude: Double,
        label: String?,
        alarmId: Long = -1L,
        radiusMeters: Int = 500
    ): Intent {
        val cleanLabel = label
            ?.let { NominatimPlaceDto.stripUnsupportedGlyphs(it) }
            ?.trim()
            ?.take(80)
            .orEmpty()
        return Intent(context, InAppNavigationActivity::class.java).apply {
            putExtra(InAppNavigationActivity.EXTRA_DEST_LAT, latitude)
            putExtra(InAppNavigationActivity.EXTRA_DEST_LON, longitude)
            putExtra(InAppNavigationActivity.EXTRA_DEST_LABEL, cleanLabel)
            if (alarmId > 0L) {
                putExtra(InAppNavigationActivity.EXTRA_ALARM_ID, alarmId)
            }
            putExtra(InAppNavigationActivity.EXTRA_RADIUS_METERS, radiusMeters.coerceIn(10, 5000))
        }
    }

    fun launchTurnByTurnNavigation(
        context: Context,
        latitude: Double,
        longitude: Double,
        label: String?,
        alarmId: Long = -1L,
        radiusMeters: Int = 500
    ): Boolean {
        val intent = buildInAppNavigationIntent(
            context = context,
            latitude = latitude,
            longitude = longitude,
            label = label,
            alarmId = alarmId,
            radiusMeters = radiusMeters
        )
        return runCatching {
            val options = ActivityOptions.makeCustomAnimation(
                context,
                R.anim.pinwake_activity_enter,
                R.anim.pinwake_activity_exit
            )
            context.startActivity(intent, options.toBundle())
            true
        }.getOrElse {
            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}
