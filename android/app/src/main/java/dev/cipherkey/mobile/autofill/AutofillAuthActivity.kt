package dev.cipherkey.mobile.autofill

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.CipherKeyApp
import dev.cipherkey.mobile.R
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.ui.CipherKeyTheme
import dev.cipherkey.mobile.ui.UnlockScreen

/**
 * Lo abre el sistema tras la autenticación del [CipherKeyAutofillService]: desbloquea (huella o maestra, reutilizando
 * [UnlockScreen]) y devuelve un Dataset con el registro elegido. Solo se ofrecen registros que encajan con la app/web.
 * Si la bóveda estaba bloqueada al llegar, se vuelve a bloquear al terminar.
 */
class AutofillAuthActivity : FragmentActivity() {
    private lateinit var session: VaultSession
    private var wasLocked = true
    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        session = (application as CipherKeyApp).session
        if (savedInstanceState == null) wasLocked = !session.isUnlocked()
        else wasLocked = savedInstanceState.getBoolean(STATE_WAS_LOCKED, true)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val userId = IntentCompat.getParcelableExtra(intent, EXTRA_USER_ID, AutofillId::class.java)
        val passId = IntentCompat.getParcelableExtra(intent, EXTRA_PASS_ID, AutofillId::class.java)
        if (pkg == null || passId == null) { cancel(); return }
        val target = FillTarget(pkg, intent.getStringExtra(EXTRA_DOMAIN), intent.getStringExtra(EXTRA_SCHEME))

        setContent {
            CipherKeyTheme {
                Surface(Modifier.fillMaxSize()) {
                    val state by session.state.collectAsState()
                    when (state) {
                        VaultSession.State.NoVault -> LaunchedEffect(Unit) { cancel() }
                        VaultSession.State.Locked -> UnlockScreen(session, autoBiometric = true)
                        VaultSession.State.Unlocked -> {
                            val records by session.records.collectAsState()
                            val matches = remember(records) { AutofillMatcher.select(target, records) }
                            Picker(matches, onPick = { deliver(it, userId, passId) }, onCancel = { cancel() })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Picker(matches: List<Record>, onPick: (Record) -> Unit, onCancel: () -> Unit) {
        if (matches.size == 1) LaunchedEffect(matches[0].uuid) { onPick(matches[0]) }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (matches.isEmpty()) "Sin coincidencias" else "Elige una cuenta", style = MaterialTheme.typography.headlineSmall)
            if (matches.isEmpty()) Text("No hay registros guardados para esta app o web.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matches, key = { it.uuid }) { r ->
                    Card(Modifier.fillMaxWidth().clickable { onPick(r) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(r.title.ifEmpty { r.url }, style = MaterialTheme.typography.titleMedium)
                            if (r.username.isNotEmpty()) Text(r.username, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text("Cancelar") }
        }
    }

    private fun deliver(r: Record, userId: AutofillId?, passId: AutofillId) {
        if (delivered) return
        delivered = true
        val pres = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.af_title, r.title.ifEmpty { r.url })
            setTextViewText(R.id.af_subtitle, r.username)
        }
        val ds = Dataset.Builder(pres)
        if (userId != null && r.username.isNotEmpty()) ds.setValue(userId, AutofillValue.forText(r.username))
        ds.setValue(passId, AutofillValue.forText(r.password))
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds.build()))
        finish()
    }

    private fun cancel() {
        delivered = true
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WAS_LOCKED, wasLocked)
    }

    /** Si el usuario se va sin elegir, se cierra: no se deja una bóveda desbloqueada por culpa del autocompletado. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && wasLocked) session.lock()
    }

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_SCHEME = "scheme"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_PASS_ID = "passId"
        private const val STATE_WAS_LOCKED = "wasLocked"
    }
}
