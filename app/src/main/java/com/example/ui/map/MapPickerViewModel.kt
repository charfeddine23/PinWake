package com.example.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.remote.dto.NominatimPlaceDto
import com.example.data.repository.PinWakeRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MapPickerUiState(
    val existingAlarm: GeoAlarmEntity? = null,
    val latitude: Double = 48.8566,
    val longitude: Double = 2.3522,
    val radiusMeters: Int = 500,
    val resolvedAddress: String = "",
    val suggestedTitle: String = "",
    val isResolvingAddress: Boolean = false,
    val isSearching: Boolean = false,
    val searchResults: List<NominatimPlaceDto> = emptyList(),
    val selectedCategory: FavoriteCategory = FavoriteCategory.NONE
)

@OptIn(FlowPreview::class)
class MapPickerViewModel(
    private val repository: PinWakeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapPickerUiState())
    val uiState: StateFlow<MapPickerUiState> = _uiState.asStateFlow()

    val favoriteAlarms: StateFlow<List<GeoAlarmEntity>> = repository.favoriteAlarms.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    private val searchQueryFlow = MutableStateFlow("")
    private var currentLanguage = "en"
    private var reverseJob: Job? = null

    init {
        viewModelScope.launch {
            searchQueryFlow
                .debounce(450L)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.trim().length < 2) {
                        _uiState.value = _uiState.value.copy(
                            isSearching = false,
                            searchResults = emptyList()
                        )
                        return@collectLatest
                    }
                    _uiState.value = _uiState.value.copy(isSearching = true)
                    val result = repository.searchPlaces(query, currentLanguage)
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchResults = result.getOrDefault(emptyList())
                    )
                }
        }
    }

    fun initialize(
        alarmId: Long,
        defaultCategory: FavoriteCategory,
        languageCode: String,
        onReady: (GeoAlarmEntity?) -> Unit
    ) {
        currentLanguage = languageCode
        viewModelScope.launch {
            val existing = if (alarmId > 0L) repository.getAlarmById(alarmId) else null
            if (existing != null) {
                _uiState.value = _uiState.value.copy(
                    existingAlarm = existing,
                    latitude = existing.latitude,
                    longitude = existing.longitude,
                    radiusMeters = existing.radiusMeters,
                    resolvedAddress = existing.address,
                    suggestedTitle = existing.title,
                    selectedCategory = existing.favoriteCategory
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    selectedCategory = defaultCategory
                )
            }
            onReady(existing)
        }
    }

    fun onSearchQueryChanged(query: String, languageCode: String) {
        currentLanguage = languageCode
        searchQueryFlow.value = query
    }

    fun clearSearchResults() {
        searchQueryFlow.value = ""
        _uiState.value = _uiState.value.copy(
            isSearching = false,
            searchResults = emptyList()
        )
    }

    fun selectSearchResult(place: NominatimPlaceDto) {
        val lat = place.latitudeDouble()
        val lon = place.longitudeDouble()
        _uiState.value = _uiState.value.copy(
            latitude = lat,
            longitude = lon,
            resolvedAddress = place.compactAddress(),
            suggestedTitle = place.shortTitle(),
            isResolvingAddress = false,
            searchResults = emptyList()
        )
    }

    fun onMapCenterSettled(lat: Double, lon: Double, languageCode: String) {
        currentLanguage = languageCode
        _uiState.value = _uiState.value.copy(
            latitude = lat,
            longitude = lon,
            isResolvingAddress = true
        )
        reverseJob?.cancel()
        reverseJob = viewModelScope.launch {
            delay(650L)
            val result = repository.reverseGeocode(lat, lon, languageCode)
            result.onSuccess { place ->
                _uiState.value = _uiState.value.copy(
                    resolvedAddress = place.compactAddress(),
                    suggestedTitle = place.shortTitle(),
                    isResolvingAddress = false
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    isResolvingAddress = false
                )
            }
        }
    }

    fun updateCoordinatesImmediate(lat: Double, lon: Double) {
        _uiState.value = _uiState.value.copy(latitude = lat, longitude = lon)
    }

    fun updateRadius(radiusMeters: Int) {
        _uiState.value = _uiState.value.copy(radiusMeters = radiusMeters.coerceIn(200, 5000))
    }

    fun updateCategory(category: FavoriteCategory) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }

    fun saveAndActivateAlarm(
        title: String,
        fallbackAddress: String,
        onSaved: (Long) -> Unit
    ) {
        val state = _uiState.value
        val address = state.resolvedAddress.ifBlank { fallbackAddress }
        val category = state.selectedCategory
        val existing = state.existingAlarm
        val entity = GeoAlarmEntity(
            id = existing?.id ?: 0L,
            title = title.trim(),
            address = address,
            latitude = state.latitude,
            longitude = state.longitude,
            radiusMeters = state.radiusMeters,
            isActive = true,
            isFavorite = category != FavoriteCategory.NONE,
            favoriteCategory = category,
            vibrateEnabled = existing?.vibrateEnabled ?: true,
            lastDistanceMeters = null,
            snoozedUntilMs = 0L,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            val savedId = repository.saveAlarm(entity)
            onSaved(savedId)
        }
    }

    class Factory(private val repository: PinWakeRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MapPickerViewModel(repository) as T
        }
    }
}
