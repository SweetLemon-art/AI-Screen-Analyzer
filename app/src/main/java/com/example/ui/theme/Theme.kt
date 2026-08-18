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

private val DarkColorScheme = darkColorScheme(
  primary = NeonCyan,
  onPrimary = Slate950,
  primaryContainer = Slate850,
  onPrimaryContainer = NeonCyanLight,
  secondary = NeonVioletLight,
  onSecondary = Slate950,
  secondaryContainer = NeonVioletDark,
  onSecondaryContainer = Color.White,
  tertiary = AmberAccent,
  onTertiary = Slate950,
  background = Slate950,
  onBackground = Slate50,
  surface = Slate900,
  onSurface = Slate50,
  surfaceVariant = Slate800,
  onSurfaceVariant = Slate400,
  outline = Slate700,
  error = RoseError,
  onError = Color.White
)

private val LightColorScheme = lightColorScheme(
  primary = NeonCyanDark,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFE0F7FA),
  onPrimaryContainer = Color(0xFF006064),
  secondary = NeonViolet,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFEDE9FE),
  onSecondaryContainer = Color(0xFF4C1D95),
  tertiary = AmberAccent,
  onTertiary = Color.White,
  background = Color(0xFFF8FAFC),
  onBackground = Slate900,
  surface = Color.White,
  onSurface = Slate900,
  surfaceVariant = Color(0xFFF1F5F9),
  onSurfaceVariant = Slate600,
  outline = Color(0xFFCBD5E1),
  error = RoseError,
  onError = Color.White
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Default to sleek futuristic dark theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography,
    content = content
  )
}

