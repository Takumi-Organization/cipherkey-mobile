package dev.cipherkey.mobile.data

import android.content.Context
import dev.cipherkey.core.crypto.VaultCrypto
import dev.cipherkey.core.format.VaultContainer
import dev.cipherkey.core.merge.Merger
import dev.cipherkey.core.model.Record
import dev.cipherkey.core.model.SyncState
import dev.cipherkey.core.model.V2Migrator
import dev.cipherkey.core.model.VaultDocument
import dev.cipherkey.core.sync.*
import dev.cipherkey.core.sync.Protocol.PullResponse
import dev.cipherkey.core.sync.Protocol.PushOp
import dev.cipherkey.core.sync.Protocol.PushResult
import dev.cipherkey.core.sync.Protocol.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Sesión de la bóveda. Las claves viven solo en memoria mientras está desbloqueada y se ponen a cero al bloquear.
 * La contraseña maestra no se conserva: solo se usa para derivar la clave (Argon2id) y se borra.
 */
class VaultSession(private val appContext: Context, val files: VaultFiles = VaultFiles(appContext.filesDir)) {
    sealed interface State {
        data object NoVault : State
        data object Locked : State
        data object Unlocked : State
    }

    data class SyncInfo(
        val configured: Boolean = false, val running: Boolean = false, val lastSync: Long = 0L,
        val pending: Int = 0, val conflicts: Int = 0, val error: String? = null, val deviceName: String = "", val server: String = "",
    )

    class WrongPassword : Exception("Contraseña maestra incorrecta")
    class BadVault(msg: String) : Exception(msg)

    private val _state = MutableStateFlow<State>(if (files.exists()) State.Locked else State.NoVault)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _records = MutableStateFlow<List<Record>>(emptyList())
    val records: StateFlow<List<Record>> = _records.asStateFlow()
    private val _sync = MutableStateFlow(SyncInfo())
    val sync: StateFlow<SyncInfo> = _sync.asStateFlow()
    private val _conflicts = MutableStateFlow<List<Record>>(emptyList())
    val conflicts: StateFlow<List<Record>> = _conflicts.asStateFlow()

    private val mu = ReentrantLock()
    private val wipeAfter = AtomicBoolean(false)
    private var doc: VaultDocument? = null
    private var key: ByteArray? = null
    private var header: ByteArray? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounce: Job? = null
    private val background = BackgroundStore(files)
    private val prefs = appContext.getSharedPreferences("sync-status", Context.MODE_PRIVATE)
    private val clock = java.util.function.LongSupplier { System.currentTimeMillis() }

    // ------------------------------------------------------------------ ciclo de vida

    fun hasVault() = files.exists()

    suspend fun create(password: CharArray) = withContext(Dispatchers.Default) {
        check(!files.exists()) { "Ya existe una bóveda" }
        val p = VaultCrypto.KdfParams.DEFAULT
        val h = VaultContainer.newHeader(VaultContainer.VERSION_3.toInt(), p, VaultCrypto.randomBytes(VaultCrypto.SALT_LENGTH))
        val k = VaultCrypto.deriveKey(password, VaultContainer.saltOf(h), p)
        password.fill('\u0000')
        locked {
            adopt(VaultDocument(), h, k)
            persist()
        }
    }

    data class ImportReport(val entries: Int, val migratedFromV2: Boolean, val previousVaultBackup: String?)

