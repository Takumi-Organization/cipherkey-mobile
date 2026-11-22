package dev.cipherkey.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cipherkey.mobile.CipherKeyApp
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_PASSWORD = 10

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, imeDone: Boolean = false, onDone: () -> Unit = {}) {
    var visible by remember { mutableStateOf(false) }
    KField(
        value = value, onChange = onChange, label = label, modifier = modifier, mono = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (imeDone) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Next),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        trailing = { KIconButton(if (visible) KI.EyeOff else KI.Eye, if (visible) "Ocultar contraseña" else "Mostrar contraseña", { visible = !visible }) },
    )
}

/** Cabecera de las pantallas de entrada: emblema plano, nombre y marcador de sección. */
@Composable
private fun Hero(kicker: String, title: String, subtitle: String, open: Boolean = false) {
    val k = kamiTokens
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        VaultEmblem(88, open)
        Spacer(Modifier.height(24.dp))
        Text(kicker, style = MaterialTheme.typography.labelMedium, color = k.accentText)
        Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = k.inkMuted)
    }
}

/** Primera ejecución: crear una bóveda o importar la del escritorio. */
@Composable
fun OnboardingScreen(session: VaultSession) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var mode by remember { mutableStateOf("choose") }
    var pwd by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().dotGrid(kamiTokens.line1).verticalScroll(rememberScrollState()).padding(24.dp).systemBarsPadding(), verticalArrangement = Arrangement.Center) {
        Hero("BIENVENIDA", "CipherKey", "Tus contraseñas, cifradas en este dispositivo.")
        Spacer(Modifier.height(32.dp))
        AnimatedContent(mode, label = "onboarding") { m ->
            when (m) {
                "choose" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    KButton("Crear una bóveda nueva", { mode = "create" }, Modifier.fillMaxWidth(), icon = KI.Plus)
                    KButton("Importar bóveda de escritorio (.ckey)", { mode = "import" }, Modifier.fillMaxWidth(), kind = KKind.Secondary, icon = KI.Import)
                }
                "create" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Elige una contraseña maestra larga. Si la olvidas no hay recuperación posible.", color = kamiTokens.inkMuted)
                    PasswordField(pwd, { pwd = it }, "Contraseña maestra (mín. $MIN_PASSWORD caracteres)")
                    StrengthBar(pwd)
                    PasswordField(pwd2, { pwd2 = it }, "Repite la contraseña", imeDone = true)
                    error?.let { ErrorLine(it) }
                    KButton("Crear bóveda", busy = busy, enabled = !busy, modifier = Modifier.fillMaxWidth(), icon = KI.Lock, onClick = {
                        when {
                            pwd.length < MIN_PASSWORD -> error = "Usa al menos $MIN_PASSWORD caracteres"
                            pwd != pwd2 -> error = "Las contraseñas no coinciden"
                            else -> { error = null; busy = true
                                scope.launch {
                                    try { session.create(pwd.toCharArray()); haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                                    catch (e: Exception) { error = e.message; busy = false }
                                } }
                        }
                    })
                    KButton("Atrás", { mode = "choose"; error = null }, Modifier.fillMaxWidth(), kind = KKind.Secondary)
                }
                else -> ImportPanel(session, onBack = { mode = "choose" }, replacing = false)
            }
        }
    }
}

/** Importación de un .ckey (v2 o v3). Si ya hay bóveda local, se aparta con copia, nunca se borra. */
@Composable
fun ImportPanel(session: VaultSession, onBack: () -> Unit, replacing: Boolean, onDone: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as CipherKeyApp
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var picked by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var pwd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<VaultSession.ImportReport?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        app.externalFlow = false
        if (uri != null) {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (bytes.size > 20_000_000) error = "El archivo es demasiado grande" else picked = (uri.lastPathSegment ?: "bóveda") to bytes
            } catch (e: Exception) { error = "No se pudo leer el archivo: ${e.message}" }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (report != null) {
            val r = report!!
            Text("Importadas ${r.entries} entradas" + if (r.migratedFromV2) " (migradas desde el formato v2)" else "" + ".", style = MaterialTheme.typography.titleMedium)
            r.previousVaultBackup?.let { Text("La bóveda anterior se conserva como copia: $it", color = kamiTokens.inkMuted) }
            Text("Tu archivo original no se ha modificado.", color = kamiTokens.inkMuted)
            KButton("Continuar", onDone, Modifier.fillMaxWidth(), icon = KI.Check)
        } else {
            if (replacing) Text("Se importará como bóveda de este dispositivo. La actual se aparta como copia recuperable, no se borra.", color = kamiTokens.inkMuted)
            KButton(picked?.first ?: "Elegir archivo .ckey", { app.externalFlow = true; picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), kind = KKind.Secondary, icon = KI.Folder)
            if (picked != null) {
                PasswordField(pwd, { pwd = it }, "Contraseña maestra de ese archivo", imeDone = true)
                KButton("Importar", busy = busy, enabled = !busy && pwd.isNotEmpty(), modifier = Modifier.fillMaxWidth(), icon = KI.Import, onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            report = session.importVault(picked!!.second, pwd.toCharArray()); pwd = ""
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        } catch (e: VaultSession.WrongPassword) { error = "Contraseña incorrecta (o el archivo está alterado)" }
                        catch (e: Exception) { error = e.message ?: "No se pudo importar" }
                        busy = false
                    }
                })
            }
            error?.let { ErrorLine(it) }
            KButton("Atrás", onBack, Modifier.fillMaxWidth(), kind = KKind.Secondary)
        }
    }
}

