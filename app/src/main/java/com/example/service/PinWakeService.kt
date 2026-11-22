package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.PinWakeApplication
import com.example.R
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.local.entity.TripOutcome
import com.example.data.repository.PinWakeRepository
import com.example.data.repository.PollingTier
import com.example.ui.alarm.AlarmRingingActivity
import com.example.util.DistanceFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PinWakeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: PinWakeRepository
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager

    private var alarmsObserverJob: Job? = null
    private var currentActiveAlarms: List<GeoAlarmEntity> = emptyList()
    private var currentTier: PollingTier = PollingTier.STANDBY
    private var lastKnownLocation: Location? = null
    private var isForegroundStarted = false
    private val triggeredLock = mutableSetOf<Long>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocationUpdate(location)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as PinWakeApplication
        repository = app.container.repository
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                serviceScope.launch {
                    val active = repository.getActiveAlarmsOnce()
                    active.forEach { alarm ->
                        repository.recordTripOutcome(alarm, TripOutcome.CANCELLED)
                    }
                    repository.deactivateAllAlarms()
                    stopTrackingAndSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_DISMISS_ALARM -> {
                notificationManager.cancel(NOTIFICATION_ALARM_ID)
                val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                if (alarmId > 0L) triggeredLock.remove(alarmId)
                ensureForegroundStarted()
                observeActiveAlarms()
                return START_STICKY
            }
            ACTION_SNOOZE_500M -> {
                notificationManager.cancel(NOTIFICATION_ALARM_ID)
                val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                val dist = intent.getFloatExtra(EXTRA_DISTANCE_METERS, -1f).let { if (it >= 0f) it else null }
                ensureForegroundStarted()
                serviceScope.launch {
                    if (alarmId > 0L) {
                        triggeredLock.remove(alarmId)
                        repository.getAlarmById(alarmId)?.let { alarm ->
                            repository.applySnooze500m(alarm, dist)
                        }
                    }
                    observeActiveAlarms()
                }
                return START_STICKY
            }
            else -> {
                ensureForegroundStarted()
                observeActiveAlarms()
                return START_STICKY
            }
        }
    }

    private fun ensureForegroundStarted() {
        if (isForegroundStarted) return
        val notification = buildTrackingNotification(
            title = getString(R.string.app_name),
            body = getString(R.string.notif_tracking_body_waiting, tierDisplayLabel(PollingTier.BALANCED_10S))
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_TRACKING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_TRACKING_ID, notification)
        }
        isForegroundStarted = true
    }

    private fun observeActiveAlarms() {
        if (alarmsObserverJob?.isActive == true) return
        alarmsObserverJob = serviceScope.launch {
            repository.activeAlarms.collectLatest { active ->
                currentActiveAlarms = active
                if (active.isEmpty()) {
                    stopTrackingAndSelf()
                    return@collectLatest
                }

                // Seed initial fix if we don't have one yet
                if (lastKnownLocation == null) {
                    lastKnownLocation = getBestLastKnownLocation()
                }

                val loc = lastKnownLocation
                if (loc != null) {
                    evaluateAlarmsAgainstLocation(loc, active)
                } else {
                    if (currentTier == PollingTier.STANDBY) {
                        applyPollingTier(PollingTier.BALANCED_10S)
                    }
                    val first = active.first()
                    repository.trackingStateManager.update {
                        it.copy(
                            isTracking = true,
                            pollingTier = currentTier,
                            activeAlarmCount = active.size,
                            closestAlarmId = first.id,
                            closestAlarmTitle = first.title,
                            closestDistanceMeters = first.lastDistanceMeters
                        )
                    }
                    updateTrackingNotification(
                        targetTitle = first.title,
                        distanceMeters = first.lastDistanceMeters,
                        tier = currentTier
                    )
                }
            }
        }
    }

    private fun handleLocationUpdate(location: Location) {
        lastKnownLocation = location
        val active = currentActiveAlarms
        if (active.isEmpty()) {
            stopTrackingAndSelf()
            return
        }
        serviceScope.launch {
            evaluateAlarmsAgainstLocation(location, active)
        }
    }

    private suspend fun evaluateAlarmsAgainstLocation(
        location: Location,
        activeAlarms: List<GeoAlarmEntity>
    ) {
        if (activeAlarms.isEmpty()) return

        var closestAlarm: GeoAlarmEntity? = null
        var minDistance = Float.MAX_VALUE
        val now = System.currentTimeMillis()
        val distanceResult = FloatArray(1)

        for (alarm in activeAlarms) {
            Location.distanceBetween(
                location.latitude,
                location.longitude,
                alarm.latitude,
                alarm.longitude,
                distanceResult
            )
            val dist = distanceResult[0]
            repository.updateLastDistance(alarm.id, dist)

            if (dist < minDistance) {
                minDistance = dist
                closestAlarm = alarm
            }

            // Accuracy sanity guard against wild coarse cell-tower jumps when GPS isn't locked
            val accuracyAcceptable = !location.hasAccuracy() ||
                location.provider == LocationManager.GPS_PROVIDER ||
                location.accuracy <= (alarm.radiusMeters * 1.5f).coerceAtLeast(500f)

            if (dist <= alarm.radiusMeters &&
                now >= alarm.snoozedUntilMs &&
                accuracyAcceptable &&
                !triggeredLock.contains(alarm.id)
            ) {
                triggeredLock.add(alarm.id)
                repository.setAlarmActive(alarm.id, active = false, lastDistanceMeters = dist)
                repository.recordTripOutcome(alarm, TripOutcome.ARRIVED)
                triggerUnmissableAlarm(alarm, dist)
            }
        }

        val remaining = repository.getActiveAlarmsOnce()
        if (remaining.isEmpty()) {
            // Keep service alive briefly or let AlarmRingingActivity handle dismiss/snooze;
            // if user snoozes, PinWakeService restarts via ACTION_SNOOZE_500M.
            stopTrackingAndSelf()
            return
        }

        val targetTier = PollingTier.forDistance(minDistance)
        if (targetTier != currentTier) {
            applyPollingTier(targetTier)
        }

        val target = closestAlarm ?: remaining.first()
        repository.trackingStateManager.update {
            it.copy(
                isTracking = true,
                pollingTier = currentTier,
                activeAlarmCount = remaining.size,
                closestAlarmId = target.id,
                closestAlarmTitle = target.title,
                closestDistanceMeters = minDistance,
                lastFixAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                lastLatitude = location.latitude,
                lastLongitude = location.longitude,
                lastUpdatedAtMs = now
            )
        }

        updateTrackingNotification(
            targetTitle = target.title,
            distanceMeters = minDistance,
            tier = currentTier
        )
    }

    @SuppressLint("MissingPermission")
    private fun applyPollingTier(tier: PollingTier) {
        if (!hasLocationPermission()) return
        locationManager.removeUpdates(locationListener)
        currentTier = tier
        if (tier == PollingTier.STANDBY) return

        val intervalMs = tier.intervalMs
        val minDist = tier.minDistanceMeters

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    minDist,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                // Network provider runs at least balanced interval to assist indoors without draining battery
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    intervalMs.coerceAtLeast(10_000L),
                    minDist,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val gps = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
        val net = runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        return when {
            gps != null && net != null -> if (gps.time >= net.time) gps else net
            gps != null -> gps
            else -> net
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun triggerUnmissableAlarm(alarm: GeoAlarmEntity, distanceMeters: Float) {
        val powerManager = getSystemService(PowerManager::class.java)
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PinWake::AlarmTriggerWakeLock"
        )
        wakeLock?.acquire(15_000L)

        val alarmIntent = Intent(this, AlarmRingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra(EXTRA_ALARM_TITLE, alarm.title)
            putExtra(EXTRA_ALARM_ADDRESS, alarm.address)
            putExtra(EXTRA_ALARM_RADIUS, alarm.radiusMeters)
            putExtra(EXTRA_ALARM_VIBRATE, alarm.vibrateEnabled)
            putExtra(EXTRA_DISTANCE_METERS, distanceMeters)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            alarm.id.toInt(),
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmNotification = NotificationCompat.Builder(this, PinWakeApplication.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_pin_wake)
            .setContentTitle(getString(R.string.notif_alarm_arrived_title, alarm.title))
            .setContentText(getString(R.string.notif_alarm_arrived_body, alarm.radiusMeters))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ALARM_ID, alarmNotification)

        runCatching {
            startActivity(alarmIntent)
        }
    }

    private fun updateTrackingNotification(
        targetTitle: String,
        distanceMeters: Float?,
        tier: PollingTier
    ) {
        val title = getString(R.string.notif_tracking_title, targetTitle)
        val tierLabel = tierDisplayLabel(tier)
        val body = if (distanceMeters != null) {
            getString(
                R.string.notif_tracking_body_dist,
                DistanceFormatter.formatCompactDistance(distanceMeters),
                tierLabel
            )
        } else {
            getString(R.string.notif_tracking_body_waiting, tierLabel)
        }
        notificationManager.notify(
            NOTIFICATION_TRACKING_ID,
            buildTrackingNotification(title, body)
        )
    }

    private fun buildTrackingNotification(title: String, body: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_CANCEL_ALARM
        }
        val cancelPending = PendingIntent.getBroadcast(
            this,
            102,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PinWakeApplication.CHANNEL_TRACKING)
            .setSmallIcon(R.drawable.ic_pin_wake)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_close,
                getString(R.string.notif_action_cancel),
                cancelPending
            )
            .build()
    }

    private fun tierDisplayLabel(tier: PollingTier): String {
        return when (tier) {
            PollingTier.STANDBY -> getString(R.string.status_standby)
            PollingTier.ECO_30S -> getString(R.string.status_tracking_eco)
            PollingTier.BALANCED_10S -> getString(R.string.status_tracking_balanced)
            PollingTier.PRECISION_2S -> getString(R.string.status_tracking_precision)
        }
    }

    private fun stopTrackingAndSelf() {
        locationManager.removeUpdates(locationListener)
        currentTier = PollingTier.STANDBY
        repository.trackingStateManager.resetToStandby()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(locationListener)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_TRACKING_ID = 4001
        const val NOTIFICATION_ALARM_ID = 4002

        const val ACTION_SYNC_TRACKING = "com.example.service.ACTION_SYNC_TRACKING"
        const val ACTION_CANCEL_ALL = "com.example.service.ACTION_CANCEL_ALL"
        const val ACTION_SNOOZE_500M = "com.example.service.ACTION_SNOOZE_500M"
        const val ACTION_DISMISS_ALARM = "com.example.service.ACTION_DISMISS_ALARM"

        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_TITLE = "extra_alarm_title"
        const val EXTRA_ALARM_ADDRESS = "extra_alarm_address"
        const val EXTRA_ALARM_RADIUS = "extra_alarm_radius"
        const val EXTRA_ALARM_VIBRATE = "extra_alarm_vibrate"
        const val EXTRA_DISTANCE_METERS = "extra_distance_meters"

        fun startOrSync(context: Context) {
            val intent = Intent(context, PinWakeService::class.java).apply {
                action = ACTION_SYNC_TRACKING
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun snooze500m(context: Context, alarmId: Long, currentDistanceMeters: Float) {
            val intent = Intent(context, PinWakeService::class.java).apply {
                action = ACTION_SNOOZE_500M
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_DISTANCE_METERS, currentDistanceMeters)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun dismissAlarm(context: Context, alarmId: Long) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ALARM_ID)
            val intent = Intent(context, PinWakeService::class.java).apply {
                action = ACTION_DISMISS_ALARM
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            runCatching { context.startService(intent) }
        }
    }
}
