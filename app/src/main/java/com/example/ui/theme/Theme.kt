package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = ElectricCyan,
    secondary = CockpitOrange,
    tertiary = NeonGreen,
    background = DeepDarkBackground,
    surface = SlateCockpitSurface,
    onPrimary = DeepDarkBackground,
    onSecondary = DeepDarkBackground,
    onBackground = TextSilver,
    onSurface = TextSilver,
    primaryContainer = SlateCockpitSurface,
    onPrimaryContainer = ElectricCyan
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark motorcycle theme
  dynamicColor: Boolean = false, // Keep consistent branding colors
  content: @Composable () -> Unit,
) {
  val colorScheme = DarkColorScheme // Standardized majestic dark motorcycle interface

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
