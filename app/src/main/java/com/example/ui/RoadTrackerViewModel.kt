package com.example.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Route
import com.example.data.RoutePoint
import com.example.data.RouteRepository
import com.example.data.SearchResult
import com.example.data.TelemetrySample
import com.example.data.DebugLogger
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

class RoadTrackerViewModel(
    private val repository: RouteRepository,
    private val context: Context
) : ViewModel() {

    // Cloud Backend Sync System
    private val sharedPrefs = context.getSharedPreferences("roadtracker_cloud_sync", Context.MODE_PRIVATE)

    private val _vaultId = MutableStateFlow(sharedPrefs.getString("vault_id", "") ?: "")
    val vaultId: StateFlow<String> = _vaultId.asStateFlow()

    private val _syncStatus = MutableStateFlow("idle") // "idle", "syncing", "synced", "failed"
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _isLightMap = MutableStateFlow(sharedPrefs.getBoolean("light_map_theme", false))
    val isLightMap: StateFlow<Boolean> = _isLightMap.asStateFlow()

    fun toggleMapTheme() {
        val newValue = !_isLightMap.value
        _isLightMap.value = newValue
        sharedPrefs.edit().putBoolean("light_map_theme", newValue).apply()
    }

    init {
        if (_vaultId.value.isEmpty()) {
            val randomId = "road_vault_" + (1000..9999).random() + "_" + (10..99).random()
            _vaultId.value = randomId
            sharedPrefs.edit().putString("vault_id", randomId).apply()
        }
        syncWithBackend()
    }

    fun updateVaultId(newId: String) {
        val cleanId = newId.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "")
        if (cleanId.isNotEmpty()) {
            _vaultId.value = cleanId
            sharedPrefs.edit().putString("vault_id", cleanId).apply()
            syncWithBackend()
        }
    }

    fun syncWithBackend() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _syncStatus.value = "syncing"
            val currentVaultId = _vaultId.value
            if (currentVaultId.isEmpty()) {
                _syncStatus.value = "idle"
                return@launch
            }
            DebugLogger.sys("SYSTEM", "Starting two-way synchronization with cloud vault ID: '$currentVaultId'")

            try {
                // Wait briefly for allRoutes to initialize from Room DB
                var localRoutes = allRoutes.value
                var attempts = 0
                while (localRoutes.isEmpty() && attempts < 20) {
                    delay(100)
                    localRoutes = allRoutes.value
                    attempts++
                }

                DebugLogger.i("SYSTEM", "Fetching remote route logs for vault ID: '$currentVaultId'")
                val cloudJson = fetchFromCloud(currentVaultId)
                val cloudRoutes = if (cloudJson != null) JsonHelper.jsonToRoutes(cloudJson) else null

                val merged = mutableListOf<Route>()
                merged.addAll(localRoutes)

                var newSyncedCount = 0
                if (cloudRoutes != null) {
                    DebugLogger.i("SYSTEM", "Retrieved ${cloudRoutes.size} routes from remote cloud repository.")
                    for (cloudRoute in cloudRoutes) {
                        val existsLocally = localRoutes.any { it.id == cloudRoute.id }
                        if (!existsLocally) {
                            try {
                                repository.insert(cloudRoute)
                                merged.add(cloudRoute)
                                newSyncedCount++
                                DebugLogger.sys("DATABASE", "Downloaded and merged new remote route: '${cloudRoute.name}'")
                            } catch (e: Exception) {
                                DebugLogger.e("DATABASE", "Failed to insert downloaded cloud route: '${cloudRoute.name}'", e)
                            }
                        }
                    }
                } else {
                    DebugLogger.i("SYSTEM", "No remote routes found in vault '$currentVaultId' (or network offline).")
                }

                _syncStatus.value = "syncing"
                val success = pushToCloud(currentVaultId, merged)
                if (success) {
                    _syncStatus.value = "synced"
                    DebugLogger.sys("SYSTEM", "Cloud synchronization successful! Sync session closed. Merged local changes pushed.")
                } else {
                    _syncStatus.value = "failed"
                    DebugLogger.w("SYSTEM", "Cloud push failed. Sync state: non-aligned.")
                }
            } catch (e: Exception) {
                DebugLogger.e("SYSTEM", "Exception occured during background cloud synchronization", e)
                _syncStatus.value = "failed"
            }
        }
    }

    fun triggerSyncPush() {
        val currentVaultId = _vaultId.value
        if (currentVaultId.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _syncStatus.value = "syncing"
            DebugLogger.i("SYSTEM", "Triggering local route changes push to vault '$currentVaultId'...")
            delay(600) // allow internal state flows to catch up
            val currentRoutes = allRoutes.value
            val success = pushToCloud(currentVaultId, currentRoutes)
            if (success) {
                _syncStatus.value = "synced"
                DebugLogger.sys("SYSTEM", "Route modifications successfully synced with cloud vault.")
            } else {
                _syncStatus.value = "failed"
                DebugLogger.e("SYSTEM", "Failed to sync local configuration changes with remote cloud vault.")
            }
        }
    }

    private fun fetchFromCloud(vaultId: String): String? {
        val urlString = "https://kvdb.io/$vaultId/routes"
        try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (connection.responseCode == 200) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else if (connection.responseCode == 404) {
                return "[]"
            }
        } catch (e: Exception) {
            android.util.Log.e("RoadTrackerViewModel", "Error fetching from Cloud Vault", e)
        }
        return null
    }

    private fun pushToCloud(vaultId: String, routes: List<Route>): Boolean {
        val urlString = "https://kvdb.io/$vaultId/routes"
        try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Content-Type", "application/json")
            
            val json = JsonHelper.routesToJson(routes)
            connection.outputStream.use { os ->
                os.write(json.toByteArray(charset("UTF-8")))
            }
            if (connection.responseCode == 200 || connection.responseCode == 201) {
                return true
            }
        } catch (e: Exception) {
            android.util.Log.e("RoadTrackerViewModel", "Error pushing to Cloud Vault", e)
        }
        return false
    }

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

    private val _drawingWaypoints = MutableStateFlow<List<RoutePoint>>(emptyList())
    val drawingWaypoints: StateFlow<List<RoutePoint>> = _drawingWaypoints.asStateFlow()

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

    // Address Search State (Like Google Maps)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchMarker = MutableStateFlow<SearchResult?>(null)
    val searchMarker: StateFlow<SearchResult?> = _searchMarker.asStateFlow()

    // Live Rider Speed / Current Altitude or details (insightful statistics)
    private val _currentSpeedKmh = MutableStateFlow(0.0)
    val currentSpeedKmh: StateFlow<Double> = _currentSpeedKmh.asStateFlow()

    // Telemetry and sensors
    private val _currentLeanAngle = MutableStateFlow(0.0)
    val currentLeanAngle: StateFlow<Double> = _currentLeanAngle.asStateFlow()

    private val _maxLeftLean = MutableStateFlow(0.0)
    val maxLeftLean: StateFlow<Double> = _maxLeftLean.asStateFlow()

    private val _maxRightLean = MutableStateFlow(0.0)
    val maxRightLean: StateFlow<Double> = _maxRightLean.asStateFlow()

    private val _currentGForce = MutableStateFlow(1.0)
    val currentGForce: StateFlow<Double> = _currentGForce.asStateFlow()

    private val _maxGForce = MutableStateFlow(1.0)
    val maxGForce: StateFlow<Double> = _maxGForce.asStateFlow()

    private val _currentAltitude = MutableStateFlow(0.0)
    val currentAltitude: StateFlow<Double> = _currentAltitude.asStateFlow()

    private val _elevationGain = MutableStateFlow(0.0)
    val elevationGain: StateFlow<Double> = _elevationGain.asStateFlow()

    private val _maxSpeed = MutableStateFlow(0.0)
    val maxSpeed: StateFlow<Double> = _maxSpeed.asStateFlow()

    private val _activeTelemetrySamples = MutableStateFlow<List<TelemetrySample>>(emptyList())
    val activeTelemetrySamples: StateFlow<List<TelemetrySample>> = _activeTelemetrySamples.asStateFlow()

    // Handlebar Power Saver (Blackout Map Mode) State
    private val _isPowerSaverMode = MutableStateFlow(false)
    val isPowerSaverMode: StateFlow<Boolean> = _isPowerSaverMode.asStateFlow()

    private val _isMapBlackedOut = MutableStateFlow(false)
    val isMapBlackedOut: StateFlow<Boolean> = _isMapBlackedOut.asStateFlow()

    private val _blackoutReason = MutableStateFlow("Power Saver Inactive")
    val blackoutReason: StateFlow<String> = _blackoutReason.asStateFlow()

    private val _recentBearings = mutableListOf<Double>()
    private var temporaryWakeupJob: Job? = null

    fun togglePowerSaverMode() {
        val newValue = !_isPowerSaverMode.value
        _isPowerSaverMode.value = newValue
        if (!newValue) {
            _isMapBlackedOut.value = false
            _blackoutReason.value = "Power Saver Disabled"
            temporaryWakeupJob?.cancel()
        } else {
            evaluatePowerSaver()
        }
    }

    fun setPowerSaverMode(enabled: Boolean) {
        _isPowerSaverMode.value = enabled
        if (!enabled) {
            _isMapBlackedOut.value = false
            _blackoutReason.value = "Power Saver Disabled"
            temporaryWakeupJob?.cancel()
        } else {
            evaluatePowerSaver()
        }
    }

    fun tempWakeup() {
        if (!_isMapBlackedOut.value) return
        temporaryWakeupJob?.cancel()
        temporaryWakeupJob = viewModelScope.launch {
            _blackoutReason.value = "Temporarily Awake"
            _isMapBlackedOut.value = false
            delay(15000) // wake up for 15 seconds
            evaluatePowerSaver()
        }
    }

    private fun calculateBearingDifference(b1: Double, b2: Double): Double {
        val diff = Math.abs(b1 - b2) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    fun calculateBearing(p1: RoutePoint, p2: RoutePoint): Double {
        val lat1 = Math.toRadians(p1.lat)
        val lat2 = Math.toRadians(p2.lat)
        val dLng = Math.toRadians(p2.lng - p1.lng)
        
        val y = Math.sin(dLng) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
        val bearingRad = Math.atan2(y, x)
        return (Math.toDegrees(bearingRad) + 360.0) % 360.0
    }

    fun evaluatePowerSaver() {
        val previousBlackout = _isMapBlackedOut.value
        
        if (!_isPowerSaverMode.value || !_isTracking.value) {
            _isMapBlackedOut.value = false
            _blackoutReason.value = if (!_isTracking.value) "Not tracking" else "Power Saver Inactive"
            if (previousBlackout) {
                DebugLogger.sys("POWER", "Map blackout deactivated. Reason: ${_blackoutReason.value}")
            }
            return
        }

        // If a temporary manual wake-up is active, suspend automatic evaluation
        if (temporaryWakeupJob?.isActive == true) {
            return
        }

        val speed = _currentSpeedKmh.value
        val lean = _currentLeanAngle.value
        val gForce = _currentGForce.value

        // 1. Stopped or very slow: rider needs maps to navigate intersections
        if (speed < 5.0) {
            _isMapBlackedOut.value = false
            _blackoutReason.value = "Woke up: Speed low (< 5 km/h)"
            if (previousBlackout) {
                DebugLogger.sys("POWER", "Map woke up automatically. Reason: ${_blackoutReason.value}")
            }
            return
        }

        // 2. High Lean angle: currently cornering (Threshold: 18 degrees)
        if (lean > 18.0) {
            _isMapBlackedOut.value = false
            _blackoutReason.value = "Woke up: Lean angle " + String.format(Locale.US, "%.1f", lean) + "°"
            if (previousBlackout) {
                DebugLogger.sys("POWER", "Map woke up automatically. Reason: ${_blackoutReason.value}")
            }
            return
        }

        // 3. High G-Force load: G-Spikes during braking/acceleration should wake screen
        if (gForce > 1.35) {
            _isMapBlackedOut.value = false
            _blackoutReason.value = "Woke up: High Gforce " + String.format(Locale.US, "%.2f", gForce) + "G"
            if (previousBlackout) {
                DebugLogger.sys("POWER", "Map woke up automatically. Reason: ${_blackoutReason.value}")
            }
            return
        }

        // 4. Direction Change (Curve Detection in recent path):
        if (_recentBearings.isNotEmpty()) {
            val latestBearing = _recentBearings.last()
            val hasLargeDeviation = _recentBearings.any { b ->
                calculateBearingDifference(b, latestBearing) > 12.0
            }
            if (hasLargeDeviation) {
                _isMapBlackedOut.value = false
                _blackoutReason.value = "Woke up: Curve detected"
                if (previousBlackout) {
                    DebugLogger.sys("POWER", "Map woke up automatically. Reason: ${_blackoutReason.value}")
                }
                return
            }
        }

        // Target state satisfied! Screen turns black to save battery while riding straight.
        _isMapBlackedOut.value = true
        _blackoutReason.value = "Map Off: Riding Straight"
        if (!previousBlackout) {
            DebugLogger.sys("POWER", "Map blacking out to conserve battery. Reason: ${_blackoutReason.value}")
        }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || !_isTracking.value) return
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]

                // G-Force magnitude
                val gForceMag = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()) / 9.80665
                _currentGForce.value = gForceMag
                if (gForceMag > _maxGForce.value) {
                    _maxGForce.value = gForceMag
                }

                // Approximate Lean Roll Angle: Math.atan2(ax, ay)
                // Ax tilts left/right. Y gravity acts vertical.
                val normX = ax.toDouble()
                val normY = ay.toDouble()
                val normZ = az.toDouble()
                
                val roll = Math.atan2(normX, Math.sqrt(normY * normY + normZ * normZ)) * (180.0 / Math.PI)
                val leanDegrees = Math.abs(roll)
                _currentLeanAngle.value = leanDegrees
                
                if (roll < 0) {
                    if (leanDegrees > _maxLeftLean.value) {
                        _maxLeftLean.value = leanDegrees
                    }
                } else {
                    if (leanDegrees > _maxRightLean.value) {
                        _maxRightLean.value = leanDegrees
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun registerSensors() {
        _currentLeanAngle.value = 0.0
        _maxLeftLean.value = 0.0
        _maxRightLean.value = 0.0
        _currentGForce.value = 1.0
        _maxGForce.value = 1.0
        _currentAltitude.value = 0.0
        _elevationGain.value = 0.0
        _maxSpeed.value = 0.0
        _activeTelemetrySamples.value = emptyList()

        sensorManager?.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    private fun unregisterSensors() {
        sensorManager?.unregisterListener(sensorListener)
    }

    override fun onCleared() {
        super.onCleared()
        unregisterSensors()
    }

    // Trigger timer ticking in background
    private fun startTimer() {
        timerJob?.cancel()
        _startTime.value = System.currentTimeMillis()
        _elapsedSeconds.value = 0L
        timerJob = viewModelScope.launch {
            while (_isTracking.value) {
                val start = _startTime.value ?: System.currentTimeMillis()
                val elapsed = (System.currentTimeMillis() - start) / 1000
                _elapsedSeconds.value = elapsed

                // Simulate altitude/lean/G values if real sensors are static/missing (for testing & dashboard previews)
                if (accelerometer == null || _currentLeanAngle.value == 0.0) {
                    val wave = Math.sin(elapsed.toDouble() / 5.0)
                    val simulatedLean = Math.abs(wave * 28.0) + (1..3).random()
                    _currentLeanAngle.value = simulatedLean
                    if (wave < 0) {
                        _maxLeftLean.value = Math.max(_maxLeftLean.value, simulatedLean)
                    } else {
                        _maxRightLean.value = Math.max(_maxRightLean.value, simulatedLean)
                    }

                    val simulatedG = 1.0 + Math.abs(Math.cos(elapsed.toDouble() / 5.0)) * 0.45 + ((0..10).random() / 100.0)
                    _currentGForce.value = simulatedG
                    _maxGForce.value = Math.max(_maxGForce.value, simulatedG)

                    val simulatedAlt = 320.0 + Math.sin(elapsed.toDouble() / 40.0) * 15.0
                    _currentAltitude.value = simulatedAlt
                    if (simulatedAlt > 320.0) {
                        _elevationGain.value = (simulatedAlt - 320.0)
                    }
                }

                // Append telemetry live sample
                val sample = TelemetrySample(
                    timeSec = elapsed,
                    speedKmh = _currentSpeedKmh.value,
                    leanAngle = _currentLeanAngle.value,
                    gForce = _currentGForce.value,
                    altitude = _currentAltitude.value
                )
                _activeTelemetrySamples.value = _activeTelemetrySamples.value + sample

                // Periodically check Power Saver triggers
                evaluatePowerSaver()

                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
        unregisterSensors()
    }

    // --- Action Methods ---

    fun startTracking() {
        if (_isTracking.value) return
        DebugLogger.i("GPS", "User clicked Start Tracking. Resetting telemetry buffers.")
        _isDrawingMode.value = false // Mutually exclusive
        _isTracking.value = true
        _activeCoordinates.value = emptyList()
        _activeDistance.value = 0.0
        _currentSpeedKmh.value = 0.0
        _recentBearings.clear()
        _isMapBlackedOut.value = false
        temporaryWakeupJob?.cancel()
        registerSensors()
        startTimer()
    }

    fun addTrackingPoint(point: RoutePoint, speedMs: Float = 0f) {
        if (!_isTracking.value) return
        val currentList = _activeCoordinates.value.toMutableList()
        
        // Prevent duplicate consecutive coordinates to save memory/storage
        if (currentList.isNotEmpty()) {
            val last = currentList.last()
            if (last.lat == point.lat && last.lng == point.lng) return
            
            // Calculate bearing and append to sliding window
            val bearing = calculateBearing(last, point)
            _recentBearings.add(bearing)
            if (_recentBearings.size > 8) {
                _recentBearings.removeAt(0)
            }

            // Increment distance using Haversine
            val increment = calculateDistance(last, point)
            _activeDistance.value += increment
        }
        
        currentList.add(point)
        _activeCoordinates.value = currentList
        val speedKmh = speedMs * 3.6 // Convert ms to km/h
        _currentSpeedKmh.value = speedKmh
        if (speedKmh > _maxSpeed.value) {
            _maxSpeed.value = speedKmh
        }

        // Recalculate power saver blackout triggers
        evaluatePowerSaver()
    }

    fun stopAndSaveTracking(customName: String?): String? {
        if (!_isTracking.value) return null
        DebugLogger.i("GPS", "Stopping active track logging. Points registered: ${_activeCoordinates.value.size}")
        _isTracking.value = false
        stopTimer()
        _recentBearings.clear()
        _isMapBlackedOut.value = false
        temporaryWakeupJob?.cancel()

        val points = _activeCoordinates.value
        val dist = _activeDistance.value
        if (points.size < 2) {
            DebugLogger.w("GPS", "Tracking stopped but discarded: too few coordinate points recorded (${points.size}).")
            _activeCoordinates.value = emptyList()
            _activeDistance.value = 0.0
            unregisterSensors()
            return "Ride canceled: Not enough movement coordinates recorded."
        }

        val name = if (customName.isNullOrBlank()) {
            "Ride of ${SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date())}"
        } else {
            customName
        }

        val avgSp = if (_elapsedSeconds.value > 0) (dist / (_elapsedSeconds.value / 3600.0)) else 0.0
        val serializedTelemetry = JsonHelper.telemetryToJson(_activeTelemetrySamples.value)

        val route = Route(
            id = generateUniqueId(),
            name = name,
            date = System.currentTimeMillis(),
            coordinates = points,
            distance = dist,
            mode = "gps",
            durationSeconds = _elapsedSeconds.value,
            maxSpeed = _maxSpeed.value,
            avgSpeed = avgSp,
            maxLeanAngle = Math.max(_maxLeftLean.value, _maxRightLean.value),
            maxGForce = _maxGForce.value,
            elevationGain = _elevationGain.value,
            telemetryJson = serializedTelemetry
        )

        DebugLogger.i("DATABASE", "Saving route '${route.name}' to local database. (Points: ${points.size}, Dist: ${String.format(Locale.US, "%.2f km", dist)})")
        viewModelScope.launch {
            try {
                repository.insert(route)
                DebugLogger.sys("DATABASE", "Route '${route.name}' successfully committed to Room DB. Syncing with backend...")
                triggerSyncPush()
            } catch (e: Exception) {
                DebugLogger.e("DATABASE", "CRITICAL error occurred while saving tracking route: '${route.name}'", e)
            }
        }

        // Reset tracking buffers
        _activeCoordinates.value = emptyList()
        _activeDistance.value = 0.0
        unregisterSensors()
        return null
    }

    fun discardActiveTracking() {
        DebugLogger.w("GPS", "User initiated tracking discard. Purging active coordinate and sensor data.")
        _isTracking.value = false
        stopTimer()
        _recentBearings.clear()
        _isMapBlackedOut.value = false
        temporaryWakeupJob?.cancel()
        _activeCoordinates.value = emptyList()
        _activeDistance.value = 0.0
        unregisterSensors()
    }

    // Toggle manual drawing mode
    fun toggleDrawingMode() {
        _isDrawingMode.value = !_isDrawingMode.value
        if (!_isDrawingMode.value) {
            _drawingWaypoints.value = emptyList()
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
            _drawingWaypoints.value = emptyList()
            _drawingPoints.value = emptyList()
            _drawingDistance.value = 0.0
        }
    }

    fun addDrawingPoint(point: RoutePoint) {
        if (!_isDrawingMode.value) return
        val currentWaypoints = _drawingWaypoints.value.toMutableList()
        currentWaypoints.add(point)
        _drawingWaypoints.value = currentWaypoints

        // Immediate straight-line feedback for responsive UX
        _drawingPoints.value = currentWaypoints
        _drawingDistance.value = calculatePathDistance(currentWaypoints)

        // Asynchronously snap to roads using OSRM
        triggerRoadRecalculation()
    }

    fun removeDrawingPoint(index: Int) {
        if (!_isDrawingMode.value) return
        val currentWaypoints = _drawingWaypoints.value.toMutableList()
        if (index in currentWaypoints.indices) {
            currentWaypoints.removeAt(index)
            _drawingWaypoints.value = currentWaypoints

            // Immediate fall back
            _drawingPoints.value = currentWaypoints
            _drawingDistance.value = calculatePathDistance(currentWaypoints)

            // Asynchronously snap to roads using OSRM
            triggerRoadRecalculation()
        }
    }

    fun undoDrawingPoint() {
        if (!_isDrawingMode.value) return
        val currentWaypoints = _drawingWaypoints.value.toMutableList()
        if (currentWaypoints.isNotEmpty()) {
            currentWaypoints.removeAt(currentWaypoints.size - 1)
            _drawingWaypoints.value = currentWaypoints

            // Immediate fall back
            _drawingPoints.value = currentWaypoints
            _drawingDistance.value = calculatePathDistance(currentWaypoints)

            // Asynchronously snap to roads using OSRM
            triggerRoadRecalculation()
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
            try {
                repository.insert(route)
                triggerSyncPush()
            } catch (e: Exception) {
                DebugLogger.e("DATABASE", "CRITICAL error occurred while saving manual route: '${route.name}'", e)
            }
        }

        // Reset drawing buffers
        _drawingWaypoints.value = emptyList()
        _drawingPoints.value = emptyList()
        _drawingDistance.value = 0.0
        _isDrawingMode.value = false
        return null
    }

    private var recalculationJob: Job? = null

    private fun triggerRoadRecalculation() {
        val waypoints = _drawingWaypoints.value
        if (waypoints.size < 2) {
            _drawingPoints.value = waypoints
            _drawingDistance.value = calculatePathDistance(waypoints)
            return
        }

        recalculationJob?.cancel()
        recalculationJob = viewModelScope.launch {
            val snapped = fetchRoadRouteMulti(waypoints)
            if (snapped.isNotEmpty()) {
                _drawingPoints.value = snapped
                _drawingDistance.value = calculatePathDistance(snapped)
            }
        }
    }

    private suspend fun fetchRoadRouteMulti(waypoints: List<RoutePoint>): List<RoutePoint> {
        if (waypoints.size < 2) return waypoints
        val coordsString = waypoints.joinToString(";") { "${it.lng},${it.lat}" }
        val urlString = "https://router.project-osrm.org/route/v1/driving/$coordsString?overview=full&geometries=geojson"
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(responseText)
                    val code = json.optString("code")
                    if (code == "Ok") {
                        val routes = json.optJSONArray("routes")
                        if (routes != null && routes.length() > 0) {
                            val routeObj = routes.getJSONObject(0)
                            val geometry = routeObj.getJSONObject("geometry")
                            val coordinates = geometry.getJSONArray("coordinates")
                            val roadPoints = mutableListOf<RoutePoint>()
                            for (i in 0 until coordinates.length()) {
                                val coord = coordinates.getJSONArray(i)
                                val lng = coord.getDouble(0)
                                val lat = coord.getDouble(1)
                                roadPoints.add(RoutePoint(lat, lng))
                            }
                            return@withContext roadPoints
                        }
                    }
                }
                waypoints
            } catch (e: Exception) {
                android.util.Log.e("RoadTrackerViewModel", "Error routing multi waypoints", e)
                waypoints
            }
        }
    }

    fun deleteRoute(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            if (_selectedRoute.value?.id == id) {
                _selectedRoute.value = null
            }
            triggerSyncPush()
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

    fun centerMapOn(point: RoutePoint) {
        _mapCenter.value = point
    }

    private var searchJob: Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            searchJob?.cancel()
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500) // Debounce search
            _isSearching.value = true
            try {
                val results = performAddressSearch(query)
                _searchResults.value = results
            } catch (e: Exception) {
                _searchResults.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    private suspend fun performAddressSearch(query: String): List<SearchResult> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val urlString = "https://nominatim.openstreetmap.org/search?format=json&q=$encodedQuery&limit=8&addressdetails=1"
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "RoadTracker/1.0 (orestevangel@gmail.com)")
                
                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = org.json.JSONArray(responseText)
                    val results = mutableListOf<SearchResult>()
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val displayName = item.optString("display_name", "")
                        val lat = item.optDouble("lat", 0.0)
                        val lon = item.optDouble("lon", 0.0)
                        
                        val parts = displayName.split(",")
                        val nameStr = parts.firstOrNull()?.trim() ?: displayName
                        val descStr = if (parts.size > 1) {
                            parts.drop(1).joinToString(",").trim()
                        } else {
                            ""
                        }
                        
                        results.add(
                            SearchResult(
                                name = nameStr,
                                description = descStr,
                                lat = lat,
                                lng = lon
                            )
                        )
                    }
                    return@withContext results
                }
                emptyList()
            } catch (e: Exception) {
                android.util.Log.e("RoadTrackerViewModel", "Error searching address", e)
                emptyList()
            }
        }
    }

    fun selectSearchResult(result: SearchResult) {
        _searchMarker.value = result
        _mapCenter.value = RoutePoint(result.lat, result.lng)
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun clearSearchMarker() {
        _searchMarker.value = null
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
            try {
                repository.insert(route)
                triggerSyncPush()
            } catch (e: Exception) {
                DebugLogger.e("DATABASE", "CRITICAL error occurred while importing GPX route: '${route.name}'", e)
            }
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

class RoadTrackerViewModelFactory(
    private val repository: RouteRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RoadTrackerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return RoadTrackerViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
