package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.example.data.remote.NominatimApiService
import com.example.di.AppContainer
import com.example.util.LanguageManager
import org.osmdroid.config.Configuration
import java.io.File

class PinWakeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        LanguageManager.applySavedLanguage(this)
        configureOsmDroid()
        createNotificationChannels()
    }

    private fun configureOsmDroid() {
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = NominatimApiService.USER_AGENT
        val cacheDir = File(cacheDir, "osmdroid")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        osmConfig.osmdroidBasePath = cacheDir
        osmConfig.osmdroidTileCache = File(cacheDir, "tiles").apply {
            if (!exists()) mkdirs()
        }
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

        manager.createNotificationChannel(trackingChannel)
        manager.createNotificationChannel(alarmChannel)
    }

    companion object {
        const val CHANNEL_TRACKING = "pin_wake_tracking_channel"
        const val CHANNEL_ALARM = "pin_wake_alarm_channel"
    }
}
