package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL_ALARM -> {
                val cancelIntent = Intent(context, PinWakeService::class.java).apply {
                    action = PinWakeService.ACTION_CANCEL_ALL
                }
                context.startService(cancelIntent)
            }
            ACTION_DISMISS_ALARM -> {
                val alarmId = intent.getLongExtra(PinWakeService.EXTRA_ALARM_ID, -1L)
                PinWakeService.dismissAlarm(context, alarmId)
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_ALARM = "com.example.ACTION_CANCEL_ALARM"
        const val ACTION_DISMISS_ALARM = "com.example.ACTION_DISMISS_ALARM"
    }
}
