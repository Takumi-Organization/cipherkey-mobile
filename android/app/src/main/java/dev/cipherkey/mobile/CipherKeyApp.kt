package dev.cipherkey.mobile

import android.app.Application
import dev.cipherkey.mobile.data.BiometricVault
import dev.cipherkey.mobile.data.Prefs
import dev.cipherkey.mobile.data.VaultSession

class CipherKeyApp : Application() {
    /** true mientras la app abre un selector de archivos del sistema: no debe bloquearse al salir a primer plano ajeno. */
    @Volatile var externalFlow: Boolean = false

    lateinit var session: VaultSession
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var biometric: BiometricVault
        private set

    override fun onCreate() {
        super.onCreate()
        session = VaultSession(this)
        prefs = Prefs(this)
        biometric = BiometricVault(session.files.bio)
    }
}
