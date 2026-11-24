package com.example.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.PinWakeApplication
import com.example.data.local.entity.TripOutcome
import com.example.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PinWakeApplication
        val repo = app?.container?.repository
        when (intent.action) {
            ACTION_CANCEL_ALARM -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                val cancelIntent = Intent(context, PinWakeService::class.java).apply {
                    action = PinWakeService.ACTION_CANCEL_ALL
                }
                val startedFgs = if (PermissionHelper.hasTrackingLocation(context)) {
                    runCatching {
                        ContextCompat.startForegroundService(context, cancelIntent)
                        true
                    }.getOrDefault(false)
                } else {
                    false
                }
                if (!startedFgs && repo != null) {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val active = repo.getActiveAlarmsOnce()
                            active.forEach { alarm ->
                                repo.recordTripOutcome(alarm, TripOutcome.CANCELLED)
                                nm?.cancel(PinWakeService.alarmNotificationIdFor(alarm.id))
                                nm?.cancel(PinWakeService.preAlertNotificationIdFor(alarm.id))
                            }
                            repo.deactivateAllAlarms()
                            nm?.cancel(PinWakeService.NOTIFICATION_TRACKING_ID)
                            nm?.cancel(PinWakeService.NOTIFICATION_ALARM_ID)
                            PinWakeService.stopTracking(context)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
            ACTION_DISMISS_ALARM -> {
                val alarmId = intent.getLongExtra(PinWakeService.EXTRA_ALARM_ID, -1L)
                context.sendBroadcast(
                    Intent(PinWakeService.ACTION_FINISH_RINGING_UI).setPackage(context.packageName)
                )
                PinWakeService.dismissAlarm(context, alarmId)
            }
            ACTION_SNOOZE_500M -> {
                val alarmId = intent.getLongExtra(PinWakeService.EXTRA_ALARM_ID, -1L)
                val dist = intent.getFloatExtra(PinWakeService.EXTRA_DISTANCE_METERS, 0f)
                if (alarmId > 0L) {
                    context.sendBroadcast(
                        Intent(PinWakeService.ACTION_FINISH_RINGING_UI).setPackage(context.packageName)
                    )
                    if (PermissionHelper.hasTrackingLocation(context)) {
                        PinWakeService.snooze500m(context, alarmId, dist)
                    } else if (repo != null) {
                        val pendingResult = goAsync()
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                repo.getAlarmById(alarmId)?.let { alarm ->
                                    repo.applySnooze500m(alarm, dist)
                                }
                            } finally {
                                pendingResult.finish()
                            }
                        }
                    }
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
