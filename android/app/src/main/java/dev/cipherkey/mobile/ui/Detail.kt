package dev.cipherkey.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.cipherkey.mobile.CipherKeyApp
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.security.VaultHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun FieldRow(label: String, value: String, secret: Boolean = false, mono: Boolean = false, copied: Boolean = false, onCopy: ((String) -> Unit)? = null, extra: @Composable () -> Unit = {}) {
    var shown by remember { mutableStateOf(false) }
    val k = kamiTokens
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = k.inkMuted)
            when {
                secret && !shown -> Text("••••••••••••", style = MaterialTheme.typography.bodyLarge.merge(Mono), color = k.inkDim)
                secret && mono -> Text(colorizedPassword(value), style = MaterialTheme.typography.bodyLarge.merge(Mono))
                else -> Text(value.ifEmpty { "—" }, style = if (mono) MaterialTheme.typography.bodyLarge.merge(Mono) else MaterialTheme.typography.bodyLarge)
            }
        }
        extra()
        if (secret) KIconButton(if (shown) KI.EyeOff else KI.Eye, if (shown) "Ocultar $label" else "Mostrar $label", { shown = !shown })
        if (onCopy != null && value.isNotEmpty()) KIconButton(if (copied) KI.Check else KI.Copy, if (copied) "$label copiado" else "Copiar $label", { onCopy(value) }, tint = if (copied) k.ok else k.ink)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(session: VaultSession, uuid: String, onBack: () -> Unit, onEdit: () -> Unit, onConflict: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as CipherKeyApp
    val records by session.records.collectAsState()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val k = kamiTokens
    val r = records.firstOrNull { it.uuid == uuid }
    if (r == null) { LaunchedEffect(Unit) { onBack() }; return }
    var showHistory by remember { mutableStateOf(false) }
    var copiedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copiedKey) { if (copiedKey != null) { delay(1600); copiedKey = null } }
    val report = remember(records) { VaultHealth.analyze(records) }
    val weak = r.uuid in report.weak
    val sameCount = if (r.uuid in report.repeated) records.count { it.uuid != r.uuid && it.password == r.password } else 0

    fun copy(label: String, v: String, key: String = label) {
        copySecret(ctx, v, app.prefs.clipboardClearSeconds)
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        copiedKey = key
        scope.launch { snack.showSnackbar("$label copiado" + if (app.prefs.clipboardClearSeconds > 0) " · se borra en ${app.prefs.clipboardClearSeconds} s" else "") }
    }

    Scaffold(
        containerColor = k.paper,
        snackbarHost = { SnackbarHost(snack) { d -> Snackbar(d, shape = RoundedCornerShape(10.dp), containerColor = k.ink, contentColor = k.paper) } },
        topBar = {
            KTopBar("Detalle", onBack) {
                KIconButton(if (r.favorite) KI.StarFilled else KI.Star, if (r.favorite) "Quitar de favoritas" else "Marcar como favorita",
                    { haptic.performHapticFeedback(HapticFeedbackType.ToggleOn); scope.launch { session.setFavorite(uuid, !r.favorite) } }, tint = if (r.favorite) k.warn else k.ink)
                KIconButton(KI.Edit, "Editar", onEdit)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            if (r.conflict != null) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp).border(1.dp, k.error, RoundedCornerShape(10.dp)).clickable(role = Role.Button) { onConflict() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(KI.Warn, null, tint = k.error); Spacer(Modifier.width(12.dp))
                    Text("Conflicto de sincronización: toca para resolver", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                }
            }
            Row(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SiteAvatar(r.title, r.url, 64, registered = true)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title.ifEmpty { "(sin título)" }, style = MaterialTheme.typography.headlineSmall)
                    if (r.url.isNotBlank()) Text(SiteIdentity.key(r.title, r.url), style = MaterialTheme.typography.bodySmall, color = k.accentText, maxLines = 1)
                    if (r.category.isNotBlank()) Tag(r.category.uppercase(), k.inkMuted, KI.Folder, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (r.password.isNotEmpty()) KButton(if (copiedKey == "Contraseña") "Copiado" else "Copiar contraseña", { copy("Contraseña", r.password) },
                Modifier.fillMaxWidth().padding(top = 12.dp), icon = if (copiedKey == "Contraseña") KI.Check else KI.Copy)
            if (weak || sameCount > 0) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (weak) Row(verticalAlignment = Alignment.CenterVertically) { Tag("DÉBIL", k.error, KI.Warn); Spacer(Modifier.width(8.dp)); Text("Fácil de adivinar. Genera una nueva desde Editar.", style = MaterialTheme.typography.bodySmall, color = k.inkDim) }
                    if (sameCount > 0) Row(verticalAlignment = Alignment.CenterVertically) { Tag("REPETIDA", k.warn, KI.Repeat); Spacer(Modifier.width(8.dp)); Text("La misma clave está en $sameCount sitio(s) más.", style = MaterialTheme.typography.bodySmall, color = k.inkDim) }
                }
            }

            SectionLabel("ACCESO")
            KCard(Modifier.fillMaxWidth(), padding = 14) {
                FieldRow("Usuario", r.username, copied = copiedKey == "Usuario", onCopy = { copy("Usuario", it) })
                HorizontalDivider(color = k.line1)
                FieldRow("Contraseña", r.password, secret = true, mono = true, copied = copiedKey == "Contraseña", onCopy = { copy("Contraseña", it) })
                if (r.password.isNotEmpty()) StrengthBar(r.password, Modifier.padding(bottom = 10.dp))
                HorizontalDivider(color = k.line1)
                FieldRow("Sitio web", r.url, copied = copiedKey == "URL", onCopy = { copy("URL", it) }, extra = {
                    val safe = r.url.startsWith("https://") || r.url.startsWith("http://")
                    if (safe) KIconButton(KI.Open, "Abrir el sitio", { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                })
            }
            if (r.tags.isNotEmpty()) {
                SectionLabel("ETIQUETAS")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { r.tags.forEach { Tag("#$it", k.inkDim) } }
            }
            if (r.notes.isNotBlank()) { SectionLabel("NOTAS"); KCard(Modifier.fillMaxWidth()) { Text(r.notes, style = MaterialTheme.typography.bodyMedium) } }
            if (r.custom.isNotEmpty()) {
                SectionLabel("CAMPOS PERSONALIZADOS")
                KCard(Modifier.fillMaxWidth(), padding = 14) { r.custom.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(color = k.line1)
                    FieldRow(c.name, c.value, secret = c.secret, copied = copiedKey == c.name, onCopy = { copy(c.name, it) })
                } }
            }
            if (r.totpSecret.isNotBlank()) {
                SectionLabel("VERIFICACIÓN EN DOS PASOS")
                KCard(Modifier.fillMaxWidth(), padding = 14) {
                    FieldRow("Secreto 2FA", r.totpSecret, secret = true, mono = true, copied = copiedKey == "Secreto 2FA", onCopy = { copy("Secreto 2FA", it) })
                    Text("CipherKey guarda el secreto; todavía no genera los códigos de 6 dígitos.", style = MaterialTheme.typography.bodySmall, color = k.inkMuted, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            if (r.history.isNotEmpty()) {
                SectionLabel("HISTORIAL")
                KButton(if (showHistory) "Ocultar ${r.history.size} contraseña(s) anteriores" else "Ver ${r.history.size} contraseña(s) anteriores", { showHistory = !showHistory }, Modifier.fillMaxWidth(), kind = KKind.Secondary, icon = KI.Clock)
                AnimatedVisibility(showHistory) {
                    KCard(Modifier.fillMaxWidth().padding(top = 8.dp), padding = 14) { r.history.forEachIndexed { i, h ->
                        if (i > 0) HorizontalDivider(color = k.line1)
                        FieldRow("Hasta ${formatDate(h.replacedAt())}", h.password(), secret = true, mono = true, copied = copiedKey == "Anterior $i", onCopy = { copy("Contraseña anterior", it, "Anterior $i") })
                    } }
                }
            }
            SectionLabel("INFORMACIÓN")
            Text("MODIFICADA  ${formatDate(r.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
            Text("CREADA      ${formatDate(r.created)}", style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
        }
    }
}