    /**
     * Importa una bóveda .ckey de escritorio (v2 o v3). Se descifra con su contraseña y se guarda como bóveda local con la
     * MISMA contraseña y sal nueva, sin identidad de sincronización. Si ya había una bóveda local, se aparta (no se borra).
     */
    suspend fun importVault(bytes: ByteArray, password: CharArray): ImportReport = withContext(Dispatchers.Default) {
        val opened = try {
            VaultContainer.open(bytes, password)
        } catch (e: VaultContainer.WrongPasswordException) {
            throw WrongPassword()
        } catch (e: VaultContainer.FormatException) {
            throw BadVault(e.message ?: "Formato no válido")
        } catch (e: GeneralSecurityException) {
            throw BadVault("No se pudo descifrar: ${e.message}")
        }
        val text = String(opened.plaintext(), Charsets.UTF_8).trim()
        val migrated = opened.version() == 2 || text.startsWith("[")
        val imported = try {
            if (migrated) V2Migrator.migrate(opened.plaintext(), ZoneId.systemDefault(), "local")
            else VaultDocument.fromBytes(opened.plaintext()).withoutSync()
        } catch (e: RuntimeException) {
            throw BadVault("Contenido de la bóveda no válido: ${e.message}")
        } finally {
            java.util.Arrays.fill(opened.key(), 0)
        }
        val p = VaultCrypto.KdfParams.DEFAULT
        val h = VaultContainer.newHeader(VaultContainer.VERSION_3.toInt(), p, VaultCrypto.randomBytes(VaultCrypto.SALT_LENGTH))
        val k = VaultCrypto.deriveKey(password, VaultContainer.saltOf(h), p)
        password.fill('\u0000')
        locked {
            if (_state.value == State.Unlocked) wipe()
            val aside = files.moveAside()
            background.clear()
            adopt(imported, h, k)
            try {
                persist()
            } catch (e: Exception) {
                // si falla la escritura, se restaura la bóveda anterior
                aside?.let { java.nio.file.Files.move(it.toPath(), files.vault.toPath()) }
                wipe()
                throw e
            }
            ImportReport(imported.live().size, migrated, aside?.name)
        }
    }

    suspend fun unlock(password: CharArray) = withContext(Dispatchers.Default) {
        val content = files.read()
        val opened = try {
            VaultContainer.open(content, password)
        } catch (e: VaultContainer.WrongPasswordException) {
            throw WrongPassword()
        } catch (e: VaultContainer.FormatException) {
            throw BadVault(e.message ?: "Formato no válido")
        } finally {
            password.fill('\u0000')
        }
        finishUnlock(opened)
    }

    suspend fun unlockWithKey(vaultKey: ByteArray) = withContext(Dispatchers.Default) {
        val opened = try {
            VaultContainer.openWithKey(files.read(), vaultKey)
        } catch (e: VaultContainer.WrongPasswordException) {
            throw WrongPassword()
        } finally {
            vaultKey.fill(0)
        }
        finishUnlock(opened)
    }

    private fun finishUnlock(opened: VaultContainer.Opened) = locked {
        val d = VaultDocument.fromBytes(opened.plaintext())
        // Integra lo que el worker hizo con la bóveda bloqueada (confirmaciones y cambios remotos, incluidos conflictos).
        val spool = background.loadSpool()
        var dirty = false
        if (spool != null && d.sync != null) {
            SyncEngine(d, NoTransport, clock).integrate(spool)
            background.clear()
            dirty = true
        }
        adopt(d, opened.header(), opened.key())
        if (dirty || opened.version() != VaultContainer.VERSION_3.toInt()) persist()
        if (d.sync != null) SyncScheduler.schedule(appContext)
    }

    /** Bloquea de inmediato; si hay una operación en curso, las claves se borran en cuanto termine. */
    fun lock() {
        _state.value = if (files.exists()) State.Locked else State.NoVault
        _records.value = emptyList()
        _conflicts.value = emptyList()
        if (mu.tryLock()) {
            try { wipe() } finally { mu.unlock() }
        } else {
            wipeAfter.set(true)
        }
    }

    private fun wipe() {
        val d = doc
        val k = key
        try {
            if (d?.sync != null && k != null) prepareBackground(d)
        } catch (_: Exception) {
            // sin cola de segundo plano no se pierde nada: lo pendiente sigue en la bóveda
        }
        key?.fill(0)
        key = null
        header = null
        doc = null
        wipeAfter.set(false)
    }

