package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.SystemClock
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
import com.example.util.AlarmVibrationHelper
import com.example.util.DistanceFormatter
import com.example.util.GpsEnforcementHelper
import com.example.util.NavigationAssistCalculator
import com.example.util.PreAlertPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PinWakeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val evaluationMutex = Mutex()
    private lateinit var repository: PinWakeRepository
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager

    private var alarmsObserverJob: Job? = null
    private var currentActiveAlarms: List<GeoAlarmEntity> = emptyList()
    private var currentTier: PollingTier = PollingTier.STANDBY
    private var lastKnownLocation: Location? = null
    private var isForegroundStarted = false
    private var isExplicitlyStopping = false
    private var trackingWakeLock: PowerManager.WakeLock? = null
    private var providersReceiverRegistered = false
    private val triggeredUpdatedAt = mutableMapOf<Long, Long>()
    private val preAlertUpdatedAt = mutableMapOf<Long, Long>()

    private val providersChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                handleGpsAvailabilityChange()
            }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            handleLocationUpdate(location)
        }

        override fun onProviderEnabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                handleGpsAvailabilityChange()
                if (currentActiveAlarms.isNotEmpty() && currentTier != PollingTier.STANDBY) {
                    applyPollingTier(currentTier)
                }
            }
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                handleGpsAvailabilityChange()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as PinWakeApplication
        repository = app.container.repository
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        if (!providersReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                providersChangedReceiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            providersReceiverRegistered = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isExplicitlyStopping = false
        // Always enter foreground synchronously before any async branching or stopSelf()
        // to satisfy Android 12+ startForegroundService() contract and avoid ANR/crash.
        if (!ensureForegroundStartedSafe()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP_TRACKING -> {
                serviceScope.launch {
                    if (repository.getActiveAlarmsOnce().isEmpty()) {
                        stopTrackingAndSelf()
                    } else {
                        observeActiveAlarms()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_CANCEL_ALL -> {
                serviceScope.launch {
                    val active = repository.getActiveAlarmsOnce()
                    active.forEach { alarm ->
                        repository.recordTripOutcome(alarm, TripOutcome.CANCELLED)
                        cancelAlarmNotifications(alarm.id)
                    }
                    triggeredUpdatedAt.clear()
                    preAlertUpdatedAt.clear()
                    repository.deactivateAllAlarms()
                    stopTrackingAndSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_DISMISS_ALARM -> {
                val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                cancelAlarmNotifications(alarmId)
                if (alarmId > 0L) {
                    triggeredUpdatedAt.remove(alarmId)
                    preAlertUpdatedAt.remove(alarmId)
                }
                sendBroadcast(Intent(ACTION_FINISH_RINGING_UI).setPackage(packageName))
                observeActiveAlarms()
                return START_STICKY
            }
            ACTION_SNOOZE_500M -> {
                val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
                cancelAlarmNotifications(alarmId)
                val dist = intent.getFloatExtra(EXTRA_DISTANCE_METERS, -1f).let { if (it >= 0f) it else null }
                sendBroadcast(Intent(ACTION_FINISH_RINGING_UI).setPackage(packageName))
                serviceScope.launch {
                    if (alarmId > 0L) {
                        triggeredUpdatedAt.remove(alarmId)
                        preAlertUpdatedAt.remove(alarmId)
                        repository.getAlarmById(alarmId)?.let { alarm ->
                            repository.applySnooze500m(alarm, dist)
                        }
                    }
                    observeActiveAlarms()
                }
                return START_STICKY
            }
            else -> {
                observeActiveAlarms()
                return START_STICKY
            }
        }
    }

    private fun cancelAlarmNotifications(alarmId: Long) {
        AlarmVibrationHelper.cancelVibration(this)
        notificationManager.cancel(NOTIFICATION_ALARM_ID)
        if (alarmId > 0L) {
            notificationManager.cancel(alarmNotificationId(alarmId))
            notificationManager.cancel(preAlertNotificationId(alarmId))
        }
    }

    private fun ensureForegroundStartedSafe(): Boolean {
        if (isForegroundStarted) return true
        val notification = buildTrackingNotification(
            title = getString(R.string.app_name),
            body = getString(R.string.notif_tracking_body_waiting, tierDisplayLabel(PollingTier.BALANCED_10S))
        )
        return runCatching {
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
            true
        }.getOrDefault(false)
    }

    private fun observeActiveAlarms() {
        if (alarmsObserverJob?.isActive == true) return
        alarmsObserverJob = serviceScope.launch {
            // Use collect (NOT collectLatest) so Room writes inside evaluation never cancel an active alarm trigger
            repository.activeAlarms.collect { active ->
                val activeIds = active.map { it.id }.toSet()
                triggeredUpdatedAt.keys.retainAll(activeIds)
                preAlertUpdatedAt.keys.retainAll(activeIds)

                // If an alarm was edited, toggled off/on, snoozed, or re-armed from History, clear stale trigger locks
                active.forEach { alarm ->
                    if (triggeredUpdatedAt[alarm.id] != alarm.updatedAt) {
                        triggeredUpdatedAt.remove(alarm.id)
                    }
                    if (preAlertUpdatedAt[alarm.id] != alarm.updatedAt) {
                        preAlertUpdatedAt.remove(alarm.id)
                    }
                }

                currentActiveAlarms = active
                if (active.isEmpty()) {
                    stopTrackingAndSelf()
                    return@collect
                }

                syncWakeLockForActiveTracking()
                scheduleWatchdogRestart(WATCHDOG_HEARTBEAT_MS)
                handleGpsAvailabilityChange()

                if (lastKnownLocation == null || !isLocationFresh(lastKnownLocation)) {
                    lastKnownLocation = getBestLastKnownLocation()
                }

                val loc = lastKnownLocation
                val gpsReady = GpsEnforcementHelper.isGpsProviderEnabled(this@PinWakeService)
                val offline = GpsEnforcementHelper.isOffline(this@PinWakeService)
                if (loc != null && isLocationFresh(loc)) {
                    serviceScope.launch {
                        evaluateAlarmsAgainstLocation(loc, currentActiveAlarms)
                    }
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
                            closestDistanceMeters = first.lastDistanceMeters,
                            isGpsEnabled = gpsReady,
                            isGpsDisabledWarning = !gpsReady &&
                                PreAlertPreferences.isForcedGpsKeepOnEnabled(this@PinWakeService),
                            isOfflineMode = offline
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
            evaluateAlarmsAgainstLocation(location, currentActiveAlarms)
        }
    }

    private suspend fun evaluateAlarmsAgainstLocation(
        location: Location,
        activeAlarmsSnapshot: List<GeoAlarmEntity>
    ) = evaluationMutex.withLock {
        val activeAlarms = repository.getActiveAlarmsOnce().ifEmpty { activeAlarmsSnapshot }
        if (activeAlarms.isEmpty()) {
            stopTrackingAndSelf()
            return@withLock
        }

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

            val accuracyAcceptable = !location.hasAccuracy() ||
                location.provider == LocationManager.GPS_PROVIDER ||
                location.accuracy <= (alarm.radiusMeters * 1.5f)
                    .coerceAtLeast(if (alarm.radiusMeters < 100) 60f else 500f)

            val preAlertOffset = PreAlertPreferences.getPreAlertDistanceMeters(this@PinWakeService)
            val alreadyTriggeredForVersion = triggeredUpdatedAt[alarm.id] == alarm.updatedAt
            if (dist <= alarm.radiusMeters &&
                now >= alarm.snoozedUntilMs &&
                accuracyAcceptable &&
                !alreadyTriggeredForVersion
            ) {
                triggeredUpdatedAt[alarm.id] = alarm.updatedAt
                preAlertUpdatedAt.remove(alarm.id)
                notificationManager.cancel(preAlertNotificationId(alarm.id))
                repository.setAlarmActive(alarm.id, active = false, lastDistanceMeters = dist)
                repository.recordTripOutcome(alarm, TripOutcome.ARRIVED)
                triggerUnmissableAlarm(alarm, dist)
            } else if (preAlertOffset > 0 &&
                dist > alarm.radiusMeters &&
                dist <= alarm.radiusMeters + preAlertOffset &&
                now >= alarm.snoozedUntilMs &&
                accuracyAcceptable &&
                !alreadyTriggeredForVersion &&
                preAlertUpdatedAt[alarm.id] != alarm.updatedAt
            ) {
                preAlertUpdatedAt[alarm.id] = alarm.updatedAt
                triggerPreAlertNotification(alarm, dist)
            } else if (preAlertOffset == 0 || dist > alarm.radiusMeters + preAlertOffset + 150f) {
                preAlertUpdatedAt.remove(alarm.id)
            }
        }

        val remaining = repository.getActiveAlarmsOnce()
        if (remaining.isEmpty()) {
            stopTrackingAndSelf()
            return@withLock
        }

        val targetTier = PollingTier.forDistance(minDistance)
        if (targetTier != currentTier) {
            applyPollingTier(targetTier)
        }

        val target = closestAlarm ?: remaining.first()
        val gpsReady = GpsEnforcementHelper.isGpsProviderEnabled(this@PinWakeService)
        val offline = GpsEnforcementHelper.isOffline(this@PinWakeService)
        if (gpsReady) {
            notificationManager.cancel(NOTIFICATION_GPS_WATCHDOG_ID)
        }
        syncWakeLockForActiveTracking()
        scheduleWatchdogRestart(WATCHDOG_HEARTBEAT_MS)

        val navMetrics = NavigationAssistCalculator.computeMetrics(
            currentLat = location.latitude,
            currentLon = location.longitude,
            currentSpeedMps = if (location.hasSpeed()) location.speed else null,
            targetLat = target.latitude,
            targetLon = target.longitude
        )

        repository.trackingStateManager.update {
            it.copy(
                isTracking = true,
                pollingTier = currentTier,
                activeAlarmCount = remaining.size,
                closestAlarmId = target.id,
                closestAlarmTitle = target.title,
                closestDistanceMeters = minDistance,
                closestBearingDegrees = navMetrics.bearingDegrees,
                closestEtaSeconds = navMetrics.etaSeconds,
                isEtaFromLiveSpeed = navMetrics.isLiveSpeedEta,
                lastSpeedMetersPerSec = navMetrics.speedMetersPerSec,
                lastFixAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                lastLatitude = location.latitude,
                lastLongitude = location.longitude,
                lastUpdatedAtMs = now,
                isGpsEnabled = gpsReady,
                isGpsDisabledWarning = !gpsReady &&
                    PreAlertPreferences.isForcedGpsKeepOnEnabled(this@PinWakeService),
                isOfflineMode = offline,
                locationProvider = location.provider
            )
        }

        updateTrackingNotification(
            targetTitle = target.title,
            distanceMeters = minDistance,
            tier = currentTier
        )
    }

    private fun isLocationFresh(location: Location?): Boolean {
        if (location == null) return false
        val ageMs = System.currentTimeMillis() - location.time
        return ageMs in 0L..MAX_CACHED_LOCATION_AGE_MS
    }

    @SuppressLint("MissingPermission")
    private fun applyPollingTier(tier: PollingTier) {
        if (!hasLocationPermission()) return
        locationManager.removeUpdates(locationListener)
        currentTier = tier
        if (tier == PollingTier.STANDBY) return

        val intervalMs = tier.intervalMs
        val minDist = tier.minDistanceMeters

        // Hardware GPS provider works 100% offline with zero internet connectivity
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
            if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
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
    private fun getBestLastKnownLocation(): Location? =
        GpsEnforcementHelper.getBestLocation(this)

    private fun handleGpsAvailabilityChange() {
        val gpsReady = GpsEnforcementHelper.isGpsProviderEnabled(this)
        val offline = GpsEnforcementHelper.isOffline(this)
        val hasActive = currentActiveAlarms.isNotEmpty()
        val enforceForced = PreAlertPreferences.isForcedGpsKeepOnEnabled(this)

        repository.trackingStateManager.update {
            it.copy(
                isGpsEnabled = gpsReady,
                isGpsDisabledWarning = hasActive && !gpsReady && enforceForced,
                isOfflineMode = offline
            )
        }

        if (hasActive && !gpsReady && enforceForced) {
            showGpsDisabledWatchdogAlert()
        } else {
            notificationManager.cancel(NOTIFICATION_GPS_WATCHDOG_ID)
        }
    }

    private fun showGpsDisabledWatchdogAlert() {
        val openLocationSettingsIntent = GpsEnforcementHelper.buildLocationSettingsIntent().apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val settingsPending = PendingIntent.getActivity(
            this,
            NOTIFICATION_GPS_WATCHDOG_ID,
            openLocationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_PROMPT_ENABLE_GPS, true)
        }
        val appPending = PendingIntent.getActivity(
            this,
            NOTIFICATION_GPS_WATCHDOG_ID + 1,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alertNotification = NotificationCompat.Builder(this, PinWakeApplication.CHANNEL_GPS_WATCHDOG)
            .setSmallIcon(R.drawable.ic_gps_warning)
            .setContentTitle(getString(R.string.notif_gps_disabled_title))
            .setContentText(getString(R.string.notif_gps_disabled_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(settingsPending)
            .setFullScreenIntent(appPending, true)
            .addAction(
                R.drawable.ic_gps_warning,
                getString(R.string.notif_action_enable_gps),
                settingsPending
            )
            .build()

        notificationManager.notify(NOTIFICATION_GPS_WATCHDOG_ID, alertNotification)
        AlarmVibrationHelper.vibrateAttention(this)
    }

    @SuppressLint("WakelockTimeout")
    private fun syncWakeLockForActiveTracking() {
        if (currentActiveAlarms.isNotEmpty()) {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val wl = trackingWakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PinWake::MandatoryBgWakeLock"
            ).also {
                it.setReferenceCounted(false)
                trackingWakeLock = it
            }
            runCatching { wl.acquire(60 * 60 * 1000L) }
        } else {
            releaseTrackingWakeLock()
        }
    }

    private fun releaseTrackingWakeLock() {
        runCatching {
            if (trackingWakeLock?.isHeld == true) {
                trackingWakeLock?.release()
            }
        }
        trackingWakeLock = null
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
            alarm.ringtoneUri?.let { putExtra(EXTRA_ALARM_RINGTONE_URI, it) }
            putExtra(EXTRA_DISTANCE_METERS, distanceMeters)
        }

        val baseReqCode = requestCodeBase(alarm.id)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            baseReqCode,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_SNOOZE_500M
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra(EXTRA_DISTANCE_METERS, distanceMeters)
        }
        val snoozePending = PendingIntent.getBroadcast(
            this,
            baseReqCode + 1,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, AlarmActionReceiver::class.java).apply {
            action = AlarmActionReceiver.ACTION_DISMISS_ALARM
            putExtra(EXTRA_ALARM_ID, alarm.id)
        }
        val dismissPending = PendingIntent.getBroadcast(
            this,
            baseReqCode + 2,
            dismissIntent,
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
            .addAction(
                R.drawable.ic_snooze,
                getString(R.string.alarm_snooze_500m),
                snoozePending
            )
            .addAction(
                R.drawable.ic_close,
                getString(R.string.notif_action_dismiss),
                dismissPending
            )
            .build()

        notificationManager.notify(alarmNotificationId(alarm.id), alarmNotification)

        if (alarm.vibrateEnabled || PreAlertPreferences.isForceSilentOverrideEnabled(this)) {
            AlarmVibrationHelper.startLoopingArrivalVibration(this)
        }

        runCatching {
            startActivity(alarmIntent)
        }
    }

    private fun triggerPreAlertNotification(alarm: GeoAlarmEntity, distanceMeters: Float) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            preAlertNotificationId(alarm.id),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val distLabel = DistanceFormatter.formatCompactDistance(distanceMeters)
        val notification = NotificationCompat.Builder(this, PinWakeApplication.CHANNEL_PRE_ALERT)
            .setSmallIcon(R.drawable.ic_pin_wake)
            .setContentTitle(getString(R.string.notif_pre_alert_title, alarm.title))
            .setContentText(getString(R.string.notif_pre_alert_body, distLabel, alarm.radiusMeters))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()

        notificationManager.notify(preAlertNotificationId(alarm.id), notification)

        if (alarm.vibrateEnabled || PreAlertPreferences.isForceSilentOverrideEnabled(this)) {
            AlarmVibrationHelper.vibratePreAlert(this)
        }
    }

    private fun requestCodeBase(alarmId: Long): Int =
        ((alarmId * 31L) and 0x0FFF_FFF0L).toInt()

    fun alarmNotificationId(alarmId: Long): Int =
        if (alarmId > 0L) NOTIFICATION_ALARM_BASE_ID + ((alarmId and 0x03FFL).toInt())
        else NOTIFICATION_ALARM_ID

    private fun preAlertNotificationId(alarmId: Long): Int =
        NOTIFICATION_PRE_ALERT_BASE_ID + ((alarmId and 0x03FFL).toInt())

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

    private fun scheduleWatchdogRestart(delayMs: Long) {
        val restartIntent = Intent(this, BootAndWatchdogReceiver::class.java).apply {
            action = BootAndWatchdogReceiver.ACTION_WATCHDOG_RESTART
        }
        val restartPending = PendingIntent.getBroadcast(
            this,
            NOTIFICATION_TRACKING_ID + 7,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(AlarmManager::class.java)
        runCatching {
            am?.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                restartPending
            )
        }
    }

    private fun cancelWatchdogRestart() {
        val restartIntent = Intent(this, BootAndWatchdogReceiver::class.java).apply {
            action = BootAndWatchdogReceiver.ACTION_WATCHDOG_RESTART
        }
        val restartPending = PendingIntent.getBroadcast(
            this,
            NOTIFICATION_TRACKING_ID + 7,
            restartIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (restartPending != null) {
            runCatching {
                getSystemService(AlarmManager::class.java)?.cancel(restartPending)
                restartPending.cancel()
            }
        }
    }

    private fun stopTrackingAndSelf() {
        isExplicitlyStopping = true
        cancelWatchdogRestart()
        locationManager.removeUpdates(locationListener)
        notificationManager.cancel(NOTIFICATION_GPS_WATCHDOG_ID)
        releaseTrackingWakeLock()
        currentTier = PollingTier.STANDBY
        repository.trackingStateManager.resetToStandby()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (currentActiveAlarms.isNotEmpty() && hasLocationPermission()) {
            scheduleWatchdogRestart(1_200L)
        }
    }

    override fun onDestroy() {
        val shouldSelfHeal = !isExplicitlyStopping &&
            currentActiveAlarms.isNotEmpty() &&
            hasLocationPermission()
        if (shouldSelfHeal) {
            scheduleWatchdogRestart(1_500L)
        }
        locationManager.removeUpdates(locationListener)
        notificationManager.cancel(NOTIFICATION_GPS_WATCHDOG_ID)
        releaseTrackingWakeLock()
        if (providersReceiverRegistered) {
            runCatching { unregisterReceiver(providersChangedReceiver) }
            providersReceiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_TRACKING_ID = 4001
        const val NOTIFICATION_ALARM_ID = 4002
        const val NOTIFICATION_GPS_WATCHDOG_ID = 4003
        const val NOTIFICATION_ALARM_BASE_ID = 5000
        const val NOTIFICATION_PRE_ALERT_BASE_ID = 6500
        private const val MAX_CACHED_LOCATION_AGE_MS = 120_000L
        private const val WATCHDOG_HEARTBEAT_MS = 60_000L

        const val ACTION_SYNC_TRACKING = "com.example.service.ACTION_SYNC_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.service.ACTION_STOP_TRACKING"
        const val ACTION_CANCEL_ALL = "com.example.service.ACTION_CANCEL_ALL"
        const val ACTION_SNOOZE_500M = "com.example.service.ACTION_SNOOZE_500M"
        const val ACTION_DISMISS_ALARM = "com.example.service.ACTION_DISMISS_ALARM"
        const val ACTION_FINISH_RINGING_UI = "com.example.service.ACTION_FINISH_RINGING_UI"

        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_TITLE = "extra_alarm_title"
        const val EXTRA_ALARM_ADDRESS = "extra_alarm_address"
        const val EXTRA_ALARM_RADIUS = "extra_alarm_radius"
        const val EXTRA_ALARM_VIBRATE = "extra_alarm_vibrate"
        const val EXTRA_ALARM_RINGTONE_URI = "extra_alarm_ringtone_uri"
        const val EXTRA_DISTANCE_METERS = "extra_distance_meters"

        fun alarmNotificationIdFor(alarmId: Long): Int =
            if (alarmId > 0L) NOTIFICATION_ALARM_BASE_ID + ((alarmId and 0x03FFL).toInt())
            else NOTIFICATION_ALARM_ID

        fun preAlertNotificationIdFor(alarmId: Long): Int =
            NOTIFICATION_PRE_ALERT_BASE_ID + ((alarmId and 0x03FFL).toInt())

        fun startOrSync(context: Context) {
            val hasPerm = com.example.util.PermissionHelper.hasTrackingLocation(context)
            if (!hasPerm) return
            val intent = Intent(context, PinWakeService::class.java).apply {
                action = ACTION_SYNC_TRACKING
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stopTracking(context: Context) {
            runCatching {
                context.stopService(Intent(context, PinWakeService::class.java))
            }
        }

        fun snooze500m(context: Context, alarmId: Long, currentDistanceMeters: Float) {
            AlarmVibrationHelper.cancelVibration(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ALARM_ID)
            nm?.cancel(alarmNotificationIdFor(alarmId))
            nm?.cancel(preAlertNotificationIdFor(alarmId))
            val intent = Intent(context, PinWakeService::class.java).apply {
                action = ACTION_SNOOZE_500M
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_DISTANCE_METERS, currentDistanceMeters)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun dismissAlarm(context: Context, alarmId: Long) {
            AlarmVibrationHelper.cancelVibration(context)
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.cancel(NOTIFICATION_ALARM_ID)
            if (alarmId > 0L) {
                nm?.cancel(alarmNotificationIdFor(alarmId))
                nm?.cancel(preAlertNotificationIdFor(alarmId))
            }
            val intent = Intent(context, PinWakeService::class.java).apply {
                action = ACTION_DISMISS_ALARM
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
