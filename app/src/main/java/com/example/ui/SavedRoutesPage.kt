package com.example.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.Route
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesPage(
    modifier: Modifier = Modifier,
    routes: List<Route>,
    selectedRoute: Route?,
    onBack: () -> Unit,
    onSelectRoute: (Route) -> Unit,
    onDeleteRoute: (Route) -> Unit,
    exportRouteToGpx: (Route) -> String,
    exportRouteToCsv: (Route) -> String,
    exportAllRoutesToJson: (List<Route>) -> String,
    importGpx: (String) -> String
) {
    val context = LocalContext.current

    // Local Search State for Saved Routes Catalog
    var localSearchQuery by remember { mutableStateOf("") }
    var localDateFilter by remember { mutableStateOf("All Date") }
    var localLengthFilter by remember { mutableStateOf("All Size") }
    var localModeFilter by remember { mutableStateOf("All Types") }

    // GPX single file importer within this page
    val gpxPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val text = inputStream.bufferedReader().use { it.readText() }
                    val result = importGpx(text)
                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read GPX file format.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Filter implementation
    val displayRoutes = remember(routes, localSearchQuery, localDateFilter, localLengthFilter, localModeFilter) {
        routes.filter { route ->
            val matchesSearch = if (localSearchQuery.trim().isEmpty()) {
                true
            } else {
                route.name.contains(localSearchQuery, ignoreCase = true)
            }

            val matchesDate = when (localDateFilter) {
                "Today" -> {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.HOUR_OF_DAY, 0)
                    cal.set(Calendar.MINUTE, 0)
                    cal.set(Calendar.SECOND, 0)
                    cal.set(Calendar.MILLISECOND, 0)
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

            val matchesLength = when (localLengthFilter) {
                "Short (< 5km)" -> route.distance < 5.0
                "Med (5-20km)" -> route.distance in 5.0..20.0
                "Long (> 20km)" -> route.distance > 20.0
                else -> true
            }

            val matchesMode = when (localModeFilter) {
                "🏍️ GPS Logs" -> route.mode == "gps"
                "🗺️ Hand Drawn" -> route.mode == "manual" || route.mode == "draw"
                else -> true
            }

            matchesSearch && matchesDate && matchesLength && matchesMode
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "JOURNAL & TRACKS",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "${routes.size} routes total • ${String.format(Locale.getDefault(), "%,.1f", routes.sumOf { it.distance })} cumulative km",
                            fontSize = 11.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("saved_routes_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Map",
                            tint = ElectricCyan
                        )
                    }
                },
                actions = {
                    // Import GPX Shortcut
                    TextButton(
                        onClick = { gpxPickerLauncher.launch("*/*") },
                        colors = ButtonDefaults.textButtonColors(contentColor = NeonGreen)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Import GPX/GP", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("IMPORT GP/GPX", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Share all routes JSON
                    IconButton(
                        onClick = {
                            if (routes.isNotEmpty()) {
                                val intent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, exportAllRoutesToJson(routes))
                                    type = "application/json"
                                }
                                context.startActivity(Intent.createChooser(intent, "Share All Routes"))
                            } else {
                                Toast.makeText(context, "No saved data to export", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share All Routes Data",
                            tint = Color.White
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
        modifier = modifier.fillMaxSize().testTag("saved_routes_page_scaffold")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Local Live Route Title/Keyword search
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("routes_keyword_search_card"),
                colors = CardDefaults.cardColors(
                    containerColor = SlateCockpitSurface
                ),
                border = BorderStroke(1.dp, GeometricBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search input logo",
                        tint = ElectricCyan,
                        modifier = Modifier.padding(start = 12.dp, end = 8.dp).size(18.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (localSearchQuery.isEmpty()) {
                            Text(
                                text = "Search saved route names...",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        }
                        BasicTextField(
                            value = localSearchQuery,
                            onValueChange = { localSearchQuery = it },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(ElectricCyan),
                            modifier = Modifier.fillMaxWidth().testTag("routes_keyword_search_input"),
                            singleLine = true
                        )
                    }
                    if (localSearchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { localSearchQuery = "" },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search query",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Triple-level premium Filter list row
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Time Horizon filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("All Date", "Today", "Last 7 Days", "Last 30 Days").forEach { option ->
                        val isSelected = localDateFilter == option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) ElectricCyan else SlateCockpitSurface)
                                .border(1.dp, if (isSelected) ElectricCyan else GeometricBorder, RoundedCornerShape(8.dp))
                                .clickable { localDateFilter = option }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
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

                // Ride Type category filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("All Types", "🏍️ GPS Logs", "🗺️ Hand Drawn").forEach { option ->
                        val isSelected = localModeFilter == option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NeonGreen else SlateCockpitSurface)
                                .border(1.dp, if (isSelected) NeonGreen else GeometricBorder, RoundedCornerShape(8.dp))
                                .clickable { localModeFilter = option }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
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

                // Route Distance filters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("All Size", "Short (< 5km)", "Med (5-20km)", "Long (> 20km)").forEach { option ->
                        val isSelected = localLengthFilter == option
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NeonPink else SlateCockpitSurface)
                                .border(1.dp, if (isSelected) NeonPink else GeometricBorder, RoundedCornerShape(8.dp))
                                .clickable { localLengthFilter = option }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
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

            // Results and Main List
            if (displayRoutes.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = "Empty filter results logo",
                            tint = GeometricBorder,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No saved routes match selection",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSilver
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Adjust your search phrase or filters.",
                            fontSize = 11.sp,
                            color = TextMuted.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        TextButton(
                            onClick = {
                                localSearchQuery = ""
                                localDateFilter = "All Date"
                                localLengthFilter = "All Size"
                                localModeFilter = "All Types"
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = ElectricCyan)
                        ) {
                            Text("Reset All Filters", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("saved_routes_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(displayRoutes, key = { it.id }) { route ->
                        val isCurrentSelected = selectedRoute?.id == route.id

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("route_list_item_${route.id}")
                                .clickable {
                                    onSelectRoute(route)
                                    Toast.makeText(context, "Showing: ${route.name}", Toast.LENGTH_SHORT).show()
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrentSelected) SlateCockpitSurface else SlateCockpitSurface.copy(alpha = 0.4f)
                            ),
                            border = BorderStroke(
                                1.5.dp,
                                if (isCurrentSelected) ElectricCyan else GeometricBorder
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Mode Indicator Tag Accent Line
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp, 38.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    if (route.mode == "gps") ElectricCyan else NeonPink
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column {
                                            Text(
                                                text = route.name,
                                                fontSize = 14.sp,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(route.date)),
                                                fontSize = 11.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }

                                    // Compact Highlight Badge
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DeepDarkBackground)
                                            .border(1.dp, GeometricBorder, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = String.format(Locale.US, "%.2f km", route.distance),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (route.mode == "gps") ElectricCyan else NeonPink
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = GeometricBorder.copy(alpha = 0.5f))
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Visual pill tag indicating GPS / Tracing details
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (route.mode == "gps") Icons.Default.TwoWheeler else Icons.Default.Gesture,
                                            contentDescription = null,
                                            tint = if (route.mode == "gps") ElectricCyan else NeonPink,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (route.mode == "gps") "Recorded Ride" else "Manual Trace",
                                            fontSize = 11.sp,
                                            color = TextMuted,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    // Action bar icons with great touch targets
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Share GPX option
                                        IconButton(
                                            onClick = {
                                                val intent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, exportRouteToGpx(route))
                                                    type = "text/xml"
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share Route GPX"))
                                            },
                                            modifier = Modifier.size(32.dp).testTag("share_gpx_${route.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Share GPX",
                                                tint = ElectricCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        // Share CSV option
                                        IconButton(
                                            onClick = {
                                                val intent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(Intent.EXTRA_TEXT, exportRouteToCsv(route))
                                                    type = "text/csv"
                                                }
                                                context.startActivity(Intent.createChooser(intent, "Share CSV Coordinates"))
                                            },
                                            modifier = Modifier.size(32.dp).testTag("share_csv_${route.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Download,
                                                contentDescription = "Share CSV data",
                                                tint = NeonGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        // Delete path option
                                        IconButton(
                                            onClick = { onDeleteRoute(route) },
                                            modifier = Modifier.size(32.dp).testTag("delete_route_${route.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "Track deletion request",
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
