package dev.cipherkey.mobile.data

import android.content.Context
import android.net.wifi.WifiManager
import dev.cipherkey.core.p2p.Locked
import dev.cipherkey.core.p2p.PeerService
import dev.cipherkey.core.p2p.PeerSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Sincronización directa con otros dispositivos por la red local, SIN servidor (docs/adr/0002-proximidad-sin-servidor.md del
 * proyecto de escritorio; el canal, en el 0001). Solo funciona con la bóveda desbloqueada: escucha, anuncia y se sincroniza
 * mientras la app está abierta, tenga o no servidor configurado. El código de seguridad lo confirma el usuario en [sas] y el
 * resumen de la primera fusión en [merge] (antes se hace una copia de seguridad local de la bóveda).
 */
class PeerManager(private val ctx: Context, private val session: VaultSession) {
    /** Petición pendiente de que el usuario compare el código. Se responde con [CompletableFuture.complete]. */
    class SasRequest(val peer: String, val sas: String, val incoming: Boolean, val answer: CompletableFuture<Boolean>)

    /** Resumen de la primera fusión con un dispositivo, pendiente de que el usuario la acepte (true) o la cancele (false). */
    class MergeRequest(val summary: PeerSync.Summary, val answer: CompletableFuture<Boolean>)

    data class PeerRow(val deviceId: String, val name: String, val lastSync: Long)
    data class NearbyRow(val address: String, val idTag: String, val linked: Boolean)
    data class Info(
        val active: Boolean = false, val port: Int = 0, val pairingOpen: Boolean = false,
        val peers: List<PeerRow> = emptyList(), val nearby: List<NearbyRow> = emptyList(), val message: String? = null,
        val discoveryError: String? = null,
    )

    private val _merge = MutableStateFlow<MergeRequest?>(null)
    val merge: StateFlow<MergeRequest?> = _merge.asStateFlow()

    private val _sas = MutableStateFlow<SasRequest?>(null)
    val sas: StateFlow<SasRequest?> = _sas.asStateFlow()
    private val _info = MutableStateFlow(Info())
    val info: StateFlow<Info> = _info.asStateFlow()

    @Volatile private var service: PeerService? = null
    private var lock: WifiManager.MulticastLock? = null

    private val locked = object : Locked {
        override fun <T> run(work: Locked.Work<T>): T = session.withDocument { work.apply(it) }
    }

    @Synchronized fun start(port: Int = PeerService.DEFAULT_PORT) {
        if (service != null) return
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wifi.createMulticastLock("cipherkey-p2p").apply { setReferenceCounted(false); acquire() }
        val s = PeerService(locked, port, true, { peer, sas, incoming -> ask(peer, sas, incoming) }, object : PeerService.Listener {
            override fun peersChanged() { refresh() }
            override fun synced(r: PeerSync.Result, incoming: Boolean) {
                _info.value = _info.value.copy(message = "Sincronizado con ${r.peerName()}: ${r.received()} recibidos, ${r.sent()} enviados")
                session.refreshFromDocument()
                session.syncSoon()
                refresh()
            }
            override fun failed(who: String, message: String, incoming: Boolean) {
                _info.value = _info.value.copy(message = message)
            }
        }, { System.currentTimeMillis() })
        s.setDefaultDeviceName(android.os.Build.MODEL ?: "Android")
        s.setMergeGate(object : PeerSync.MergeGate {
            override fun approve(summary: PeerSync.Summary): Boolean = askMerge(summary)
            override fun backup() { session.backupVault() }
        })
        try {
            s.start()
            s.ensureIdentity() // identidad de proximidad dentro de la bóveda, sin servidor
        } catch (e: Exception) {
            s.close()
            lock?.release(); lock = null
            _info.value = Info(message = "No se pudo iniciar la proximidad: ${e.message}")
            return
        }
        service = s
        _info.value = _info.value.copy(active = true, port = s.port(), discoveryError = s.discoveryError())
        refresh()
    }

