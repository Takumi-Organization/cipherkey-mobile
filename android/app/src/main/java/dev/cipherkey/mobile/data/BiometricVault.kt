package dev.cipherkey.mobile.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Desbloqueo biométrico: la clave de la bóveda (derivada con Argon2id) se guarda envuelta con una clave AES del Android
 * Keystore que EXIGE autenticación biométrica en cada uso y se invalida si cambian las huellas. La contraseña maestra
 * nunca se guarda. El Keystore no exporta la clave: sin una autenticación válida el fichero envuelto no sirve de nada.
 *
 * @param requireAuth solo las pruebas instrumentadas lo ponen a false (no se puede automatizar la huella).
 */
class BiometricVault(private val file: File, private val alias: String = "cipherkey_bio_v1", private val requireAuth: Boolean = true) {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isEnabled(): Boolean = file.exists() && ks.containsAlias(alias)

    fun disable() {
        file.delete()
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    /** Cipher de cifrado listo para pasarlo a BiometricPrompt como CryptoObject al activar. */
    fun encryptCipher(): Cipher {
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (requireAuth) {
                    setUserAuthenticationRequired(true)
                    setInvalidatedByBiometricEnrollment(true)
                    if (Build.VERSION.SDK_INT >= 30) {
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    }
                }
            }
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
    }

    /** Guarda la clave de bóveda envuelta con el cipher ya autenticado. */
    fun finishEnable(cipher: Cipher, vaultKey: ByteArray) {
        val ct = cipher.doFinal(vaultKey)
        val o = JsonObject()
        o.addProperty("iv", Base64.getEncoder().encodeToString(cipher.iv))
        o.addProperty("ct", Base64.getEncoder().encodeToString(ct))
        file.writeText(o.toString())
    }

    /** Cipher de descifrado, o null si la clave se invalidó (cambio de huellas) o no hay nada guardado. */
    fun decryptCipher(): Cipher? {
        if (!isEnabled()) return null
        return try {
            val iv = Base64.getDecoder().decode(JsonParser.parseString(file.readText()).asJsonObject.get("iv").asString)
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            null
        }
    }

    fun finishUnlock(cipher: Cipher): ByteArray {
        val ct = Base64.getDecoder().decode(JsonParser.parseString(file.readText()).asJsonObject.get("ct").asString)
        return cipher.doFinal(ct)
    }

    private fun key(): SecretKey = (ks.getKey(alias, null) as SecretKey)
}
