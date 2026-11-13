package dev.cipherkey.mobile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.VaultFiles
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Sincronización extremo a extremo: dos dispositivos (dos [VaultSession] con directorios distintos) contra un servidor
 * REAL en marcha. ESTADO: ejecutadas 2026-11-13 en emulator-5554 (API 36) contra el servidor local en 127.0.0.1:8787 (vía 10.0.2.2): 3 de 3 OK.
 *
 * Arranque del servidor (en el anfitrión):  CK_ADMIN_TOKEN=<tok> CK_BIND=0.0.0.0:8787 cargo run --release   (en server/)
 * Ejecución:  ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.ck.admin=<tok>
 *             [-Pandroid.testInstrumentationRunnerArguments.ck.server=http://10.0.2.2:8787]
 * Sin `ck.admin` las pruebas se omiten (assume), no fallan.
 *
 * Definición de los escenarios (la nota del proyecto solo los nombra):
 *  A: alta de la bóveda en el servidor, emparejado de un segundo dispositivo y convergencia de ambos lados.
 *  B: ediciones concurrentes de campos DISTINTOS del mismo registro -> fusión automática sin conflicto.
 *  C: ediciones concurrentes del MISMO campo -> conflicto conservando ambas versiones; se resuelve y converge.
 */
class SyncScenariosTest {
    private lateinit var ctx: Context
    private lateinit var server: String
    private lateinit var admin: String
    private val dirs = mutableListOf<File>()
    private val pwd = "contraseña-de-prueba-larga"

    @Before fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        admin = args.getString("ck.admin").orEmpty()
        assumeTrue("Sin ck.admin: prueba omitida", admin.isNotEmpty())
        server = args.getString("ck.server") ?: "http://10.0.2.2:8787"
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After fun tearDown() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun newSession(name: String): VaultSession {
        val d = File(ctx.cacheDir, "sync-test-$name-${UUID.randomUUID()}").also { it.mkdirs(); dirs += it }
        return VaultSession(ctx, VaultFiles(d))
    }

    private fun record(title: String, user: String, pass: String, notes: String = "") =
        Record.create("test", System.currentTimeMillis()).also { it.title = title; it.username = user; it.password = pass; it.notes = notes }

    /** Dos dispositivos con la misma bóveda y un registro ya sincronizado en ambos. */
    private fun pairedDevices(): Triple<VaultSession, VaultSession, String> = runBlocking {
        val a = newSession("a"); val b = newSession("b")
        a.create(pwd.toCharArray())
        val r = record("Cuenta", "ana", "pass-1", "nota-1")
        a.upsert(r)
        a.enableSync(server, admin, "dispositivo-A")
        val code = a.newPairingCode().encode()
        b.create(pwd.toCharArray())
        b.pair(code, "dispositivo-B")
        b.syncNow()
        Triple(a, b, r.uuid)
    }

    @Test fun escenarioA_altaEmparejadoYConvergencia() = runBlocking {
        val (a, b, uuid) = pairedDevices()
        assertEquals("el registro inicial debe llegar a B", "pass-1", b.find(uuid)?.password)

        // B crea un registro nuevo y A lo recibe
        val nuevo = record("Banco", "bea", "pass-banco")
        b.upsert(nuevo); b.syncNow()
        a.syncNow()
        assertEquals("pass-banco", a.find(nuevo.uuid)?.password)

        // A borra el original y B ve el borrado
        a.delete(uuid); a.syncNow()
        b.syncNow()
        assertNull(b.find(uuid))
        assertEquals(0, a.sync.value.pending)
        assertEquals(0, b.conflicts.value.size)
    }

    @Test fun escenarioB_camposDistintosSeFusionanSinConflicto() = runBlocking {
        val (a, b, uuid) = pairedDevices()
        val ea = a.find(uuid)!!.copy().also { it.username = "ana-nueva" }
        val eb = b.find(uuid)!!.copy().also { it.notes = "nota-de-B" }
        a.upsert(ea); b.upsert(eb)
        a.syncNow(); b.syncNow(); a.syncNow()

        for (s in listOf(a, b)) {
            val r = s.find(uuid)!!
            assertEquals("ana-nueva", r.username)
            assertEquals("nota-de-B", r.notes)
            assertEquals("pass-1", r.password)
            assertTrue("no debe haber conflictos", s.conflicts.value.isEmpty())
        }
    }

    @Test fun escenarioC_mismoCampoGeneraConflictoYSeResuelve() = runBlocking {
        val (a, b, uuid) = pairedDevices()
        a.upsert(a.find(uuid)!!.copy().also { it.password = "pass-de-A" })
        b.upsert(b.find(uuid)!!.copy().also { it.password = "pass-de-B" })
        a.syncNow(); b.syncNow(); a.syncNow()

        // el que sube segundo (B) ve el conflicto con ambas versiones; nada se descarta en silencio
        val conflicted = listOf(a, b).firstOrNull { it.conflicts.value.isNotEmpty() }
        assertNotNull("alguno de los dos debe tener el conflicto", conflicted)
        val c = conflicted!!.conflicts.value.single { it.uuid == uuid }
        val seen = setOf(c.password, c.conflict?.password)
        assertEquals(setOf("pass-de-A", "pass-de-B"), seen)

        // se resuelve conservando la versión local y ambos dispositivos convergen sin conflictos
        conflicted.resolve(uuid, VaultSession.Choice.KEEP_MINE)
        conflicted.syncNow()
        val other = if (conflicted === a) b else a
        other.syncNow(); conflicted.syncNow()
        assertTrue(a.conflicts.value.isEmpty() && b.conflicts.value.isEmpty())
        assertEquals(a.find(uuid)!!.password, b.find(uuid)!!.password)
    }
}
