package dev.cipherkey.mobile.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.security.PasswordGenerator
import kotlinx.coroutines.launch

/** Un icono que gira mientras se sincroniza. */
@Composable
fun SyncIcon(running: Boolean, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "sync")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a")
    Icon(Icons.Rounded.Sync, "Sincronización", tint = tint, modifier = modifier.rotate(if (running) angle else 0f))
}

@Composable
fun StatTile(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    FlatCard(modifier) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DashboardTab(session: VaultSession, onOpen: (String) -> Unit, onSync: () -> Unit, onConflicts: () -> Unit) {
    val records by session.records.collectAsState()
    val sync by session.sync.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val favorites = records.count { it.favorite }
    val categories = records.map { it.category }.filter { it.isNotBlank() }.toSet().size
    val weak = records.count { it.password.isNotEmpty() && PasswordGenerator.strength(it.password).ordinal <= 1 }
    val repeated = records.filter { it.password.isNotEmpty() }.groupBy { it.password }.values.filter { it.size > 1 }.sumOf { it.size }
    val recent = records.sortedByDescending { it.updatedAt }.take(5)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Panel", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("${records.size}", "Contraseñas", Icons.Rounded.Key, Modifier.weight(1f))
            StatTile("$favorites", "Favoritos", Icons.Rounded.Star, Modifier.weight(1f))
            StatTile("$categories", "Categorías", Icons.Rounded.Folder, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        AnimatedVisibility(sync.conflicts > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { onConflicts() }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${sync.conflicts} conflicto(s) por resolver", fontWeight = FontWeight.SemiBold)
                        Text("Se editó lo mismo en dos dispositivos. No se ha perdido nada.", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
        FlatCard(Modifier.fillMaxWidth().clickable { onSync() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = sync.configured && sync.error == null
                SyncIcon(sync.running, if (!sync.configured) MaterialTheme.colorScheme.onSurfaceVariant else if (ok) successColor() else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        !sync.configured -> "Sincronización desactivada"
                        sync.running -> "Sincronizando…"
                        sync.error != null -> "Sin sincronizar"
                        sync.pending > 0 -> "${sync.pending} cambio(s) pendiente(s)"
                        else -> "Todo sincronizado"
                    }, fontWeight = FontWeight.SemiBold)
                    Text(when {
                        !sync.configured -> "Opcional: toca para configurarla"
                        sync.error != null -> sync.error!!
                        else -> "Última vez: ${formatDate(sync.lastSync)}"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (sync.configured) TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); scope.launch { try { session.syncNow() } catch (_: Exception) {} } }) { Text("Ahora") }
            }
        }
        if (weak > 0 || repeated > 0) {
            Spacer(Modifier.height(12.dp))
            FlatCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = warningColor())
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Seguridad de tus contraseñas", fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(if (weak > 0) "$weak débil(es)" else null, if (repeated > 0) "$repeated repetida(s)" else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SectionTitle("ÚLTIMAS MODIFICACIONES")
        if (recent.isEmpty()) Text("Aún no hay contraseñas. Añade la primera desde la pestaña Bóveda.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        recent.forEach { RecordRow(it, onClick = { onOpen(it.uuid) }) }
    }
}

@Composable
fun RecordRow(r: Record, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(r.title.ifEmpty { "?" })
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title.ifEmpty { "(sin título)" }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(r.username.ifEmpty { r.url.ifEmpty { "—" } }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (r.conflict != null) Icon(Icons.Rounded.Warning, "Conflicto", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        if (r.favorite) Icon(Icons.Rounded.Star, "Favorito", tint = warningColor(), modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VaultTab(session: VaultSession, onOpen: (String) -> Unit, onAdd: () -> Unit, onGenerator: () -> Unit) {
    val records by session.records.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }       // all | fav | cat:<x> | tag:<x>
    val categories = records.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    val tags = records.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sorted()
    val shown = records.filter { r ->
        val matches = query.isBlank() || listOf(r.title, r.username, r.url, r.notes, r.category).any { it.contains(query, ignoreCase = true) } || r.tags.any { it.contains(query, ignoreCase = true) }
        val f = when {
            filter == "fav" -> r.favorite
            filter.startsWith("cat:") -> r.category == filter.removePrefix("cat:")
            filter.startsWith("tag:") -> filter.removePrefix("tag:") in r.tags
            else -> true
        }
        matches && f
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Nueva") }) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bóveda", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onGenerator) { Icon(Icons.Rounded.AutoFixHigh, "Generador de contraseñas") }
            }
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Buscar") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(14.dp),
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Borrar búsqueda") } })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                item { FilterChip(filter == "all", { filter = "all" }, { Text("Todas") }) }
                item { FilterChip(filter == "fav", { filter = "fav" }, { Text("Favoritas") }, leadingIcon = { Icon(Icons.Rounded.Star, null, Modifier.size(16.dp)) }) }
                items(categories) { c -> FilterChip(filter == "cat:$c", { filter = "cat:$c" }, { Text(c) }, leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp)) }) }
                items(tags) { t -> FilterChip(filter == "tag:$t", { filter = "tag:$t" }, { Text("#$t") }) }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Key, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(8.dp))
                        Text(if (records.isEmpty()) "Tu bóveda está vacía" else "Sin resultados", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.uuid }) { r ->
                        Box(Modifier.animateItem()) { RecordRow(r, onClick = { onOpen(r.uuid) }) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
