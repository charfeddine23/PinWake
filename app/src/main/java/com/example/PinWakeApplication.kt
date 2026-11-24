package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.example.di.AppContainer
import com.example.util.LanguageManager
import org.maplibre.android.MapLibre

class PinWakeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (!"robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) {
            MapLibre.getInstance(this)
        }
        container = AppContainer(this)
        LanguageManager.applySavedLanguage(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val trackingChannel = NotificationChannel(
            CHANNEL_TRACKING,
            getString(R.string.notif_channel_tracking_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_tracking_desc)
            setShowBadge(false)
        }

        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val alarmAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            getString(R.string.notif_channel_alarm_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_alarm_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setBypassDnd(true)
            setSound(alarmSound, alarmAttributes)
        }

        val gpsWatchdogChannel = NotificationChannel(
            CHANNEL_GPS_WATCHDOG,
            getString(R.string.notif_channel_gps_watchdog_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_gps_watchdog_desc)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setBypassDnd(true)
            setSound(alarmSound, alarmAttributes)
        }

        val preAlertChannel = NotificationChannel(
            CHANNEL_PRE_ALERT,
            getString(R.string.notif_channel_pre_alert_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_pre_alert_desc)
            enableVibration(true)
        }

        manager.createNotificationChannel(trackingChannel)
        manager.createNotificationChannel(alarmChannel)
        manager.createNotificationChannel(gpsWatchdogChannel)
        manager.createNotificationChannel(preAlertChannel)
    }

    companion object {
        const val CHANNEL_TRACKING = "pin_wake_tracking_channel"
        const val CHANNEL_ALARM = "pin_wake_alarm_channel_v2"
        const val CHANNEL_GPS_WATCHDOG = "pin_wake_gps_watchdog_channel"
        const val CHANNEL_PRE_ALERT = "pin_wake_pre_alert_channel"
    }
}
