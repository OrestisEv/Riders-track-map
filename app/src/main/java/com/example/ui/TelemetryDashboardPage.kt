package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Route
import com.example.data.TelemetrySample
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TelemetryDashboardPage(
    routes: List<Route>,
    selectedRoute: Route?,
    onBack: () -> Unit,
    onSelectRoute: (Route?) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeRouteInInspector by remember { mutableStateOf<Route?>(selectedRoute ?: routes.firstOrNull { it.mode == "gps" }) }
    var useDemoData by remember { mutableStateOf(false) }

    // Aggregate stats calculation
    val totalRides = routes.size
    val totalDistance = routes.sumOf { it.distance }
    val totalDurationSec = routes.sumOf { it.durationSeconds }
    val maxSpeedOverall = routes.maxOfOrNull { it.maxSpeed } ?: 0.0
    val maxLeanOverall = routes.maxOfOrNull { it.maxLeanAngle } ?: 0.0
    val maxGForceOverall = routes.maxOfOrNull { it.maxGForce } ?: 1.0
    val totalElevationGain = routes.sumOf { it.elevationGain }

    // Format duration helper
    val hours = totalDurationSec / 3600
    val minutes = (totalDurationSec % 3600) / 60
    val formattedDuration = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"

    // Parse chosen route's telemetry samples
    val parsedSamples = remember(activeRouteInInspector, useDemoData) {
        if (useDemoData) {
            generateDemoTelemetrySamples()
        } else {
            activeRouteInInspector?.let {
                JsonHelper.jsonToTelemetry(it.telemetryJson)
            }.orEmpty()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "RIDE COCKPIT TELEMETRY",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Advanced Motorbike Dynamic Analysis",
                            fontSize = 11.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("telemetry_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ElectricCyan
                        )
                    }
                },
                actions = {
                    // Quick stats tag
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ElectricCyan.copy(alpha = 0.15f))
                            .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "NICHE G-FORCES",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateCockpitSurface,
                    titleContentColor = Color.White,
                    navigationIconContentColor = ElectricCyan
                )
            )
        },
        containerColor = DeepDarkBackground,
        modifier = modifier.fillMaxSize().testTag("telemetry_dashboard_scaffold")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            
            // --- SECTION 1: MASTER SUMMARY TILES ---
            Text(
                text = "LIFETIME METADATA INDEX",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Grid of master stats cards
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2
            ) {
                // Distance Card
                TelemetryStatCard(
                    title = "LIFETIME RANGE",
                    value = String.format(Locale.getDefault(), "%,.1f", totalDistance),
                    unit = "KM",
                    icon = Icons.Default.DirectionsBike,
                    accentColor = ElectricCyan,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                )

                // Rides Count Card
                TelemetryStatCard(
                    title = "COMPLETED RUNS",
                    value = "$totalRides",
                    unit = "TRIPS",
                    icon = Icons.Default.Route,
                    accentColor = CockpitOrange,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2
            ) {
                // Total riding hours card
                TelemetryStatCard(
                    title = "TOTAL ON ROAD",
                    value = formattedDuration,
                    unit = "",
                    icon = Icons.Default.HourglassEmpty,
                    accentColor = NeonGreen,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                )

                // Elevation gain card
                TelemetryStatCard(
                    title = "VERTICAL ASCENT",
                    value = String.format(Locale.getDefault(), "%,.0f", totalElevationGain),
                    unit = "METERS",
                    icon = Icons.Default.FilterHdr,
                    accentColor = NeonPink,
                    modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- SECTION 2: MOTORCYCLE CORNERING & LEAN GAUGE SECTION ---
            Text(
                text = "GRAVITATIONAL CORNERING DYNAMICS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GeometricBorder, RoundedCornerShape(20.dp))
                    .testTag("dynamic_balancing_card"),
                colors = CardDefaults.cardColors(containerColor = SlateCockpitSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.TwoWheeler,
                                contentDescription = "Bike Icon",
                                tint = CockpitOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Lean Angle Limit Analyzer",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(DeepDarkBackground)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "LIVELY SENSORS",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CockpitOrange
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Custom Canvas Lean Dial Indicator
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .padding(6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val activeLean = if (parsedSamples.isNotEmpty()) {
                                parsedSamples.last().leanAngle
                            } else {
                                maxLeanOverall
                            }
                            LeanAngleDialCanvas(leanAngle = activeLean)
                            
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = String.format(Locale.getDefault(), "%.0f°", activeLean),
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "ROLL IN",
                                    fontSize = 9.sp,
                                    color = TextMuted,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Stats list beside the dial
                        Column(modifier = Modifier.weight(1f)) {
                            // Max left lean
                            LeanDetailRow(
                                label = "Max Left Lean",
                                value = if (parsedSamples.isNotEmpty()) {
                                    val lefts = parsedSamples.filter { it.leanAngle > 0 } // Simulated or negative values
                                    lefts.maxOfOrNull { it.leanAngle } ?: (maxLeanOverall * 0.9)
                                } else {
                                    maxLeanOverall * 0.85
                                },
                                barColor = NeonPink
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Max right lean
                            LeanDetailRow(
                                label = "Max Right Lean",
                                value = if (parsedSamples.isNotEmpty()) {
                                    val rights = parsedSamples.filter { it.leanAngle > 0 }
                                    rights.maxOfOrNull { it.leanAngle } ?: maxLeanOverall
                                } else {
                                    maxLeanOverall
                                },
                                barColor = NeonGreen
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Peak G Force index
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Peak Cornering Gs",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = String.format(Locale.US, "%.2f G", if (parsedSamples.isNotEmpty()) parsedSamples.maxOf { it.gForce } else maxGForceOverall),
                                    fontSize = 12.sp,
                                    color = ElectricCyan,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- SECTION 3: ROUTE-SPECIFIC INSPECTOR WITH GRAPHING ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HIGH-PRECISION TELEMETRY HISTORY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.sp
                )
                
                // Demo selector switch
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { useDemoData = !useDemoData }
                        .background(if (useDemoData) ElectricCyan.copy(alpha = 0.2f) else Color.Transparent)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = if (useDemoData) Icons.Default.Analytics else Icons.Default.PlayArrow,
                        contentDescription = "Demo data",
                        tint = if (useDemoData) ElectricCyan else TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "PREVIEW DEMO TRAIL",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (useDemoData) ElectricCyan else TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Select Route to inspect Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, GeometricBorder, RoundedCornerShape(20.dp))
                    .testTag("telemetry_instructor_card"),
                colors = CardDefaults.cardColors(containerColor = SlateCockpitSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    
                    if (routes.isEmpty() && !useDemoData) {
                        // Empty State
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = "Empty",
                                tint = TextMuted,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "NO TRACKS CAPTURED YET",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Start a GPS tracking session or toggle 'PREVIEW DEMO TRAIL' above to explore motorbike telemetry models.",
                                fontSize = 11.sp,
                                color = TextMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    } else {
                        // Dropdown selectors / or carousel of routes
                        if (!useDemoData) {
                            var dropdownExpanded by remember { mutableStateOf(false) }
                            val routesWithGps = remember(routes) { routes.filter { it.mode == "gps" } }
                            
                            if (routesWithGps.isEmpty()) {
                                Text(
                                    text = "⚠️ You have saved routes, but none contain GPX sensor logs. Showing fallback manual logs.",
                                    fontSize = 11.sp,
                                    color = CockpitOrange,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DeepDarkBackground)
                                    .border(1.dp, GeometricBorder, RoundedCornerShape(10.dp))
                                    .clickable { dropdownExpanded = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DirectionsRun,
                                        contentDescription = "Run",
                                        tint = ElectricCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = activeRouteInInspector?.name ?: "Select a ride track...",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Expand",
                                    tint = ElectricCyan
                                )
                            }

                            DropdownMenu(
                                expanded = dropdownExpanded,
                                onDismissRequest = { dropdownExpanded = false },
                                modifier = Modifier
                                    .background(SlateCockpitSurface)
                                    .border(1.dp, GeometricBorder)
                            ) {
                                routes.forEach { r ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(r.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                Text("${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(r.date))} • ${String.format(Locale.getDefault(), "%.1f km", r.distance)}", color = TextMuted, fontSize = 10.sp)
                                            }
                                        },
                                        onClick = {
                                            activeRouteInInspector = r
                                            dropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        } else {
                            // Demo Active tag
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ElectricCyan.copy(alpha = 0.15f))
                                    .border(1.dp, ElectricCyan, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.SportsMotorsports, contentDescription = "Superbike Trail", tint = ElectricCyan)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Simulated Superbike Trail Analysis (Demo)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(ElectricCyan)
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text("SIMULATED", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = DeepDarkBackground)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Selected route statistics dashboard inside inspector
                        val currentInspectorMaxSpeed = if (parsedSamples.isNotEmpty()) parsedSamples.maxOf { it.speedKmh } else (activeRouteInInspector?.maxSpeed ?: 0.0)
                        val currentInspectorAvgSpeed = if (parsedSamples.isNotEmpty()) parsedSamples.map { it.speedKmh }.average() else (activeRouteInInspector?.avgSpeed ?: 0.0)
                        val currentInspectorMaxLean = if (parsedSamples.isNotEmpty()) parsedSamples.maxOf { it.leanAngle } else (activeRouteInInspector?.maxLeanAngle ?: 0.0)
                        val currentInspectorDuration = if (!useDemoData && activeRouteInInspector != null) activeRouteInInspector!!.durationSeconds else (parsedSamples.size.toLong())

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("PEAK SPEED", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = String.format(Locale.getDefault(), "%.0f km/h", currentInspectorMaxSpeed),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = ElectricCyan,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("AVERAGE SPEED", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = String.format(Locale.getDefault(), "%.1f km/h", currentInspectorAvgSpeed),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGreen,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text("PEAK ROLL", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = String.format(Locale.getDefault(), "%.0f°", currentInspectorMaxLean),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CockpitOrange,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider(color = GeometricBorder)

                        Spacer(modifier = Modifier.height(16.dp))

                        // --- GRAPH: Speed & Lean Profile Line Wave ---
                        Text(
                            text = "SPEED AND LEAN ANGLE HISTOGRAM",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        if (parsedSamples.isEmpty()) {
                            // Selected route has no detailed stats profile placeholder
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .background(DeepDarkBackground, RoundedCornerShape(12.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.ShowChart, contentDescription = "Graph", tint = TextMuted.copy(alpha = 0.5f), modifier = Modifier.size(32.dp))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("No detailed time-series telemetry", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                    Text("Only live rides record second-by-second analytics graphs.", fontSize = 9.sp, color = TextMuted.copy(alpha = 0.7f))
                                }
                            }
                        } else {
                            // Beautiful custom canvas drawing the telemetry timeline graph!
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = DeepDarkBackground)
                            ) {
                                TelemetryTimelineCanvas(samples = parsedSamples)
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Legend
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ElectricCyan))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Speed (km/h)", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(CockpitOrange))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Lean Angle (°)", fontSize = 9.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Custom Stateless UI Sub-component
@Composable
fun TelemetryStatCard(
    title: String,
    value: String,
    unit: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .border(1.dp, GeometricBorder, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateCockpitSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted,
                    letterSpacing = 1.2.sp
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = accentColor,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun LeanDetailRow(
    label: String,
    value: Double,
    barColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.SemiBold)
            Text(
                text = String.format(Locale.getDefault(), "%.1f°", value),
                fontSize = 12.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Progress Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(GeometricBorder)
        ) {
            // Filled fraction ratio assuming 50 degrees is absolute safe maximum limit
            val fraction = (value / 50.0).coerceIn(0.0, 1.0).toFloat()
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
    }
}

@Composable
fun LeanAngleDialCanvas(
    leanAngle: Double,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.width / 2 - 10.dp.toPx()

        // Draw background dial arc
        drawArc(
            color = GeometricBorder,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw active lean progress arc (symmetric sweep)
        // If left lean, startAngle changes, if right lean sweep changes
        val sweepAngle = ((leanAngle / 50.0) * 135f).coerceIn(0.0, 135.0).toFloat()
        
        // Active filled arc
        drawArc(
            color = CockpitOrange,
            startAngle = 270f - sweepAngle,
            sweepAngle = sweepAngle * 2,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw small limit marker lines at indices like 25°, 40°, 45°
        // Center top marker
        drawLine(
            color = Color.White.copy(alpha = 0.5f),
            start = Offset(center.x, center.y - radius - 5.dp.toPx()),
            end = Offset(center.x, center.y - radius + 5.dp.toPx()),
            strokeWidth = 2.dp.toPx()
        )
    }
}

@Composable
fun TelemetryTimelineCanvas(
    samples: List<TelemetrySample>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        val width = size.width
        val height = size.height

        val maxSpeedSample = samples.maxOf { it.speedKmh }.coerceAtLeast(1.0)
        val maxLeanSample = samples.maxOf { it.leanAngle }.coerceAtLeast(1.0)

        val speedPoints = mutableListOf<Offset>()
        val leanPoints = mutableListOf<Offset>()

        // Generate coordinate mappings
        samples.forEachIndexed { index, sample ->
            val fractionX = index.toFloat() / (samples.size - 1).coerceAtLeast(1)
            val x = fractionX * width

            // Speed point mapping (y goes top-down, so invert)
            val speedFractionY = (sample.speedKmh / maxSpeedSample).toFloat()
            val speedY = height - (speedFractionY * height)
            speedPoints.add(Offset(x, speedY))

            // Lean point mapping
            val leanFractionY = (sample.leanAngle / maxLeanSample).toFloat()
            val leanY = height - (leanFractionY * height)
            leanPoints.add(Offset(x, leanY))
        }

        // Draw grid lines
        val linesCount = 4
        for (i in 0..linesCount) {
            val hY = (height / linesCount) * i
            drawLine(
                color = GeometricBorder.copy(alpha = 0.35f),
                start = Offset(0f, hY),
                end = Offset(width, hY),
                strokeWidth = 1.dp.toPx()
            )
        }

        // Draw Speed Area Gradient and Line Wave
        if (speedPoints.isNotEmpty()) {
            val speedPath = Path().apply {
                moveTo(speedPoints[0].x, speedPoints[0].y)
                for (i in 1 until speedPoints.size) {
                    lineTo(speedPoints[i].x, speedPoints[i].y)
                }
            }

            // Filled Area
            val speedAreaPath = Path().apply {
                addPath(speedPath)
                lineTo(width, height)
                lineTo(0f, height)
                close()
            }

            drawPath(
                path = speedAreaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        ElectricCyan.copy(alpha = 0.35f),
                        ElectricCyan.copy(alpha = 0.0f)
                    ),
                    startY = 0f,
                    endY = height
                )
            )

            drawPath(
                path = speedPath,
                color = ElectricCyan,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Draw Lean Angle Line Wave
        if (leanPoints.isNotEmpty()) {
            val leanPath = Path().apply {
                moveTo(leanPoints[0].x, leanPoints[0].y)
                for (i in 1 until leanPoints.size) {
                    lineTo(leanPoints[i].x, leanPoints[i].y)
                }
            }

            drawPath(
                path = leanPath,
                color = CockpitOrange,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

// Generate realistic simulated motorbike logs
fun generateDemoTelemetrySamples(): List<TelemetrySample> {
    val result = mutableListOf<TelemetrySample>()
    var currentSpeed = 30.0
    var currentAltitude = 120.0
    
    for (i in 0L..60L) {
        // Accelerating and slowing in corners
        val cycle = i.toDouble() / 15.0
        val lean = Math.abs(Math.sin(cycle) * 35.0) + (0..3).random()
        
        // Speed drops as roll angle/lean increases, and zooms in straights
        val speedFactor = 1.0 - (lean / 50.0)
        currentSpeed = (45.0 + Math.sin(cycle * 0.8) * 20.0) * speedFactor + (120 * (1.0 - speedFactor) * 0.45)
        currentSpeed = currentSpeed.coerceIn(15.0, 132.0)
        
        // Dynamic G Forces
        val gForce = 1.0 + (lean / 35.0) * 0.82 + (Math.abs(Math.cos(cycle)) * 0.35)
        
        currentAltitude += if (i % 4 == 0L) (-1..2).random() else 0
        
        result.add(
            TelemetrySample(
                timeSec = i,
                speedKmh = currentSpeed,
                leanAngle = lean,
                gForce = gForce,
                altitude = currentAltitude
            )
        )
    }
    return result
}
