package dev.cipherkey.mobile.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.security.PasswordGenerator
import dev.cipherkey.mobile.security.VaultHealth
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Un icono que gira mientras se sincroniza (quieto si el sistema pide reducir animaciones). */
@Composable
fun SyncIcon(running: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val angle = if (running && !reduced) {
        val t = rememberInfiniteTransition(label = "sync")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a").value
    } else 0f
    Icon(KI.Sync, "Sincronización", tint = tint, modifier = modifier.rotate(angle))
}

/** Medidor plano de 270° dividido en 40 marcas; las llenas van en acento. Cifra grande en el centro. */
@Composable
private fun ScoreGauge(score: Int, level: VaultHealth.Level, modifier: Modifier = Modifier) {
    val k = kamiTokens
    val reduced = rememberReducedMotion()
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(score) { target = score / 100f }
    val p = if (reduced) score / 100f else animateFloatAsState(target, tween(900, easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)), label = "score").value
    Box(modifier.size(232.dp).semantics(mergeDescendants = true) { contentDescription = "Puntuación de salud $score sobre 100, ${level.label.lowercase()}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val n = 40; val sweep = 270f; val start = 135f
            val outer = size.minDimension / 2; val inner = outer * 0.80f
            val filled = (p * n).toInt()
            for (i in 0 until n) {
                val a = Math.toRadians((start + sweep * i / (n - 1)).toDouble())
                val c = if (i < filled) k.accent else k.line1
                val from = Offset(center.x + (inner * cos(a)).toFloat(), center.y + (inner * sin(a)).toFloat())
                val to = Offset(center.x + (outer * cos(a)).toFloat(), center.y + (outer * sin(a)).toFloat())
                drawLine(c, from, to, strokeWidth = 4.dp.toPx())
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(p * 100).toInt().coerceAtMost(score)}", fontSize = 76.sp, lineHeight = 80.sp, fontWeight = FontWeight.Bold, color = k.accentText)
            Text("DE 100", style = MaterialTheme.typography.labelMedium, color = k.inkMuted)
        }
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 14.dp, horizontal = 14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = kamiTokens.inkMuted)
    }
}

@Composable
private fun StrengthRow(label: String, count: Int, total: Int, color: Color) {
    val k = kamiTokens
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $count" }, verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(), Modifier.width(96.dp), style = MaterialTheme.typography.labelSmall, color = k.inkDim)
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawRect(k.line1, Offset.Zero, size)
            if (total > 0 && count > 0) drawRect(color, Offset.Zero, Size(size.width * count / total, size.height))
        }
        Text("$count", Modifier.width(40.dp), style = MaterialTheme.typography.labelLarge, color = k.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun IssueRow(title: String, detail: String, count: Int, tone: Color, onClick: () -> Unit) {
    val k = kamiTokens
    val clean = count == 0
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !clean, role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).border(1.dp, if (clean) k.ok else tone, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            if (clean) Icon(KI.Check, "Sin problemas", Modifier.size(18.dp), tint = k.ok)
            else Text("$count", style = MaterialTheme.typography.labelLarge, color = tone)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (clean) "Ninguna" else detail, style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
        }
        if (!clean) Icon(KI.Chevron, null, Modifier.size(18.dp), tint = k.inkMuted)
    }
    HorizontalDivider(color = k.line1)
}

