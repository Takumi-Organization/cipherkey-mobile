package dev.cipherkey.mobile.ui

import android.app.Activity
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.cipherkey.mobile.R
import dev.cipherkey.mobile.data.Prefs

/**
 * Identidad Takumi (2026-11-22): negro y tinta, monocromo estricto, esquinas con corte diagonal, Outfit para la interfaz y
 * JetBrains Mono para datos (contraseñas, códigos) y etiquetas. Sin degradados, sombras, cristal ni colores dinámicos.
 */
@Immutable
class Kami(
    val dark: Boolean,
    val paper: Color, val raised: Color, val high: Color,
    val ink: Color, val inkDim: Color, val inkMuted: Color, val inkFaint: Color,
    val line1: Color, val line2: Color, val line3: Color,
    /** Relleno del acento (bloques, botones). */
    val accent: Color, val onAccent: Color,
    /** Acento apto como texto sobre `paper` (contraste AA). */
    val accentText: Color,
    val secondary: Color, val error: Color, val warn: Color, val ok: Color,
)

val LocalKami = staticCompositionLocalOf<Kami> { kami(true, Accents.PRESETS[0].color) }

/** Atajo para los tokens del tema actual. */
val kamiTokens: Kami @Composable get() = LocalKami.current

data class AccentPreset(val name: String, val color: Color)

object Accents {
    /** El ORDEN se conserva (la preferencia guarda el índice): 0 Bermellón (antes Naranja), 1 Lima, 2 Fucsia, 3 Egeo (Azul eléctrico), 4 Oro (Ámbar). */
    val PRESETS = listOf(
        AccentPreset("Bermellón", Color(0xFFFF5A4A)),
        AccentPreset("Lima", Color(0xFFB6E04A)),
        AccentPreset("Fucsia", Color(0xFFFF6FB1)),
        AccentPreset("Egeo", Color(0xFF62B6E8)),
        AccentPreset("Oro", Color(0xFFF2B33D)),
    )

    /** Color del acento personalizado a partir de un tono HSV: saturado y luminoso. */
    fun custom(hue: Float): Color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue.coerceIn(0f, 360f), 0.85f, 1f)))
}

// ------------------------------------------------------------------ contraste (WCAG)

private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
fun luminance(c: Color) = 0.2126f * channel(c.red) + 0.7152f * channel(c.green) + 0.0722f * channel(c.blue)
fun contrast(a: Color, b: Color): Float {
    val la = luminance(a); val lb = luminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun mix(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

/** Acerca `c` a negro o blanco, paso a paso, hasta alcanzar el contraste pedido contra `bg`. */
fun ensureContrast(c: Color, bg: Color, target: Float, toward: Color): Color {
    var out = c
    var i = 0
    while (contrast(out, bg) < target && i < 40) { out = mix(out, toward, 0.05f); i++ }
    return out
}

private val NIGHT_INK = Color(0xFFECEFF4)
private val DAY_INK = Color(0xFF14171C)

/**
 * Takumi (tokens.json): sin color de acento, así que `accentBase` se ignora (la preferencia se conserva por compatibilidad).
 * Error / aviso / correcto = tinta: se distinguen por icono y texto, nunca por color.
 */
fun kami(dark: Boolean, @Suppress("UNUSED_PARAMETER") accentBase: Color): Kami {
    val ink = if (dark) Color(0xFFF2F2F2) else Color(0xFF0A0A0A)
    val paper = if (dark) Color(0xFF000000) else Color(0xFFF4F4F4)
    return Kami(
        dark = dark, paper = paper,
        raised = if (dark) Color(0xFF080808) else Color(0xFFFFFFFF),
        high = if (dark) Color(0xFF111111) else Color(0xFFEBEBEB),
        ink = ink,
        inkDim = if (dark) Color(0xFFC9C9C9) else Color(0xFF333333),
        inkMuted = if (dark) Color(0xFF8C8C8C) else Color(0xFF6B6B6B),
        inkFaint = if (dark) Color(0xFF4A4A4A) else Color(0xFFB0B0B0),
        line1 = if (dark) Color(0xFF1C1C1C) else Color(0xFFE0E0E0), line2 = if (dark) Color(0xFF3A3A3A) else Color(0xFFC0C0C0), line3 = if (dark) Color(0xFF3A3A3A) else Color(0xFFC0C0C0),
        accent = ink, onAccent = paper, accentText = ink,
        secondary = if (dark) Color(0xFF8C8C8C) else Color(0xFF6B6B6B), error = ink, warn = ink, ok = ink,
    )
}

// ------------------------------------------------------------------ preferencias de apariencia reactivas

/** Estado de apariencia que lee `CipherKeyTheme`; se persiste en `Prefs`. Sin cargar (pruebas), usa oscuro + Bermellón. */
object ThemeState {
    var mode by mutableIntStateOf(1)          // 0 sistema, 1 oscuro, 2 claro
    var accentIndex by mutableIntStateOf(4)   // -1 = personalizado
    var hue by mutableFloatStateOf(150f)
    private var prefs: Prefs? = null

    fun load(p: Prefs) { prefs = p; mode = p.themeMode; accentIndex = p.accentIndex; hue = p.accentHue }
    fun updateMode(v: Int) { mode = v; prefs?.themeMode = v }
    fun updateAccent(i: Int) { accentIndex = i; prefs?.accentIndex = i }
    fun updateHue(h: Float) { hue = h; accentIndex = -1; prefs?.accentHue = h; prefs?.accentIndex = -1 }
    fun accentColor(): Color = if (accentIndex in Accents.PRESETS.indices) Accents.PRESETS[accentIndex].color else Accents.custom(hue)
}

// ------------------------------------------------------------------ tipografía: Inter (interfaz) y JetBrains Mono (datos), ambas OFL

@OptIn(ExperimentalTextApi::class)
private fun outfit(w: Int, key: FontWeight) = Font(R.font.outfit_variable, key, variationSettings = FontVariation.Settings(FontVariation.weight(w)))
/** Outfit: pesos de la app → pesos Takumi (lo «Bold», cifras y títulos grandes, pasa a 200; el cuerpo a 300; lo enfatizado a 500). */
val InterFamily = FontFamily(outfit(300, FontWeight.Normal), outfit(400, FontWeight.Medium), outfit(500, FontWeight.SemiBold), outfit(200, FontWeight.Bold))
val DataMono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal), Font(R.font.jetbrains_mono_regular, FontWeight.Bold), Font(R.font.jetbrains_mono_regular, FontWeight.SemiBold))

