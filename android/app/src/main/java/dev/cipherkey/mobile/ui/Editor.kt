package dev.cipherkey.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = { Text(if (original == null) "Nueva contraseña" else "Editar") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onClose() }) { Icon(Icons.Rounded.Close, "Cerrar") } },
                actions = { Button(onClick = { save() }, enabled = !saving, modifier = Modifier.padding(end = 8.dp)) { Text("Guardar") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).imePadding().padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(title, { title = it; error = null }, label = { Text("Servicio *") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error != null && title.isBlank())
            OutlinedTextField(username, { username = it }, label = { Text("Usuario o correo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Contraseña") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showPwd) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { Row {
                    IconButton(onClick = { showPwd = !showPwd }) { Icon(if (showPwd) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Mostrar u ocultar") }
                    IconButton(onClick = { showGen = true }) { Icon(Icons.Rounded.AutoFixHigh, "Generar contraseña") }
                } })
            StrengthBar(password)
            OutlinedTextField(url, { url = it }, label = { Text("Sitio web (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(category, { category = it }, label = { Text("Categoría") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val existing = records.map { it.category }.filter { it.isNotBlank() }.distinct().take(8)
            if (existing.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { existing.forEach { c -> SuggestionChip(onClick = { category = c }, label = { Text(c) }) } }
            OutlinedTextField(tags, { tags = it }, label = { Text("Etiquetas (separadas por comas)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Notas") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(totp, { totp = it }, label = { Text("Secreto 2FA (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            SectionTitle("CAMPOS PERSONALIZADOS", Modifier.padding(top = 4.dp))
            custom.forEachIndexed { i, c ->
                FlatCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(c.name, { custom[i] = Record.Custom(it, c.value, c.secret) }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(c.value, { custom[i] = Record.Custom(c.name, it, c.secret) }, label = { Text("Valor") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (c.secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(c.secret, { custom[i] = Record.Custom(c.name, c.value, it) }); Spacer(Modifier.width(8.dp)); Text("Secreto", Modifier.weight(1f))
                        TextButton(onClick = { custom.removeAt(i) }) { Text("Quitar", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            OutlinedButton(onClick = { custom.add(Record.Custom("", "", false)) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Añadir campo") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(favorite, { favorite = it }); Spacer(Modifier.width(12.dp)); Text("Favorita") }
            if (original != null) {
                TextButton(onClick = { confirmDelete = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (showGen) GeneratorSheet(onDismiss = { showGen = false }, onUse = { password = it; showPwd = true; showGen = false })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("¿Eliminar «${original?.title}»?") },
        text = { Text("Se eliminará también de tus otros dispositivos al sincronizar.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; scope.launch { session.delete(uuid!!); haptic.performHapticFeedback(HapticFeedbackType.Confirm); onDeleted() } }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("¿Descartar los cambios?") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Descartar") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Seguir editando") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorSheet(onDismiss: () -> Unit, onUse: ((String) -> Unit)?) {
    val haptic = LocalHapticFeedback.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var o by remember { mutableStateOf(PasswordGenerator.Options()) }
    var out by remember { mutableStateOf(PasswordGenerator.generate(o)) }
    fun regen(n: PasswordGenerator.Options = o) { o = n; out = try { PasswordGenerator.generate(n) } catch (_: IllegalArgumentException) { "" } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Generador de contraseñas", style = MaterialTheme.typography.titleLarge)
            FlatCard(Modifier.fillMaxWidth()) {
                Text(out.ifEmpty { "Elige al menos un tipo de carácter" }, style = MaterialTheme.typography.titleMedium.merge(Mono))
                Spacer(Modifier.height(8.dp)); StrengthBar(out)
            }
            Text("Longitud: ${o.length}")
            Slider(value = o.length.toFloat(), onValueChange = { regen(o.copy(length = it.toInt())) }, valueRange = PasswordGenerator.MIN_LENGTH.toFloat()..PasswordGenerator.MAX_LENGTH.toFloat())
            @Composable fun opt(label: String, v: Boolean, set: (Boolean) -> PasswordGenerator.Options) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(v, { regen(set(it)) }) }
            }
            opt("Mayúsculas (A-Z)", o.upper) { o.copy(upper = it) }
            opt("Minúsculas (a-z)", o.lower) { o.copy(lower = it) }
            opt("Números (0-9)", o.digits) { o.copy(digits = it) }
            opt("Símbolos (!@#…)", o.symbols) { o.copy(symbols = it) }
            opt("Excluir caracteres ambiguos (O/0, l/1…)", o.excludeAmbiguous) { o.copy(excludeAmbiguous = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.ContextClick); regen() }, Modifier.weight(1f)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Otra") }
                if (onUse != null) Button(onClick = { onUse(out) }, enabled = out.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Usar") }
                else Button(onClick = { copySecret(ctx, out, 30); haptic.performHapticFeedback(HapticFeedbackType.Confirm) }, enabled = out.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Copiar") }
            }
        }
    }
}