@Composable
fun DashboardTab(session: VaultSession, onOpen: (String) -> Unit, onSync: () -> Unit, onConflicts: () -> Unit, onFilter: (String) -> Unit = {}) {
    val records by session.records.collectAsState()
    val sync by session.sync.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val k = kamiTokens
    val report = remember(records) { VaultHealth.analyze(records) }
    val favorites = records.count { it.favorite }
    val categories = records.map { it.category }.filter { it.isNotBlank() }.toSet().size
    val recent = remember(records) { records.sortedByDescending { it.updatedAt }.take(4) }
    val summary = when {
        report.total == 0 -> "Añade contraseñas para ver aquí su salud."
        report.weak.isEmpty() && report.repeated.isEmpty() && report.old.isEmpty() -> "Nada que arreglar: todas son fuertes, únicas y recientes."
        else -> listOfNotNull(
            if (report.weak.isNotEmpty()) "${report.weak.size} débil(es)" else null,
            if (report.repeated.isNotEmpty()) "${report.repeated.size} repetida(s)" else null,
            if (report.old.isNotEmpty()) "${report.old.size} sin cambiar en un año" else null,
        ).joinToString(" · ")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp + LocalBottomInset.current)) {
        ScreenTitle("PANEL", "Salud de la bóveda")
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ScoreGauge(report.score, report.level)
            Spacer(Modifier.height(8.dp))
            val levelColor = when (report.level) {
                VaultHealth.Level.SOLID, VaultHealth.Level.GOOD -> k.ok
                VaultHealth.Level.IMPROVABLE -> k.warn
                VaultHealth.Level.AT_RISK -> k.error
                VaultHealth.Level.EMPTY -> k.inkMuted
            }
            Tag(report.level.label, levelColor, icon = if (levelColor == k.ok) KI.Check else if (report.level == VaultHealth.Level.EMPTY) null else KI.Warn)
            Text(summary, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = k.inkDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }

        Row(Modifier.fillMaxWidth().padding(top = 20.dp).border(1.dp, k.line2, RoundedCornerShape(10.dp)).height(IntrinsicSize.Min)) {
            StatCell("${records.size}", "ENTRADAS", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(k.line2))
            StatCell("$favorites", "FAVORITAS", Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(k.line2))
            StatCell("$categories", "CATEGORÍAS", Modifier.weight(1f))
        }

        AnimatedVisibility(sync.conflicts > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp).border(1.dp, k.error, RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onConflicts() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(KI.Warn, null, tint = k.error)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${sync.conflicts} conflicto(s) por resolver", style = MaterialTheme.typography.titleSmall)
                    Text("Se editó lo mismo en dos dispositivos. No se ha perdido nada.", style = MaterialTheme.typography.bodySmall, color = k.inkDim)
                }
                Icon(KI.Chevron, null)
            }
        }

        SectionLabel("FORTALEZA")
        val names = PasswordGenerator.Strength.entries
        names.indices.reversed().forEach { i -> StrengthRow(names[i].label, report.byStrength[i], report.total, strengthColor(names[i])) }

        SectionLabel("A REVISAR")
        IssueRow("Contraseñas débiles", "Fáciles de adivinar: cámbialas por una generada", report.weak.size, k.error) { onFilter("weak") }
        IssueRow("Contraseñas repetidas", "${report.repeatedGroups} grupo(s) comparten la misma clave", report.repeated.size, k.warn) { onFilter("rep") }
        IssueRow("Sin cambiar en un año", "Conviene renovarlas", report.old.size, k.warn) { onFilter("old") }

        SectionLabel("SINCRONIZACIÓN")
        KCard(Modifier.fillMaxWidth().clickable(role = Role.Button) { onSync() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = sync.configured && sync.error == null
                SyncIcon(sync.running, if (!sync.configured) k.inkMuted else if (ok) k.ok else k.error)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        !sync.configured -> "Sincronización desactivada"
                        sync.running -> "Sincronizando…"
                        sync.error != null -> "Sin sincronizar"
                        sync.pending > 0 -> "${sync.pending} cambio(s) pendiente(s)"
                        else -> "Todo sincronizado"
                    }, style = MaterialTheme.typography.titleSmall)
                    Text(when {
                        !sync.configured -> "Opcional: toca para configurarla"
                        sync.error != null -> sync.error!!
                        else -> "Última vez: ${formatDate(sync.lastSync)}"
                    }, style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
                }
                if (sync.configured) KButton("Ahora", onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); scope.launch { try { session.syncNow() } catch (_: Exception) {} } }, kind = KKind.Secondary)
            }
        }

        SectionLabel("ÚLTIMAS MODIFICACIONES")
        if (recent.isEmpty()) Text("Aún no hay contraseñas. Añade la primera desde la pestaña Bóveda.", color = k.inkMuted)
        recent.forEach { RecordRow(it, onClick = { onOpen(it.uuid) }, health = report) }
    }
}

/** Fila de una entrada: avatar por dominio, nombre, usuario, categoría y avisos de fortaleza (con texto, no solo color). */
@Composable
fun RecordRow(r: Record, onClick: () -> Unit, health: VaultHealth.Report? = null) {
    val k = kamiTokens
    val strength = remember(r.password) { if (r.password.isEmpty()) null else PasswordGenerator.strength(r.password) }
    val weak = health?.weak?.contains(r.uuid) ?: false
    val rep = health?.repeated?.contains(r.uuid) ?: false
    val desc = buildString {
        append(r.title.ifEmpty { "sin título" }); if (r.username.isNotEmpty()) append(", ${r.username}")
        if (r.category.isNotBlank()) append(", categoría ${r.category}")
        if (r.favorite) append(", favorita"); if (weak) append(", contraseña débil"); if (rep) append(", contraseña repetida"); if (r.conflict != null) append(", con conflicto")
    }
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = desc }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SiteAvatar(r.title, r.url, 44)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title.ifEmpty { "(sin título)" }, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (r.favorite) { Spacer(Modifier.width(6.dp)); Icon(KI.StarFilled, null, Modifier.size(13.dp), tint = k.warn) }
                }
                Text(r.username.ifEmpty { SiteIdentity.key(r.title, r.url).takeIf { r.url.isNotEmpty() } ?: "—" }, style = MaterialTheme.typography.bodySmall, color = k.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.category.isNotBlank() || weak || rep || r.conflict != null) {
                    Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (r.category.isNotBlank()) Tag(r.category.uppercase(), k.inkMuted)
                        if (weak) Tag("DÉBIL", k.error, KI.Warn)
                        if (rep) Tag("REPETIDA", k.warn, KI.Repeat)
                        if (r.conflict != null) Tag("CONFLICTO", k.error, KI.Warn)
                    }
                }
            }
            if (strength != null) { Spacer(Modifier.width(10.dp)); StrengthTicks(strength) }
        }
        HorizontalDivider(color = k.line1)
    }
}