    private fun prepareBackground(d: VaultDocument) {
        val spool = Spool()
        SyncEngine(d, NoTransport, clock).exportTo(spool)
        background.saveSpool(spool)
        background.save(d.sync.serverUrl, d.sync.deviceId, DeviceKey.fromPrivateBase64(d.sync.devicePrivateKey))
    }

    fun vaultKeyCopy(): ByteArray = key?.clone() ?: throw IllegalStateException("Bóveda bloqueada")

    // ------------------------------------------------------------------ edición

    fun find(uuid: String): Record? = _records.value.firstOrNull { it.uuid == uuid }

    suspend fun upsert(edited: Record) = withContext(Dispatchers.Default) {
        locked {
            val d = requireDoc()
            val now = System.currentTimeMillis()
            val existing = d.find(edited.uuid).orElse(null)
            if (existing == null) {
                edited.created = now
                edited.updatedAt = now
                edited.updatedBy = deviceId()
                d.records.add(edited)
            } else {
                val i = d.records.indexOf(existing)
                if (existing.password.isNotEmpty() && existing.password != edited.password) {
                    edited.history = (listOf(Record.HistoryItem(existing.password, now)) + existing.history).take(10).toMutableList()
                } else {
                    edited.history = existing.history
                }
                edited.created = existing.created
                edited.rev = existing.rev
                edited.conflict = existing.conflict
                edited.touch(deviceId(), now)
                d.records[i] = edited
            }
            SyncEngine.markDirty(d, edited)
            persist()
        }
        syncSoon()
    }

    suspend fun delete(uuid: String) = withContext(Dispatchers.Default) {
        locked {
            val d = requireDoc()
            val r = d.find(uuid).orElseThrow { IllegalArgumentException("No existe") }
            r.markDeleted(deviceId(), System.currentTimeMillis())
            SyncEngine.markDirty(d, r)
            persist()
        }
        syncSoon()
    }

    suspend fun setFavorite(uuid: String, fav: Boolean) = withContext(Dispatchers.Default) {
        locked {
            val d = requireDoc()
            val r = d.find(uuid).orElseThrow { IllegalArgumentException("No existe") }
            r.favorite = fav
            r.touch(deviceId(), System.currentTimeMillis())
            SyncEngine.markDirty(d, r)
            persist()
        }
        syncSoon()
    }

    suspend fun changePassword(old: CharArray, new: CharArray) = withContext(Dispatchers.Default) {
        locked {
            val d = requireDoc()
            val h = header!!
            val oldKey = VaultCrypto.deriveKey(old, VaultContainer.saltOf(h), VaultContainer.paramsOf(h))
            old.fill('\u0000')
            val same = MessageDigest.isEqual(oldKey, key)
            oldKey.fill(0)
            if (!same) {
                new.fill('\u0000')
                throw WrongPassword()
            }
            val p = VaultCrypto.KdfParams.DEFAULT
            val nh = VaultContainer.newHeader(VaultContainer.VERSION_3.toInt(), p, VaultCrypto.randomBytes(VaultCrypto.SALT_LENGTH))
            val nk = VaultCrypto.deriveKey(new, VaultContainer.saltOf(nh), p)
            new.fill('\u0000')
            val ok = key; val oh = header
            key = nk; header = nh
            try {
                persist()
            } catch (e: Exception) {
                key = ok; header = oh
                nk.fill(0)
                throw e
            }
            ok?.fill(0)
            d.sync // (la clave de sincronización no cambia: los demás dispositivos no se ven afectados)
        }
    }

    /** Copia cifrada sin la identidad de sincronización de este dispositivo. */
    fun exportEncrypted(): ByteArray = locked {
        val d = requireDoc()
        VaultContainer.seal(header!!, key!!, d.withoutSync().toBytes())
    }

    /** Relee el fichero, lo descifra y comprueba que coincide con lo que hay en memoria. */
    fun verifyIntegrity(): Boolean = locked {
        val d = requireDoc()
        val opened = VaultContainer.openWithKey(files.read(), key!!)
        val onDisk = VaultDocument.fromBytes(opened.plaintext())
        java.util.Arrays.fill(opened.key(), 0)
        onDisk.records.size == d.records.size && onDisk.vaultId == d.vaultId &&
            onDisk.records.zip(d.records).all { (a, b) -> a.toJson() == b.toJson() }
    }

