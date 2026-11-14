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
 * Sincronización directa con otros dispositivos de la bóveda por la red local (ver docs/adr/0001 del proyecto de escritorio).
 * Solo funciona con la bóveda desbloqueada y la sincronización configurada: escucha, anuncia y se sincroniza mientras la
 * app está abierta. El código de seguridad lo confirma el usuario en [sas].
 */
class PeerManager(private val ctx: Context, private val session: VaultSession) {
    /** Petición pendiente de que el usuario compare el código. Se responde con [CompletableFuture.complete]. */
    class SasRequest(val peer: String, val sas: String, val incoming: Boolean, val answer: CompletableFuture<Boolean>)

    data class PeerRow(val deviceId: String, val name: String, val lastSync: Long)
    data class NearbyRow(val address: String, val idTag: String)
    data class Info(
        val active: Boolean = false, val port: Int = 0, val pairingOpen: Boolean = false,
        val peers: List<PeerRow> = emptyList(), val nearby: List<NearbyRow> = emptyList(), val message: String? = null,
    )

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
        try {
            s.start()
        } catch (e: Exception) {
            lock?.release(); lock = null
            _info.value = Info(message = "No se pudo abrir el puerto de proximidad: ${e.message}")
            return
        }
        service = s
        _info.value = _info.value.copy(active = true, port = s.port())
        refresh()
    }

    @Synchronized fun stop() {
        service?.close()
        service = null
        lock?.release(); lock = null
        _info.value = Info()
        _sas.value?.answer?.complete(false)
        _sas.value = null
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
        val linked = session.readDocument { d -> d.sync.peers.keys.map { id -> id to dev.cipherkey.core.sync.DeviceKey.hex(dev.cipherkey.core.p2p.Beacon.idTag(java.util.Base64.getDecoder().decode(d.syncKey), id)) } }
        var ok = 0
        for (n in s.nearby()) {
            if (linked.none { it.second == n.idTagHex() }) continue
            try { s.syncWith(InetSocketAddress(n.address(), n.port()), false); ok++ } catch (_: Exception) { }
        }
        ok
    }

    fun unlink(deviceId: String) {
        session.withDocument { it.sync.peers.remove(deviceId) }
        refresh()
    }

    fun refresh() {
        val s = service ?: return
        val peers = try {
            session.readDocument { d -> d.sync.peers.map { (id, p) -> PeerRow(id, p.name, p.lastSync) } }
        } catch (_: Exception) { emptyList() }
        val near = s.nearby().map { NearbyRow("${it.address().hostAddress}:${it.port()}", it.idTagHex()) }
        _info.value = _info.value.copy(active = true, port = s.port(), pairingOpen = s.pairingOpen(), peers = peers, nearby = near)
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
