package dev.cipherkey.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cipherkey.mobile.CipherKeyApp
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.launch

@Composable
private fun FieldRow(label: String, value: String, secret: Boolean = false, mono: Boolean = false, onCopy: ((String) -> Unit)? = null, extra: @Composable () -> Unit = {}) {
    var shown by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (secret && !shown) "••••••••••••" else value.ifEmpty { "—" }, style = if (mono) MaterialTheme.typography.bodyLarge.merge(Mono) else MaterialTheme.typography.bodyLarge)
        }
        extra()
        if (secret) IconButton(onClick = { shown = !shown }) { Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (shown) "Ocultar" else "Mostrar") }
        if (onCopy != null && value.isNotEmpty()) IconButton(onClick = { onCopy(value) }) { Icon(Icons.Rounded.ContentCopy, "Copiar $label") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(session: VaultSession, uuid: String, onBack: () -> Unit, onEdit: () -> Unit, onConflict: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as CipherKeyApp
    val records by session.records.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val r = records.firstOrNull { it.uuid == uuid }
    if (r == null) { LaunchedEffect(Unit) { onBack() }; return }
    var showHistory by remember { mutableStateOf(false) }

    fun copy(label: String, v: String) {
        copySecret(ctx, v, app.prefs.clipboardClearSeconds)
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        scope.launch { snack.showSnackbar("$label copiado" + if (app.prefs.clipboardClearSeconds > 0) " (se borra en ${app.prefs.clipboardClearSeconds} s)" else "") }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(title = { Text(r.title.ifEmpty { "(sin título)" }, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") } },
                actions = {
                    IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.ToggleOn); scope.launch { session.setFavorite(uuid, !r.favorite) } }) {
                        Icon(if (r.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorito", tint = if (r.favorite) warningColor() else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Editar") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            if (r.conflict != null) {
                Surface(onClick = onConflict, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(12.dp))
                        Text("Conflicto de sincronización: toca para resolver", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Avatar(r.title.ifEmpty { "?" }, 56); Spacer(Modifier.width(16.dp))
                Column {
                    Text(r.title.ifEmpty { "(sin título)" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (r.category.isNotBlank()) AssistChip(onClick = {}, label = { Text(r.category) }, leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(16.dp)) })
                }
            }
            FlatCard(Modifier.fillMaxWidth()) {
                FieldRow("Usuario", r.username, onCopy = { copy("Usuario", it) })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FieldRow("Contraseña", r.password, secret = true, mono = true, onCopy = { copy("Contraseña", it) })
                if (r.password.isNotEmpty()) StrengthBar(r.password, Modifier.padding(bottom = 4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FieldRow("Sitio web", r.url, onCopy = { copy("URL", it) }, extra = {
                    val safe = r.url.startsWith("https://") || r.url.startsWith("http://")
                    if (safe) IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Abrir") }
                })
            }
            if (r.tags.isNotEmpty()) {
                SectionTitle("ETIQUETAS")
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { r.tags.forEach { SuggestionChip(onClick = {}, label = { Text("#$it") }) } }
            }
            if (r.notes.isNotBlank()) { SectionTitle("NOTAS"); FlatCard(Modifier.fillMaxWidth()) { Text(r.notes) } }
            if (r.custom.isNotEmpty()) {
                SectionTitle("CAMPOS PERSONALIZADOS")
                FlatCard(Modifier.fillMaxWidth()) { r.custom.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FieldRow(c.name, c.value, secret = c.secret, onCopy = { copy(c.name, it) })
                } }
            }
            if (r.totpSecret.isNotBlank()) { SectionTitle("2FA (TOTP)"); FlatCard(Modifier.fillMaxWidth()) { FieldRow("Secreto", r.totpSecret, secret = true, onCopy = { copy("Secreto 2FA", it) }) } }
            if (r.history.isNotEmpty()) {
                SectionTitle("HISTORIAL")
                TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Ocultar ${r.history.size} contraseña(s) anteriores" else "Ver ${r.history.size} contraseña(s) anteriores") }
                AnimatedVisibility(showHistory) {
                    FlatCard(Modifier.fillMaxWidth()) { r.history.forEachIndexed { i, h ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FieldRow("Hasta ${formatDate(h.replacedAt())}", h.password(), secret = true, mono = true, onCopy = { copy("Contraseña anterior", it) })
                    } }
                }
            }
            SectionTitle("INFORMACIÓN")
            Text("Modificada: ${formatDate(r.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Creada: ${formatDate(r.created)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
