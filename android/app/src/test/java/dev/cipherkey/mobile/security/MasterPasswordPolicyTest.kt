package dev.cipherkey.mobile.security

import org.junit.Assert.*
import org.junit.Test

class MasterPasswordPolicyTest {
    @Test fun aceptaCambioValido() = assertNull(MasterPasswordPolicy.validateChange("actual-1234", "nueva-larga-99", "nueva-larga-99"))
    @Test fun exigeActual() = assertNotNull(MasterPasswordPolicy.validateChange("", "nueva-larga-99", "nueva-larga-99"))
    @Test fun rechazaCorta() = assertTrue(MasterPasswordPolicy.validateChange("a", "corta", "corta")!!.contains("10"))
    @Test fun rechazaIgualQueActual() = assertNotNull(MasterPasswordPolicy.validateChange("misma-clave-1", "misma-clave-1", "misma-clave-1"))
    @Test fun rechazaNoCoincidente() = assertNotNull(MasterPasswordPolicy.validateChange("actual-1234", "nueva-larga-99", "nueva-larga-98"))
}
