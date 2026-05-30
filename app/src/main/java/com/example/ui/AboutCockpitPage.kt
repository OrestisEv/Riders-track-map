package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutCockpitPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var specTabActive by remember { mutableStateOf(false) }
    var liveGlowVal by remember { mutableStateOf(1.0f) }

    // Start a simple coroutine to pulsate sensor signals
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            liveGlowVal = if (liveGlowVal == 1.0f) 1.55f else 1.0f
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "VELOCITRON MANIFESTO",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "Automated Riding Telemetry & Physics Vault",
                            fontSize = 11.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("about_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = ElectricCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateCockpitSurface,
                    titleContentColor = Color.White,
                    navigationIconContentColor = ElectricCyan
                ),
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ElectricCyan.copy(alpha = 0.15f))
                            .border(1.dp, ElectricCyan, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "VER: 2.4.0-RC",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            )
        },
        containerColor = DeepDarkBackground,
        modifier = modifier.fillMaxSize().testTag("about_cockpit_scaffold")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // --- HERO BRAND BANNER ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(SlateCockpitSurface, DeepDarkBackground)
                        )
                    )
                    .border(1.dp, GeometricBorder, RoundedCornerShape(24.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Logo representation
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(ElectricCyan.copy(alpha = 0.15f))
                            .border(2.dp, ElectricCyan, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsMotorsports,
                            contentDescription = "Superbike Helmet",
                            tint = ElectricCyan,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "VELOCITRON",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "THE NICHE MOTORBIKE INTELLIGENCE VAULT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CockpitOrange,
                        letterSpacing = 1.2.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Velocitron empowers motorcycle riders with professional physics indicators, custom path builders, and high-fidelity telemetry visualizers directly on an offline-secure database.",
                        fontSize = 13.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- TAB SWITCHER (FEATURES VS TECHNICAL SPECS) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SlateCockpitSurface)
                    .border(1.dp, GeometricBorder, RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                Button(
                    onClick = { specTabActive = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!specTabActive) ElectricCyan else Color.Transparent,
                        contentColor = if (!specTabActive) DeepDarkBackground else Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("about_features_tab")
                ) {
                    Text("Epic Features", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Button(
                    onClick = { specTabActive = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (specTabActive) ElectricCyan else Color.Transparent,
                        contentColor = if (specTabActive) DeepDarkBackground else Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("about_specs_tab")
                ) {
                    Text("Digital Spec & Physics", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- CONTENTS SWAP ANIMATION ---
            AnimatedContent(
                targetState = specTabActive,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "ManifestoDetails"
            ) { isSpec ->
                if (!isSpec) {
                    // Feature cards lists with gorgeous color coding
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        FeatureShowcaseRow(
                            title = "Gravitational Cockpit Telemetry",
                            desc = "Calculates active body tilt angles, banking rolls, vertical elevation climbs, and peak G-forces in corners synchronously.",
                            icon = Icons.Default.BarChart,
                            color = NeonPink,
                            tag = "01"
                        )

                        FeatureShowcaseRow(
                            title = "GPS Track Vault & Import Hub",
                            desc = "Logs high-precision routes natively with live GPS coordinates, or imports external GPX files directly into an SQLite database.",
                            icon = Icons.Default.Route,
                            color = ElectricCyan,
                            tag = "02"
                        )

                        FeatureShowcaseRow(
                            title = "High-Fidelity Joint Timeline Graph",
                            desc = "Analyzes how throttle speed correlates directly to lean-degrees across a beautifully rendered time-series timeline.",
                            icon = Icons.Default.Timeline,
                            color = CockpitOrange,
                            tag = "03"
                        )

                        FeatureShowcaseRow(
                            title = "Decentralized Encrypted Replication Vault",
                            desc = "Syncs and archives recorded motorbike logs with custom alphanumeric vault identities to ensure full recoverability across riders.",
                            icon = Icons.Default.CloudQueue,
                            color = NeonGreen,
                            tag = "04"
                        )
                    }
                } else {
                    // Technical specification sheet
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, GeometricBorder, RoundedCornerShape(20.dp)),
                        colors = CardDefaults.cardColors(containerColor = SlateCockpitSurface),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "ENGINEERING BLUEPRINTS",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = ElectricCyan,
                                letterSpacing = 1.2.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            SpecDataRow(label = "Primary Sensor Sampling", value = "50Hz Accelerometer Polling")
                            SpecDataRow(label = "Corner Angle Formula", value = "Radial Euler Rotation Matrix")
                            SpecDataRow(label = "Local Persistence Engine", value = "Room SQL DB (V2 destruct migration)")
                            SpecDataRow(label = "Web Mapping Base", value = "Leaflet Hybrid JS + WebView bridge")
                            SpecDataRow(label = "Acceleration Peak G Limit", value = "3.5 G Dynamic Envelope")
                            SpecDataRow(label = "Maximum Roll Target Angle", value = "60° Superbike Safe Corner Limit")
                            SpecDataRow(label = "Encryption Level", value = "SHA-256 Route Identity Vault")

                            Spacer(modifier = Modifier.height(16.dp))

                            HorizontalDivider(color = GeometricBorder)

                            Spacer(modifier = Modifier.height(16.dp))

                            // Interactive sensor preview indicator (wow effect)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(DeepDarkBackground)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(NeonGreen)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "VIRTUAL TELEMETRY SENSORS",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Text(
                                    text = "ACTIVE SIM ${String.format("%.2f G", liveGlowVal)}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGreen,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            // Core safety manifesto footnote
            Text(
                text = "🛡️ Ride aggressively but safely. Keep Velocitron securely dashboard-mounted while analyzing corner rolls on the track.",
                fontSize = 11.sp,
                color = TextMuted,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
fun FeatureShowcaseRow(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    tag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GeometricBorder, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = SlateCockpitSurface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.15f))
                    .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "#$tag",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = color,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = TextMuted,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun SpecDataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = TextMuted,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            fontSize = 12.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}
