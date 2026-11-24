package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL_ALARM -> {
                val cancelIntent = Intent(context, PinWakeService::class.java).apply {
                    action = PinWakeService.ACTION_CANCEL_ALL
                }
                ContextCompat.startForegroundService(context, cancelIntent)
            }
            ACTION_DISMISS_ALARM -> {
                val alarmId = intent.getLongExtra(PinWakeService.EXTRA_ALARM_ID, -1L)
                PinWakeService.dismissAlarm(context, alarmId)
            }
            ACTION_SNOOZE_500M -> {
                val alarmId = intent.getLongExtra(PinWakeService.EXTRA_ALARM_ID, -1L)
                val dist = intent.getFloatExtra(PinWakeService.EXTRA_DISTANCE_METERS, 0f)
                if (alarmId > 0L) {
                    PinWakeService.snooze500m(context, alarmId, dist)
                }
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_ALARM = "com.example.ACTION_CANCEL_ALARM"
        const val ACTION_SNOOZE_500M = "com.example.ACTION_SNOOZE_500M"
        const val ACTION_DISMISS_ALARM = "com.example.ACTION_DISMISS_ALARM"
    }
}
