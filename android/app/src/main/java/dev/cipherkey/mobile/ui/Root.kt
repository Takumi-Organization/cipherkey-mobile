package dev.cipherkey.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cipherkey.mobile.CipherKeyApp
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.launch

private sealed interface Screen {
    data class Tabs(val tab: Int, val filter: String = "all") : Screen
    data class Detail(val uuid: String) : Screen
    data class Editor(val uuid: String?) : Screen
    data object Conflicts : Screen
    data object SyncSetup : Screen
    data object Import : Screen
}

/** Raíz: sin bóveda → alta/importación; bloqueada → desbloqueo; abierta → pestañas y pantallas. Al bloquear se pierde la navegación. */
@Composable
fun AppRoot(session: VaultSession, onSecureChanged: () -> Unit) {
    val state by session.state.collectAsState()
    SasDialog()
    MergeDialog()
    when (state) {
        VaultSession.State.NoVault -> OnboardingScreen(session)
        VaultSession.State.Locked -> UnlockScreen(session, autoBiometric = true)
        VaultSession.State.Unlocked -> Unlocked(session, onSecureChanged)
    }
}

@Composable
private fun Unlocked(session: VaultSession, onSecureChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Tabs(1))) }
    var showGen by remember { mutableStateOf(false) }
    val current = stack.last()
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun setTab(t: Int, filter: String = "all") { stack = listOf(Screen.Tabs(t, filter)) }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val s = current) {
        is Screen.Tabs -> Scaffold(
            containerColor = kamiTokens.paper,
            bottomBar = { KNavBar(s.tab) { setTab(it) } },
        ) { pad ->
            Box(Modifier.padding(pad).consumeWindowInsets(pad).systemBarsPadding()) {
                when (s.tab) {
                    0 -> DashboardTab(session, onOpen = { push(Screen.Detail(it)) },
                        onSync = { if (session.sync.value.configured) scope.launch { try { session.syncNow() } catch (_: Exception) {} } else push(Screen.SyncSetup) },
                        onConflicts = { push(Screen.Conflicts) }, onFilter = { setTab(1, it) })
                    1 -> VaultTab(session, onOpen = { push(Screen.Detail(it)) }, onAdd = { push(Screen.Editor(null)) }, onGenerator = { showGen = true }, initialFilter = s.filter)
                    else -> SettingsTab(session, onSecureChanged, onSyncSetup = { push(Screen.SyncSetup) }, onConflicts = { push(Screen.Conflicts) },
                        onImport = { push(Screen.Import) }, onGenerator = { showGen = true })
                }
            }
        }
        is Screen.Detail -> Box(Modifier.systemBarsPadding()) {
            DetailScreen(session, s.uuid, onBack = { pop() }, onEdit = { push(Screen.Editor(s.uuid)) }, onConflict = { push(Screen.Conflicts) })
        }
        is Screen.Editor -> Box(Modifier.systemBarsPadding()) {
            EditorScreen(session, s.uuid, onClose = { pop() }, onDeleted = { setTab(1) })
        }
        Screen.Conflicts -> Box(Modifier.systemBarsPadding()) { ConflictsScreen(session, onBack = { pop() }) }
        Screen.Import -> Column(Modifier.systemBarsPadding().padding(24.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("02 // DATOS", style = MaterialTheme.typography.labelMedium, color = kamiTokens.accentText)
            Text("Importar bóveda", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            ImportPanel(session, onBack = { pop() }, replacing = true, onDone = { pop() })
        }
        Screen.SyncSetup -> Box(Modifier.systemBarsPadding().imePadding()) { SyncSetupScreen(session, onBack = { pop() }) }
    }
    if (showGen) GeneratorSheet(onDismiss = { showGen = false }, onUse = null)
}


/** Barra inferior plana: línea superior, marca de acento sobre la pestaña activa y zonas de 64 dp. */
@Composable
private fun KNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val k = kamiTokens
    Column(Modifier.background(k.paper).navigationBarsPadding()) {
        HorizontalDivider(color = k.line2)
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            listOf(Triple("Panel", KI.Shield, 0), Triple("Bóveda", KI.Key, 1), Triple("Ajustes", KI.Settings, 2)).forEach { (label, icon, i) ->
                val on = selected == i
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = on; contentDescription = label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) k.accent else androidx.compose.ui.graphics.Color.Transparent))
                    Spacer(Modifier.height(9.dp))
                    Icon(icon, null, Modifier.size(24.dp), tint = if (on) k.accentText else k.inkMuted)
                    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (on) k.accentText else k.inkMuted, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun StatBlock(value: Int, label: String, tone: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    val k = kamiTokens
    Column(modifier.border(1.dp, if (value > 0) tone else k.line2, RoundedCornerShape(4.dp)).padding(12.dp)) {
        Text("$value", style = MaterialTheme.typography.headlineMedium, color = if (value > 0) tone else k.inkMuted)
        Text(label, style = MaterialTheme.typography.labelSmall, color = k.inkMuted)
    }
}

