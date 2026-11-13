package dev.cipherkey.mobile

import android.app.Activity
import android.content.Intent
import android.service.autofill.Dataset
import android.view.View
import android.view.autofill.AutofillManager
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.autofill.AutofillAuthActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * AutofillAuthActivity con la bóveda desbloqueada: coincidencia única -> Dataset; sin coincidencia -> cancelado.
 * Solo se ejecuta si el dispositivo NO tiene bóveda (crea una y la borra al terminar): nunca pisa datos reales.
 * La integración con el sistema (FillResponse, desplegable) se verificó a mano con una app de prueba aparte.
 */
class AutofillAuthActivityTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CipherKeyApp

    @Before fun setUp() {
        assumeTrue("Hay una bóveda real: prueba omitida", !app.session.hasVault())
        runBlocking {
            app.session.create("contraseña-de-prueba".toCharArray())
            app.session.upsert(Record.create("test", 1L).also { it.title = "App"; it.username = "ana"; it.password = "secreto-1"; it.url = "androidapp://com.example.coincide" })
        }
    }

    @After fun tearDown() {
        app.session.lock()
        app.session.files.vault.delete()
    }

    private fun intentFor(pkg: String): Intent {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val v = View(ctx)
        return Intent(ctx, AutofillAuthActivity::class.java)
            .putExtra(AutofillAuthActivity.EXTRA_PACKAGE, pkg)
            .putExtra(AutofillAuthActivity.EXTRA_USER_ID, View(ctx).autofillId)
            .putExtra(AutofillAuthActivity.EXTRA_PASS_ID, v.autofillId)
    }

    @Test fun unaCoincidenciaDevuelveDataset() {
        ActivityScenario.launchActivityForResult<AutofillAuthActivity>(intentFor("com.example.coincide")).use { sc ->
            val deadline = System.currentTimeMillis() + 10_000
            while (sc.result.resultCode != Activity.RESULT_OK && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals(Activity.RESULT_OK, sc.result.resultCode)
            val ds = IntentCompat.getParcelableExtra(sc.result.resultData, AutofillManager.EXTRA_AUTHENTICATION_RESULT, Dataset::class.java)
            assertNotNull(ds)
        }
    }

    @Test fun sinCoincidenciaNoDevuelveNada() {
        ActivityScenario.launchActivityForResult<AutofillAuthActivity>(intentFor("com.example.otra")).use { sc ->
            sc.onActivity { it.finish() } // la pantalla "Sin coincidencias" solo ofrece cancelar
            assertNotEquals(Activity.RESULT_OK, sc.result.resultCode)
        }
    }
}