    @Synchronized fun stop() {
        service?.close()
        service = null
        lock?.release(); lock = null
        _info.value = Info()
        _sas.value?.answer?.complete(false)
        _sas.value = null
        _merge.value?.answer?.complete(false)
        _merge.value = null
    }

    fun allowPairing() {
        val s = service
        if (s == null) {
            _info.value = _info.value.copy(message = "La proximidad no está activa: desbloquea la bóveda")
            return
        }
        s.openPairingWindow(120_000)
        _info.value = _info.value.copy(message = "Vinculación permitida durante 2 minutos")
        refresh()
    }

    suspend fun pairWith(address: String): PeerSync.Result = withContext(Dispatchers.IO) {
        requireService().syncWith(parse(address), true)
    }

    suspend fun syncWith(address: String): PeerSync.Result = withContext(Dispatchers.IO) {
        requireService().syncWith(parse(address), false)
    }

    private fun requireService(): PeerService =
        service ?: throw IllegalStateException("La proximidad no está activa: desbloquea la bóveda")

    /** Sincroniza con los dispositivos vinculados que se ven ahora en la red. Devuelve cuántos lo lograron. */
    suspend fun syncNearby(): Int = withContext(Dispatchers.IO) {
        val s = service ?: return@withContext 0
        val linked = linkedTags()
        var ok = 0
        for (n in s.nearby()) {
            if (n.idTagHex() !in linked) continue
            try { s.syncWith(InetSocketAddress(n.address(), n.port()), false); ok++ } catch (_: Exception) { }
        }
        ok
    }

    fun unlink(deviceId: String) {
        session.withDocument { it.link?.peers?.remove(deviceId) }
        refresh()
    }

    /** Etiquetas de baliza de los dispositivos ya vinculados (la etiqueta sale de su clave pública fijada). */
    private fun linkedTags(): Set<String> = try {
        session.readDocument { d ->
            d.link?.peers?.values?.map { dev.cipherkey.core.sync.DeviceKey.hex(dev.cipherkey.core.p2p.Beacon.idTag(it.publicKey)) }?.toSet() ?: emptySet()
        }
    } catch (_: Exception) { emptySet() }

    fun refresh() {
        val s = service ?: return
        val peers = try {
            session.readDocument { d -> d.link?.peers?.map { (id, p) -> PeerRow(id, p.name, p.lastSync) } ?: emptyList() }
        } catch (_: Exception) { emptyList() }
        val linked = linkedTags()
        val near = s.nearby().map { NearbyRow("${it.address().hostAddress}:${it.port()}", it.idTagHex(), it.idTagHex() in linked) }
        _info.value = _info.value.copy(active = true, port = s.port(), pairingOpen = s.pairingOpen(), peers = peers, nearby = near,
            discoveryError = s.discoveryError())
    }

    private fun askMerge(summary: PeerSync.Summary): Boolean {
        val req = MergeRequest(summary, CompletableFuture())
        _merge.value?.answer?.complete(false)
        _merge.value = req
        return try {
            req.answer.get(120, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            if (_merge.value === req) _merge.value = null
        }
    }

    private fun ask(peer: String, sas: String, incoming: Boolean): Boolean {
        val req = SasRequest(peer, sas, incoming, CompletableFuture())
        _sas.value?.answer?.complete(false)
        _sas.value = req
        return try {
            req.answer.get(120, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        } finally {
            if (_sas.value === req) _sas.value = null
        }
    }

    private fun parse(address: String): InetSocketAddress {
        val a = address.trim()
        val i = a.lastIndexOf(':')
        val host = if (i < 0) a else a.substring(0, i)
        val port = if (i < 0) PeerService.DEFAULT_PORT else a.substring(i + 1).toInt()
        require(host.isNotEmpty()) { "Dirección vacía" }
        return InetSocketAddress(host, port)
    }
}
