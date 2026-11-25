package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.PinWakeApplication
import com.example.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores active GPS wake monitoring after device reboot, app update,
 * or watchdog restart whenever any wake alarm is armed.
 */
class BootAndWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val isSupportedAction = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_WATCHDOG_RESTART
        if (!isSupportedAction) return

        val app = context.applicationContext as? PinWakeApplication ?: return
        val repo = app.container.repository
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (PermissionHelper.hasTrackingLocation(context)) {
                    val active = repo.getActiveAlarmsOnce()
                    if (active.isNotEmpty()) {
                        PinWakeService.startOrSync(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_WATCHDOG_RESTART = "com.example.service.ACTION_WATCHDOG_RESTART"
    }
}
