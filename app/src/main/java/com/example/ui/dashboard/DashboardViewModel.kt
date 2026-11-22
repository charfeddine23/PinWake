package com.example.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.local.entity.TripHistoryEntity
import com.example.data.repository.PinWakeRepository
import com.example.data.repository.TrackingTelemetry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

class DashboardViewModel(
    private val repository: PinWakeRepository
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

    class Factory(private val repository: PinWakeRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(repository) as T
        }
    }
}
