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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.example.data.SearchResult
import com.example.data.DebugLogger
import com.example.ui.LeafletMapView
import com.example.ui.RoadTrackerViewModel
import com.example.ui.RoadTrackerViewModelFactory
import com.example.ui.SavedRoutesPage
import com.example.ui.TelemetryDashboardPage
import com.example.ui.AboutCockpitPage
import com.example.ui.theme.*
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
                DebugLogger.i("GPS", "Location update: [${location.latitude}, ${location.longitude}], Accuracy: ${location.accuracy}m, Speed: ${location.speed} m/s")
                val point = RoutePoint(location.latitude, location.longitude)
                viewModel.addTrackingPoint(point, location.speed)
                currentUserLocation.value = point
            }
        }
    }

    private val showDeveloperErrorDialog = mutableStateOf(false)

    private fun getCertificateSHA1(context: Context): String {
        try {
            val pm = context.packageManager
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                android.content.pm.PackageManager.GET_SIGNATURES
            }
            val packageInfo = pm.getPackageInfo(context.packageName, flags)
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
            
            if (!signatures.isNullOrEmpty()) {
                val cert = signatures[0].toByteArray()
                val md = java.security.MessageDigest.getInstance("SHA-1")
                val publicKey = md.digest(cert)
                val hexString = StringBuilder()
                for (i in publicKey.indices) {
                    val appendString = Integer.toHexString(0xFF and publicKey[i].toInt()).uppercase()
                    if (appendString.length == 1) {
                        hexString.append("0")
                    }
                    hexString.append(appendString)
                    if (i < publicKey.size - 1) {
                        hexString.append(":")
                    }
                }
                return hexString.toString()
            }
        } catch (e: Exception) {
            DebugLogger.e("SIGNATURE_SHA1", "Error fetching SHA-1 signature", e)
        }
        return "Could not retrieve SHA-1 fingerprint"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize diagnostic debug logger
        DebugLogger.init(applicationContext)
        DebugLogger.sys("SYSTEM", "Velocitron mobile subsystem active. Version 2.4.0-RC initialized successfully.")

        // Initialize Supabase Manager safely
        try {
            com.example.data.SupabaseManager.initialize(this)
        } catch (e: Exception) {
            DebugLogger.e("SUPABASE", "Exception initializing SupabaseManager", e)
        }

        val database = AppDatabase.getDatabase(this)
        val repository = RouteRepository(database.routeDao())
        viewModel = ViewModelProvider(this, RoadTrackerViewModelFactory(repository, this))[RoadTrackerViewModel::class.java]

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Handle deep link when the activity is opened via OAuth callback
        lifecycleScope.launch {
            try {
                intent?.let {
                    com.example.data.SupabaseManager.handleDeepLink(it)
                }
            } catch (e: Exception) {
                DebugLogger.e("DEEP_LINK", "Error handling deep link in onCreate", e)
            }
        }

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
                Box(modifier = Modifier.fillMaxSize()) {
                    RoadTrackerApp(
                        viewModel = viewModel,
                        currentUserLocation = currentUserLocation,
                        onStartLocationUpdates = { startLocationUpdates() },
                        onStopLocationUpdates = { stopLocationUpdates() },
                        onFetchLastLocation = { forceCenter -> fetchLastKnownLocation(forceCenter) },
                        onSignInClick = {
                            lifecycleScope.launch {
                                try {
                                    com.example.data.SupabaseManager.loginWithGoogleOAuth()
                                } catch (e: Exception) {
                                    val msg = e.localizedMessage ?: ""
                                    if (msg.contains("10") || msg.contains("DEVELOPER_ERROR") || msg.contains("developer", ignoreCase = true)) {
                                        showDeveloperErrorDialog.value = true
                                    } else {
                                        Toast.makeText(this@MainActivity, "OAuth failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        onSignOutClick = {
                            lifecycleScope.launch {
                                try {
                                    com.example.data.SupabaseManager.signOut()
                                } catch (e: Exception) {
                                    DebugLogger.e("SIGN_OUT", "Error during signout", e)
                                }
                                Toast.makeText(this@MainActivity, "Signed out successfully", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    if (showDeveloperErrorDialog.value) {
                        GoogleDeveloperErrorDialog(
                            onDismiss = { showDeveloperErrorDialog.value = false },
                            packageName = packageName,
                            sha1 = getCertificateSHA1(this@MainActivity),
                            clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        DebugLogger.i("GPS", "Starting high-precision location updates. Interval: 3000ms.")
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
            DebugLogger.e("GPS", "Failed to request location updates", e)
            e.printStackTrace()
        }
    }

    private fun stopLocationUpdates() {
        DebugLogger.i("GPS", "Stopping high-precision location updates.")
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch {
            try {
                intent?.let {
                    com.example.data.SupabaseManager.handleDeepLink(it)
                }
            } catch (e: Exception) {
                DebugLogger.e("DEEP_LINK", "Error handling deep link in onNewIntent", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLastKnownLocation(forceCenter: Boolean = false) {
        DebugLogger.i("GPS", "Querying last known location profile (forceCenter=$forceCenter)...")
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    DebugLogger.i("GPS", "Acquired last known location: [Lat: ${location.latitude}, Lng: ${location.longitude}]")
                    val point = RoutePoint(location.latitude, location.longitude)
                    currentUserLocation.value = point
                    if (forceCenter) {
                        viewModel.centerMapOn(point)
                    }
                    viewModel.selectRoute(null) // Reset selection to trigger centering
                } else {
                    DebugLogger.w("GPS", "Last known location is currently null.")
                }
            }
        } catch (e: Exception) {
            DebugLogger.e("GPS", "Error fetching last known location", e)
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
    onFetchLastLocation: (Boolean) -> Unit,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit
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
    val isPowerSaverMode by viewModel.isPowerSaverMode.collectAsStateWithLifecycle()
    val isMapBlackedOut by viewModel.isMapBlackedOut.collectAsStateWithLifecycle()
    val blackoutReason by viewModel.blackoutReason.collectAsStateWithLifecycle()
    val currentLeanAngle by viewModel.currentLeanAngle.collectAsStateWithLifecycle()
    val currentGForce by viewModel.currentGForce.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchMarker by viewModel.searchMarker.collectAsStateWithLifecycle()

    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val isLightMap by viewModel.isLightMap.collectAsStateWithLifecycle()
    var showSyncDialog by remember { mutableStateOf(false) }

    // Trigger Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            hasLocationPermission = true
            onFetchLastLocation(true)
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

    // Filtering states
    var dateFilter by remember { mutableStateOf("All Date") }
    var lengthFilter by remember { mutableStateOf("All Size") }

    val filteredRoutes = remember(routes, dateFilter, lengthFilter) {
        routes.filter { route ->
            val matchesDate = when (dateFilter) {
                "Today" -> {
                    val cal = java.util.Calendar.getInstance()
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    cal.set(java.util.Calendar.MILLISECOND, 0)
                    route.date >= cal.timeInMillis
                }
                "Last 7 Days" -> {
                    val threshold = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
                    route.date >= threshold
                }
                "Last 30 Days" -> {
                    val threshold = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
                    route.date >= threshold
                }
                else -> true
            }

            val matchesLength = when (lengthFilter) {
                "Short (< 5km)" -> route.distance < 5.0
                "Med (5-20km)" -> route.distance in 5.0..20.0
                "Long (> 20km)" -> route.distance > 20.0
                else -> true
            }

            matchesDate && matchesLength
        }
    }

    // Dialog state controllers
    var showSaveDialog by remember { mutableStateOf(false) }
    var showDrawingSaveDialog by remember { mutableStateOf(false) }
    var routeNameToSave by remember { mutableStateOf("") }
    var pendingRouteForDeletion by remember { mutableStateOf<Route?>(null) }
    
    // Bottom Sheet Expansion State
    var isSheetExpanded by remember { mutableStateOf(false) }
    var showRoutesPage by remember { mutableStateOf(false) }
    var showTelemetryPage by remember { mutableStateOf(false) }
    var showAboutPage by remember { mutableStateOf(false) }

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

    val sessionStatus by com.example.data.SupabaseManager.sessionState.collectAsStateWithLifecycle()
    var demoBypass by remember { mutableStateOf(false) }

    if (sessionStatus !is io.github.jan.supabase.auth.status.SessionStatus.Authenticated && !demoBypass) {
        GoogleSignInScreen(
            onSignInClick = onSignInClick,
            onDemoBypassClick = { demoBypass = true }
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.statusBars
        ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DeepDarkBackground)
        ) {
            // 1. Header Stats Bar (Material 3 Surface Container)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(SlateCockpitSurface)
                    .border(1.dp, GeometricBorder, RoundedCornerShape(24.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Lifetime Coverage Stats Column
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showTelemetryPage = true
                            }
                            .padding(vertical = 4.dp)
                            .testTag("lifetime_stats_column")
                    ) {
                        Text(
                            text = "LIFETIME",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = String.format(Locale.getDefault(), "%,.0f", totalDistance),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "KM",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMuted
                            )
                        }
                    }

                    // Divider Lines
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(32.dp)
                            .background(GeometricBorder)
                    )

                    // Tracked Routes Count Column (Clickable to open dedicated fuller journal tracks page)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showRoutesPage = true
                            }
                            .padding(vertical = 4.dp)
                            .testTag("routes_stats_column")
                    ) {
                        Text(
                            text = "ROUTES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CockpitOrange,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$totalRidesCount",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Divider Lines
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(32.dp)
                            .background(GeometricBorder)
                    )

                    // Ready Status Indicators with Glow Effect
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "STATUS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonGreen,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            val pulseState = rememberInfiniteTransition(label = "gpsGlow")
                            val alphaGlow by pulseState.animateFloat(
                                initialValue = 0.4f,
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
                                        if (isTracking) NeonGreen
                                        else if (hasLocationPermission) ElectricCyan
                                        else NeonPink
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isTracking) "ACTIVE" else "READY",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = if (isTracking) alphaGlow else 1.0f)
                            )
                        }
                    }
                }
            }

            // 2. Main Map Area (Hero Section) framed nicely
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(MapSubFrame)
                    .border(1.5.dp, SlateCockpitSurface, RoundedCornerShape(32.dp))
            ) {
                // Interactive Leaflet Map Inside Frame
                LeafletMapView(
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    userLocation = currentUserLocation.value
                )

                // Head-Up Navigation Dashboards floating on Map
                if (!isMapBlackedOut) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                        .align(Alignment.TopCenter)
                ) {
                    // Majestic Floating Search Bar (Google Maps style)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .testTag("address_search_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = GlassyOverlay
                        ),
                        border = BorderStroke(1.5.dp, GeometricBorder),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search Address",
                                    tint = ElectricCyan,
                                    modifier = Modifier.padding(start = 12.dp, end = 8.dp).size(20.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "Search address, city, highway...",
                                            color = TextMuted,
                                            fontSize = 14.sp
                                        )
                                    }
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { viewModel.updateSearchQuery(it) },
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            color = Color.White,
                                            fontSize = 14.sp
                                        ),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(ElectricCyan),
                                        modifier = Modifier.fillMaxWidth().testTag("address_search_input"),
                                        singleLine = true
                                    )
                                }
                                if (isSearching) {
                                    CircularProgressIndicator(
                                        color = ElectricCyan,
                                        modifier = Modifier.size(18.dp).padding(end = 12.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.updateSearchQuery("") },
                                        modifier = Modifier.size(32.dp).testTag("clear_search_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear Search",
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // Show live suggestions
                            if (searchResults.isNotEmpty()) {
                                Divider(color = GeometricBorder, modifier = Modifier.padding(vertical = 4.dp))
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                ) {
                                    items(searchResults) { result ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    viewModel.selectSearchResult(result)
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Place,
                                                contentDescription = "Place result",
                                                tint = CockpitOrange,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = result.name,
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (result.description.isNotEmpty()) {
                                                    Text(
                                                        text = result.description,
                                                        color = TextSilver.copy(alpha = 0.7f),
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // If a search marker is selected, show a beautiful "Selected Pin HUD" with options (e.g. Center, Add node if manual, Clear)
                    AnimatedVisibility(
                        visible = searchMarker != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        searchMarker?.let { marker ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .testTag("selected_pin_card"),
                                colors = CardDefaults.cardColors(
                                    containerColor = GlassyOverlay
                                ),
                                border = BorderStroke(1.5.dp, CockpitOrange.copy(alpha = 0.8f)),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "PINNED LOCATION",
                                            fontSize = 9.sp,
                                            color = CockpitOrange,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        Text(
                                            text = marker.name,
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (marker.description.isNotEmpty()) {
                                            Text(
                                                text = marker.description,
                                                color = TextSilver.copy(alpha = 0.7f),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(8.dp))
                                    
                                    // Action to add it as coordinate node if we are in design mode!
                                    if (isDrawingMode) {
                                        IconButton(
                                            onClick = {
                                                viewModel.addDrawingPoint(com.example.data.RoutePoint(marker.lat, marker.lng))
                                                Toast.makeText(context, "Added waypoint to design path", Toast.LENGTH_SHORT).show()
                                            },
                                            colors = IconButtonDefaults.iconButtonColors(
                                                containerColor = CockpitOrange,
                                                contentColor = DeepDarkBackground
                                            ),
                                            modifier = Modifier.size(36.dp).testTag("add_pin_to_drawing")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add node to drawing",
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    
                                    IconButton(
                                        onClick = { viewModel.clearSearchMarker() },
                                        colors = IconButtonDefaults.iconButtonColors(
                                            containerColor = SlateCockpitSurface,
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.size(36.dp).testTag("clear_pin_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Dismiss Pin",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Active GPS Recording HUD Overlay
                    AnimatedVisibility(
                        visible = isTracking,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(GlassyOverlay)
                                .border(1.dp, NeonGreen, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Live Trip Distance
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("TRIP DIST", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text(
                                        text = String.format("%.2f KM", activeDistance),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonGreen,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                // Live Speed
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("SPEED", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text(
                                        text = String.format("%.0f KM/H", currentSpeedKmh),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                // Elapse stopwatch timer
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("DURATION", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text(
                                        text = formatElapsedTime(elapsedSeconds),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
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
                                .clip(RoundedCornerShape(16.dp))
                                .background(GlassyOverlay)
                                .border(1.dp, CockpitOrange, RoundedCornerShape(16.dp))
                                .padding(12.dp)
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
                                            tint = CockpitOrange,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "MANUAL ROUTE DESIGN",
                                            fontSize = 11.sp,
                                            color = CockpitOrange,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                    Text(
                                        text = String.format("%.2f KM", drawingDistance),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricCyan,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap coordinates on map to build path nodes. Tap any marker node to remove it.",
                                    fontSize = 10.sp,
                                    color = TextSilver
                                )
                            }
                        }
                    }
                }
                } // End if (!isMapBlackedOut) for top overlays

                // 3. Floating Quick Action Buttons on Map
                if (!isMapBlackedOut) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(bottom = if (isSheetExpanded) 360.dp else 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    // Location zoom controls
                    FloatingActionButton(
                        onClick = {
                            onFetchLastLocation(true)
                            currentUserLocation.value?.let {
                                viewModel.centerMapOn(it)
                            }
                        },
                        containerColor = SlateCockpitSurface,
                        contentColor = ElectricCyan,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = "Center Location",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Drawing toggle modes
                    FloatingActionButton(
                        onClick = {
                            viewModel.toggleDrawingMode()
                        },
                        containerColor = if (isDrawingMode) CockpitOrange else SlateCockpitSurface,
                        contentColor = if (isDrawingMode) DeepDarkBackground else CockpitOrange,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isDrawingMode) Icons.Default.Map else Icons.Default.Gesture,
                            contentDescription = "Manual Drawing mode",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Saved routes list controller drawer button
                    FloatingActionButton(
                        onClick = {
                            isSheetExpanded = !isSheetExpanded
                        },
                        containerColor = if (isSheetExpanded) ElectricCyan else SlateCockpitSurface,
                        contentColor = if (isSheetExpanded) DeepDarkBackground else ElectricCyan,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isSheetExpanded) Icons.Default.Book else Icons.Default.History,
                            contentDescription = "Ride Journal Panel",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Cloud Sync settings button
                    FloatingActionButton(
                        onClick = {
                            showSyncDialog = true
                        },
                        containerColor = when (syncStatus) {
                            "synced" -> NeonGreen.copy(alpha = 0.15f)
                            "syncing" -> ElectricCyan.copy(alpha = 0.15f)
                            "failed" -> NeonPink.copy(alpha = 0.15f)
                            else -> SlateCockpitSurface
                        },
                        contentColor = when (syncStatus) {
                            "synced" -> NeonGreen
                            "syncing" -> ElectricCyan
                            "failed" -> NeonPink
                            else -> Color.White
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(48.dp)
                            .border(
                                1.dp,
                                when (syncStatus) {
                                    "synced" -> NeonGreen.copy(alpha = 0.5f)
                                    "syncing" -> ElectricCyan.copy(alpha = 0.5f)
                                    "failed" -> NeonPink.copy(alpha = 0.5f)
                                    else -> Color.Transparent
                                },
                                RoundedCornerShape(16.dp)
                            )
                    ) {
                        Icon(
                            imageVector = when (syncStatus) {
                                "synced" -> Icons.Default.CloudDone
                                "syncing" -> Icons.Default.Sync
                                "failed" -> Icons.Default.CloudQueue
                                else -> Icons.Default.Cloud
                            },
                            contentDescription = "Cloud Storage Sync",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Telemetry Dashboard Cockpit Button
                    FloatingActionButton(
                        onClick = {
                            showTelemetryPage = true
                        },
                        containerColor = SlateCockpitSurface,
                        contentColor = NeonPink,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(48.dp).testTag("telemetry_cockpit_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Telemetry Cockpit Dashboard",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Map Theme Toggle Button
                    FloatingActionButton(
                        onClick = {
                            viewModel.toggleMapTheme()
                        },
                        containerColor = if (isLightMap) Color(0xFFFFD166) else SlateCockpitSurface,
                        contentColor = if (isLightMap) DeepDarkBackground else Color.White,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (isLightMap) Icons.Default.DarkMode else Icons.Default.LightMode,
                            contentDescription = "Toggle Map Theme",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Velocitron Manifesto/About Button
                    FloatingActionButton(
                        onClick = {
                            showAboutPage = true
                        },
                        containerColor = SlateCockpitSurface,
                        contentColor = ElectricCyan,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(48.dp).testTag("about_cockpit_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Velocitron Manifesto",
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Start GPS ride tracking command button
                    if (isDrawingMode) {
                        // Save manual draw points
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Undo draw coordinates node
                            FloatingActionButton(
                                onClick = { viewModel.undoDrawingPoint() },
                                containerColor = SlateCockpitSurface,
                                contentColor = TextSilver,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Default.Undo, contentDescription = "Undo node", modifier = Modifier.size(18.dp))
                            }

                            ExtendedFloatingActionButton(
                                onClick = {
                                    if (viewModel.drawingPoints.value.size < 2) {
                                        Toast.makeText(context, "Traced road requires at least 2 coordinate points.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        routeNameToSave = "Manual Ride - " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date())
                                        showDrawingSaveDialog = true
                                    }
                                },
                                containerColor = CockpitOrange,
                                contentColor = DeepDarkBackground,
                                shape = RoundedCornerShape(18.dp),
                                icon = { Icon(Icons.Default.Done, "Save path") },
                                text = { Text("Save Path", fontWeight = FontWeight.Bold) },
                                modifier = Modifier.height(48.dp)
                            )
                        }
                    } else {
                        // Start recording ride tracking
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
                            containerColor = if (isTracking) NeonPink else ElectricCyan,
                            contentColor = DeepDarkBackground,
                            elevation = FloatingActionButtonDefaults.elevation(12.dp),
                            shape = RoundedCornerShape(20.dp),
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
                } // End if (!isMapBlackedOut) for FABs

                // Power Saver Status HUD Toggle (floating Bottom Start)
                if (!isMapBlackedOut) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .navigationBarsPadding()
                            .padding(bottom = if (isSheetExpanded) 360.dp else 16.dp, start = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Card(
                            modifier = Modifier
                                .wrapContentSize()
                                .clickable { viewModel.togglePowerSaverMode() }
                                .testTag("power_saver_toggle_button"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isPowerSaverMode) GlassyOverlay else SlateCockpitSurface.copy(alpha = 0.6f)
                            ),
                            border = BorderStroke(
                                1.5.dp,
                                if (isPowerSaverMode) ElectricCyan else GeometricBorder
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPowerSaverMode) Icons.Default.Bolt else Icons.Default.Power,
                                    contentDescription = "Toggle Power Saver",
                                    tint = if (isPowerSaverMode) ElectricCyan else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = "HANDLEBAR SAVER",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPowerSaverMode) ElectricCyan else TextMuted
                                    )
                                    Text(
                                        text = if (isPowerSaverMode) "ACTIVE" else "DISABLED",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                // Solid High-Contrast Blackout AMOLED Power Saver HUD Overlay
                if (isMapBlackedOut) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF020204)) // Pitch-black background to shut down pixels
                            .clickable { viewModel.tempWakeup() },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Top Pulsing Active Banner
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ElectricCyan.copy(alpha = 0.15f))
                                    .border(1.dp, ElectricCyan.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val infinitePulse = rememberInfiniteTransition(label = "pulseActivity")
                                val ledAlpha by infinitePulse.animateFloat(
                                    initialValue = 0.3f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(800, easing = EaseInOutSine),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "led"
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(ElectricCyan.copy(alpha = ledAlpha))
                                )
                                Text(
                                    text = "HANDLEBAR SAVER ACTIVE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ElectricCyan,
                                    letterSpacing = 1.2.sp
                                )
                            }

                            // Big Visual Speed Center Instrument
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "CURRENT SPEED",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextMuted,
                                    letterSpacing = 1.5.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.0f", currentSpeedKmh),
                                        fontSize = 86.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 86.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "KM/H",
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ElectricCyan,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(bottom = 14.dp)
                                    )
                                }
                            }

                            // Detailed Telemetry Panel
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Detailed stats row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("LIVE LEAN ANGLE", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = String.format(Locale.US, "%.1f°", currentLeanAngle),
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = NeonGreen,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("ACTIVE G-FORCE", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = String.format(Locale.US, "%.2fG", currentGForce),
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Status reason text
                                Text(
                                    text = "Heuristic: $blackoutReason",
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            }

                            // Bottom guide tips and disable control
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "SCREEN OFF TO REDUCE HEAT & PRESERVE BATTERY",
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "TAP SCREEN ANYWHERE TO REVEAL MAP (15s)",
                                    fontSize = 12.sp,
                                    color = NeonGreen,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                                
                                Button(
                                    onClick = { viewModel.setPowerSaverMode(false) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SlateCockpitSurface,
                                        contentColor = Color.White
                                    ),
                                    border = BorderStroke(1.5.dp, GeometricBorder),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Text("Deactivate Saver", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 4. Slide-Up Ride Journal History Sheet (floating within viewport container)
                androidx.compose.animation.AnimatedVisibility(
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
                            .height(420.dp),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = GlassyOverlay,
                        tonalElevation = 8.dp,
                        border = BorderStroke(1.5.dp, GeometricBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            // Header drag bar
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
                                        tint = ElectricCyan,
                                        contentDescription = "Motorbike routes"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "RIDE COOPER JNL",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.5.sp
                                    )
                                }
                                
                                Row(
                                    modifier = Modifier.wrapContentSize(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Custom Import GPX Route Button
                                    TextButton(
                                        onClick = { gpxPickerLauncher.launch("application/gpx+xml") },
                                        colors = ButtonDefaults.textButtonColors(contentColor = NeonGreen)
                                    ) {
                                        Icon(Icons.Default.UploadFile, contentDescription = "Import Tracker GPX", modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("IMPORT GPX", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Export All Data Button
                                    TextButton(
                                        onClick = {
                                            if (routes.isNotEmpty()) {
                                                val intent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, viewModel.exportAllRoutesToJson(routes))
                                                    type = "application/json"
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share All Routes JSON"))
                                            } else {
                                                Toast.makeText(context, "No saved data to export", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.textButtonColors(contentColor = ElectricCyan)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = "Export All Journeys", modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("EXPORT ALL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // Close
                                    IconButton(onClick = { isSheetExpanded = false }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close sheet",
                                            tint = TextMuted
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = GeometricBorder, modifier = Modifier.padding(bottom = 8.dp))

                            if (routes.isEmpty()) {
                                // Empty placeholder state
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
                                            tint = GeometricBorder,
                                            modifier = Modifier.size(54.dp)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = "Your coloring canvas is blank!",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = TextMuted
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Record a GPS ride or draw a custom traced path.",
                                            fontSize = 11.sp,
                                            color = TextMuted.copy(alpha = 0.7f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                // Dynamic premium filters
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp)
                                ) {
                                    // Row 1: Date Presets scrollable chips
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 6.dp)
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf("All Date", "Today", "Last 7 Days", "Last 30 Days").forEach { option ->
                                            val isSelected = dateFilter == option
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) ElectricCyan else SlateCockpitSurface)
                                                    .border(1.dp, if (isSelected) ElectricCyan else GeometricBorder, RoundedCornerShape(8.dp))
                                                    .clickable { dateFilter = option }
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = option,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) DeepDarkBackground else Color.White
                                                )
                                            }
                                        }
                                    }

                                    // Row 2: Length boundary chips
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 4.dp)
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf("All Size", "Short (< 5km)", "Med (5-20km)", "Long (> 20km)").forEach { option ->
                                            val isSelected = lengthFilter == option
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) NeonPink else SlateCockpitSurface)
                                                    .border(1.dp, if (isSelected) NeonPink else GeometricBorder, RoundedCornerShape(8.dp))
                                                    .clickable { lengthFilter = option }
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = option,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) DeepDarkBackground else Color.White
                                                )
                                            }
                                        }
                                    }
                                }

                                if (filteredRoutes.isEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "No routes match active filters.",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextMuted
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            TextButton(
                                                onClick = {
                                                    dateFilter = "All Date"
                                                    lengthFilter = "All Size"
                                                },
                                                colors = ButtonDefaults.textButtonColors(contentColor = ElectricCyan)
                                            ) {
                                                Text("Reset Filters", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                } else {
                                    // History journal paths column list
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(bottom = 12.dp)
                                    ) {
                                        items(filteredRoutes) { route ->
                                            val isCurrentSelected = selectedRoute?.id == route.id
                                            
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(if (isCurrentSelected) SlateCockpitSurface else DeepDarkBackground)
                                                    .border(
                                                        1.5.dp,
                                                        if (isCurrentSelected) ElectricCyan else GeometricBorder,
                                                        RoundedCornerShape(14.dp)
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
                                                        // Route mode colored visual tag indicators
                                                        Box(
                                                            modifier = Modifier
                                                                .size(4.dp, 36.dp)
                                                                .clip(RoundedCornerShape(2.dp))
                                                                .background(
                                                                    if (route.mode == "gps") ElectricCyan
                                                                    else NeonPink
                                                                )
                                                        )
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        
                                                        Column(modifier = Modifier.fillMaxWidth()) {
                                                            Text(
                                                                text = route.name,
                                                                fontSize = 13.sp,
                                                                color = Color.White,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text(
                                                                text = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(route.date)) + 
                                                                       " • " + if(route.mode == "gps") "🏍️ GPS" else "🗺️ Draw",
                                                                fontSize = 11.sp,
                                                                color = TextMuted
                                                            )
                                                        }
                                                    }

                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        // Segment distance indicator badge
                                                        Box(
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .background(DeepDarkBackground)
                                                                .border(1.dp, GeometricBorder, RoundedCornerShape(8.dp))
                                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                                        ) {
                                                            Text(
                                                                text = String.format(Locale.US, "%.2f km", route.distance),
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = if (route.mode == "gps") ElectricCyan else NeonPink
                                                            )
                                                        }

                                                        // Native GPX exporter triggers
                                                        IconButton(
                                                            onClick = {
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
                                                                tint = TextMuted,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }

                                                        // CSV timed coordinate exporter triggers
                                                        IconButton(
                                                            onClick = {
                                                                val intent = Intent().apply {
                                                                    action = Intent.ACTION_SEND
                                                                    putExtra(Intent.EXTRA_TEXT, viewModel.exportRouteToCsvWithTimestamps(route))
                                                                    type = "text/csv"
                                                                }
                                                                context.startActivity(Intent.createChooser(intent, "Share Coordinates CSV"))
                                                            },
                                                            modifier = Modifier.size(28.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Download,
                                                                contentDescription = "Export CSV with Timestamps",
                                                                tint = TextMuted,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }

                                                        // Trash routing cleaner
                                                        IconButton(
                                                            onClick = {
                                                                pendingRouteForDeletion = route
                                                            },
                                                            modifier = Modifier.size(28.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.DeleteOutline,
                                                                contentDescription = "Delete path record",
                                                                tint = NeonPink,
                                                                modifier = Modifier.size(17.dp)
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
        }
    }

    // Full Screen Overlay Page showing all saved routes (opened by clicking ROUTES stat)
    AnimatedVisibility(
        visible = showRoutesPage,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        SavedRoutesPage(
            routes = routes,
            selectedRoute = selectedRoute,
            onBack = { showRoutesPage = false },
            onSelectRoute = { route ->
                viewModel.selectRoute(route)
                showRoutesPage = false
            },
            onDeleteRoute = { route ->
                pendingRouteForDeletion = route
            },
            exportRouteToGpx = { viewModel.exportRouteToGpx(it) },
            exportRouteToCsv = { viewModel.exportRouteToCsvWithTimestamps(it) },
            exportAllRoutesToJson = { viewModel.exportAllRoutesToJson(it) },
            importGpx = { viewModel.importGpx(it) }
        )
    }

    // Full Screen Overlay Page showing motorbike telemetry charts
    AnimatedVisibility(
        visible = showTelemetryPage,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        TelemetryDashboardPage(
            routes = routes,
            selectedRoute = selectedRoute,
            onBack = { showTelemetryPage = false },
            onSelectRoute = { route2 ->
                viewModel.selectRoute(route2)
            }
        )
    }

    // Full Screen Overlay Manifesto About Page
    AnimatedVisibility(
        visible = showAboutPage,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        AboutCockpitPage(
            onBack = { showAboutPage = false }
        )
    }

    // Modal: Save Recorded Ride Name dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { 
                showSaveDialog = false 
            },
            containerColor = SlateCockpitSurface,
            tonalElevation = 6.dp,
            title = {
                Text(
                    "CONCLUDE RIDE MOTORCYCLE",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan,
                    letterSpacing = 1.2.sp
                )
            },
            text = {
                Column {
                    Text(
                        "Would you like to save this logged route and add its coloring overlay permanently?",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = routeNameToSave,
                        onValueChange = { routeNameToSave = it },
                        label = { Text("Route Name", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = GeometricBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
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
                    colors = ButtonDefaults.textButtonColors(contentColor = NeonPink)
                ) {
                    Text("DISCARD", fontWeight = FontWeight.Bold)
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
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = DeepDarkBackground)
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
            containerColor = SlateCockpitSurface,
            tonalElevation = 6.dp,
            title = {
                Text(
                    "SAVE MANUAL PATH TRACE",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = CockpitOrange,
                    letterSpacing = 1.2.sp
                )
            },
            text = {
                Column {
                    Text(
                        "Provide a custom name for this manually drawn route.",
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = routeNameToSave,
                        onValueChange = { routeNameToSave = it },
                        label = { Text("Route Name", color = TextMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CockpitOrange,
                            unfocusedBorderColor = GeometricBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDrawingSaveDialog = false }) {
                    Text("CANCEL", color = TextMuted, fontWeight = FontWeight.SemiBold)
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
                    colors = ButtonDefaults.buttonColors(containerColor = CockpitOrange, contentColor = DeepDarkBackground)
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
            containerColor = SlateCockpitSurface,
            tonalElevation = 6.dp,
            title = {
                Text(
                    "DELETE ROUTE RECORD",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonPink,
                    letterSpacing = 1.2.sp
                )
            },
            text = {
                Text(
                    "Are you absolutely sure you want to delete '${pendingRouteForDeletion?.name}'? This will erase the road coloring for this journey. Action cannot be undone.",
                    fontSize = 12.sp,
                    color = TextSilver
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingRouteForDeletion = null }) {
                    Text("CANCEL", color = TextMuted, fontWeight = FontWeight.SemiBold)
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
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPink, contentColor = Color.White)
                ) {
                    Text("YES, DELETE", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Modal: Cloud Backend Sync Panel (Supabase Sync & User Profile)
    if (showSyncDialog) {
        val isUserAuthenticated = sessionStatus is io.github.jan.supabase.auth.status.SessionStatus.Authenticated
        val userName = if (isUserAuthenticated) {
            com.example.data.SupabaseManager.getCurrentUserName() ?: "Cyclist / Rider"
        } else {
            "Offline Rider"
        }
        val userEmail = if (isUserAuthenticated) {
            com.example.data.SupabaseManager.getCurrentUserEmail() ?: "Supabase Connected"
        } else {
            "No cloud connection"
        }
        val scope = rememberCoroutineScope()
        
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Profile Icon",
                        tint = ElectricCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CLOUD SYNC PROFILE",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 1.sp
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (isUserAuthenticated) {
                            "Your routes are secured by your Google account and synced to Supabase database."
                        } else {
                            "You are currently in Offline Cockpit mode. Sync features are suspended."
                        },
                        fontSize = 12.sp,
                        color = TextSilver,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // User Details Segment
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DeepDarkBackground)
                            .border(1.dp, GeometricBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "SIGNED IN AS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = userName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = userEmail,
                            fontSize = 12.sp,
                            color = TextSilver
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Sync Status Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DeepDarkBackground)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "SYNC ENGINE:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isUserAuthenticated) NeonGreen else Color(0xFFE9A13F))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isUserAuthenticated) "ACTIVE & SECURE" else "OFFLINE & LOCAL",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isUserAuthenticated) NeonGreen else Color(0xFFE9A13F)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    if (isUserAuthenticated) {
                        val pendingOps = com.example.data.SupabaseManager.getPendingOperationsCount()
                        if (pendingOps > 0) {
                            Text(
                                text = "⏳ Offline mode active. $pendingOps operations queued. Will synchronize automatically when mobile network is online.",
                                fontSize = 11.sp,
                                color = NeonPink,
                                lineHeight = 15.sp
                            )
                        } else {
                            Text(
                                text = "💡 Any routes recorded on other devices logging into this Google account will automatically synchronize to this device instantly.",
                                fontSize = 10.sp,
                                color = TextMuted,
                                lineHeight = 14.sp
                            )
                        }
                    } else {
                        Text(
                            text = "💡 Any routes, drawn tracks, or telemetry cockpit journals you record are securely stored in your local SQLite Room database.",
                            fontSize = 10.sp,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }
                }
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isUserAuthenticated) {
                        TextButton(
                            onClick = {
                                val uid = com.example.data.SupabaseManager.getCurrentUserId()
                                if (uid != null) {
                                    scope.launch {
                                        com.example.data.SupabaseManager.syncFromCloudToRoom(uid)
                                        Toast.makeText(context, "Cloud sync refresh started", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = ElectricCyan
                            )
                        ) {
                            Text("REFRESH SYNC", fontWeight = FontWeight.Bold)
                        }
                    }

                    Button(
                        onClick = {
                            onSignOutClick()
                            demoBypass = false
                            showSyncDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isUserAuthenticated) NeonPink.copy(alpha = 0.2f) else Color(0xFFE9A13F).copy(alpha = 0.2f),
                            contentColor = if (isUserAuthenticated) NeonPink else Color(0xFFE9A13F)
                        ),
                        border = BorderStroke(1.dp, (if (isUserAuthenticated) NeonPink else Color(0xFFE9A13F)).copy(alpha = 0.5f))
                    ) {
                        Text(if (isUserAuthenticated) "SIGN OUT" else "EXIT OFFLINE", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSyncDialog = false }
                ) {
                    Text("CLOSE", color = TextSilver)
                }
            },
            containerColor = SlateCockpitSurface,
            tonalElevation = 6.dp
        )
    }
    } // Ends the authenticated check else block
}

@Composable
fun GoogleSignInScreen(
    onSignInClick: () -> Unit,
    onDemoBypassClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepDarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(24.dp)
        ) {
            // Logo Icon Container
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(2.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsBike,
                    contentDescription = "Motorcycle Logo",
                    tint = ElectricCyan,
                    modifier = Modifier.size(46.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "ROAD TRACKER",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 3.sp,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "Supa-Charged Motorcycle Cockpit & Telemetry Logs",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Cockpit themed continue with google button
            OutlinedButton(
                onClick = onSignInClick,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = SlateCockpitSurface,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, ElectricCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("continue_with_google_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Google Logo Outline",
                        tint = ElectricCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Continue with Google",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = 0.5.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            TextButton(
                onClick = onDemoBypassClick,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = TextMuted
                ),
                modifier = Modifier.testTag("offline_bypass_button")
            ) {
                Text(
                    text = "Launch Offline Cockpit Mode",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Text(
            text = "built from moto riders for moto riders!",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            color = ElectricCyan.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )
    }
}

@Composable
fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = ElectricCyan,
            modifier = Modifier
                .size(20.dp)
                .offset(y = 2.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = TextSilver,
                lineHeight = 14.sp
            )
        }
    }
}

fun formatElapsedTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

@Composable
fun GoogleDeveloperErrorDialog(
    onDismiss: () -> Unit,
    packageName: String,
    sha1: String,
    clientId: String
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateCockpitSurface,
        titleContentColor = Color.White,
        textContentColor = TextSilver,
        tonalElevation = 8.dp,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Config Mismatch",
                    tint = NeonPink,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Google Sign-In Error 10",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "This is a Developer Error (CommonStatusCodes.DEVELOPER_ERROR) indicating your phone's signing key and package name are not registered with Google.",
                        fontSize = 14.sp,
                        color = TextSilver
                    )
                }
                
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DeepDarkBackground, RoundedCornerShape(8.dp))
                            .border(1.dp, GeometricBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "REQUIRED GOOGLE CONSOLE CONFIG",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Package Name:",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = packageName,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.White
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "SHA-1 Fingerprint (Your Key):",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = sha1,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Yellow
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Configured Web Client ID:",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = if (clientId.isNotBlank()) clientId else "[NOT SET / BLANK]",
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (clientId.isNotBlank()) TextSilver else NeonPink,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                item {
                    Text(
                        text = "To resolve this:\n" +
                                "1. Go to Firebase Console / Google Cloud Console Project APIs -> Credentials.\n" +
                                "2. Register the SHA-1 footprint above under your Android app configuration.\n" +
                                "3. Make sure GOOGLE_WEB_CLIENT_ID matches your project's Web Application Client ID.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = TextMuted
                    )
                }
                
                item {
                    Text(
                        text = "Pro Tip: You can bypass this sync completely and use the app offline with full features by choosing 'Enter Offline Demo Cockpit' on the Login Screen!",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeonGreen
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = ElectricCyan)
            ) {
                Text("Dismiss", fontWeight = FontWeight.Bold)
            }
        }
    )
}
