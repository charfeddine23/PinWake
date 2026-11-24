package com.example.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Safe LocationEngine backed by Android LocationManager that completes getLastLocation()
 * with an empty LocationEngineResult instead of firing "Last location unavailable"
 * exceptions when the device or emulator has no cached GPS fix yet.
 */
class SafeMapLocationEngine(context: Context) : LocationEngine {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val callbackListeners =
        ConcurrentHashMap<LocationEngineCallback<LocationEngineResult>, LocationListener>()

    @SuppressLint("MissingPermission")
    override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
        if (!hasLocationPermission() || locationManager == null) {
            callback.onSuccess(LocationEngineResult.create(emptyList()))
            return
        }

        val best = listOfNotNull(
            runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
            runCatching { locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
        ).maxByOrNull { it.time }

        if (best != null) {
            callback.onSuccess(LocationEngineResult.create(best))
        } else {
            callback.onSuccess(LocationEngineResult.create(emptyList()))
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        callback: LocationEngineCallback<LocationEngineResult>,
        looper: Looper?
    ) {
        if (!hasLocationPermission() || locationManager == null) return
        removeLocationUpdates(callback)

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                callback.onSuccess(LocationEngineResult.create(location))
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        callbackListeners[callback] = listener

        val targetLooper = looper ?: Looper.getMainLooper()
        val intervalMs = request.interval.coerceAtLeast(1_000L)
        val minDist = request.displacement.coerceAtLeast(0f)

        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).forEach { provider ->
            val enabled = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
            if (enabled) {
                runCatching {
                    locationManager.requestLocationUpdates(
                        provider,
                        intervalMs,
                        minDist,
                        listener,
                        targetLooper
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        pendingIntent: PendingIntent
    ) {
        if (!hasLocationPermission() || locationManager == null) return
        val provider = when {
            runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                LocationManager.GPS_PROVIDER
            runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            else -> return
        }
        runCatching {
            locationManager.requestLocationUpdates(
                provider,
                request.interval.coerceAtLeast(1_000L),
                request.displacement.coerceAtLeast(0f),
                pendingIntent
            )
        }
    }

    @SuppressLint("MissingPermission")
    override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {
        val listener = callbackListeners.remove(callback) ?: return
        runCatching { locationManager?.removeUpdates(listener) }
    }

    @SuppressLint("MissingPermission")
    override fun removeLocationUpdates(pendingIntent: PendingIntent) {
        runCatching { locationManager?.removeUpdates(pendingIntent) }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
