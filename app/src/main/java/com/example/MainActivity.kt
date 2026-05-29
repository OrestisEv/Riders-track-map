package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.Route
import com.example.data.RoutePoint
import com.example.data.RouteRepository
import com.example.ui.LeafletMapView
import com.example.ui.RoadTrackerViewModel
import com.example.ui.RoadTrackerViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var viewModel: RoadTrackerViewModel

    private val currentUserLocation = mutableStateOf<RoutePoint?>(null)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                val point = RoutePoint(location.latitude, location.longitude)
                viewModel.addTrackingPoint(point, location.speed)
                currentUserLocation.value = point
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(this)
        val repository = RouteRepository(database.routeDao())
        viewModel = ViewModelProvider(this, RoadTrackerViewModelFactory(repository))[RoadTrackerViewModel::class.java]

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Reactively observe tracking flow to start/stop live GPS updates
        lifecycleScope.launch {
            viewModel.isTracking.collectLatest { tracking ->
                if (tracking) {
                    startLocationUpdates()
                } else {
                    stopLocationUpdates()
                }
            }
        }

        setContent {
            MyApplicationTheme {
                RoadTrackerApp(
                    viewModel = viewModel,
                    currentUserLocation = currentUserLocation,
                    onStartLocationUpdates = { startLocationUpdates() },
                    onStopLocationUpdates = { stopLocationUpdates() },
                    onFetchLastLocation = { fetchLastKnownLocation() }
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L).apply {
            setMinUpdateIntervalMillis(1500L)
            setMinUpdateDistanceMeters(1.0f)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                android.os.Looper.getMainLooper()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")
    private fun fetchLastKnownLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val point = RoutePoint(location.latitude, location.longitude)
                    currentUserLocation.value = point
                    viewModel.selectRoute(null) // Reset selection to trigger centering
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@Suppress("DefaultLocale")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadTrackerApp(
    viewModel: RoadTrackerViewModel,
    currentUserLocation: MutableState<RoutePoint?>,
    onStartLocationUpdates: () -> Unit,
    onStopLocationUpdates: () -> Unit,
    onFetchLastLocation: () -> Unit
) {
    val context = LocalContext.current
    var hasLocationPermission by remember { mutableStateOf(false) }

    // State bindings
    val routes by viewModel.allRoutes.collectAsStateWithLifecycle()
    val isTracking by viewModel.isTracking.collectAsStateWithLifecycle()
    val activeDistance by viewModel.activeDistance.collectAsStateWithLifecycle()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsStateWithLifecycle()
    val isDrawingMode by viewModel.isDrawingMode.collectAsStateWithLifecycle()
    val drawingDistance by viewModel.drawingDistance.collectAsStateWithLifecycle()
    val selectedRoute by viewModel.selectedRoute.collectAsStateWithLifecycle()
    val currentSpeedKmh by viewModel.currentSpeedKmh.collectAsStateWithLifecycle()

    // Trigger Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            hasLocationPermission = true
            onFetchLastLocation()
        } else {
            Toast.makeText(context, "Location permission is required for motorcycle live recording mode.", Toast.LENGTH_LONG).show()
        }
    }

    // Auto trigger on launch
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // Dialog state controllers
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDrawingSaveDialog by remember { mutableStateOf(false) }
    var routeNameToSave by remember { mutableStateOf("") }
    var pendingRouteForDeletion by remember { mutableStateOf<Route?>(null) }
    
    // Bottom Sheet Expansion State
    var isSheetExpanded by remember { mutableStateOf(false) }

    // Document picker for GPX files
    val gpxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val text = inputStream.bufferedReader().use { it.readText() }
                    val result = viewModel.importGpx(text)
                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read GPX file format.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Cumulative stats
    val totalDistance = routes.sumOf { it.distance }
    val totalRidesCount = routes.size

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.statusBars
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF08080C))
        ) {
            // 1. Leaflet Interactive Map Layer (Taking full screen space)
            LeafletMapView(
                modifier = Modifier.fillMaxSize(),
                viewModel = viewModel,
                userLocation = currentUserLocation.value
            )

            // 2. Head-Up Display: Dynamic TFT Dashboard Stats (Top Overlays)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter)
            ) {
                // Main stats block
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xCC111116))
                        .border(1.dp, Color(0x3300E5FF), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Total coverage
                        Column {
                            Text(
                                text = "TOTAL COVERAGE",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8E8E93),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = String.format("%.1f KM", totalDistance),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF00E5FF),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Total journeys
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "SAVED PATHS",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8E8E93),
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "$totalRidesCount",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFF6B00),
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // GPS Indicator status lamp
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val pulseState = rememberInfiniteTransition(label = "gpsGlow")
                            val alphaGlow by pulseState.animateFloat(
                                initialValue = 0.3f,
                                targetValue = 1.0f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = EaseInOutSine),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "glow"
                            )
                            
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isTracking) Color(0xFF39FF14)
                                        else if (hasLocationPermission) Color(0xFF00E5FF)
                                        else Color(0xFFFF007F)
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isTracking) "REC GO" else if (hasLocationPermission) "GPS RDY" else "NO GPS",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isTracking) Color(0xFF39FF14).copy(alpha = alphaGlow) else Color(0xFFE5E5EA),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Active Recording HUD Overlay
                AnimatedVisibility(
                    visible = isTracking,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xDD0D0D13))
                            .border(1.dp, Color(0xFF39FF14), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Trip Distance
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("TRIP DIST", fontSize = 9.sp, color = Color(0xFF8E8E93))
                                Text(
                                    text = String.format("%.2f KM", activeDistance),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF39FF14),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            // Odometer speed
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("SPEED", fontSize = 9.sp, color = Color(0xFF8E8E93))
                                Text(
                                    text = String.format("%.0f KM/H", currentSpeedKmh),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE5E5EA),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            // Elapsed stopwatch
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("DURATION", fontSize = 9.sp, color = Color(0xFF8E8E93))
                                Text(
                                    text = formatElapsedTime(elapsedSeconds),
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE5E5EA),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }

                // Manual Drawing HUD Overlay
                AnimatedVisibility(
                    visible = isDrawingMode,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xEE1A110D))
                            .border(1.dp, Color(0xFFFF6B00), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.EditRoad,
                                        contentDescription = "Edit Mode",
                                        tint = Color(0xFFFF6B00),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "MANUAL ROUTE DESIGN",
                                        fontSize = 11.sp,
                                        color = Color(0xFFFF6B00),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = String.format("%.2f KM", drawingDistance),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00E5FF),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap coordinates on map to build path nodes. Tap any marker node to remove it.",
                                fontSize = 10.sp,
                                color = Color(0xFFB3B3C2)
                            )
                        }
                    }
                }
            }

            // 3. Floating Quick action buttons on Map
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = if (isSheetExpanded) 360.dp else 90.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Location zoom utility
                FloatingActionButton(
                    onClick = {
                        onFetchLastLocation()
                        currentUserLocation.value?.let {
                            viewModel.selectRoute(null) // trigger centering
                        }
                    },
                    containerColor = Color(0xFF13131D),
                    contentColor = Color(0xFF00E5FF),
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MyLocation,
                        contentDescription = "Center Location",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Drawing toggle mode fab
                FloatingActionButton(
                    onClick = {
                        viewModel.toggleDrawingMode()
                    },
                    containerColor = if (isDrawingMode) Color(0xFFFF6B00) else Color(0xFF13131D),
                    contentColor = if (isDrawingMode) Color(0xFF0D0D13) else Color(0xFFFF6B00),
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        imageVector = if (isDrawingMode) Icons.Default.Map else Icons.Default.Gesture,
                        contentDescription = "Manual Drawing mode",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Saved Routes Journal Drawer toggle
                FloatingActionButton(
                    onClick = {
                        isSheetExpanded = !isSheetExpanded
                    },
                    containerColor = if (isSheetExpanded) Color(0xFF00E5FF) else Color(0xFF13131D),
                    contentColor = if (isSheetExpanded) Color(0xFF0D0D13) else Color(0xFF00E5FF),
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        imageVector = if (isSheetExpanded) Icons.Default.Book else Icons.Default.History,
                        contentDescription = "Ride Journal Panel",
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // GIANT Live Stop/Start Tracking Control FAB
                if (isDrawingMode) {
                    // Manual Draw save/cancel group
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Undo point button
                        FloatingActionButton(
                            onClick = { viewModel.undoDrawingPoint() },
                            containerColor = Color(0xCC1A1D24),
                            contentColor = Color(0xFFE5E5EA),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo node", modifier = Modifier.size(18.dp))
                        }

                        // Save drawing route button
                        ExtendedFloatingActionButton(
                            onClick = {
                                if (viewModel.drawingPoints.value.size < 2) {
                                    Toast.makeText(context, "Traced road requires at least 2 coordinate points.", Toast.LENGTH_SHORT).show()
                                } else {
                                    routeNameToSave = "Manual Ride - " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
                                    showDrawingSaveDialog = true
                                }
                            },
                            containerColor = Color(0xFFFF6B00),
                            contentColor = Color(0xFF0D0D13),
                            icon = { Icon(Icons.Default.Done, "Save path") },
                            text = { Text("Save Path") },
                            modifier = Modifier.height(48.dp)
                        )
                    }
                } else {
                    // Start GPS ride track toggle FAB (impossible to miss)
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 1.0f,
                        targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "scale"
                    )

                    ExtendedFloatingActionButton(
                        onClick = {
                            if (isTracking) {
                                routeNameToSave = "Motorcycle Route - " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
                                showSaveDialog = true
                            } else {
                                viewModel.startTracking()
                            }
                        },
                        containerColor = if (isTracking) Color(0xFFFF007F) else Color(0xFF39FF14),
                        contentColor = Color(0xFF08080C),
                        elevation = FloatingActionButtonDefaults.elevation(12.dp),
                        modifier = Modifier
                            .scale(if (isTracking) pulseScale else 1.0f)
                            .height(56.dp)
                            .testTag("submit_button"),
                        icon = {
                            Icon(
                                imageVector = if (isTracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (isTracking) "Stop Recording" else "Start Ride Tracking",
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        text = {
                            Text(
                                text = if (isTracking) "STOP RIDE" else "START RIDE",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                letterSpacing = 1.2.sp
                            )
                        }
                    )
                }
            }

            // 4. Slide-Up Stats & Route History Journal Sheet
            AnimatedVisibility(
                visible = isSheetExpanded,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = Color(0xFF0D0D15),
                    tonalElevation = 8.dp,
                    border = BorderStroke(1.dp, Color(0x3300E5FF))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        // Drag handle / Title row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.TwoWheeler,
                                    contentDescription = "Motorbike routes",
                                    tint = Color(0xFF00E5FF)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "RIDE COOPER JNL",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFFE5E5EA),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp
                                )
                            }
                            
                            Row(
                                modifier = Modifier.wrapContentSize(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Import GPX button
                                TextButton(
                                    onClick = { gpxPickerLauncher.launch("application/gpx+xml") },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF39FF14))
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = "Import Tracker GPX", modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("IMPORT GPX", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }

                                // Minimize icon
                                IconButton(onClick = { isSheetExpanded = false }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close sheet",
                                        tint = Color(0xFF8E8E93)
                                    )
                                }
                            }
                        }

                        Divider(color = Color(0x338E8E93), modifier = Modifier.padding(bottom = 8.dp))

                        if (routes.isEmpty()) {
                            // Blank empty state panel
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Map,
                                        contentDescription = "No routes",
                                        tint = Color(0xFF48484A),
                                        modifier = Modifier.size(54.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Your coloring canvas is blank!",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = Color(0xFF8E8E93)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Record a GPS ride or draw a custom traced path.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF545456),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            // Saved routes LazyList
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                items(routes) { route ->
                                    val isCurrentSelected = selectedRoute?.id == route.id
                                    
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isCurrentSelected) Color(0xFF1E1E2F) else Color(0xFF13131D))
                                            .border(
                                                1.dp,
                                                if (isCurrentSelected) Color(0xFF00E5FF) else Color(0x118E8E93),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                viewModel.selectRoute(route)
                                            }
                                            .padding(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Mode icon color tag indicator
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp, 36.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(
                                                            if (route.mode == "gps") Color(0xFF00E5FF)
                                                            else Color(0xFFFF007F)
                                                        )
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    Text(
                                                        text = route.name,
                                                        fontSize = 13.sp,
                                                        color = Color(0xFFE5E5EA),
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(route.date)) + 
                                                               " • " + if(route.mode == "gps") "🏍️ GPS" else "🗺️ Draw",
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF8E8E93)
                                                    )
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                // Distance display Badge
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(Color(0xFF08080C))
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = String.format("%.2f km", route.distance),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = if (route.mode == "gps") Color(0xFF00E5FF) else Color(0xFFFF007F)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))

                                                // Share GPX button
                                                IconButton(
                                                    onClick = {
                                                        // Generate GPX string and share it natively
                                                        val intent = Intent().apply {
                                                            action = Intent.ACTION_SEND
                                                            putExtra(Intent.EXTRA_TEXT, viewModel.exportRouteToGpx(route))
                                                            type = "text/xml"
                                                        }
                                                        context.startActivity(Intent.createChooser(intent, "Share Journey GPX"))
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Share,
                                                        contentDescription = "Export GPX data",
                                                        tint = Color(0xFF8E8E93),
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }

                                                // Trash/Delete Button
                                                IconButton(
                                                    onClick = {
                                                        pendingRouteForDeletion = route
                                                    },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.DeleteOutline,
                                                        contentDescription = "Delete path record",
                                                        tint = Color(0xFFFF453A),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal: Save Recorded Ride Name dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { 
                showSaveDialog = false 
            },
            containerColor = Color(0xFF13131D),
            title = {
                Text(
                    "CONCLUDE RIDE MOTORCYCLE",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )
            },
            text = {
                Column {
                    Text(
                        "Would you like to save this logged route and add its coloring overlay permanently?",
                        fontSize = 12.sp,
                        color = Color(0xFFE5E5EA),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = routeNameToSave,
                        onValueChange = { routeNameToSave = it },
                        label = { Text("Route Name", color = Color(0xFF8E8E93)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF00E5FF),
                            unfocusedBorderColor = Color(0x33E5E5EA),
                            focusedTextColor = Color(0xFFE5E5EA),
                            unfocusedTextColor = Color(0xFFE5E5EA)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.discardActiveTracking()
                        showSaveDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF453A))
                ) {
                    Text("DISCARD", fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val error = viewModel.stopAndSaveTracking(routeNameToSave)
                        if (error != null) {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Road coloring registered successfully!", Toast.LENGTH_SHORT).show()
                        }
                        showSaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF39FF14), contentColor = Color(0xFF0D0D13))
                ) {
                    Text("SAVE RIDE", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Modal: Save Drawn Route Name dialog
    if (showDrawingSaveDialog) {
        AlertDialog(
            onDismissRequest = { showDrawingSaveDialog = false },
            containerColor = Color(0xFF13131D),
            title = {
                Text(
                    "SAVE MANUAL PATH TRACE",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF6B00)
                )
            },
            text = {
                Column {
                    Text(
                        "Provide a custom name for this manually drawn route.",
                        fontSize = 12.sp,
                        color = Color(0xFFE5E5EA),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = routeNameToSave,
                        onValueChange = { routeNameToSave = it },
                        label = { Text("Route Name", color = Color(0xFF8E8E93)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF6B00),
                            unfocusedBorderColor = Color(0x33E5E5EA),
                            focusedTextColor = Color(0xFFE5E5EA),
                            unfocusedTextColor = Color(0xFFE5E5EA)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDrawingSaveDialog = false }) {
                    Text("CANCEL", color = Color(0xFF8E8E93))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val error = viewModel.saveDrawingRoute(routeNameToSave)
                        if (error != null) {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Manual route saved!", Toast.LENGTH_SHORT).show()
                        }
                        showDrawingSaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B00), contentColor = Color(0xFF0D0D13))
                ) {
                    Text("SAVE TRACE", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Modal: Delete Path Confirmation
    if (pendingRouteForDeletion != null) {
        AlertDialog(
            onDismissRequest = { pendingRouteForDeletion = null },
            containerColor = Color(0xFF13131D),
            title = {
                Text(
                    "DELETE ROUTE RECORD",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFF453A)
                )
            },
            text = {
                Text(
                    "Are you absolutely sure you want to delete '${pendingRouteForDeletion?.name}'? This will erase the road coloring for this journey. Action cannot be undone.",
                    fontSize = 12.sp,
                    color = Color(0xFFE5E5EA)
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingRouteForDeletion = null }) {
                    Text("CANCEL", color = Color(0xFF8E8E93))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRouteForDeletion?.let {
                            viewModel.deleteRoute(it.id)
                            Toast.makeText(context, "Route deleted", Toast.LENGTH_SHORT).show()
                        }
                        pendingRouteForDeletion = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A), contentColor = Color(0xFFE5E5EA))
                ) {
                    Text("YES, DELETE", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

fun formatElapsedTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
