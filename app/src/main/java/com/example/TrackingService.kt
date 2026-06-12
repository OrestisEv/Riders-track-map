package com.example

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.data.RoutePoint
import com.example.data.DebugLogger
import com.example.ui.RoadTrackerViewModel
import com.google.android.gms.location.*

class TrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceHandlerThread: android.os.HandlerThread? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                DebugLogger.i("TRACKING_SERVICE", "Service location update: [${location.latitude}, ${location.longitude}], Accuracy: ${location.accuracy}m, Speed: ${location.speed} m/s")
                val point = RoutePoint(location.latitude, location.longitude)
                
                // Keep MainActivity state up-to-date
                MainActivity.currentUserLocation.value = point
                
                // Send to ViewModel
                activeViewModel?.let { vm ->
                    val isTelemetryEnabled = getSharedPreferences("roadtracker_cloud_sync", Context.MODE_PRIVATE)
                        .getBoolean("is_telemetry_enabled", true)
                    
                    if (isTelemetryEnabled) {
                        vm.addTrackingPoint(point, location.speed)
                    } else {
                        // With telemetry off, we record points but don't record angle, gravity, peak values
                        vm.addTrackingPoint(point, location.speed)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DebugLogger.sys("TRACKING_SERVICE", "TrackingService onCreate - initializing providers.")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        // Setup a dedicated HandlerThread to process GPS location callbacks on a background thread.
        // This ensures updates are processed continuously even if the main UI Thread looper is throttled or suspended when screen is turned off.
        serviceHandlerThread = android.os.HandlerThread("TrackingServiceLocationThread").apply {
            start()
        }
        
        // Setup WakeLock to keep CPU awake during long foreground rides so GPS keeps tracking with screen off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoadTracker::TrackingWakeLock")
        try {
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
            DebugLogger.i("TRACKING_SERVICE", "Wakelock acquired.")
        } catch (e: Exception) {
            DebugLogger.e("TRACKING_SERVICE", "Failed to acquire wakelock", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        DebugLogger.i("TRACKING_SERVICE", "onStartCommand action: $action")
        
        if (action == ACTION_START_TRACKING) {
            startForegroundService()
            requestLocationUpdates()
        } else if (action == ACTION_STOP_TRACKING) {
            stopLocationUpdates()
            
            // If GPS mode is Low accuracy (0), capture the last point when stopping
            val sharedPrefs = getSharedPreferences("roadtracker_cloud_sync", Context.MODE_PRIVATE)
            val accuracyPreset = sharedPrefs.getInt("gps_accuracy_preset", 2)
            if (accuracyPreset == 0) {
                try {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            val point = RoutePoint(location.latitude, location.longitude)
                            MainActivity.currentUserLocation.value = point
                            activeViewModel?.addTrackingPoint(point, location.speed)
                            DebugLogger.i("TRACKING_SERVICE", "Low Accuracy preset: Captured ending coordinate point.")
                        }
                    }
                } catch (e: Exception) {
                    DebugLogger.e("TRACKING_SERVICE", "Failed to capture low-accuracy ending location", e)
                }
            }
            
            stopSelf()
        }
        
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "road_tracker_channel"
        val channelName = "Road Tracker Core Service"
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var channel = notificationManager.getNotificationChannel(channelId)
            if (channel == null) {
                channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps motorcycle telemetry and high-precision GPS tracking running fluidly in background"
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        // Action intent to open the app when clicking the notification
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (notificationIntent != null) {
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            android.app.PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags)
        } else null

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Velocitron Co-Pilot Active")
            .setContentText("Telemetry sensor array and motorcycle track logging is engaged in background.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val sharedPrefs = getSharedPreferences("roadtracker_cloud_sync", Context.MODE_PRIVATE)
        val accuracyPreset = sharedPrefs.getInt("gps_accuracy_preset", 2)
        DebugLogger.i("TRACKING_SERVICE", "Starting location updates with accuracyPreset: $accuracyPreset")

        if (accuracyPreset == 0) {
            // Low Accuracy: Collect start and end point.
            // Under start, we capture the starting point immediately.
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        val point = RoutePoint(location.latitude, location.longitude)
                        MainActivity.currentUserLocation.value = point
                        activeViewModel?.addTrackingPoint(point, location.speed)
                        DebugLogger.i("TRACKING_SERVICE", "Low Accuracy preset: Captured starting coordinate point.")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("TRACKING_SERVICE", "Failed to capture startup location", e)
            }
            // Do not request continuous period updates to conserve 100% of background battery.
            return
        }

        // Setup intervals based on preset:
        // Mix: 5 minutes (300,000 milliseconds)
        // Max: 3 seconds (3,000 milliseconds)
        val intervalMillis = if (accuracyPreset == 1) 300000L else 3000L
        val minDistance = if (accuracyPreset == 1) 10.0f else 1.0f

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis).apply {
            setMinUpdateIntervalMillis(intervalMillis / 2)
            setMinUpdateDistanceMeters(minDistance)
        }.build()

        val looper = serviceHandlerThread?.looper ?: android.os.Looper.getMainLooper()
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                looper
            )
        } catch (e: Exception) {
            DebugLogger.e("TRACKING_SERVICE", "Failed to start location updates in service", e)
        }
    }

    private fun stopLocationUpdates() {
        DebugLogger.i("TRACKING_SERVICE", "Removing location updates from high-precision client service.")
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroy() {
        DebugLogger.sys("TRACKING_SERVICE", "Destroying tracking service. Releasing resources.")
        stopLocationUpdates()
        serviceHandlerThread?.quitSafely()
        serviceHandlerThread = null
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    companion object {
        const val ACTION_START_TRACKING = "com.example.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.action.STOP_TRACKING"
        const val NOTIFICATION_ID = 2026

        // Shared static references to keep things clean and direct
        var activeViewModel: RoadTrackerViewModel? = null
    }
}
