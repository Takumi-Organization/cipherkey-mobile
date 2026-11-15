package dev.cipherkey.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.PeerManager
import dev.cipherkey.mobile.data.VaultFiles
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Proximidad SIN servidor entre dos bóvedas temporales distintas (claves de prueba obvias) dentro del mismo proceso, con
 * sockets reales en loopback: vincular con código, resumen de la primera fusión, copia de seguridad, unión sin pérdida,
 * rechazo del código y desvincular. No toca la bóveda real de la app (usa directorios en la caché de la prueba).
 */
class ProximityNoServerTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dirs = mutableListOf<File>()

    @After fun tearDown() { dirs.forEach { it.deleteRecursively() } }

    private fun dir() = File(ctx.cacheDir, "prox-${UUID.randomUUID()}").also { it.mkdirs(); dirs += it }

    private suspend fun device(name: String): Triple<VaultSession, PeerManager, File> {
        val d = dir()
        val s = VaultSession(ctx, VaultFiles(d))
        s.create("clave-de-prueba-$name".toCharArray())
        val pm = PeerManager(ctx, s)
        pm.start(0)
        assertTrue("la proximidad debe arrancar sin servidor", pm.info.value.active)
        assertFalse(s.sync.value.configured)
        return Triple(s, pm, d)
    }

    private fun rec(title: String, pass: String) = Record.create("t", System.currentTimeMillis()).also { it.title = title; it.password = pass; it.username = "u" }

    /** Simula a la persona: acepta el código y el resumen (o los rechaza) en cuanto aparecen. */
    private fun kotlinx.coroutines.CoroutineScope.user(pm: PeerManager, acceptSas: Boolean, acceptMerge: Boolean, seen: MutableList<String>) = launch(Dispatchers.Default) {
        while (true) {
            pm.sas.value?.let { seen += it.sas; it.answer.complete(acceptSas) }
            pm.merge.value?.let { seen += "merge:${it.summary.mineOnly()}/${it.summary.theirsOnly()}/${it.summary.differing()}"; it.answer.complete(acceptMerge) }
            delay(30)
        }
    }

    private fun backups(d: File) = d.listFiles { f -> f.name.startsWith("vault.ckey.proximidad-") }?.size ?: 0

    @Test fun dosBovedasSeUnenSinServidorSinPerderNada() = runBlocking {
        val (s1, pm1, d1) = device("uno"); val (s2, pm2, d2) = device("dos")
        val seen1 = mutableListOf<String>(); val seen2 = mutableListOf<String>()
        val j1 = user(pm1, true, true, seen1); val j2 = user(pm2, true, true, seen2)
        try {
            s1.upsert(rec("Solo en 1", "a")); s2.upsert(rec("Solo en 2", "b")); s2.upsert(rec("Otra de 2", "c"))
            pm2.allowPairing()
            val r = pm1.pairWith("127.0.0.1:${pm2.info.value.port}")
            assertTrue(r.newlyTrusted() && r.firstMerge())
            assertEquals(3, s1.records.value.size); assertEquals(3, s2.records.value.size)
            assertEquals(seen1.first { !it.startsWith("merge") }, seen2.first { !it.startsWith("merge") })
            assertTrue(seen1.contains("merge:1/2/0")); assertTrue(seen2.contains("merge:2/1/0"))
            assertEquals(1, backups(d1)); assertEquals(1, backups(d2))
            pm1.refresh(); assertEquals(1, pm1.info.value.peers.size)
            // sin servidor, la bóveda lo sigue estando
            assertFalse(s1.sync.value.configured)
            // desvincular en 2: 1 ya no puede sincronizar con 2
            pm2.refresh()
            pm2.unlink(pm2.info.value.peers.first().deviceId)
            s1.upsert(rec("Despues", "z"))
            val failed = try { pm1.syncWith("127.0.0.1:${pm2.info.value.port}"); false } catch (_: Exception) { true }
            assertTrue("tras desvincular no debe sincronizar", failed)
            assertNull(s2.records.value.firstOrNull { it.title == "Despues" })
        } finally { j1.cancel(); j2.cancel(); pm1.stop(); pm2.stop() }
    }

    @Test fun codigoRechazadoNoVinculaNiIntercambiaNada() = runBlocking {
        val (s1, pm1, _) = device("uno"); val (s2, pm2, _) = device("dos")
        val j1 = user(pm1, true, true, mutableListOf()); val j2 = user(pm2, false, true, mutableListOf())
        try {
            s1.upsert(rec("Secreta", "x"))
            pm2.allowPairing()
            val failed = try { pm1.pairWith("127.0.0.1:${pm2.info.value.port}"); false } catch (_: Exception) { true }
            assertTrue(failed)
            assertTrue(s2.records.value.isEmpty())
            pm1.refresh(); assertTrue(pm1.info.value.peers.isEmpty())
        } finally { j1.cancel(); j2.cancel(); pm1.stop(); pm2.stop() }
    }
}
