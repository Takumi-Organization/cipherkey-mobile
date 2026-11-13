package dev.cipherkey.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.BiometricVault
import dev.cipherkey.mobile.data.VaultFiles
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Envoltura de la clave con el Android Keystore y desbloqueo con esa clave, SIN la autenticación biométrica
 * (requireAuth=false: no se puede automatizar una huella). El BiometricPrompt y la huella real no se prueban aquí.
 */
class BiometricVaultTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "bio-${UUID.randomUUID()}").also { it.mkdirs() }
    private val alias = "cipherkey_test_${UUID.randomUUID()}"
    private val bio = BiometricVault(File(dir, "bio.json"), alias, requireAuth = false)

    @After fun tearDown() { bio.disable(); dir.deleteRecursively() }

    @Test fun envolverYDesbloquearConLaClaveDelKeystore() = runBlocking {
        val s = VaultSession(ctx, VaultFiles(dir))
        s.create("clave-biometria-1".toCharArray())
        s.upsert(Record.create("t", 1L).also { it.title = "Secreto"; it.password = "p" })
        assertFalse(bio.isEnabled())
        val enc = bio.encryptCipher()
        val key = s.vaultKeyCopy()
        bio.finishEnable(enc, key)
        key.fill(0)
        assertTrue(bio.isEnabled())

        s.lock()
        val dec = bio.decryptCipher()!!
        s.unlockWithKey(bio.finishUnlock(dec))
        assertEquals("Secreto", s.records.value.single().title)

        bio.disable()
        assertFalse(bio.isEnabled())
        assertNull(bio.decryptCipher())
    }
}
