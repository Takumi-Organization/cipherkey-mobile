package dev.cipherkey.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Iconos propios: trazo de 2 px, esquinas rectas, rejilla de 24. Sustituyen a Material Icons para que toda la app comparta
 * el mismo dibujo plano a línea (Ryoku Kami). Se tiñen con `tint`.
 */
object KI {
    private fun icon(name: String, vararg paths: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter, strokeLineMiter = 4f,
            )
        }
        return b.build()
    }

    val Key by lazy { icon("Key", "M4 15a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M11 12l9 -9", "M16 7l3 3", "M14 9l2 2") }
    val Shield by lazy { icon("Shield", "M12 3l8 3v6c0 5 -3.5 8 -8 9c-4.5 -1 -8 -4 -8 -9V6z", "M8.5 12l2.5 2.5l4.5 -5") }
    val Settings by lazy { icon("Settings", "M4 6h9", "M17 6h3", "M13 4v4h4V4z", "M4 12h3", "M11 12h9", "M7 10v4h4v-4z", "M4 18h11", "M19 18h1", "M15 16v4h4v-4z") }
    val Vault by lazy { icon("Vault", "M4 4h16v16H4z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M12 9V7", "M12 17v-2") }
    val Lock by lazy { icon("Lock", "M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4") }
    val Fingerprint by lazy {
        icon("Fingerprint", "M4 10a8 8 0 0 1 16 0", "M7 12a5 5 0 0 1 10 0v2c0 2 0.5 3.5 1.5 5", "M10 13a2 2 0 0 1 4 0v2c0 2 1 3.5 2 5",
            "M12 13v2c0 3 -1 5 -3 6", "M7 15c0 3 -0.5 5 -2 6")
    }
    val Copy by lazy { icon("Copy", "M9 9h11v11H9z", "M5 15H4V4h11v1") }
    val Eye by lazy { icon("Eye", "M2 12c3 -5 6 -7 10 -7s7 2 10 7c-3 5 -6 7 -10 7s-7 -2 -10 -7z", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0") }
    val EyeOff by lazy { icon("EyeOff", "M2 12c3 -5 6 -7 10 -7s7 2 10 7c-3 5 -6 7 -10 7s-7 -2 -10 -7z", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M4 4l16 16") }
    val Star by lazy { icon("Star", "M12 3l2.7 5.6l6.1 0.9l-4.4 4.3l1 6.1L12 17l-5.4 2.9l1 -6.1L3.2 9.5l6.1 -0.9z") }
    val StarFilled by lazy { icon("StarFilled", "M12 3l2.7 5.6l6.1 0.9l-4.4 4.3l1 6.1L12 17l-5.4 2.9l1 -6.1L3.2 9.5l6.1 -0.9z", filled = true) }
    val Plus by lazy { icon("Plus", "M12 5v14", "M5 12h14") }
    val Search by lazy { icon("Search", "M4 10a6 6 0 1 0 12 0a6 6 0 1 0 -12 0", "M15 15l5 5") }
    val Close by lazy { icon("Close", "M5 5l14 14", "M19 5L5 19") }
    val Back by lazy { icon("Back", "M20 12H5", "M11 6l-6 6l6 6") }
    val Chevron by lazy { icon("Chevron", "M9 5l7 7l-7 7") }
    val Folder by lazy { icon("Folder", "M3 6h6l2 2h10v11H3z") }
    val Warn by lazy { icon("Warn", "M12 3l10 18H2z", "M12 10v5", "M12 18v1") }
    val Check by lazy { icon("Check", "M4 12l5 5L20 6") }
    val Open by lazy { icon("Open", "M14 4h6v6", "M20 4l-9 9", "M18 14v6H4V6h6") }
    val Sync by lazy { icon("Sync", "M4 12a8 8 0 0 1 14 -5", "M18 3v5h-5", "M20 12a8 8 0 0 1 -14 5", "M6 21v-5h5") }
    val Spark by lazy { icon("Spark", "M12 3l2 6l6 2l-6 2l-2 6l-2 -6l-6 -2l6 -2z") }
    val Trash by lazy { icon("Trash", "M4 7h16", "M9 7V4h6v3", "M6 7l1 14h10l1 -14", "M10 11v6", "M14 11v6") }
    val Radar by lazy { icon("Radar", "M11 11h2v2h-2z", "M8 8a5.7 5.7 0 0 0 0 8", "M16 8a5.7 5.7 0 0 1 0 8", "M5 5a10 10 0 0 0 0 14", "M19 5a10 10 0 0 1 0 14") }
    val Edit by lazy { icon("Edit", "M4 20l1 -4L16 5l3 3L8 19z", "M14 7l3 3") }
    val Import by lazy { icon("Import", "M12 4v12", "M7 11l5 5l5 -5", "M4 20h16") }
    val Export by lazy { icon("Export", "M12 16V4", "M7 9l5 -5l5 5", "M4 20h16") }
    val Clock by lazy { icon("Clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0", "M12 7v5l3 2") }
    val Link by lazy { icon("Link", "M10 14a4 4 0 0 0 5.7 0l3 -3a4 4 0 0 0 -5.7 -5.7l-1 1", "M14 10a4 4 0 0 0 -5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1 -1") }
    val Repeat by lazy { icon("Repeat", "M4 9h14l-3 -3", "M20 15H6l3 3") }
    val Dot by lazy { icon("Dot", "M9 9h6v6H9z", filled = true) }
}