    // ------------------------------------------------------------------ conflictos

    enum class Choice { KEEP_MINE, TAKE_OTHER, KEEP_BOTH }

    suspend fun resolve(uuid: String, choice: Choice) = withContext(Dispatchers.Default) {
        locked {
            val d = requireDoc()
            val r = d.find(uuid).orElseThrow { IllegalArgumentException("No existe") }
            val now = System.currentTimeMillis()
            val i = d.records.indexOf(r)
            when (choice) {
                Choice.KEEP_MINE, Choice.TAKE_OTHER -> {
                    val out = Merger.resolve(r, choice == Choice.TAKE_OTHER, deviceId(), now)
                    d.records[i] = out
                    SyncEngine.markDirty(d, out)
                }
                Choice.KEEP_BOTH -> {
                    val copy = Merger.keepBoth(r, deviceId(), now)
                    d.records.add(copy)
                    SyncEngine.markDirty(d, copy)
                    val mine = Merger.resolve(r, false, deviceId(), now)
                    d.records[i] = mine
                    SyncEngine.markDirty(d, mine)
                }
            }
            persist()
        }
        syncSoon()
    }

    // ------------------------------------------------------------------ sincronización

    suspend fun enableSync(serverUrl: String, adminToken: String, deviceName: String) = withContext(Dispatchers.IO) {
        locked {
            val d = requireDoc()
            val dk = DeviceKey.generate()
            val id = ServerClient.createVault(serverUrl.trim(), adminToken, d.vaultId, dk, deviceName)
            attach(d, serverUrl.trim(), id, deviceName, dk)
            persist()
        }
        SyncScheduler.schedule(appContext)
        syncNow()
    }

    suspend fun pair(code: String, deviceName: String) = withContext(Dispatchers.IO) {
        val pc = PairingCode.decode(code)
        locked {
            val d = requireDoc()
            val dk = DeviceKey.generate()
            val id = ServerClient.enroll(pc.server(), pc.vaultId(), pc.token(), dk, deviceName)
            d.vaultId = pc.vaultId()
            d.syncKey = pc.syncKey()
            attach(d, pc.server(), id, deviceName, dk)
            persist()
        }
        SyncScheduler.schedule(appContext)
        syncNow()
    }

    private fun attach(d: VaultDocument, url: String, deviceId: String, name: String, dk: DeviceKey) {
        val s = SyncState()
        s.serverUrl = url; s.deviceId = deviceId; s.deviceName = name; s.devicePrivateKey = dk.privateBase64()
        d.sync = s
        d.records.forEach { SyncEngine.markDirty(d, it) } // subida inicial
    }

    suspend fun newPairingCode(): PairingCode = withContext(Dispatchers.IO) {
        locked {
            val d = requireDoc()
            PairingCode(d.sync.serverUrl, d.vaultId, d.syncKey, client(d).newEnrollment().token())
        }
    }

    suspend fun devices(): Pair<String, List<ServerClient.DeviceInfo>> = withContext(Dispatchers.IO) {
        locked { val d = requireDoc(); d.sync.deviceId to client(d).devices() }
    }

    suspend fun revoke(deviceId: String) = withContext(Dispatchers.IO) { locked { client(requireDoc()).revoke(deviceId) } }

    suspend fun disableSync() = withContext(Dispatchers.IO) {
        locked { val d = requireDoc(); d.sync = null; persist() }
        background.clear()
        SyncScheduler.cancel(appContext)
    }

