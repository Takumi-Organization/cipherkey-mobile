package dev.cipherkey.mobile.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
private fun ThemeSection() {
    val k = kamiTokens
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KChip("Grafito", ThemeState.mode == 1, { ThemeState.updateMode(1) })
        KChip("Washi", ThemeState.mode == 2, { ThemeState.updateMode(2) })
        KChip("Sistema", ThemeState.mode == 0, { ThemeState.updateMode(0) })
    }
    Text("ACENTO", style = MaterialTheme.typography.labelMedium, color = k.inkMuted, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fun swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) = @Composable {
            val fill = kami(k.dark, color).accent
            val on = kami(k.dark, color).onAccent
            Box(
                Modifier.size(48.dp).background(fill, RoundedCornerShape(4.dp)).border(if (selected) 3.dp else 1.dp, if (selected) k.ink else k.line2, RoundedCornerShape(4.dp))
                    .clickable(role = Role.RadioButton, onClickLabel = "Elegir acento $name", onClick = onClick)
                    .semantics { contentDescription = "Acento $name" + if (selected) ", seleccionado" else "" },
                contentAlignment = Alignment.Center,
            ) { if (selected) Icon(KI.Check, null, tint = on) }
        }
        Accents.PRESETS.forEachIndexed { i, a -> swatch(a.color, a.name, ThemeState.accentIndex == i) { ThemeState.updateAccent(i) }() }
        swatch(Accents.custom(ThemeState.hue), "personalizado", ThemeState.accentIndex == -1) { ThemeState.updateHue(ThemeState.hue) }()
    }
    val name = if (ThemeState.accentIndex in Accents.PRESETS.indices) Accents.PRESETS[ThemeState.accentIndex].name else "Personalizado"
    Text("$name · el color se aplica a botones, avatares activos y marcadores", style = MaterialTheme.typography.bodySmall, color = k.inkMuted, modifier = Modifier.padding(top = 6.dp))
    Text("TONO PERSONALIZADO", style = MaterialTheme.typography.labelMedium, color = k.inkMuted, modifier = Modifier.padding(top = 10.dp))
    Slider(value = ThemeState.hue, onValueChange = { ThemeState.updateHue(it) }, valueRange = 0f..360f,
        modifier = Modifier.semantics { contentDescription = "Tono del acento personalizado" },
        colors = SliderDefaults.colors(thumbColor = k.accent, activeTrackColor = k.line3, inactiveTrackColor = k.line2))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(session: VaultSession, onSecureChanged: () -> Unit, onSyncSetup: () -> Unit, onConflicts: () -> Unit, onImport: () -> Unit, onGenerator: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as CipherKeyApp
    val activity = ctx.findActivity()
    val scope = rememberCoroutineScope()
    val k = kamiTokens
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
        ScreenTitle("02 // AJUSTES", "Ajustes")
        msg?.let { Row(Modifier.fillMaxWidth().border(1.dp, k.accent, RoundedCornerShape(4.dp)).padding(12.dp)) { Text("> $it", style = MaterialTheme.typography.bodySmall.merge(Mono), color = k.accentText) } }

        SectionLabel("00 // APARIENCIA", Modifier.padding(top = 16.dp))
        KCard(Modifier.fillMaxWidth()) { ThemeSection() }

        SectionLabel("01 // SEGURIDAD")
        KCard(Modifier.fillMaxWidth()) {
            Text("BLOQUEO POR INACTIVIDAD", style = MaterialTheme.typography.labelMedium, color = k.inkMuted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min", 0 to "Nunca").forEach { (v, l) ->
                    KChip(l, autoLock == v, { autoLock = v; app.prefs.autoLockSeconds = v })
                }
            }
            KSwitchRow("Bloquear al salir de la app", lockOnLeave, { lockOnLeave = it; app.prefs.lockOnLeave = it })
            KSwitchRow("Ocultar contenido en capturas y recientes", secure, { secure = it; app.prefs.secureScreen = it; onSecureChanged() })
            if (Biometrics.available(activity)) KSwitchRow("Desbloqueo con huella", bio, { on ->
                if (!on) { app.biometric.disable(); bio = false; msg = "Huella desactivada" }
                else try {
                    val c = app.biometric.encryptCipher()
                    Biometrics.prompt(activity, "Activar huella", "Confirma para guardar la clave", c, { cipher ->
                        val key = session.vaultKeyCopy()
                        try { app.biometric.finishEnable(cipher, key); bio = true; msg = "Huella activada" } catch (e: Exception) { app.biometric.disable(); msg = "No se pudo activar la huella" }
                        finally { key.fill(0) }
                    }, { err -> app.biometric.disable(); bio = false; if (err.isNotBlank()) msg = err })
                } catch (e: Exception) { app.biometric.disable(); msg = "No se pudo preparar la huella" }
            })
            Spacer(Modifier.height(8.dp))
            KButton("Bloquear ahora", { session.lock() }, Modifier.fillMaxWidth(), icon = KI.Lock)
        }

        SectionLabel("02 // DATOS")
        KCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton("Cambiar contraseña maestra", { showChange = true }, Modifier.fillMaxWidth(), kind = KKind.Secondary, icon = KI.Key)
                KButton("Exportar copia cifrada (.ckey)", { app.externalFlow = true; exporter.launch("cipherkey-copia.ckey") }, Modifier.fillMaxWidth(), kind = KKind.Secondary, icon = KI.Export)
                KButton("Importar bóveda (.ckey)", onImport, Modifier.fillMaxWidth(), kind = KKind.Secondary, icon = KI.Import)
                KButton("Generador de contraseñas", onGenerator, Modifier.fillMaxWidth(), kind = KKind.Secondary, icon = KI.Spark)
            }
        }

        SectionLabel("03 // PROXIMIDAD (RED LOCAL, SIN SERVIDOR)")
        ProximityCard(app)

        SectionLabel("04 // SINCRONIZACIÓN CON SERVIDOR (OPCIONAL)")
        KCard(Modifier.fillMaxWidth()) {
            if (!sync.configured) {
                Row(verticalAlignment = Alignment.CenterVertically) { Tag("DESACTIVADA", k.inkMuted); }
                Text("No hace falta para sincronizar por proximidad: tu bóveda funciona sin servidor.", style = MaterialTheme.typography.bodySmall, color = k.inkMuted, modifier = Modifier.padding(top = 8.dp))
                KButton("Configurar", onSyncSetup, Modifier.fillMaxWidth().padding(top = 12.dp), icon = KI.Sync)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { Tag(if (sync.error == null) "ACTIVA" else "CON ERROR", if (sync.error == null) k.ok else k.error, if (sync.error == null) KI.Check else KI.Warn) }
                Text("${sync.deviceName} · ${sync.server}", style = MaterialTheme.typography.bodySmall.merge(Mono), modifier = Modifier.padding(top = 8.dp))
                sync.error?.let { Text(it, color = k.error, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    KButton("Sincronizar", { scope.launch { try { session.syncNow(); msg = "Sincronizado" } catch (e: Exception) { msg = null } } }, icon = KI.Sync)
                    if (sync.conflicts > 0) KButton("${sync.conflicts} conflicto(s)", onConflicts, kind = KKind.Danger, icon = KI.Warn)
                    KButton("Emparejar otro dispositivo", { scope.launch { try { pairCode = session.newPairingCode().encode() } catch (e: Exception) { msg = "No se pudo crear el código: ${e.message}" } } }, kind = KKind.Secondary, icon = KI.Link)
                    KButton("Dispositivos", { scope.launch { try { devices = session.devices() } catch (e: Exception) { msg = "No se pudo leer la lista: ${e.message}" } } }, kind = KKind.Secondary, icon = KI.Radar)
                    KButton("Desactivar", { scope.launch { session.disableSync(); msg = "Sincronización desactivada" } }, kind = KKind.Danger)
                }
            }
        }

        SectionLabel("05 // AUTOCOMPLETADO")
        KCard(Modifier.fillMaxWidth()) {
            Text("Elige CipherKey como servicio de autocompletado para rellenar usuario y contraseña en otras apps y webs.", style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
            KButton("Activar autocompletado", {
                val i = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${ctx.packageName}"))
                try { ctx.startActivity(i) } catch (_: Exception) { msg = "Ábrelo en Ajustes > Contraseñas y cuentas" }
            }, Modifier.fillMaxWidth().padding(top = 12.dp), kind = KKind.Secondary, icon = KI.Shield)
        }

        SectionLabel("06 // ACERCA DE")
        Text("CipherKey Android · tipografía Komiki Mono (licencia MIT, incluida en la app).", style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
    }
    pairCode?.let { code ->
        AlertDialog(onDismissRequest = { pairCode = null }, title = { Text("Código de emparejamiento") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Escríbelo en el otro dispositivo (Sincronización > Emparejar con código). Caduca y solo vale una vez. Contiene la clave de sincronización: no lo compartas.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.foundation.text.selection.SelectionContainer { Text(code, style = MaterialTheme.typography.bodySmall.merge(Mono)) }
            } },
            confirmButton = { KTextButton(onClick = { copySecret(ctx, code, app.prefs.clipboardClearSeconds); msg = "Código copiado" }) { Text("Copiar") } },
            dismissButton = { KTextButton(onClick = { pairCode = null }) { Text("Cerrar") } })
    }
    devices?.let { (me, list) ->
        AlertDialog(onDismissRequest = { devices = null }, title = { Text("Dispositivos") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.name() + if (d.deviceId() == me) " (este)" else "")
                            Tag(if (d.revoked()) "REVOCADO" else "ACTIVO", if (d.revoked()) k.error else k.ok)
                        }
                        if (d.deviceId() != me && !d.revoked()) KTextButton(onClick = { scope.launch { try { session.revoke(d.deviceId()); devices = session.devices() } catch (e: Exception) { msg = "No se pudo revocar: ${e.message}" } } }) { Text("Revocar", color = k.error) }
                    }
                }
            } },
            confirmButton = { KTextButton(onClick = { devices = null }) { Text("Cerrar") } })
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
            error?.let { ErrorLine(it) }
        } },
        confirmButton = { KTextButton(enabled = !busy, onClick = {
            val e = MasterPasswordPolicy.validateChange(old, new, rep)
            if (e != null) { error = e; return@KTextButton }
            busy = true
            scope.launch {
                try { session.changePassword(old.toCharArray(), new.toCharArray()); onDone() }
                catch (_: VaultSession.WrongPassword) { error = "La contraseña actual no es correcta"; busy = false }
                catch (ex: Exception) { error = ex.message ?: "No se pudo cambiar"; busy = false }
            }
        }) { Text("Cambiar") } },
        dismissButton = { KTextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
fun SyncSetupScreen(session: VaultSession, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val k = kamiTokens
    var mode by remember { mutableStateOf("new") }
    var server by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(android.os.Build.MODEL ?: "Android") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Scaffold(containerColor = k.paper, topBar = { KTopBar("Sincronización", onBack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("04 // SERVIDOR", style = MaterialTheme.typography.labelMedium, color = k.accentText)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KChip("Servidor propio", mode == "new", { mode = "new" })
                KChip("Emparejar con código", mode == "pair", { mode = "pair" })
            }
            KField(name, { name = it }, "Nombre de este dispositivo")
            if (mode == "new") {
                KField(server, { server = it }, "URL del servidor (https://…)", mono = true)
                PasswordField(token, { token = it }, "Token de administrador")
            } else {
                KField(code, { code = it }, "Código de emparejamiento", singleLine = false, mono = true)
                Row(verticalAlignment = Alignment.Top) { Tag("ATENCIÓN", k.warn, KI.Warn); Spacer(Modifier.width(8.dp)); Text("Emparejar sustituye el contenido de esta bóveda por la del grupo de sincronización.", style = MaterialTheme.typography.bodySmall, color = k.inkDim) }
            }
            error?.let { ErrorLine(it) }
            KButton(if (busy) "Conectando…" else "Activar", {
                busy = true; error = null
                scope.launch {
                    try {
                        if (mode == "new") session.enableSync(server, token, name.trim()) else session.pair(code.trim(), name.trim())
                        onBack()
                    } catch (e: Exception) { error = e.message ?: "No se pudo configurar"; busy = false }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy, icon = KI.Sync)
        }
    }
}

@Composable
fun ConflictsScreen(session: VaultSession, onBack: () -> Unit) {
    val conflicts by session.conflicts.collectAsState()
    val scope = rememberCoroutineScope()
    val k = kamiTokens
    Scaffold(containerColor = k.paper, topBar = { KTopBar("Conflictos", onBack) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (conflicts.isEmpty()) Text("No hay conflictos.", color = k.inkMuted)
            conflicts.forEach { r ->
                val o: Record? = r.conflict
                KCard(Modifier.fillMaxWidth(), border = k.error) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SiteAvatar(r.title, r.url, 40); Spacer(Modifier.width(12.dp))
                        Text(r.title.ifEmpty { "(sin título)" }, style = MaterialTheme.typography.titleMedium)
                    }
                    Text("ESTE DISPOSITIVO", style = MaterialTheme.typography.labelMedium, color = k.inkMuted, modifier = Modifier.padding(top = 12.dp))
                    Text("${r.username} · ${"•".repeat(minOf(r.password.length, 12))}", style = MaterialTheme.typography.bodyMedium.merge(Mono))
                    if (o != null) {
                        Text("OTRO DISPOSITIVO", style = MaterialTheme.typography.labelMedium, color = k.inkMuted, modifier = Modifier.padding(top = 8.dp))
                        Text(if (o.deleted) "Eliminada" else "${o.username} · ${"•".repeat(minOf(o.password.length, 12))}", style = MaterialTheme.typography.bodyMedium.merge(Mono))
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        items.forEach { (l, f) -> KButton(l, f, Modifier.fillMaxWidth(), kind = KKind.Secondary) }
    }
}

/**
 * Sincronización directa con otros dispositivos de la misma red, SIN servidor: vincular (código de 6 dígitos en ambos), lista de
 * vinculados, desvincular y sincronizar. Disponible siempre que la bóveda esté desbloqueada.
 */
@Composable
private fun ProximityCard(app: CipherKeyApp) {
    val scope = rememberCoroutineScope()
    val k = kamiTokens
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

    KCard(Modifier.fillMaxWidth(), border = if (info.pairingOpen) k.accent else k.line2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).border(1.dp, if (info.active) k.accent else k.line2, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                Icon(KI.Radar, null, tint = if (info.active) k.accentText else k.inkFaint)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(if (info.active) "ESCUCHANDO" else "INACTIVA", if (info.active) k.ok else k.inkMuted, if (info.active) KI.Check else null)
                    if (info.pairingOpen) Tag("VINCULACIÓN ABIERTA", k.accentText, KI.Link)
                }
                Text(if (info.active) "TCP ${info.port} · descubrimiento UDP 47891" else "Reintenta para activar la red local", style = MaterialTheme.typography.bodySmall.merge(Mono), color = k.inkMuted, modifier = Modifier.padding(top = 4.dp))
            }
            if (!info.active) KButton("Reintentar", { app.peers.start() }, kind = KKind.Secondary)
        }
        info.discoveryError?.let { Row(Modifier.padding(top = 8.dp)) { Tag("AVISO", k.error, KI.Warn); Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = k.error, modifier = Modifier.weight(1f)) } }
        Text("Sincroniza con tu escritorio u otro móvil en la misma red, sin servidor. En uno pulsa «Permitir vinculación» y en el otro «Vincular»; compara el código de 6 dígitos en las dos pantallas.", style = MaterialTheme.typography.bodySmall, color = k.inkMuted, modifier = Modifier.padding(top = 10.dp))
        if (info.pairingOpen) Text("El otro dispositivo ya puede conectarse.", style = MaterialTheme.typography.bodySmall, color = k.accentText, modifier = Modifier.padding(top = 6.dp))
        (msg ?: info.message)?.let { Text("> $it", style = MaterialTheme.typography.bodySmall.merge(Mono), modifier = Modifier.padding(top = 8.dp)) }

        Text("VINCULADOS", style = MaterialTheme.typography.labelMedium, color = k.inkMuted, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        HorizontalDivider(color = k.line1)
        if (info.peers.isEmpty()) Text("Ninguno todavía.", color = k.inkMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
        info.peers.forEach { p ->
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(p.name.ifEmpty { p.deviceId }, style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Tag("VINCULADO", k.ok, KI.Link)
                        Text(if (p.lastSync == 0L) "Sin sincronizar" else formatDate(p.lastSync), style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
                    }
                }
                KTextButton(onClick = { app.peers.unlink(p.deviceId) }) { Text("Desvincular", color = k.error) }
            }
            HorizontalDivider(color = k.line1)
        }
        if (info.nearby.isNotEmpty()) {
            Text("CERCANOS EN LA RED", style = MaterialTheme.typography.labelMedium, color = k.inkMuted, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            HorizontalDivider(color = k.line1)
            info.nearby.forEach { n ->
                Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(n.address, style = MaterialTheme.typography.bodyMedium.merge(Mono))
                        Tag(if (n.linked) "VINCULADO" else "CERCANO", if (n.linked) k.ok else k.secondary, if (n.linked) KI.Link else KI.Radar)
                    }
                    if (!n.linked) KButton("Vincular", { run("Conectando… compara el código en ambos") { val r = app.peers.pairWith(n.address); "Vinculado con ${r.peerName()}: ${r.received()} recibidas, ${r.sent()} enviadas" } }, enabled = !busy, kind = KKind.Secondary)
                }
                HorizontalDivider(color = k.line1)
            }
        }
        Spacer(Modifier.height(12.dp))
        KField(addr, { addr = it }, "Dirección (IP:puerto)", mono = true)
        FlowRowButtons(
            "Vincular por dirección" to { if (!busy && addr.isNotBlank()) run("Conectando… compara el código en ambos") { val r = app.peers.pairWith(addr); "Vinculado con ${r.peerName()}: ${r.received()} recibidas, ${r.sent()} enviadas" } },
            "Sincronizar con la dirección" to { if (!busy && addr.isNotBlank()) run("Sincronizando…") { val r = app.peers.syncWith(addr); "Sincronizado: ${r.received()} recibidos, ${r.sent()} enviados" } },
            "Sincronizar con cercanos" to { if (!busy) run("Sincronizando…") { val n = app.peers.syncNearby(); if (n > 0) "Sincronizado con $n dispositivo(s)" else "No hay dispositivos vinculados cerca" } },
            "Permitir vinculación (2 min)" to { app.peers.allowPairing() },
        )
    }
}
