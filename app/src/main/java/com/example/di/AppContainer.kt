package com.example.di

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.remote.GitHubReleaseApiService
import com.example.data.remote.NominatimApiService
import com.example.data.repository.PinWakeRepository
import com.example.data.repository.TrackingStateManager
import com.example.util.ApkUpdateManager

class AppContainer(context: Context) {
    private val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    private val nominatimApi: NominatimApiService by lazy {
        NominatimApiService.create()
    }

    private val gitHubReleaseApi: GitHubReleaseApiService by lazy {
        GitHubReleaseApiService.create()
    }

    val trackingStateManager: TrackingStateManager by lazy {
        TrackingStateManager()
    }

    val repository: PinWakeRepository by lazy {
        PinWakeRepository(
            geoAlarmDao = database.geoAlarmDao(),
            tripHistoryDao = database.tripHistoryDao(),
            nominatimApi = nominatimApi,
            trackingStateManager = trackingStateManager
        )
    }

    val apkUpdateManager: ApkUpdateManager by lazy {
        ApkUpdateManager(
            context = context.applicationContext,
            gitHubApi = gitHubReleaseApi
        )
    }
}
