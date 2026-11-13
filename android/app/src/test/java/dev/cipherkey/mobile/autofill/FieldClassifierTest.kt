package dev.cipherkey.mobile.autofill

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldClassifierTest {
    private fun c(hints: List<String>? = null, input: Int = 0, html: String? = null, id: String? = null) = FieldClassifier.classify(hints, input, html, id)

    @Test fun hintsTienenPrioridad() {
        assertEquals(FieldKind.PASSWORD, c(listOf("password"), input = 0x21))
        assertEquals(FieldKind.USERNAME, c(listOf("username")))
        assertEquals(FieldKind.USERNAME, c(listOf("emailAddress")))
        assertEquals(FieldKind.OTHER, c(listOf("newPassword"), input = 0x81))
    }

    @Test fun htmlType() {
        assertEquals(FieldKind.PASSWORD, c(html = "password"))
        assertEquals(FieldKind.USERNAME, c(html = "email"))
        assertEquals(FieldKind.OTHER, c(html = "text"))
    }

    @Test fun inputType() {
        assertEquals(FieldKind.PASSWORD, c(input = 0x81))
        assertEquals(FieldKind.PASSWORD, c(input = 0x91))
        assertEquals(FieldKind.PASSWORD, c(input = 0xE1))
        assertEquals(FieldKind.PASSWORD, c(input = 0x12)) // PIN numérico
        assertEquals(FieldKind.USERNAME, c(input = 0x21))
        assertEquals(FieldKind.OTHER, c(input = 0x01))
        assertEquals(FieldKind.OTHER, c(input = 0x02))
    }

    @Test fun idEntrySoloEnCamposDeTexto() {
        assertEquals(FieldKind.USERNAME, c(input = 1, id = "login_user"))
        assertEquals(FieldKind.OTHER, c(input = 2, id = "login_user"))
        assertEquals(FieldKind.OTHER, c(input = 1, id = "search_box"))
    }
}
