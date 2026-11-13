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
import kotlinx.coroutines.launch

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(session: VaultSession, onSecureChanged: () -> Unit, onSyncSetup: () -> Unit, onConflicts: () -> Unit) {
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

        SectionTitle("SINCRONIZACIÓN")
        FlatCard(Modifier.fillMaxWidth()) {
            if (!sync.configured) {
                Text("Desactivada. Es opcional: tu bóveda funciona sin servidor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onSyncSetup, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Configurar") }
            } else {
                Text("${sync.deviceName} · ${sync.server}")
                sync.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { scope.launch { try { session.syncNow(); msg = "Sincronizado" } catch (e: Exception) { msg = null } } }) { Text("Sincronizar") }
                    if (sync.conflicts > 0) OutlinedButton(onClick = onConflicts) { Text("${sync.conflicts} conflicto(s)") }
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
