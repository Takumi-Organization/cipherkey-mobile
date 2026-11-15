package dev.cipherkey.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cipherkey.mobile.CipherKeyApp
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.launch

private sealed interface Screen {
    data class Tabs(val tab: Int) : Screen
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
    fun setTab(t: Int) { stack = listOf(Screen.Tabs(t)) }
    BackHandler(enabled = stack.size > 1) { pop() }

    when (val s = current) {
        is Screen.Tabs -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(s.tab == 0, { setTab(0) }, { Icon(Icons.Rounded.Home, null) }, label = { Text("Panel") })
                    NavigationBarItem(s.tab == 1, { setTab(1) }, { Icon(Icons.Rounded.Key, null) }, label = { Text("Bóveda") })
                    NavigationBarItem(s.tab == 2, { setTab(2) }, { Icon(Icons.Rounded.Settings, null) }, label = { Text("Ajustes") })
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).consumeWindowInsets(pad).systemBarsPadding()) {
                when (s.tab) {
                    0 -> DashboardTab(session, onOpen = { push(Screen.Detail(it)) },
                        onSync = { if (session.sync.value.configured) scope.launch { try { session.syncNow() } catch (_: Exception) {} } else push(Screen.SyncSetup) },
                        onConflicts = { push(Screen.Conflicts) })
                    1 -> VaultTab(session, onOpen = { push(Screen.Detail(it)) }, onAdd = { push(Screen.Editor(null)) }, onGenerator = { showGen = true })
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
        Screen.Import -> Column(Modifier.systemBarsPadding().padding(24.dp)) {
            Text("Importar bóveda", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            ImportPanel(session, onBack = { pop() }, replacing = true, onDone = { pop() })
        }
        Screen.SyncSetup -> Box(Modifier.systemBarsPadding().imePadding()) { SyncSetupScreen(session, onBack = { pop() }) }
    }
    if (showGen) GeneratorSheet(onDismiss = { showGen = false }, onUse = null)
}


/** Resumen de la primera fusión con otro dispositivo: hay que aceptarlo (antes se hace una copia de seguridad local). */
@Composable
private fun MergeDialog() {
    val app = LocalContext.current.applicationContext as CipherKeyApp
    val req by app.peers.merge.collectAsState()
    val r = req ?: return
    val x = r.summary
    AlertDialog(
        onDismissRequest = { r.answer.complete(false) },
        title = { Text("Primera sincronización") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text("Con «${x.peerName()}»:")
                Text("• ${x.mineOnly()} entradas nuevas de este dispositivo (se enviarán)")
                Text("• ${x.theirsOnly()} entradas nuevas del otro dispositivo (se añadirán aquí)")
                Text("• ${x.differing()} con el mismo identificador y contenido distinto (se conservan las dos versiones como conflicto)")
                Text("Se hará una copia de seguridad de tu bóveda antes de fusionar. No se borra ni se sustituye ninguna entrada.")
            }
        },
        confirmButton = { TextButton(onClick = { r.answer.complete(true) }) { Text("Fusionar") } },
        dismissButton = { TextButton(onClick = { r.answer.complete(false) }) { Text("Cancelar") } },
    )
}

/** Código de seguridad de la vinculación por proximidad: debe coincidir con el del otro dispositivo. */
@Composable
private fun SasDialog() {
    val app = LocalContext.current.applicationContext as CipherKeyApp
    val req by app.peers.sas.collectAsState()
    val r = req ?: return
    AlertDialog(
        onDismissRequest = { r.answer.complete(false) },
        title = { Text("Código de seguridad") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Text(if (r.incoming) "«${r.peer}» quiere vincularse con este dispositivo." else "Vinculando con «${r.peer}».")
                Text(r.sas.substring(0, 3) + " " + r.sas.substring(3), style = MaterialTheme.typography.displaySmall)
                Text("Comprueba que el otro dispositivo muestra este mismo código antes de aceptar.")
            }
        },
        confirmButton = { TextButton(onClick = { r.answer.complete(true) }) { Text("Coincide") } },
        dismissButton = { TextButton(onClick = { r.answer.complete(false) }) { Text("No coincide") } },
    )
}
