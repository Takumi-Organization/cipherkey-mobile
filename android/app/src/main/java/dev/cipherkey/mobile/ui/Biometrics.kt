package dev.cipherkey.mobile.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

object Biometrics {
    /** El móvil declara hardware de reconocimiento facial (no implica que Android lo clasifique como biometría fuerte). */
    fun faceHardware(a: FragmentActivity): Boolean = a.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FACE)

    fun available(a: FragmentActivity): Boolean =
        BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** BiometricPrompt ligado a un Cipher del Keystore: sin una biometría fuerte válida (huella o rostro, según el móvil) el Keystore no usa la clave. */
    fun prompt(a: FragmentActivity, title: String, subtitle: String, cipher: Cipher, onSuccess: (Cipher) -> Unit, onFail: (String) -> Unit) {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c != null) onSuccess(c) else onFail("Autenticación sin clave")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        }
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Usar contraseña").build()
        BiometricPrompt(a, ContextCompat.getMainExecutor(a), cb).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}
