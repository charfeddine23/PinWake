package com.example.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.local.entity.TripHistoryEntity
import com.example.data.repository.PinWakeRepository
import com.example.data.repository.TrackingTelemetry
import com.example.util.ApkUpdateManager
import com.example.util.AppUpdateInfo
import com.example.util.UpdateDownloadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class DashboardUiState(
    val alarms: List<GeoAlarmEntity> = emptyList(),
    val recentTrips: List<TripHistoryEntity> = emptyList(),
    val telemetry: TrackingTelemetry = TrackingTelemetry(),
    val homeFavorite: GeoAlarmEntity? = null,
    val workFavorite: GeoAlarmEntity? = null,
    val stationFavorite: GeoAlarmEntity? = null
) {
    val activeCount: Int get() = alarms.count { it.isActive }
}

data class UpdateUiState(
    val isChecking: Boolean = false,
    val availableUpdate: AppUpdateInfo? = null,
    val isDownloading: Boolean = false,
    val downloadPercent: Int = 0,
    val downloadedMb: Double = 0.0,
    val totalMb: Double = 0.0,
    val downloadedApkFile: File? = null,
    val errorMessage: String? = null
)

sealed class UpdateCheckEvent {
    data object UpToDate : UpdateCheckEvent()
    data class UpdateFound(val version: String) : UpdateCheckEvent()
    data object Failed : UpdateCheckEvent()
}

class DashboardViewModel(
    private val repository: PinWakeRepository,
    private val apkUpdateManager: ApkUpdateManager
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.allAlarms,
        repository.recentTrips,
        repository.trackingStateManager.telemetry
    ) { alarms, trips, telemetry ->
        DashboardUiState(
            alarms = alarms,
            recentTrips = trips,
            telemetry = telemetry,
            homeFavorite = alarms.firstOrNull {
                it.isFavorite && it.favoriteCategory == FavoriteCategory.HOME
            },
            workFavorite = alarms.firstOrNull {
                it.isFavorite && it.favoriteCategory == FavoriteCategory.WORK
            },
            stationFavorite = alarms.firstOrNull {
                it.isFavorite && it.favoriteCategory == FavoriteCategory.STATION
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DashboardUiState()
    )

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _updateEvent = MutableStateFlow<UpdateCheckEvent?>(null)
    val updateEvent: StateFlow<UpdateCheckEvent?> = _updateEvent.asStateFlow()

    private var downloadJob: Job? = null

    init {
        checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean) {
        if (_updateState.value.isChecking || _updateState.value.isDownloading) return
        viewModelScope.launch {
            _updateState.value = _updateState.value.copy(isChecking = true, errorMessage = null)
            val result = apkUpdateManager.checkForUpdate()
            result.onSuccess { info ->
                _updateState.value = _updateState.value.copy(
                    isChecking = false,
                    availableUpdate = info
                )
                if (manual) {
                    _updateEvent.value = if (info != null) {
                        UpdateCheckEvent.UpdateFound(info.versionName)
                    } else {
                        UpdateCheckEvent.UpToDate
                    }
                }
            }.onFailure {
                _updateState.value = _updateState.value.copy(isChecking = false)
                if (manual) {
                    _updateEvent.value = UpdateCheckEvent.Failed
                }
            }
        }
    }

    fun consumeUpdateEvent() {
        _updateEvent.value = null
    }

    fun startInAppDownload(onReadyToInstall: (File) -> Unit) {
        val current = _updateState.value
        val readyFile = current.downloadedApkFile
        if (readyFile != null && readyFile.exists()) {
            onReadyToInstall(readyFile)
            return
        }
        val info = current.availableUpdate ?: return
        if (current.isDownloading) return

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _updateState.value = _updateState.value.copy(
                isDownloading = true,
                downloadPercent = 0,
                downloadedMb = 0.0,
                totalMb = info.apkAsset.size / (1024.0 * 1024.0),
                errorMessage = null
            )
            apkUpdateManager.downloadApk(info).collect { state ->
                when (state) {
                    is UpdateDownloadState.Progress -> {
                        _updateState.value = _updateState.value.copy(
                            isDownloading = true,
                            downloadPercent = state.percent,
                            downloadedMb = state.downloadedBytes / (1024.0 * 1024.0),
                            totalMb = (if (state.totalBytes > 0) state.totalBytes else info.apkAsset.size) /
                                (1024.0 * 1024.0)
                        )
                    }
                    is UpdateDownloadState.Completed -> {
                        _updateState.value = _updateState.value.copy(
                            isDownloading = false,
                            downloadPercent = 100,
                            downloadedApkFile = state.apkFile
                        )
                        onReadyToInstall(state.apkFile)
                    }
                    is UpdateDownloadState.Failed -> {
                        _updateState.value = _updateState.value.copy(
                            isDownloading = false,
                            errorMessage = state.message
                        )
                    }
                }
            }
        }
    }

    fun setAlarmActive(alarm: GeoAlarmEntity, active: Boolean, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            repository.setAlarmActive(alarm.id, active, alarm.lastDistanceMeters)
            val hasAnyActive = repository.getActiveAlarmsOnce().isNotEmpty()
            onDone(hasAnyActive)
        }
    }

    fun deleteAlarm(alarmId: Long, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            repository.deleteAlarm(alarmId)
            val hasAnyActive = repository.getActiveAlarmsOnce().isNotEmpty()
            if (!hasAnyActive) {
                repository.trackingStateManager.resetToStandby()
            }
            onDone(hasAnyActive)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    class Factory(
        private val repository: PinWakeRepository,
        private val apkUpdateManager: ApkUpdateManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(repository, apkUpdateManager) as T
        }
    }
}
