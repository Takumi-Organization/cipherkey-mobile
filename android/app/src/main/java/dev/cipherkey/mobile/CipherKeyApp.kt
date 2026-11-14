package dev.cipherkey.mobile

import android.app.Application
import dev.cipherkey.mobile.data.BiometricVault
import dev.cipherkey.mobile.data.Prefs
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.data.PeerManager
import kotlinx.coroutines.flow.distinctUntilChanged
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
        biometric = BiometricVault(session.files.bio)
        peers = PeerManager(this, session)
        // La proximidad solo está activa con la bóveda desbloqueada y la sincronización configurada
        appScope.launch {
            kotlinx.coroutines.flow.combine(session.state, session.sync) { st, sy -> st == VaultSession.State.Unlocked && sy.configured }
                .distinctUntilChanged().collect { on -> if (on) peers.start() else peers.stop() }
        }
    }
}
