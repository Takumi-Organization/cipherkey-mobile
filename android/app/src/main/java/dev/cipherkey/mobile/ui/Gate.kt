package dev.cipherkey.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (imeDone) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Next),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Ocultar" else "Mostrar") } },
    )
}

@Composable
private fun Hero(icon: @Composable () -> Unit, title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).systemBarsPadding(), verticalArrangement = Arrangement.Center) {
        Hero({ Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp)) },
            "CipherKey", "Tus contraseñas, cifradas en este dispositivo.")
        Spacer(Modifier.height(32.dp))
        AnimatedContent(mode, label = "onboarding") { m ->
            when (m) {
                "choose" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { mode = "create" }, Modifier.fillMaxWidth().height(52.dp)) { Text("Crear una bóveda nueva") }
                    OutlinedButton(onClick = { mode = "import" }, Modifier.fillMaxWidth().height(52.dp)) { Text("Importar bóveda de escritorio (.ckey)") }
                }
                "create" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Elige una contraseña maestra larga. Si la olvidas no hay recuperación posible.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PasswordField(pwd, { pwd = it }, "Contraseña maestra (mín. $MIN_PASSWORD caracteres)")
                    StrengthBar(pwd)
                    PasswordField(pwd2, { pwd2 = it }, "Repite la contraseña", imeDone = true)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                        when {
                            pwd.length < MIN_PASSWORD -> error = "Usa al menos $MIN_PASSWORD caracteres"
                            pwd != pwd2 -> error = "Las contraseñas no coinciden"
                            else -> { error = null; busy = true
                                scope.launch {
                                    try { session.create(pwd.toCharArray()); haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                                    catch (e: Exception) { error = e.message; busy = false }
                                } }
                        }
                    }) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Crear bóveda") }
                    TextButton(onClick = { mode = "choose"; error = null }) { Text("Atrás") }
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
            r.previousVaultBackup?.let { Text("La bóveda anterior se conserva como copia: $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Tu archivo original no se ha modificado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onDone, Modifier.fillMaxWidth()) { Text("Continuar") }
        } else {
            if (replacing) Text("Se importará como bóveda de este dispositivo. La actual se aparta como copia recuperable, no se borra.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { app.externalFlow = true; picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(picked?.first ?: "Elegir archivo .ckey")
            }
            if (picked != null) {
                PasswordField(pwd, { pwd = it }, "Contraseña maestra de ese archivo", imeDone = true)
                Button(enabled = !busy && pwd.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            report = session.importVault(picked!!.second, pwd.toCharArray()); pwd = ""
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        } catch (e: VaultSession.WrongPassword) { error = "Contraseña incorrecta (o el archivo está alterado)" }
                        catch (e: Exception) { error = e.message ?: "No se pudo importar" }
                        busy = false
                    }
                }) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Importar") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = onBack) { Text("Atrás") }
        }
    }
}

/** Pantalla de bloqueo: contraseña maestra o huella. Tras varios fallos se impone una espera creciente. */
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
        val c = try { app.biometric.decryptCipher() } catch (e: Exception) { null } ?: run { error = "La huella ya no es válida: usa la contraseña"; return }
        Biometrics.prompt(activity, "Desbloquear CipherKey", "Confirma con tu huella", c, { cipher ->
            scope.launch {
                try { session.unlockWithKey(app.biometric.finishUnlock(cipher)); haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
                catch (e: Exception) { error = "No se pudo desbloquear con huella: usa la contraseña" }
            }
        }, { msg -> if (msg.isNotBlank()) error = null })
    }
    LaunchedEffect(Unit) { if (bioReady && autoBiometric) tryBiometric() }

    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.06f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).systemBarsPadding().imePadding(), verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().scale(pulse.value), contentAlignment = Alignment.Center) {
            Hero({ Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(38.dp)) },
                "Bóveda bloqueada", "Introduce tu contraseña maestra")
        }
        Spacer(Modifier.height(28.dp))
        PasswordField(pwd, { pwd = it; error = null }, "Contraseña maestra", imeDone = true, onDone = { if (!busy && waitSeconds == 0 && pwd.isNotEmpty()) {} })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        if (waitSeconds > 0) Text("Espera $waitSeconds s para volver a intentarlo", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        Button(enabled = !busy && pwd.isNotEmpty() && waitSeconds == 0, modifier = Modifier.fillMaxWidth().height(52.dp), onClick = {
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
        }) { if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Desbloquear") }
        if (bioReady) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { tryBiometric() }, Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Rounded.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Usar huella")
            }
        }
    }
}