    /** Sincroniza ahora. Los errores de red no pierden nada: la cola queda en la bóveda. */
    suspend fun syncNow(): SyncEngine.Summary? = withContext(Dispatchers.IO) {
        if (_state.value != State.Unlocked) return@withContext null
        _sync.value = _sync.value.copy(running = true)
        try {
            val s = locked {
                val d = requireDoc()
                if (d.sync == null) return@locked null
                try {
                    SyncEngine(d, client(d), clock).sync()
                } finally {
                    persist()
                }
            }
            if (s != null) prefs.edit().putLong("last", System.currentTimeMillis()).remove("error").apply()
            s
        } catch (e: Exception) {
            val msg = if (e is java.io.IOException) "Sin conexión con el servidor" else (e.message ?: e.javaClass.simpleName)
            prefs.edit().putString("error", msg).apply()
            SyncScheduler.retryWhenConnected(appContext)
            throw e
        } finally {
            _sync.value = _sync.value.copy(running = false)
            publish()
        }
    }

    /** Agrupa ediciones seguidas en una sola sincronización. Los fallos se ignoran: el estado muestra el error. */
    fun syncSoon() {
        if (_sync.value.configured.not()) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(1500)
            try { syncNow() } catch (_: Exception) { }
        }
    }

    /**
     * Ejecuta {@code work} sobre el documento con el cerrojo de la sesión y guarda después (lo usa la sincronización directa
     * entre dispositivos). Falla si la bóveda está bloqueada. Cada llamada es corta: nunca se mantiene durante la red.
     */
    fun <T> withDocument(work: (VaultDocument) -> T): T = locked {
        val d = requireDoc()
        try { work(d) } finally { persist() }
    }

    /** Guarda lo último y deja una copia de seguridad cifrada de la bóveda (se usa antes de la primera fusión por proximidad). */
    fun backupVault(): java.io.File = locked {
        persist()
        files.copyBackup("proximidad")
    }

    /** Solo lectura: no vuelve a cifrar ni a escribir el fichero (para refrescar listas sin desgastar el almacenamiento). */
    fun <T> readDocument(work: (VaultDocument) -> T): T = locked { work(requireDoc()) }

    /** Reexpone los registros tras un cambio hecho por otro camino (p. ej. sincronización directa). */
    fun refreshFromDocument() { locked { publish() } }

    /** Usada por WorkManager con la bóveda desbloqueada en este proceso. */
    fun isUnlocked() = _state.value == State.Unlocked

    // ------------------------------------------------------------------ interno

    private fun client(d: VaultDocument) = ServerClient(d.sync.serverUrl, d.sync.deviceId, DeviceKey.fromPrivateBase64(d.sync.devicePrivateKey))

    private fun deviceId() = doc?.sync?.deviceId?.ifEmpty { null } ?: "local"

    private fun requireDoc(): VaultDocument = doc ?: throw IllegalStateException("La bóveda está bloqueada")

    private fun adopt(d: VaultDocument, h: ByteArray, k: ByteArray) {
        doc = d; header = h; key = k
        _state.value = State.Unlocked
        publish()
    }

    private fun persist() {
        val d = requireDoc()
        files.writeVault(VaultContainer.seal(header!!, key!!, d.toBytes()))
        publish()
    }

    private fun publish() {
        val d = doc ?: return
        _records.value = d.live().sortedBy { it.title.lowercase() }.map { it.copy() }
        _conflicts.value = d.records.filter { it.conflict != null }.map { it.copy() }
        _sync.value = SyncInfo(
            configured = d.sync != null, running = _sync.value.running && d.sync != null, lastSync = prefs.getLong("last", 0L),
            pending = d.sync?.pending?.size ?: 0, conflicts = _conflicts.value.size, error = prefs.getString("error", null),
            deviceName = d.sync?.deviceName ?: "", server = d.sync?.serverUrl ?: "",
        )
    }

    private inline fun <T> locked(block: () -> T): T {
        mu.lock()
        try {
            return block()
        } finally {
            if (wipeAfter.get()) wipe()
            mu.unlock()
        }
    }

    private object NoTransport : Transport {
        override fun pull(since: Long, limit: Int): PullResponse = throw java.io.IOException("sin transporte")
        override fun push(ops: MutableList<PushOp>): MutableList<PushResult> = throw java.io.IOException("sin transporte")
    }
}
