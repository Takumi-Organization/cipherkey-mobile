package dev.cipherkey.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Takumi no usa cristal ni desenfoque: la barra flotante es un panel opaco con borde de 1 dp. Se conservan los nombres
 * de la API anterior (`glass`, `glassSource`) para no tocar cada pantalla.
 */
/** Alto de la barra de pestañas flotante (sin la barra de navegación del sistema): el contenido lo suma a su relleno inferior. */
val TabBarHeight = 86.dp
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

class GlassBackdrop internal constructor()

@Composable fun rememberGlassBackdrop(): GlassBackdrop = remember { GlassBackdrop() }

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier = this

@Composable
@Suppress("UNUSED_PARAMETER")
fun Modifier.glass(shape: Shape, blur: Dp = 0.dp, tint: Color? = null): Modifier {
    val k = kamiTokens
    return this.clip(shape).background(tint ?: k.raised).border(1.dp, k.line3, shape)
}
