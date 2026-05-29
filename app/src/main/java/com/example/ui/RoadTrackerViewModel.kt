package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Route
import com.example.data.RoutePoint
import com.example.data.RouteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoadTrackerViewModel(private val repository: RouteRepository) : ViewModel() {

    // Saved Routes from Room
    val allRoutes: StateFlow<List<Route>> = repository.allRoutes
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // GPS Tracking State
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _activeCoordinates = MutableStateFlow<List<RoutePoint>>(emptyList())
    val activeCoordinates: StateFlow<List<RoutePoint>> = _activeCoordinates.asStateFlow()

    private val _activeDistance = MutableStateFlow(0.0) // Cumulative km
    val activeDistance: StateFlow<Double> = _activeDistance.asStateFlow()

    private val _startTime = MutableStateFlow<Long?>(null)
    val startTime: StateFlow<Long?> = _startTime.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private var timerJob: Job? = null

    // Manual Drawing State
    private val _isDrawingMode = MutableStateFlow(false)
    val isDrawingMode: StateFlow<Boolean> = _isDrawingMode.asStateFlow()

    private val _drawingPoints = MutableStateFlow<List<RoutePoint>>(emptyList())
    val drawingPoints: StateFlow<List<RoutePoint>> = _drawingPoints.asStateFlow()

    private val _drawingDistance = MutableStateFlow(0.0)
    val drawingDistance: StateFlow<Double> = _drawingDistance.asStateFlow()

    // Selected Route (for map focus and detail inspector view)
    private val _selectedRoute = MutableStateFlow<Route?>(null)
    val selectedRoute: StateFlow<Route?> = _selectedRoute.asStateFlow()

    // Map Center State
    private val _mapCenter = MutableStateFlow<RoutePoint?>(null)
    val mapCenter: StateFlow<RoutePoint?> = _mapCenter.asStateFlow()

    // Live Rider Speed / Current Altitude or details (insightful statistics)
    private val _currentSpeedKmh = MutableStateFlow(0.0)
    val currentSpeedKmh: StateFlow<Double> = _currentSpeedKmh.asStateFlow()

    // Trigger timer ticking in background
    private fun startTimer() {
        timerJob?.cancel()
        _startTime.value = System.currentTimeMillis()
        _elapsedSeconds.value = 0L
        timerJob = viewModelScope.launch {
            while (_isTracking.value) {
                val start = _startTime.value ?: System.currentTimeMillis()
                _elapsedSeconds.value = (System.currentTimeMillis() - start) / 1000
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // --- Action Methods ---

    fun startTracking() {
        if (_isTracking.value) return
        _isDrawingMode.value = false // Mutually exclusive
        _isTracking.value = true
        _activeCoordinates.value = emptyList()
        _activeDistance.value = 0.0
        _currentSpeedKmh.value = 0.0
        startTimer()
    }

    fun addTrackingPoint(point: RoutePoint, speedMs: Float = 0f) {
        if (!_isTracking.value) return
        val currentList = _activeCoordinates.value.toMutableList()
        
        // Prevent duplicate consecutive coordinates to save memory/storage
        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.lat == point.lat && last.lng == point.lng) return
            
            // Increment distance using Haversine
            val increment = calculateDistance(last, point)
            _activeDistance.value += increment
        }
        
        currentList.add(point)
        _activeCoordinates.value = currentList
        _currentSpeedKmh.value = speedMs * 3.6 // Convert ms to km/h
    }

    fun stopAndSaveTracking(customName: String?): String? {
        if (!_isTracking.value) return null
        _isTracking.value = false
        stopTimer()

        val points = _activeCoordinates.value
        val dist = _activeDistance.value
        if (points.size < 2) {
            // Cancel ride if there aren't enough points recorded
            _activeCoordinates.value = emptyList()
            _activeDistance.value = 0.0
            return "Ride canceled: Not enough movement coordinates recorded."
        }

        val name = if (customName.isNullOrBlank()) {
            "Ride of ${SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date())}"
        } else {
            customName
        }

        val route = Route(
            id = generateUniqueId(),
            name = name,
            date = System.currentTimeMillis(),
            coordinates = points,
            distance = dist,
            mode = "gps"
        )

        viewModelScope.launch {
            repository.insert(route)
        }

        // Reset tracking buffers
        _activeCoordinates.value = emptyList()
        _activeDistance.value = 0.0
        return null
    }

    fun discardActiveTracking() {
        _isTracking.value = false
        stopTimer()
        _activeCoordinates.value = emptyList()
        _activeDistance.value = 0.0
    }

    // Toggle manual drawing mode
    fun toggleDrawingMode() {
        _isDrawingMode.value = !_isDrawingMode.value
        if (!_isDrawingMode.value) {
            _drawingPoints.value = emptyList()
            _drawingDistance.value = 0.0
        } else {
            _isTracking.value = false // Mutually exclusive
            stopTimer()
        }
    }

    fun setDrawingModeActive(active: Boolean) {
        _isDrawingMode.value = active
        if (!active) {
            _drawingPoints.value = emptyList()
            _drawingDistance.value = 0.0
        }
    }

    fun addDrawingPoint(point: RoutePoint) {
        if (!_isDrawingMode.value) return
        val currentList = _drawingPoints.value.toMutableList()
        currentList.add(point)
        _drawingPoints.value = currentList
        _drawingDistance.value = calculatePathDistance(currentList)
    }

    fun removeDrawingPoint(index: Int) {
        if (!_isDrawingMode.value) return
        val currentList = _drawingPoints.value.toMutableList()
        if (index in currentList.indices) {
            currentList.removeAt(index)
            _drawingPoints.value = currentList
            _drawingDistance.value = calculatePathDistance(currentList)
        }
    }

    fun undoDrawingPoint() {
        if (!_isDrawingMode.value) return
        val currentList = _drawingPoints.value.toMutableList()
        if (currentList.isNotEmpty()) {
            currentList.removeAt(currentList.size - 1)
            _drawingPoints.value = currentList
            _drawingDistance.value = calculatePathDistance(currentList)
        }
    }

    fun saveDrawingRoute(customName: String?): String? {
        val points = _drawingPoints.value
        val dist = _drawingDistance.value
        if (points.size < 2) {
            return "Requires at least 2 points to build a ride path."
        }

        val name = if (customName.isNullOrBlank()) {
            "Manual Ride - ${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date())}"
        } else {
            customName
        }

        val route = Route(
            id = generateUniqueId(),
            name = name,
            date = System.currentTimeMillis(),
            coordinates = points,
            distance = dist,
            mode = "manual"
        )

        viewModelScope.launch {
            repository.insert(route)
        }

        // Reset drawing buffers
        _drawingPoints.value = emptyList()
        _drawingDistance.value = 0.0
        _isDrawingMode.value = false
        return null
    }

    fun deleteRoute(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            if (_selectedRoute.value?.id == id) {
                _selectedRoute.value = null
            }
        }
    }

    fun selectRoute(route: Route?) {
        _selectedRoute.value = route
        if (route != null && route.coordinates.isNotEmpty()) {
            // Suggest centering map on selected route
            _mapCenter.value = route.coordinates.first()
        }
    }

    fun clearMapCenterTrigger() {
        _mapCenter.value = null
    }

    fun importGpx(gpxText: String, customName: String? = null): String {
        val points = mutableListOf<RoutePoint>()
        // Simple XML tag search using regex (highly resilient, doesn't depend on sensitive parsers)
        val regex = """<trkpt\s+lat=["'](-?\d+\.?\d*)["']\s+lon=["'](-?\d+\.?\d*)["']""".toRegex()
        val matches = regex.findAll(gpxText)
        for (match in matches) {
            val lat = match.groupValues[1].toDoubleOrNull()
            val lng = match.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null) {
                points.add(RoutePoint(lat, lng))
            }
        }

        if (points.size < 2) {
            return "Failed to import: Found less than 2 tracking nodes in GPX format."
        }

        val distance = calculatePathDistance(points)
        val name = if (!customName.isNullOrBlank()) {
            customName
        } else {
            // Find name tags in XML
            val nameRegex = """<name>(.*?)</name>""".toRegex()
            val nameMatch = nameRegex.find(gpxText)
            nameMatch?.groupValues?.get(1) ?: "GPX Import - ${SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())}"
        }

        val route = Route(
            id = generateUniqueId(),
            name = name,
            date = System.currentTimeMillis(),
            coordinates = points,
            distance = distance,
            mode = "gps"
        )

        viewModelScope.launch {
            repository.insert(route)
        }

        return "Successfully imported route: $name (${String.format(Locale.US, "%.2f", distance)} km)"
    }

    fun exportRouteToGpx(route: Route): String {
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<gpx version=\"1.1\" creator=\"RoadTracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        builder.append("  <metadata>\n")
        builder.append("    <name>${route.name}</name>\n")
        val formattedDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date(route.date))
        builder.append("    <time>$formattedDate</time>\n")
        builder.append("  </metadata>\n")
        builder.append("  <trk>\n")
        builder.append("    <name>${route.name}</name>\n")
        builder.append("    <trkseg>\n")
        for (point in route.coordinates) {
            builder.append("      <trkpt lat=\"${point.lat}\" lon=\"${point.lng}\" />\n")
        }
        builder.append("    </trkseg>\n")
        builder.append("  </trk>\n")
        builder.append("</gpx>")
        return builder.toString()
    }

    fun exportRouteToCsvWithTimestamps(route: Route): String {
        val sfd = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val builder = java.lang.StringBuilder()
        builder.append("Latitude,Longitude,Timestamp\n")
        route.coordinates.forEachIndexed { index, point ->
            // Assume 5 seconds interval per coordinate point for simulated timestamp
            val pointTime = route.date + (index * 5000L)
            val formattedTime = sfd.format(Date(pointTime))
            builder.append("${point.lat},${point.lng},\"$formattedTime\"\n")
        }
        return builder.toString()
    }

    fun exportAllRoutesToJson(routes: List<Route>): String {
        return JsonHelper.routesToJson(routes)
    }

    // --- Distance Helpers ---

    private fun calculateDistance(p1: RoutePoint, p2: RoutePoint): Double {
        val r = 6371.0 // Earth radius in kilometers
        val dLat = Math.toRadians(p2.lat - p1.lat)
        val dLng = Math.toRadians(p2.lng - p1.lng)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(p1.lat)) * Math.cos(Math.toRadians(p2.lat)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun calculatePathDistance(points: List<RoutePoint>): Double {
        if (points.size < 2) return 0.0
        var dist = 0.0
        for (i in 0 until points.size - 1) {
            dist += calculateDistance(points[i], points[i + 1])
        }
        return dist
    }

    private fun generateUniqueId(): String {
        return Date().time.toString(36) + (100000..999999).random().toString(36)
    }
}

class RoadTrackerViewModelFactory(private val repository: RouteRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RoadTrackerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RoadTrackerViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
