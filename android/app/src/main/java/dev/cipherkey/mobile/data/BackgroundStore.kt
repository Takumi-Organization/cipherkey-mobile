package dev.cipherkey.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.cipherkey.core.sync.DeviceKey
import dev.cipherkey.core.sync.ServerClient
import dev.cipherkey.core.sync.Spool
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lo mínimo para sincronizar en segundo plano con la bóveda BLOQUEADA: la clave de firma del dispositivo (envuelta en el
 * Keystore, sin exigir biometría porque no descifra datos) y la cola de sobres cifrados ([Spool]). La clave de
 * sincronización y el contenido NO están aquí: sin desbloquear la bóveda nadie puede leer lo descargado.
 */
class BackgroundStore(private val files: VaultFiles, private val alias: String = "cipherkey_bg_v1") {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isConfigured() = files.bgMeta.exists()

    fun save(serverUrl: String, deviceId: String, deviceKey: DeviceKey) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(deviceKey.privateBase64().toByteArray())
        val o = JsonObject()
        o.addProperty("serverUrl", serverUrl); o.addProperty("deviceId", deviceId)
        o.addProperty("iv", Base64.getEncoder().encodeToString(c.iv)); o.addProperty("ct", Base64.getEncoder().encodeToString(ct))
        files.bgMeta.writeText(o.toString())
    }

    fun clear() {
        files.bgMeta.delete()
        files.spool.delete()
    }

    fun client(): ServerClient? {
        if (!isConfigured()) return null
        val o = JsonParser.parseString(files.bgMeta.readText()).asJsonObject
        val iv = Base64.getDecoder().decode(o.get("iv").asString)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val priv = String(c.doFinal(Base64.getDecoder().decode(o.get("ct").asString)))
        return ServerClient(o.get("serverUrl").asString, o.get("deviceId").asString, DeviceKey.fromPrivateBase64(priv))
    }

    fun loadSpool(): Spool? = if (files.spool.exists()) Spool.fromJson(files.spool.readText()) else null

    fun saveSpool(s: Spool) = files.spool.writeText(s.toJson())

    private fun key(): SecretKey {
        if (!ks.containsAlias(alias)) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
        return ks.getKey(alias, null) as SecretKey
    }
}
