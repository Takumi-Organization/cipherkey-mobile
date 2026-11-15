package dev.cipherkey.mobile.security

import dev.cipherkey.core.model.Record
import org.junit.Assert.*
import org.junit.Test

class VaultHealthTest {
    private val now = 2_000_000_000_000L
    private fun rec(pwd: String, updated: Long = now) = Record().apply { password = pwd; updatedAt = updated }

    @Test fun boveda_vacia() {
        val r = VaultHealth.analyze(emptyList(), now)
        assertEquals(VaultHealth.Level.EMPTY, r.level)
        assertEquals(0, r.total)
    }

    @Test fun detecta_debiles_repetidas_y_antiguas() {
        val weak = rec("123456")
        val a = rec("Zq7!pW#m2Lr9&xT4vB"); val b = rec("Zq7!pW#m2Lr9&xT4vB")
        val old = rec("kR8%tY2@nQ5^wE1uP7", now - VaultHealth.OLD_AFTER_MS - 1)
        val r = VaultHealth.analyze(listOf(weak, a, b, old), now)
        assertEquals(setOf(weak.uuid), r.weak)
        assertEquals(setOf(a.uuid, b.uuid), r.repeated)
        assertEquals(1, r.repeatedGroups)
        assertEquals(setOf(old.uuid), r.old)
        assertEquals(4, r.total)
        assertEquals(4, r.byStrength.sum())
    }

    @Test fun puntuacion_alta_con_claves_fuertes_y_baja_con_debiles() {
        val strong = List(5) { rec("Zq7!pW#m2Lr9&xT4vB$it") }.mapIndexed { i, r -> r.also { it.password = "Zq7!pW#m2Lr9&xT4vB$i" + "k".repeat(i) + "%Rt" } }
        assertTrue(VaultHealth.analyze(strong, now).score >= 85)
        val weak = List(5) { rec("abc123") }
        val rep = VaultHealth.analyze(weak, now)
        assertTrue(rep.score < 40)
        assertEquals(VaultHealth.Level.AT_RISK, rep.level)
    }

    @Test fun ignora_borradas_y_sin_contrasena() {
        val gone = rec("123456").also { it.deleted = true }
        val empty = rec("")
        val r = VaultHealth.analyze(listOf(gone, empty), now)
        assertEquals(0, r.total); assertEquals(1, r.withoutPassword)
    }
}
