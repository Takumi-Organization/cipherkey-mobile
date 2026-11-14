package dev.cipherkey.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.VaultFiles
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Simula un SEGUNDO dispositivo que se empareja con el código que muestra la UI del primero
 * (-e ck.pair <código>), baja sus registros y sube uno nuevo. Para verificar el emparejado desde la pantalla
 * sin un segundo emulador: se lanza con `am instrument` sobre el dispositivo donde corre la app (no desinstala nada).
 */
class SecondDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "second-${UUID.randomUUID()}").also { it.mkdirs() }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun segundoDispositivoSeEmparejaYSubeUnRegistro() = runBlocking {
        val code = InstrumentationRegistry.getArguments().getString("ck.pair").orEmpty()
        assumeTrue("Sin ck.pair: prueba omitida", code.isNotEmpty())
        val title = InstrumentationRegistry.getArguments().getString("ck.title") ?: "Desde el segundo dispositivo"
        val s = VaultSession(ctx, VaultFiles(dir))
        s.create("contraseña-segundo-dispositivo".toCharArray())
        s.pair(code, "Segundo dispositivo")
        s.syncNow()
        assertTrue("debe haber bajado los registros del primero", s.records.value.isNotEmpty())
        s.upsert(Record.create("second", System.currentTimeMillis()).also { it.title = title; it.username = "bea"; it.password = "clave-segundo-1" })
        s.syncNow()
        assertEquals(0, s.sync.value.pending)
    }
}
