package com.taha.goit.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taha.goit.data.api.TransitRepository
import com.taha.goit.data.model.BusRealtimeLocation
import com.taha.goit.data.model.RouteStop
import com.taha.goit.data.model.SearchItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MainUiState(
    val searchQuery: String = "",
    val searchResults: List<SearchItem> = emptyList(),
    val isSearching: Boolean = false,
    val selectedRouteCode: String? = null,
    val selectedRouteStops: List<RouteStop> = emptyList(),
    val realtimeBuses: List<BusRealtimeLocation> = emptyList(),
    val isTrackingActive: Boolean = false,
    val errorMessage: String? = null
)

class MainViewModel : ViewModel() {
    private val repository = TransitRepository()

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var trackingJob: Job? = null

    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        if (query.trim().length >= 2) {
            search(query.trim())
        } else {
            _uiState.value = _uiState.value.copy(searchResults = emptyList())
        }
    }

    private fun search(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            val items = repository.searchRouteAndStation(query)
            _uiState.value = _uiState.value.copy(
                searchResults = items,
                isSearching = false
            )
        }
    }

    fun selectRoute(routeCode: String, hatNo: Int?) {
        _uiState.value = _uiState.value.copy(
            selectedRouteCode = routeCode,
            searchResults = emptyList(),
            searchQuery = routeCode
        )

        // Fetch stops
        hatNo?.let { no ->
            viewModelScope.launch {
                val stops = repository.getRouteStops(no)
                _uiState.value = _uiState.value.copy(selectedRouteStops = stops)
            }
        }

        // Start live tracking loop for this line
        startRealtimeTracking(routeCode)
    }

    private fun startRealtimeTracking(routeCode: String) {
        trackingJob?.cancel()
        trackingJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTrackingActive = true)
            while (true) {
                val buses = repository.getRealtimeBuses(routeCode)
                _uiState.value = _uiState.value.copy(realtimeBuses = buses)
                delay(8000) // Refresh every 8 seconds
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        trackingJob?.cancel()
    }
}