/** Resumen de la primera fusión con otro dispositivo: hay que aceptarlo (antes se hace una copia de seguridad local). */
@Composable
private fun MergeDialog() {
    val app = LocalContext.current.applicationContext as CipherKeyApp
    val req by app.peers.merge.collectAsState()
    val r = req ?: return
    val x = r.summary
    val k = kamiTokens
    AlertDialog(
        onDismissRequest = { r.answer.complete(false) },
        title = { Column { Text("PRIMERA SINCRONIZACIÓN", style = MaterialTheme.typography.labelMedium, color = k.accentText); Text("Con «${x.peerName()}»") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBlock(x.mineOnly(), "SE ENVIARÁN", k.accentText, Modifier.weight(1f))
                    StatBlock(x.theirsOnly(), "SE AÑADIRÁN", k.ok, Modifier.weight(1f))
                    StatBlock(x.differing(), "CONFLICTOS", k.warn, Modifier.weight(1f))
                }
                Text("Nuevas de este dispositivo, nuevas del otro, y entradas con el mismo identificador y contenido distinto (se conservan las dos versiones como conflicto).", style = MaterialTheme.typography.bodySmall, color = k.inkDim)
                Text("Se hará una copia de seguridad de tu bóveda antes de fusionar. No se borra ni se sustituye ninguna entrada.", style = MaterialTheme.typography.bodySmall, color = k.inkDim)
            }
        },
        confirmButton = { KTextButton(onClick = { r.answer.complete(true) }) { Text("Fusionar") } },
        dismissButton = { KTextButton(onClick = { r.answer.complete(false) }) { Text("Cancelar") } },
    )
}

/** Código de seguridad de la vinculación por proximidad: debe coincidir con el del otro dispositivo. */
@Composable
private fun SasDialog() {
    val app = LocalContext.current.applicationContext as CipherKeyApp
    val req by app.peers.sas.collectAsState()
    val r = req ?: return
    val k = kamiTokens
    AlertDialog(
        onDismissRequest = { r.answer.complete(false) },
        title = { Column { Text("CÓDIGO DE SEGURIDAD", style = MaterialTheme.typography.labelMedium, color = k.accentText); Text(if (r.incoming) "«${r.peer}» quiere vincularse" else "Vinculando con «${r.peer}»") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Código de seguridad: " + r.sas.toList().joinToString(" ") }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    r.sas.forEachIndexed { i, c ->
                        if (i == 3) Spacer(Modifier.width(12.dp))
                        Box(Modifier.padding(horizontal = 2.dp).size(width = 38.dp, height = 56.dp).border(1.5.dp, k.accent, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Text("$c", fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = k.accentText)
                        }
                    }
                }
                Text("Comprueba que el otro dispositivo muestra exactamente este código antes de aceptar. Si no coincide, alguien podría estar interceptando la conexión.", style = MaterialTheme.typography.bodySmall, color = k.inkDim)
            }
        },
        confirmButton = { KTextButton(onClick = { r.answer.complete(true) }) { Text("Coincide") } },
        dismissButton = { KTextButton(onClick = { r.answer.complete(false) }) { Text("No coincide", color = k.error) } },
    )
}
