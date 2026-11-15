package dev.cipherkey.mobile.ui

import android.app.Activity
import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.cipherkey.mobile.R
import dev.cipherkey.mobile.data.Prefs

/**
 * Identidad «Ryoku Kami»: superficies planas, bordes finos, Grafito (oscuro) o Washi (claro), un solo acento saturado y
 * Komiki Mono. Sin degradados, sin sombras, sin colores dinámicos del sistema.
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
    val PRESETS = listOf(
        AccentPreset("Naranja", Color(0xFFFF6A1F)),
        AccentPreset("Lima", Color(0xFFB4E61D)),
        AccentPreset("Fucsia", Color(0xFFFF2E93)),
        AccentPreset("Azul eléctrico", Color(0xFF2F6BFF)),
        AccentPreset("Ámbar", Color(0xFFFFB300)),
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

private val GRAFITO_INK = Color(0xFFECE4D8)
private val WASHI_INK = Color(0xFF26211C)

fun kami(dark: Boolean, accentBase: Color): Kami {
    val paper = if (dark) Color(0xFF211D1A) else Color(0xFFF3ECE0)
    val ink = if (dark) GRAFITO_INK else WASHI_INK
    // relleno: en Washi se oscurece lo justo para que el bloque se distinga del papel (3:1, gráficos)
    val fill = if (dark) accentBase else ensureContrast(accentBase, paper, 3f, Color.Black)
    val onFill = if (contrast(Color(0xFF14110F), fill) >= contrast(Color.White, fill)) Color(0xFF14110F) else Color.White
    val text = if (dark) ensureContrast(accentBase, paper, 4.5f, Color.White) else ensureContrast(accentBase, paper, 4.5f, Color.Black)
    return Kami(
        dark = dark, paper = paper,
        raised = if (dark) Color(0xFF2A2521) else Color(0xFFFAF5EC),
        high = if (dark) Color(0xFF352F2A) else Color(0xFFE9E0D1),
        ink = ink,
        inkDim = if (dark) Color(0xFFC9C0B3) else Color(0xFF4A423A),
        inkMuted = if (dark) Color(0xFFA69D90) else Color(0xFF675D52),
        inkFaint = if (dark) Color(0xFF8F867A) else Color(0xFF8A8074),
        line1 = ink.copy(alpha = 0.16f), line2 = ink.copy(alpha = 0.30f), line3 = ink.copy(alpha = 0.48f),
        accent = fill, onAccent = onFill, accentText = text,
        secondary = if (dark) Color(0xFF8FBDD1) else Color(0xFF2B6A86),
        error = if (dark) Color(0xFFF27A70) else Color(0xFFB3261E),
        warn = if (dark) Color(0xFFF0B25E) else Color(0xFF8A5A00),
        ok = if (dark) Color(0xFF7FD0AE) else Color(0xFF1F6F4D),
    )
}

// ------------------------------------------------------------------ preferencias de apariencia reactivas

/** Estado de apariencia que lee `CipherKeyTheme`; se persiste en `Prefs`. Sin cargar (pruebas), usa Grafito + naranja. */
object ThemeState {
    var mode by mutableIntStateOf(1)          // 0 sistema, 1 Grafito, 2 Washi
    var accentIndex by mutableIntStateOf(0)   // -1 = personalizado
    var hue by mutableFloatStateOf(150f)
    private var prefs: Prefs? = null

    fun load(p: Prefs) { prefs = p; mode = p.themeMode; accentIndex = p.accentIndex; hue = p.accentHue }
    fun updateMode(v: Int) { mode = v; prefs?.themeMode = v }
    fun updateAccent(i: Int) { accentIndex = i; prefs?.accentIndex = i }
    fun updateHue(h: Float) { hue = h; accentIndex = -1; prefs?.accentHue = h; prefs?.accentIndex = -1 }
    fun accentColor(): Color = if (accentIndex in Accents.PRESETS.indices) Accents.PRESETS[accentIndex].color else Accents.custom(hue)
}

// ------------------------------------------------------------------ tipografía: Komiki Mono (MIT), única fuente

val KomikiMono = FontFamily(
    Font(R.font.komikimono_regular, FontWeight.Normal),
    Font(R.font.komikimono_bold, FontWeight.Bold),
    Font(R.font.komikimono_bold, FontWeight.SemiBold),
    Font(R.font.komikimono_bold, FontWeight.Medium),
)

// «calt 0»: la fuente deriva de Fira Code y fusionaría secuencias como «->» o «==»; en una contraseña eso engaña.
private fun t(size: Int, line: Int, bold: Boolean = false, spacing: Float = 0f) = TextStyle(
    fontFamily = KomikiMono, fontSize = size.sp, lineHeight = line.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    letterSpacing = spacing.sp, fontFeatureSettings = "calt 0, liga 0",
)

private val KamiTypography = Typography(
    displayLarge = t(56, 60, true), displayMedium = t(40, 48, true), displaySmall = t(32, 40, true),
    headlineLarge = t(28, 36, true), headlineMedium = t(28, 36, true), headlineSmall = t(20, 28, true),
    titleLarge = t(20, 28, true), titleMedium = t(16, 24, true), titleSmall = t(14, 20, true),
    bodyLarge = t(15, 22), bodyMedium = t(14, 22), bodySmall = t(12, 18),
    labelLarge = t(13, 18, true, 0.3f), labelMedium = t(11, 16, true, 0.9f), labelSmall = t(11, 16, true, 0.9f),
)

/** Estilo para datos (contraseñas, códigos): la misma Komiki Mono, sin ligaduras. */
val Mono = t(14, 22)

private val KamiShapes = Shapes(
    extraSmall = RoundedCornerShape(4), small = RoundedCornerShape(4), medium = RoundedCornerShape(4),
    large = RoundedCornerShape(4), extraLarge = RoundedCornerShape(4),
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
