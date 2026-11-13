package dev.cipherkey.mobile.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Ficheros privados de la app. Nunca se sobrescribe sin dejar una copia recuperable (.bak). */
class VaultFiles(private val dir: File) {
    val vault = File(dir, "vault.ckey")
    val spool = File(dir, "bgsync-spool.json")
    val bgMeta = File(dir, "bgsync-device.json")
    val bio = File(dir, "bio-unlock.json")

    fun exists() = vault.exists()

    fun read(): ByteArray = vault.readBytes()

    /** Escritura atómica: temporal + fsync + .bak de la versión anterior + renombrado. */
    fun writeVault(bytes: ByteArray) {
        val tmp = File(dir, "vault.ckey.tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (vault.exists()) Files.copy(vault.toPath(), File(dir, "vault.ckey.bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp.toPath(), vault.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Aparta una bóveda existente (p. ej. al importar otra) sin borrarla. Devuelve el fichero de la copia. */
    fun moveAside(): File? {
        if (!vault.exists()) return null
        val aside = File(dir, "vault.ckey.replaced-${System.currentTimeMillis()}.bak")
        Files.move(vault.toPath(), aside.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return aside
    }
}
