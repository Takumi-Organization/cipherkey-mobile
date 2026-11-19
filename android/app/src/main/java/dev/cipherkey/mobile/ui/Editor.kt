package dev.cipherkey.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.security.PasswordGenerator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(session: VaultSession, uuid: String?, onClose: () -> Unit, onDeleted: () -> Unit) {
    val records by session.records.collectAsState()
    val original = remember(uuid) { uuid?.let { session.find(it) } }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var title by remember { mutableStateOf(original?.title ?: "") }
    var username by remember { mutableStateOf(original?.username ?: "") }
    var password by remember { mutableStateOf(original?.password ?: "") }
    var url by remember { mutableStateOf(original?.url ?: "") }
    var category by remember { mutableStateOf(original?.category ?: "") }
    var tags by remember { mutableStateOf(original?.tags?.joinToString(", ") ?: "") }
    var notes by remember { mutableStateOf(original?.notes ?: "") }
    var totp by remember { mutableStateOf(original?.totpSecret ?: "") }
    var favorite by remember { mutableStateOf(original?.favorite ?: false) }
    val custom = remember { mutableStateListOf<Record.Custom>().apply { original?.custom?.let { addAll(it) } } }
    var showGen by remember { mutableStateOf(false) }
    var showPwd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val dirty = original == null && (title.isNotEmpty() || username.isNotEmpty() || password.isNotEmpty()) ||
        original != null && (title != original.title || username != original.username || password != original.password || url != original.url ||
            category != original.category || notes != original.notes || totp != original.totpSecret || favorite != original.favorite ||
            tags != original.tags.joinToString(", ") || custom.toList() != original.custom)
    BackHandler(enabled = dirty) { confirmDiscard = true }

    fun save() {
        if (title.isBlank()) { error = "El nombre del servicio es obligatorio"; return }
        saving = true
        val r = original?.copy() ?: Record()
        r.title = title.trim(); r.username = username.trim(); r.password = password; r.url = url.trim()
        r.category = category.trim(); r.notes = notes; r.totpSecret = totp.trim(); r.favorite = favorite
        r.tags = tags.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct().toMutableList()
        r.custom = custom.filter { it.name.isNotBlank() }.toMutableList()
        scope.launch {
            try { session.upsert(r); haptic.performHapticFeedback(HapticFeedbackType.Confirm); onClose() }
            catch (e: Exception) { error = e.message; saving = false }
        }
    }

    val k = kamiTokens
    Scaffold(
        containerColor = k.paper,
        topBar = {
            KTopBar(if (original == null) "Nueva contraseña" else "Editar", onBack = { if (dirty) confirmDiscard = true else onClose() }, backIcon = KI.Close, backDescription = "Cerrar") {
                KButton("Guardar", { save() }, Modifier.padding(end = 12.dp), enabled = !saving, icon = KI.Check)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) { Tag("ERROR", k.error, KI.Warn); Spacer(Modifier.width(8.dp)); Text(it, color = k.error, style = MaterialTheme.typography.bodySmall) } }
            SectionLabel("IDENTIDAD", Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SiteAvatar(title.ifBlank { "?" }, url, 48)
                Spacer(Modifier.width(14.dp))
                Text("Así se verá en tu lista. El color sale del dominio; no se consulta ninguna red.", style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
            }
            KField(title, { title = it; error = null }, "Servicio *", isError = error != null && title.isBlank())
            KField(username, { username = it }, "Usuario o correo")
            SectionLabel("SECRETO", Modifier.padding(top = 8.dp))
            KField(password, { password = it }, "Contraseña", mono = true,
                visualTransformation = if (showPwd) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailing = { Row {
                    KIconButton(if (showPwd) KI.EyeOff else KI.Eye, if (showPwd) "Ocultar contraseña" else "Mostrar contraseña", { showPwd = !showPwd })
                    KIconButton(KI.Spark, "Generar contraseña", { showGen = true }, tint = k.accentText)
                } })
            StrengthBar(password)
            KField(totp, { totp = it }, "Secreto 2FA (opcional)", mono = true)
            SectionLabel("ORGANIZACIÓN", Modifier.padding(top = 8.dp))
            KField(url, { url = it }, "Sitio web o app", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supporting = "Web: https://ejemplo.com. App Android: androidapp://<paquete>, p. ej. androidapp://com.example.app (así el autocompletado la reconoce).")
            KField(category, { category = it }, "Categoría")
            val existing = records.map { it.category }.filter { it.isNotBlank() }.distinct().take(8)
            if (existing.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { existing.forEach { c -> KChip(c, category == c, { category = c }) } }
            KField(tags, { tags = it }, "Etiquetas (separadas por comas)")
            KField(notes, { notes = it }, "Notas", singleLine = false, minLines = 3)

            SectionLabel("CAMPOS PERSONALIZADOS", Modifier.padding(top = 8.dp))
            custom.forEachIndexed { i, c ->
                KCard(Modifier.fillMaxWidth()) {
                    KField(c.name, { custom[i] = Record.Custom(it, c.value, c.secret) }, "Nombre")
                    Spacer(Modifier.height(8.dp))
                    KField(c.value, { custom[i] = Record.Custom(c.name, it, c.secret) }, "Valor",
                        visualTransformation = if (c.secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KSwitchRow("Secreto", c.secret, { custom[i] = Record.Custom(c.name, c.value, it) }, Modifier.weight(1f))
                        KButton("Quitar", { custom.removeAt(i) }, kind = KKind.Danger, icon = KI.Trash)
                    }
                }
            }
            KButton("Añadir campo", { custom.add(Record.Custom("", "", false)) }, Modifier.fillMaxWidth(), kind = KKind.Secondary, icon = KI.Plus)
            KSwitchRow("Favorita", favorite, { favorite = it })
            if (original != null) KButton("Eliminar", { confirmDelete = true }, Modifier.fillMaxWidth(), kind = KKind.Danger, icon = KI.Trash)
        }
    }
    if (showGen) GeneratorSheet(onDismiss = { showGen = false }, onUse = { password = it; showPwd = true; showGen = false })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("¿Eliminar «${original?.title}»?") },
        text = { Text("Se eliminará también de tus otros dispositivos al sincronizar.") },
        confirmButton = { KTextButton(onClick = { confirmDelete = false; scope.launch { session.delete(uuid!!); haptic.performHapticFeedback(HapticFeedbackType.Confirm); onDeleted() } }) { Text("Eliminar", color = k.error) } },
        dismissButton = { KTextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("¿Descartar los cambios?") },
        confirmButton = { KTextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Descartar") } },
        dismissButton = { KTextButton(onClick = { confirmDiscard = false }) { Text("Seguir editando") } })
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorSheet(onDismiss: () -> Unit, onUse: ((String) -> Unit)?) {
    val haptic = LocalHapticFeedback.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val k = kamiTokens
    var o by remember { mutableStateOf(PasswordGenerator.Options()) }
    var out by remember { mutableStateOf(PasswordGenerator.generate(o)) }
    fun regen(n: PasswordGenerator.Options = o) { o = n; out = try { PasswordGenerator.generate(n) } catch (_: IllegalArgumentException) { "" } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = k.raised, shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp).size(width = 36.dp, height = 3.dp).background(k.line3)) }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GENERADOR", style = MaterialTheme.typography.labelMedium, color = k.accentText)
            Text("Contraseña nueva", style = MaterialTheme.typography.titleLarge)
            KCard(Modifier.fillMaxWidth(), container = k.paper) {
                if (out.isEmpty()) Text("Elige al menos un tipo de carácter", color = k.inkMuted)
                else Text(colorizedPassword(out), style = MaterialTheme.typography.titleMedium.merge(Mono), modifier = Modifier.semantics { contentDescription = "Contraseña generada, ${out.length} caracteres" })
                Spacer(Modifier.height(10.dp)); StrengthBar(out)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LONGITUD", style = MaterialTheme.typography.labelMedium, color = k.inkMuted, modifier = Modifier.weight(1f))
                Text("${o.length}", style = MaterialTheme.typography.titleMedium, color = k.accentText)
            }
            Slider(value = o.length.toFloat(), onValueChange = { regen(o.copy(length = it.toInt())) }, valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..PasswordGenerator.MAX_LENGTH.toFloat(),
                colors = SliderDefaults.colors(thumbColor = k.accent, activeTrackColor = k.accent, inactiveTrackColor = k.line2, activeTickColor = k.accent, inactiveTickColor = k.line2))
            KSwitchRow("Mayúsculas (A-Z)", o.upper, { regen(o.copy(upper = it)) })
            KSwitchRow("Minúsculas (a-z)", o.lower, { regen(o.copy(lower = it)) })
            KSwitchRow("Números (0-9)", o.digits, { regen(o.copy(digits = it)) })
            KSwitchRow("Símbolos (!@#…)", o.symbols, { regen(o.copy(symbols = it)) })
            KSwitchRow("Excluir caracteres ambiguos (O/0, l/1…)", o.excludeAmbiguous, { regen(o.copy(excludeAmbiguous = it)) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KButton("Otra", { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); regen() }, Modifier.weight(1f), kind = KKind.Secondary, icon = KI.Sync)
                if (onUse != null) KButton("Usar", { onUse(out) }, Modifier.weight(1f), enabled = out.isNotEmpty(), icon = KI.Check)
                else KButton("Copiar", { copySecret(ctx, out, 30); haptic.performHapticFeedback(HapticFeedbackType.Confirm) }, Modifier.weight(1f), enabled = out.isNotEmpty(), icon = KI.Copy)
            }
        }
    }
}
