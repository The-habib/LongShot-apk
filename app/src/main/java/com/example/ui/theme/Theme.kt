package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = PremiumPrimary,
    secondary = PremiumSecondary,
    tertiary = PremiumSecondary,
    background = PremiumBackground,
    surface = PremiumSurface,
    onBackground = PremiumOnBackground,
    onSurface = PremiumOnSurface,
    surfaceVariant = Color(0xFF222222)
  )

private val LightColorScheme = DarkColorScheme // Force dark theme for premium feel

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false, // Disable dynamic colors to keep premium dark look
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
