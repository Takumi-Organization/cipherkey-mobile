package dev.cipherkey.mobile.ui

import org.junit.Assert.*
import org.junit.Test

class SiteIdentityTest {
    @Test fun clave_de_dominio_ignora_esquema_www_y_ruta() {
        assertEquals("github.com", SiteIdentity.key("GitHub", "https://www.github.com/login?x=1"))
        assertEquals("com.example.app", SiteIdentity.key("App", "androidapp://com.example.app"))
        assertEquals("router", SiteIdentity.key("Router", ""))
        assertEquals("192.168.1.1", SiteIdentity.key("Router", "http://192.168.1.1:8080/admin"))
    }

    @Test fun inicial_y_tono_son_estables() {
        assertEquals("G", SiteIdentity.initial("github", "https://github.com"))
        assertEquals("1", SiteIdentity.initial("1Password", ""))
        assertEquals("?", SiteIdentity.initial("", ""))
        val a = SiteIdentity.toneIndex("github.com", 10)
        assertEquals(a, SiteIdentity.toneIndex("github.com", 10))
        assertTrue(a in 0 until 10)
    }
}
