package dev.cipherkey.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Misma identidad que el escritorio: acento azul #1B8AC9, fondos pizarra, tarjetas planas con borde fino. Sin degradados. */
object CK {
    val Accent = Color(0xFF1B8AC9)
    val Success = Color(0xFF1F9D6B)
    val Warning = Color(0xFFD98A00)
    val Danger = Color(0xFFD64545)
    val SuccessDark = Color(0xFF3CCF91)
    val WarningDark = Color(0xFFF0B429)
    val DangerDark = Color(0xFFF26D6D)
}

private val Light = lightColorScheme(
    primary = CK.Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFD8ECF8), onPrimaryContainer = Color(0xFF0B2538),
    secondary = Color(0xFF66707F), onSecondary = Color.White,
    background = Color(0xFFF6F8FB), onBackground = Color(0xFF1B2330),
    surface = Color(0xFFF6F8FB), onSurface = Color(0xFF1B2330),
    surfaceVariant = Color(0xFFEAEEF4), onSurfaceVariant = Color(0xFF6B7686),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEAEEF4), surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = Color(0xFFA9B3C1), outlineVariant = Color(0xFFDDE3EC),
    error = CK.Danger, onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF4DB0E8), onPrimary = Color(0xFF06263B),
    primaryContainer = Color(0xFF20384A), onPrimaryContainer = Color(0xFFE8F4FC),
    secondary = Color(0xFF98A2B3), onSecondary = Color(0xFF15171B),
    background = Color(0xFF1B1E23), onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF1B1E23), onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF15171B), onSurfaceVariant = Color(0xFF8B95A5),
    surfaceContainerLowest = Color(0xFF15171B), surfaceContainerLow = Color(0xFF23272E), surfaceContainer = Color(0xFF23272E),
    surfaceContainerHigh = Color(0xFF2B3038), surfaceContainerHighest = Color(0xFF343A44),
    outline = Color(0xFF5A6372), outlineVariant = Color(0xFF343A44),
    error = CK.DangerDark, onError = Color(0xFF3B0A0A),
)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)

@Composable
fun CipherKeyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

@Composable
fun successColor() = if (isSystemInDarkTheme()) CK.SuccessDark else CK.Success

@Composable
fun warningColor() = if (isSystemInDarkTheme()) CK.WarningDark else CK.Warning
