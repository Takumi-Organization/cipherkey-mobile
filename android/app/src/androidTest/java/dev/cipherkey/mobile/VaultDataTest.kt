package dev.cipherkey.mobile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.VaultFiles
import dev.cipherkey.mobile.data.VaultSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Cambio de contraseña maestra y exportar/importar, en directorios temporales (no toca la bóveda real). */
class VaultDataTest {
    private lateinit var ctx: Context
    private val dirs = mutableListOf<File>()

    @Before fun setUp() { ctx = InstrumentationRegistry.getInstrumentation().targetContext }
    @After fun tearDown() { dirs.forEach { it.deleteRecursively() } }

    private fun session(): VaultSession {
        val d = File(ctx.cacheDir, "vd-${UUID.randomUUID()}").also { it.mkdirs(); dirs += it }
        return VaultSession(ctx, VaultFiles(d))
    }

    @Test fun cambiarContrasenaMaestra() = runBlocking {
        val s = session()
        s.create("clave-original-1".toCharArray())
        s.upsert(Record.create("t", 1L).also { it.title = "X"; it.password = "p" })
        try { s.changePassword("incorrecta".toCharArray(), "clave-nueva-22".toCharArray()); fail("debía rechazar la actual incorrecta") } catch (_: VaultSession.WrongPassword) {}
        s.changePassword("clave-original-1".toCharArray(), "clave-nueva-22".toCharArray())
        s.lock()
        try { s.unlock("clave-original-1".toCharArray()); fail("la antigua ya no debe valer") } catch (_: VaultSession.WrongPassword) {}
        s.unlock("clave-nueva-22".toCharArray())
        assertEquals(1, s.records.value.size)
        assertEquals("X", s.records.value[0].title)
    }

    @Test fun exportarEImportar() = runBlocking {
        val a = session()
        a.create("clave-export-1".toCharArray())
        a.upsert(Record.create("t", 1L).also { it.title = "Banco"; it.password = "p1" })
        val bytes = a.exportEncrypted()
        val b = session()
        val report = b.importVault(bytes, "clave-export-1".toCharArray())
        assertEquals(1, report.entries)
        assertEquals("Banco", b.records.value.single().title)
        try { session().importVault(bytes, "otra-clave-xx".toCharArray()); fail("contraseña errónea") } catch (_: VaultSession.WrongPassword) {}
    }
}