@Composable
private fun VaultFab(onClick: () -> Unit) {
    val k = kamiTokens
    Row(
        Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).background(k.accent).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(KI.Plus, null, tint = k.onAccent); Spacer(Modifier.width(8.dp))
        Text("NUEVA", color = k.onAccent, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VaultTab(session: VaultSession, onOpen: (String) -> Unit, onAdd: () -> Unit, onGenerator: () -> Unit, initialFilter: String = "all") {
    val records by session.records.collectAsState()
    val k = kamiTokens
    var query by remember { mutableStateOf("") }
    var filter by remember(initialFilter) { mutableStateOf(initialFilter) }       // all | fav | weak | rep | old | cat:<x> | tag:<x>
    val report = remember(records) { VaultHealth.analyze(records) }
    val categories = records.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    val tags = records.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = remember(records, query, filter, report) {
        records.filter { r ->
            val matches = query.isBlank() || listOf(r.title, r.username, r.url, r.notes, r.category).any { it.contains(query, ignoreCase = true) } || r.tags.any { it.contains(query, ignoreCase = true) }
            val f = when {
                filter == "fav" -> r.favorite
                filter == "weak" -> r.uuid in report.weak
                filter == "rep" -> r.uuid in report.repeated
                filter == "old" -> r.uuid in report.old
                filter.startsWith("cat:") -> r.category == filter.removePrefix("cat:")
                filter.startsWith("tag:") -> filter.removePrefix("tag:") in r.tags
                else -> true
            }
            matches && f
        }
    }
    // favoritas arriba y el resto por letra, con cabeceras de sección
    val rows = remember(shown, filter, query) {
        val sorted = shown.sortedBy { it.title.lowercase() }
        buildList<Any> {
            val favs = if (filter == "all" && query.isBlank()) sorted.filter { it.favorite } else emptyList()
            if (favs.isNotEmpty()) { add("FAVORITAS"); addAll(favs) }
            var last = ""
            for (r in sorted) {
                if (r in favs) continue
                val l = SiteIdentity.initial(r.title, r.url)
                if (l != last) { add(l); last = l }
                add(r)
            }
        }
    }
    Scaffold(containerColor = k.paper, floatingActionButton = { Box(Modifier.padding(bottom = LocalBottomInset.current)) { VaultFab(onAdd) } }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            ScreenTitle("BÓVEDA", "${records.size} contraseñas") { KIconButton(KI.Spark, "Generador de contraseñas", onGenerator) }
            KField(query, { query = it }, "Buscar", placeholder = "Nombre, usuario, sitio, etiqueta…", leading = { Icon(KI.Search, null, Modifier.size(20.dp)) },
                trailing = if (query.isNotEmpty()) ({ KIconButton(KI.Close, "Borrar búsqueda", { query = "" }) }) else null)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { KChip("Todas", filter == "all", { filter = "all" }) }
                item { KChip("Favoritas", filter == "fav", { filter = "fav" }, KI.Star) }
                if (report.weak.isNotEmpty()) item { KChip("Débiles ${report.weak.size}", filter == "weak", { filter = "weak" }, KI.Warn) }
                if (report.repeated.isNotEmpty()) item { KChip("Repetidas ${report.repeated.size}", filter == "rep", { filter = "rep" }, KI.Repeat) }
                if (report.old.isNotEmpty()) item { KChip("Antiguas ${report.old.size}", filter == "old", { filter = "old" }, KI.Clock) }
                items(categories) { c -> KChip(c, filter == "cat:$c", { filter = "cat:$c" }, KI.Folder) }
                items(tags) { t -> KChip("#$t", filter == "tag:$t", { filter = "tag:$t" }) }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().dotGrid(k.line1), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        if (records.isEmpty()) {
                            VaultEmblem(96, open = true)
                            Spacer(Modifier.height(20.dp))
                            Text("BÓVEDA VACÍA", style = MaterialTheme.typography.labelMedium, color = k.accentText)
                            Text("Aquí vivirán tus contraseñas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                            Text("Cifradas en este dispositivo. Empieza con la primera.", style = MaterialTheme.typography.bodySmall, color = k.inkMuted, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            KButton("Añadir la primera", onAdd, icon = KI.Plus)
                        } else {
                            Icon(KI.Search, null, Modifier.size(40.dp), tint = k.inkFaint)
                            Text("SIN RESULTADOS", style = MaterialTheme.typography.labelMedium, color = k.inkMuted, modifier = Modifier.padding(top = 12.dp))
                            Text("Prueba con otra búsqueda o quita el filtro.", style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp + LocalBottomInset.current)) {
                    items(rows, key = { if (it is Record) it.uuid else "h:$it" }) { row ->
                        if (row is Record) Box(Modifier.animateItem()) { RecordRow(row, onClick = { onOpen(row.uuid) }, health = report) }
                        else Text(row as String, Modifier.padding(top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = k.accentText)
                    }
                }
            }
        }
    }
}