/** Pantalla de bloqueo: contraseña maestra o biometría fuerte (huella o rostro). Tras varios fallos se impone una espera creciente. */
@Composable
fun UnlockScreen(session: VaultSession, autoBiometric: Boolean) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as CipherKeyApp
    val activity = ctx.findActivity()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var pwd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fails by remember { mutableIntStateOf(0) }
    var waitSeconds by remember { mutableIntStateOf(0) }
    val bioReady = remember { app.biometric.isEnabled() && Biometrics.available(activity) }

    LaunchedEffect(waitSeconds) { if (waitSeconds > 0) { delay(1000); waitSeconds-- } }

    fun tryBiometric() {
        val c = try { app.biometric.decryptCipher() } catch (e: Exception) { null } ?: run { error = "La biometría ya no es válida (¿cambiaste tu huella o rostro?): usa la contraseña"; return }
        Biometrics.prompt(activity, "Desbloquear CipherKey", "Confirma con tu huella o rostro", c, { cipher ->
            scope.launch {
                try { session.unlockWithKey(app.biometric.finishUnlock(cipher)); haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                catch (e: Exception) { error = "No se pudo desbloquear con biometría: usa la contraseña" }
            }
        }, { msg -> if (msg.isNotBlank()) error = null })
    }
    LaunchedEffect(Unit) { if (bioReady && autoBiometric) tryBiometric() }

    val reduced = rememberReducedMotion()
    val pulse = if (bioReady && !reduced) rememberInfiniteTransition(label = "pulse").animateFloat(0.55f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a").value else 1f
    val k = kamiTokens
    Column(Modifier.fillMaxSize().dotGrid(k.line1).verticalScroll(rememberScrollState()).padding(24.dp).systemBarsPadding().imePadding(), verticalArrangement = Arrangement.Center) {
        Hero("BÓVEDA BLOQUEADA", "CipherKey", "Introduce tu contraseña maestra para abrirla.")
        Spacer(Modifier.height(28.dp))
        PasswordField(pwd, { pwd = it; error = null }, "Contraseña maestra", imeDone = true, onDone = { })
        error?.let { ErrorLine(it, Modifier.padding(top = 8.dp)) }
        if (waitSeconds > 0) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag("ESPERA $waitSeconds s", k.warn, KI.Clock); Spacer(Modifier.width(8.dp)); Text("Demasiados intentos fallidos", style = MaterialTheme.typography.bodySmall, color = k.inkMuted)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            KButton("Desbloquear", busy = busy, enabled = !busy && pwd.isNotEmpty() && waitSeconds == 0, modifier = Modifier.weight(1f).heightIn(min = 56.dp), icon = KI.Lock, onClick = {
                busy = true
                scope.launch {
                    try {
                        session.unlock(pwd.toCharArray()); pwd = ""
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    } catch (e: VaultSession.WrongPassword) {
                        fails++; error = "Contraseña incorrecta"; haptic.performHapticFeedback(HapticFeedbackType.Reject)
                        if (fails >= 3) waitSeconds = minOf(300, 5 shl (fails - 3))
                    } catch (e: Exception) { error = e.message ?: "No se pudo abrir la bóveda" }
                    busy = false
                }
            })
            if (bioReady) Box(
                Modifier.size(56.dp).graphicsLayer { alpha = pulse }.border(2.dp, k.accent, TkSm)
                    .clickable(role = Role.Button, onClickLabel = "Desbloquear con huella o rostro") { tryBiometric() },
                contentAlignment = Alignment.Center,
            ) { Icon(KI.Fingerprint, "Usar huella o rostro", Modifier.size(30.dp), tint = k.accentText) }
        }
    }
}

@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    val k = kamiTokens
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Tag("ERROR", k.error, KI.Warn); Spacer(Modifier.width(8.dp)); Text(text, color = k.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f, fill = false))
    }
}
