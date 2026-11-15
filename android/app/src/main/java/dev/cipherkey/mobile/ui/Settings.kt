package dev.cipherkey.mobile.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.CipherKeyApp
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.security.MasterPasswordPolicy
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(session: VaultSession, onSecureChanged: () -> Unit, onSyncSetup: () -> Unit, onConflicts: () -> Unit, onImport: () -> Unit, onGenerator: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as CipherKeyApp
    val activity = ctx.findActivity()
    val scope = rememberCoroutineScope()
    val sync by session.sync.collectAsState()
    var autoLock by remember { mutableIntStateOf(app.prefs.autoLockSeconds) }
    var lockOnLeave by remember { mutableStateOf(app.prefs.lockOnLeave) }
    var secure by remember { mutableStateOf(app.prefs.secureScreen) }
    var bio by remember { mutableStateOf(app.biometric.isEnabled()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var showChange by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<Pair<String, List<dev.cipherkey.core.sync.ServerClient.DeviceInfo>>?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        app.externalFlow = false
        if (uri != null) scope.launch {
            try {
                val bytes = session.exportEncrypted()
                ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                msg = "Copia cifrada exportada"
            } catch (e: Exception) { msg = "No se pudo exportar: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp)) }

        SectionTitle("SEGURIDAD")
        FlatCard(Modifier.fillMaxWidth()) {
            Text("Bloqueo por inactividad")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min", 0 to "Nunca").forEach { (v, l) ->
                    FilterChip(autoLock == v, { autoLock = v; app.prefs.autoLockSeconds = v }, { Text(l) })
                }
            }
            SwitchRow("Bloquear al salir de la app", lockOnLeave) { lockOnLeave = it; app.prefs.lockOnLeave = it }
            SwitchRow("Ocultar contenido en capturas y recientes", secure) { secure = it; app.prefs.secureScreen = it; onSecureChanged() }
            if (Biometrics.available(activity)) SwitchRow("Desbloqueo con huella", bio) { on ->
                if (!on) { app.biometric.disable(); bio = false; msg = "Huella desactivada" }
                else try {
                    val c = app.biometric.encryptCipher()
                    Biometrics.prompt(activity, "Activar huella", "Confirma para guardar la clave", c, { cipher ->
                        val k = session.vaultKeyCopy()
                        try { app.biometric.finishEnable(cipher, k); bio = true; msg = "Huella activada" } catch (e: Exception) { app.biometric.disable(); msg = "No se pudo activar la huella" }
                        finally { k.fill(0) }
                    }, { err -> app.biometric.disable(); bio = false; if (err.isNotBlank()) msg = err })
                } catch (e: Exception) { app.biometric.disable(); msg = "No se pudo preparar la huella" }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { session.lock() }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Lock, null); Spacer(Modifier.width(8.dp)); Text("Bloquear ahora") }
        }

        SectionTitle("DATOS")
        FlatCard(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { showChange = true }, Modifier.fillMaxWidth()) { Text("Cambiar contraseña maestra") }
            OutlinedButton(onClick = { app.externalFlow = true; exporter.launch("cipherkey-copia.ckey") }, Modifier.fillMaxWidth()) { Text("Exportar copia cifrada (.ckey)") }
            OutlinedButton(onClick = onImport, Modifier.fillMaxWidth()) { Text("Importar bóveda (.ckey)") }
            OutlinedButton(onClick = onGenerator, Modifier.fillMaxWidth()) { Text("Generador de contraseñas") }
        }

        SectionTitle("PROXIMIDAD (RED LOCAL, SIN SERVIDOR)")
        ProximityCard(app)

        SectionTitle("SINCRONIZACIÓN CON SERVIDOR (OPCIONAL)")
        FlatCard(Modifier.fillMaxWidth()) {
            if (!sync.configured) {
                Text("Desactivada. No hace falta para sincronizar por proximidad: tu bóveda funciona sin servidor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onSyncSetup, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Configurar") }
            } else {
                Text("${sync.deviceName} · ${sync.server}")
                sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { scope.launch { try { session.syncNow(); msg = "Sincronizado" } catch (e: Exception) { msg = null } } }) { Text("Sincronizar") }
                    if (sync.conflicts > 0) OutlinedButton(onClick = onConflicts) { Text("${sync.conflicts} conflicto(s)") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { try { pairCode = session.newPairingCode().encode() } catch (e: Exception) { msg = "No se pudo crear el código: ${e.message}" } } }) { Text("Emparejar otro dispositivo") }
                    OutlinedButton(onClick = { scope.launch { try { devices = session.devices() } catch (e: Exception) { msg = "No se pudo leer la lista: ${e.message}" } } }) { Text("Dispositivos") }
                }
                TextButton(onClick = { scope.launch { session.disableSync(); msg = "Sincronización desactivada" } }) { Text("Desactivar", color = MaterialTheme.colorScheme.error) }
            }
        }

        SectionTitle("AUTOCOMPLETADO")
        FlatCard(Modifier.fillMaxWidth()) {
            Text("Elige CipherKey como servicio de autocompletado para rellenar usuario y contraseña en otras apps y webs.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                val i = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${ctx.packageName}"))
                try { ctx.startActivity(i) } catch (_: Exception) { msg = "Ábrelo en Ajustes > Contraseñas y cuentas" }
            }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Activar autocompletado") }
        }
    }
    pairCode?.let { code ->
        AlertDialog(onDismissRequest = { pairCode = null }, title = { Text("Código de emparejamiento") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Escríbelo en el otro dispositivo (Sincronización > Emparejar con código). Caduca y solo vale una vez. Contiene la clave de sincronización: no lo compartas.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(code, style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { TextButton(onClick = { copySecret(ctx, code, app.prefs.clipboardClearSeconds); msg = "Código copiado" }) { Text("Copiar") } },
            dismissButton = { TextButton(onClick = { pairCode = null }) { Text("Cerrar") } })
    }
    devices?.let { (me, list) ->
        AlertDialog(onDismissRequest = { devices = null }, title = { Text("Dispositivos") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name() + if (d.deviceId() == me) " (este)" else "")
                            Text(if (d.revoked()) "Revocado" else "Activo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (d.deviceId() != me && !d.revoked()) TextButton(onClick = { scope.launch { try { session.revoke(d.deviceId()); devices = session.devices() } catch (e: Exception) { msg = "No se pudo revocar: ${e.message}" } } }) { Text("Revocar", color = MaterialTheme.colorScheme.error) }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { devices = null }) { Text("Cerrar") } })
    }
    if (showChange) ChangePasswordDialog(session, onDismiss = { showChange = false }, onDone = {
        showChange = false
        // la clave cambió: lo guardado para la huella ya no sirve
        app.biometric.disable(); bio = false
        msg = "Contraseña cambiada. Vuelve a activar la huella si la usabas."
    })
}

@Composable
private fun ChangePasswordDialog(session: VaultSession, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var rep by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Cambiar contraseña maestra") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PasswordField(old, { old = it; error = null }, "Contraseña actual")
            PasswordField(new, { new = it; error = null }, "Nueva contraseña (mín. ${MasterPasswordPolicy.MIN_LENGTH})")
            PasswordField(rep, { rep = it; error = null }, "Repite la nueva", imeDone = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val e = MasterPasswordPolicy.validateChange(old, new, rep)
            if (e != null) { error = e; return@TextButton }
            busy = true
            scope.launch {
                try { session.changePassword(old.toCharArray(), new.toCharArray()); onDone() }
                catch (_: VaultSession.WrongPassword) { error = "La contraseña actual no es correcta"; busy = false }
                catch (ex: Exception) { error = ex.message ?: "No se pudo cambiar"; busy = false }
            }
        }) { Text("Cambiar") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSetupScreen(session: VaultSession, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("new") }
    var server by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("Sincronización") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(mode == "new", { mode = "new" }, { Text("Servidor propio") })
                FilterChip(mode == "pair", { mode = "pair" }, { Text("Emparejar con código") })
            }
            OutlinedTextField(name, { name = it }, label = { Text("Nombre de este dispositivo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (mode == "new") {
                OutlinedTextField(server, { server = it }, label = { Text("URL del servidor (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                PasswordField(token, { token = it }, "Token de administrador")
            } else {
                OutlinedTextField(code, { code = it }, label = { Text("Código de emparejamiento") }, modifier = Modifier.fillMaxWidth())
                Text("Atención: emparejar sustituye el contenido de esta bóveda por la del grupo de sincronización.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if (mode == "new") session.enableSync(server, token, name.trim()) else session.pair(code.trim(), name.trim())
                        onBack()
                    } catch (e: Exception) { error = e.message ?: "No se pudo configurar"; busy = false }
                }
            }) { Text(if (busy) "Conectando…" else "Activar") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(session: VaultSession, onBack: () -> Unit) {
    val conflicts by session.conflicts.collectAsState()
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text("Conflictos") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (conflicts.isEmpty()) Text("No hay conflictos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            conflicts.forEach { r ->
                val o: Record? = r.conflict
                FlatCard(Modifier.fillMaxWidth()) {
                    Text(r.title.ifEmpty { "(sin título)" }, style = MaterialTheme.typography.titleMedium)
                    Text("Este dispositivo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${r.username} · ${"•".repeat(minOf(r.password.length, 12))}")
                    if (o != null) {
                        Text("Otro dispositivo", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        Text(if (o.deleted) "Eliminada" else "${o.username} · ${"•".repeat(minOf(o.password.length, 12))}")
                    }
                    FlowRowButtons(
                        "Mantener la mía" to { scope.launch { session.resolve(r.uuid, VaultSession.Choice.KEEP_MINE) } },
                        "Tomar la otra" to { scope.launch { session.resolve(r.uuid, VaultSession.Choice.TAKE_OTHER) } },
                        "Conservar ambas" to { scope.launch { session.resolve(r.uuid, VaultSession.Choice.KEEP_BOTH) } },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(vararg items: Pair<String, () -> Unit>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        items.forEach { (l, f) -> OutlinedButton(onClick = f) { Text(l) } }
    }
}


/**
 * Sincronización directa con otros dispositivos de la misma red, SIN servidor: vincular (código de 6 dígitos en ambos), lista de
 * vinculados, desvincular y sincronizar. Disponible siempre que la bóveda esté desbloqueada.
 */
@Composable
private fun ProximityCard(app: CipherKeyApp) {
    val scope = rememberCoroutineScope()
    val info by app.peers.info.collectAsState()
    var addr by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { app.peers.refresh(); kotlinx.coroutines.delay(3000) } }

    fun run(label: String, block: suspend () -> String) {
        busy = true; msg = label
        scope.launch {
            msg = try { block() } catch (e: Exception) { "Error: ${e.message}" }
            busy = false
            app.peers.refresh()
        }
    }

    FlatCard(Modifier.fillMaxWidth()) {
        Text("Sincroniza con tu escritorio u otro móvil en la misma red, sin servidor. En uno pulsa «Permitir vinculación» y en el otro «Vincular»; compara el código de 6 dígitos en las dos pantallas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (info.active) "Escuchando en el puerto ${info.port} (TCP; descubrimiento por UDP 47891)" else "Inactiva", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        info.discoveryError?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (!info.active) TextButton(onClick = { app.peers.start() }) { Text("Reintentar") }
        if (info.pairingOpen) Text("Vinculación permitida: el otro dispositivo ya puede conectarse.", color = MaterialTheme.colorScheme.primary)
        (msg ?: info.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        Text("Dispositivos vinculados", style = MaterialTheme.typography.labelLarge)
        if (info.peers.isEmpty()) Text("Ninguno todavía.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        info.peers.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name.ifEmpty { p.deviceId })
                    Text(if (p.lastSync == 0L) "Sin sincronizar" else "Última vez: ${formatDate(p.lastSync)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { app.peers.unlink(p.deviceId) }) { Text("Desvincular", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (info.nearby.isNotEmpty()) {
            Text("Cercanos en la red", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            info.nearby.forEach { n ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.address + if (n.linked) "  ·  vinculado" else "", Modifier.weight(1f))
                    if (!n.linked) TextButton(enabled = !busy, onClick = { run("Conectando… compara el código en ambos") { val r = app.peers.pairWith(n.address); "Vinculado con ${r.peerName()}: ${r.received()} recibidas, ${r.sent()} enviadas" } }) { Text("Vincular") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(addr, { addr = it }, label = { Text("Dirección (IP:puerto)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRowButtons(
            "Vincular por dirección" to { if (!busy && addr.isNotBlank()) run("Conectando… compara el código en ambos") { val r = app.peers.pairWith(addr); "Vinculado con ${r.peerName()}: ${r.received()} recibidas, ${r.sent()} enviadas" } },
            "Sincronizar con la dirección" to { if (!busy && addr.isNotBlank()) run("Sincronizando…") { val r = app.peers.syncWith(addr); "Sincronizado: ${r.received()} recibidos, ${r.sent()} enviados" } },
            "Sincronizar con cercanos" to { if (!busy) run("Sincronizando…") { val n = app.peers.syncNearby(); if (n > 0) "Sincronizado con $n dispositivo(s)" else "No hay dispositivos vinculados cerca" } },
            "Permitir vinculación (2 min)" to { app.peers.allowPairing() },
        )
    }
}
