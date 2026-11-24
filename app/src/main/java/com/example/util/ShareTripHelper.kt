package com.example.util

import android.content.Context
import android.content.Intent
import com.example.R
import com.example.data.local.entity.GeoAlarmEntity
import java.util.Locale

object ShareTripHelper {

    fun buildAlarmShareText(
        context: Context,
        alarm: GeoAlarmEntity
    ): String {
        return buildDestinationShareText(
            context = context,
            title = alarm.title,
            latitude = alarm.latitude,
            longitude = alarm.longitude,
            radiusMeters = alarm.radiusMeters,
            distanceMeters = if (alarm.isActive) alarm.lastDistanceMeters else null
        )
    }

    fun buildDestinationShareText(
        context: Context,
        title: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        distanceMeters: Float? = null
    ): String {
        val mapUrl = String.format(
            Locale.US,
            "https://maps.google.com/?q=%.6f,%.6f",
            latitude,
            longitude
        )
        val radiusText = DistanceFormatter.formatRadiusBadge(context, radiusMeters)
        val safeTitle = title.ifBlank { context.getString(R.string.app_name) }
        return if (distanceMeters != null) {
            val remaining = DistanceFormatter.formatCompactDistance(distanceMeters)
            context.getString(R.string.share_trip_live_template, safeTitle, remaining, radiusText, mapUrl)
        } else {
            context.getString(R.string.share_destination_template, safeTitle, radiusText, mapUrl)
        }
    }

    fun launchShareSheet(
        context: Context,
        text: String,
        chooserTitle: String
    ) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(sendIntent, chooserTitle))
    }
}
