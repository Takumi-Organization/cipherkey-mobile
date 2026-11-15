package dev.cipherkey.mobile

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.cipherkey.mobile.ui.AppRoot
import dev.cipherkey.mobile.ui.CipherKeyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Actividad única. Bloqueo: por inactividad (con la app abierta) y al salir de la app (si el ajuste está activo).
 * FLAG_SECURE según el ajuste (por defecto activo): oculta secretos en capturas y recientes.
 */
class MainActivity : FragmentActivity() {
    private val app get() = application as CipherKeyApp
    private var lastInteraction = SystemClock.elapsedRealtime()
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySecureFlag()
        setContent {
            CipherKeyTheme {
                Surface(Modifier.fillMaxSize()) { AppRoot(app.session, onSecureChanged = { applySecureFlag() }) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(1000)
                    val limit = app.prefs.autoLockSeconds
                    if (limit > 0 && app.session.isUnlocked() && SystemClock.elapsedRealtime() - lastInteraction > limit * 1000L) app.session.lock()
                }
            }
        }
    }

    fun applySecureFlag() {
        // En release `DebugHooks.captureAllowed` es siempre false (ver src/release): FLAG_SECURE solo depende del ajuste.
        if (app.prefs.secureScreen && !DebugHooks.captureAllowed(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteraction = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        val limit = app.prefs.autoLockSeconds
        // sin bloqueo al salir, el tiempo en segundo plano cuenta como inactividad
        if (stoppedAt > 0 && limit > 0 && SystemClock.elapsedRealtime() - stoppedAt > limit * 1000L) app.session.lock()
        lastInteraction = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        if (app.prefs.lockOnLeave && !app.externalFlow && !isChangingConfigurations) app.session.lock()
    }
}
