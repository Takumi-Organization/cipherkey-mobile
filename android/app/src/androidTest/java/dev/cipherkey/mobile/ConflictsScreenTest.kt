package dev.cipherkey.mobile

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.data.VaultFiles
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.ui.CipherKeyTheme
import dev.cipherkey.mobile.ui.ConflictsScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Pantalla de conflictos con un conflicto REAL generado entre dos dispositivos contra el servidor local (ver SyncScenariosTest). */
class ConflictsScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val dirs = mutableListOf<File>()

    @After fun tearDown() { dirs.forEach { it.deleteRecursively() } }

    @Test fun resolverConflictoDesdeLaPantalla() {
        val args = InstrumentationRegistry.getArguments()
        val admin = args.getString("ck.admin").orEmpty()
        assumeTrue("Sin ck.admin: prueba omitida", admin.isNotEmpty())
        val server = args.getString("ck.server") ?: "http://10.0.2.2:8787"
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        fun session() = VaultSession(ctx, VaultFiles(File(ctx.cacheDir, "cs-${UUID.randomUUID()}").also { it.mkdirs(); dirs += it }))
        val a = session(); val b = session()
        val pwd = "contraseña-de-prueba-larga"
        val uuid = runBlocking {
            a.create(pwd.toCharArray())
            val r = Record.create("t", System.currentTimeMillis()).also { it.title = "Cuenta conflictiva"; it.username = "ana"; it.password = "base" }
            a.upsert(r); a.enableSync(server, admin, "A")
            b.create(pwd.toCharArray()); b.pair(a.newPairingCode().encode(), "B"); b.syncNow()
            a.upsert(a.find(r.uuid)!!.copy().also { it.password = "de-A" })
            b.upsert(b.find(r.uuid)!!.copy().also { it.password = "de-B" })
            a.syncNow(); b.syncNow(); a.syncNow()
            r.uuid
        }
        val conflicted = listOf(a, b).first { it.conflicts.value.isNotEmpty() }
        rule.setContent { CipherKeyTheme { ConflictsScreen(conflicted, onBack = {}) } }
        rule.onNodeWithText("Cuenta conflictiva").assertIsDisplayed()
        rule.onNodeWithText("Mantener la mía").assertIsDisplayed()
        rule.onNodeWithText("Mantener la mía").performClick()
        rule.waitUntil(5_000) { conflicted.conflicts.value.isEmpty() }
        assertTrue(conflicted.find(uuid) != null)
    }
}
