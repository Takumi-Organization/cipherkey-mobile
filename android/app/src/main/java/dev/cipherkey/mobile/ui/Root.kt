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
