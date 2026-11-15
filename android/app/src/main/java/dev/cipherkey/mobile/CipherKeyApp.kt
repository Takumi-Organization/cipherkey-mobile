package dev.cipherkey.mobile

import android.app.Application
import dev.cipherkey.mobile.data.BiometricVault
import dev.cipherkey.mobile.data.Prefs
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.data.PeerManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CipherKeyApp : Application() {
    /** true mientras la app abre un selector de archivos del sistema: no debe bloquearse al salir a primer plano ajeno. */
    @Volatile var externalFlow: Boolean = false

    lateinit var session: VaultSession
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var biometric: BiometricVault
        private set

    lateinit var peers: PeerManager
        private set
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        session = VaultSession(this)
        prefs = Prefs(this)
        dev.cipherkey.mobile.ui.ThemeState.load(prefs)
        biometric = BiometricVault(session.files.bio)
        peers = PeerManager(this, session)
        // La proximidad solo está activa con la bóveda desbloqueada; NO necesita servidor de sincronización
        appScope.launch {
            session.state.map { it == VaultSession.State.Unlocked }
                .distinctUntilChanged().collect { on -> if (on) peers.start() else peers.stop() }
        }
    }
}