// Cifras tabulares; «calt 0» solo importa en la fuente de datos (JetBrains Mono fusiona «->» o «==» y en una contraseña eso engaña).
private fun t(size: Int, line: Int, bold: Boolean = false, spacing: Float = 0f) = TextStyle(
    fontFamily = InterFamily, fontSize = size.sp, lineHeight = line.sp,
    fontWeight = if (!bold) FontWeight.Normal else if (size >= 28) FontWeight.Bold else FontWeight.SemiBold,
    letterSpacing = (if (bold && size >= 28) 2f else spacing).sp, fontFeatureSettings = "tnum",
)
/** Etiquetas: JetBrains Mono espaciada (los kickers ya vienen en MAYÚSCULAS). */
private fun lab(size: Int, line: Int) = TextStyle(fontFamily = DataMono, fontSize = size.sp, lineHeight = line.sp, letterSpacing = 1.2.sp, fontFeatureSettings = "tnum")

private val KamiTypography = Typography(
    displayLarge = t(56, 60, true), displayMedium = t(40, 48, true), displaySmall = t(32, 40, true),
    headlineLarge = t(28, 36, true), headlineMedium = t(28, 36, true), headlineSmall = t(20, 28, true),
    titleLarge = t(20, 28, true), titleMedium = t(16, 24, true), titleSmall = t(14, 20, true),
    bodyLarge = t(15, 22), bodyMedium = t(14, 22), bodySmall = t(12, 18),
    labelLarge = lab(13, 18), labelMedium = lab(12, 16), labelSmall = lab(11, 16),
)

/** Estilo para datos (contraseñas, códigos): JetBrains Mono, sin ligaduras. */
val Mono = TextStyle(fontFamily = DataMono, fontSize = 14.sp, lineHeight = 22.sp, fontFeatureSettings = "calt 0, liga 0")

/** Corte diagonal del logo en la esquina superior derecha (máx. 22 dp); el resto, ángulo recto. */
fun TakumiCut(cut: androidx.compose.ui.unit.Dp) = CutCornerShape(topStart = 0.dp, topEnd = cut.coerceAtMost(22.dp), bottomEnd = 0.dp, bottomStart = 0.dp)
val TkSm = TakumiCut(10.dp)
val TkLg = TakumiCut(22.dp)

private val KamiShapes = Shapes(
    extraSmall = TakumiCut(6.dp), small = TkSm, medium = TakumiCut(14.dp), large = TkLg, extraLarge = TkLg,
)

private fun scheme(k: Kami): ColorScheme {
    val base = if (k.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = k.accent, onPrimary = k.onAccent,
        primaryContainer = mix(k.paper, k.accent, 0.16f), onPrimaryContainer = k.ink,
        secondary = k.secondary, onSecondary = k.paper, secondaryContainer = k.high, onSecondaryContainer = k.ink,
        tertiary = k.warn, onTertiary = k.paper,
        background = k.paper, onBackground = k.ink, surface = k.paper, onSurface = k.ink,
        surfaceVariant = k.high, onSurfaceVariant = k.inkMuted, surfaceTint = Color.Transparent,
        surfaceContainerLowest = k.paper, surfaceContainerLow = k.raised, surfaceContainer = k.raised,
        surfaceContainerHigh = k.high, surfaceContainerHighest = k.high,
        inverseSurface = k.ink, inverseOnSurface = k.paper, inversePrimary = k.accent,
        outline = k.line3, outlineVariant = k.line2,
        error = k.error, onError = k.paper, errorContainer = mix(k.paper, k.error, 0.16f), onErrorContainer = k.ink,
        scrim = Color.Black,
    )
}

@Composable
fun CipherKeyTheme(content: @Composable () -> Unit) {
    val dark = when (ThemeState.mode) { 0 -> isSystemInDarkTheme(); 1 -> true; else -> false }
    val k = remember(dark, ThemeState.accentIndex, ThemeState.hue) { kami(dark, ThemeState.accentColor()) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as? Activity)?.window ?: return@SideEffect
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
            w.decorView.setBackgroundColor(k.paper.toArgb())
        }
    }
    CompositionLocalProvider(LocalKami provides k) {
        MaterialTheme(colorScheme = scheme(k), typography = KamiTypography, shapes = KamiShapes) {
            CompositionLocalProvider(LocalContentColor provides k.ink) { content() }
        }
    }
}

@Composable
fun successColor() = kamiTokens.ok

@Composable
fun warningColor() = kamiTokens.warn

/** «Reducir animaciones» del sistema (escala de animación a 0): las animaciones decorativas se omiten. */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx: Context = LocalContext.current
    return remember { try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Exception) { false } }
}
