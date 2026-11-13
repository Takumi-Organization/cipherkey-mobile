package dev.cipherkey.mobile.autofill

import dev.cipherkey.core.model.Record
import org.junit.Assert.*
import org.junit.Test

class AutofillMatcherTest {
    private fun rec(title: String, url: String, user: String = "u", pass: String = "p") =
        Record.create("t", 1L).also { it.title = title; it.url = url; it.username = user; it.password = pass }

    private fun web(domain: String, scheme: String? = "https") = FillTarget("com.android.chrome", domain, scheme)

    @Test fun hostOf_normaliza() {
        assertEquals("example.com", AutofillMatcher.hostOf("https://www.Example.com:8443/login?a=1#x"))
        assertEquals("example.com", AutofillMatcher.hostOf("example.com/path"))
        assertEquals("example.com", AutofillMatcher.hostOf("https://user:pw@example.com/"))
        assertNull(AutofillMatcher.hostOf(""))
        assertNull(AutofillMatcher.hostOf("androidapp://com.foo"))
        assertNull(AutofillMatcher.hostOf("https://[::1]/"))
    }

    @Test fun mismoDominioYSubdominio() {
        assertTrue(AutofillMatcher.matches(web("example.com"), rec("a", "https://example.com")))
        assertTrue(AutofillMatcher.matches(web("login.example.com"), rec("a", "https://example.com")))
        assertTrue(AutofillMatcher.matches(web("www.example.com"), rec("a", "example.com")))
    }

    @Test fun noCoincideConSimilaresNiAlReves() {
        assertFalse(AutofillMatcher.matches(web("evilexample.com"), rec("a", "https://example.com")))
        assertFalse(AutofillMatcher.matches(web("example.com.evil.io"), rec("a", "https://example.com")))
        assertFalse(AutofillMatcher.matches(web("example.com"), rec("a", "https://login.example.com")))
        assertFalse(AutofillMatcher.matches(web("other.com"), rec("a", "https://example.com")))
    }

    @Test fun sufijosPublicosYUnaEtiquetaNoSirvenDeDominioPadre() {
        assertFalse(AutofillMatcher.matches(web("evil.co.uk"), rec("a", "https://co.uk")))
        assertFalse(AutofillMatcher.matches(web("evil.com"), rec("a", "https://com")))
        assertFalse(AutofillMatcher.matches(web("evil.localhost"), rec("a", "localhost")))
    }

    @Test fun ipsSoloExactas() {
        assertTrue(AutofillMatcher.matches(web("192.168.1.5"), rec("a", "http://192.168.1.5:8080/")))
        assertFalse(AutofillMatcher.matches(web("1.192.168.1.5"), rec("a", "192.168.1.5")))
    }

    @Test fun httpNoRecibeCredencialesDeHttps() {
        assertFalse(AutofillMatcher.matches(web("example.com", "http"), rec("a", "https://example.com")))
        assertTrue(AutofillMatcher.matches(web("example.com", "http"), rec("a", "http://example.com")))
        assertTrue(AutofillMatcher.matches(web("example.com", null), rec("a", "https://example.com")))
    }

    @Test fun paquetesSoloConAndroidappExacto() {
        val t = FillTarget("com.foo.app")
        assertTrue(AutofillMatcher.matches(t, rec("a", "androidapp://com.foo.app")))
        assertFalse(AutofillMatcher.matches(t, rec("a", "androidapp://com.foo.app2")))
        assertFalse(AutofillMatcher.matches(t, rec("a", "androidapp://com.foo")))
        assertFalse(AutofillMatcher.matches(t, rec("com.foo.app", "")))
        assertFalse(AutofillMatcher.matches(t, rec("a", "https://foo.com")))
    }

    @Test fun registroDeAppNoSirveEnNavegadorYViceversa() {
        assertFalse(AutofillMatcher.matches(web("example.com"), rec("a", "androidapp://com.android.chrome")))
        assertFalse(AutofillMatcher.matches(FillTarget("com.foo.app"), rec("a", "https://example.com")))
    }

    @Test fun borradosYVaciosNoSeOfrecen() {
        val d = rec("a", "https://example.com").also { it.deleted = true }
        assertFalse(AutofillMatcher.matches(web("example.com"), d))
        assertTrue(AutofillMatcher.select(web("example.com"), listOf(rec("a", "https://example.com", user = "", pass = ""))).isEmpty())
        assertFalse(AutofillMatcher.matches(web("example.com"), rec("a", "")))
    }

    @Test fun selectOrdenaExactoPrimeroYNoDevuelveAjenos() {
        val sub = rec("Zeta", "https://example.com")
        val exact = rec("Beta", "https://login.example.com")
        val ajeno = rec("Alfa", "https://other.com")
        val out = AutofillMatcher.select(web("login.example.com"), listOf(sub, ajeno, exact))
        assertEquals(listOf(exact.uuid, sub.uuid), out.map { it.uuid })
    }

    @Test fun sinDominioWebNoCoincidePorUrlWeb() {
        assertTrue(AutofillMatcher.select(FillTarget("com.foo"), listOf(rec("a", "https://example.com"))).isEmpty())
    }

    @Test fun localhostConPuertoYEsquemaHttp() {
        val t = FillTarget("dev.cipherkey.testapp", "localhost", "http")
        assertTrue(AutofillMatcher.matches(t, rec("a", "http://localhost:8000/login.html")))
        assertFalse(AutofillMatcher.matches(t, rec("a", "http://localhost.evil.test:8000/")))
    }
}
