package dev.cipherkey.mobile.autofill

import dev.cipherkey.core.model.Record

/** A qué app o web pertenece el formulario que pide autocompletado. */
data class FillTarget(val packageName: String, val webDomain: String? = null, val webScheme: String? = null)

/**
 * Emparejamiento de registros con el formulario. Lógica pura (sin Android) para poder probarla en la JVM.
 *
 * Reglas, pensadas contra suplantación (phishing):
 *  - Web: el dominio del registro debe ser el de la página o un dominio PADRE suyo en frontera de etiqueta
 *    (`example.com` sirve a `login.example.com`, nunca a `evilexample.com` ni al revés).
 *  - No se aceptan sufijos públicos comunes (`co.uk`...) ni dominios de una sola etiqueta como dominio de registro.
 *  - Una página http no recibe credenciales guardadas para https.
 *  - App: solo si el registro guarda `androidapp://<paquete>` exacto en la URL.
 *  - Sin coincidencia no se ofrece nada: nunca se listan todos los registros.
 */
object AutofillMatcher {
    const val APP_SCHEME = "androidapp://"

    // Lista mínima de sufijos públicos de varias etiquetas; no sustituye a la Public Suffix List completa.
    private val publicSuffixes = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.nz", "co.jp", "ne.jp", "or.jp",
        "com.br", "com.mx", "com.ar", "com.co", "co.in", "co.za", "com.cn", "com.tr", "com.es", "com.pt",
    )

    /** Host en minúsculas de una URL (con o sin esquema), sin puerto, usuario ni `www.`; null si no es un host usable. */
    fun hostOf(url: String): String? {
        var s = url.trim()
        if (s.isEmpty() || s.startsWith(APP_SCHEME, ignoreCase = true)) return null
        val i = s.indexOf("://")
        if (i >= 0) s = s.substring(i + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) return null // IPv6: no se empareja
        s = s.substringBefore(':').trim().trimEnd('.').lowercase()
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        if (s.isEmpty() || s.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) return null
        return s
    }

    fun androidPackageOf(url: String): String? {
        val s = url.trim()
        if (!s.startsWith(APP_SCHEME)) return null
        return s.removePrefix(APP_SCHEME).takeIf { it.isNotEmpty() }
    }

    private fun isIp(h: String) = h.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }

    fun domainMatches(pageHost: String, recordHost: String): Boolean {
        val p = pageHost.lowercase().removePrefix("www.")
        val r = recordHost.lowercase().removePrefix("www.")
        if (p == r) return true
        if (isIp(p) || isIp(r)) return false
        if (!r.contains('.') || r in publicSuffixes) return false
        return p.endsWith(".$r")
    }

    fun matches(target: FillTarget, record: Record): Boolean {
        if (record.deleted) return false
        val url = record.url.trim()
        if (url.isEmpty()) return false
        val pkg = androidPackageOf(url)
        if (pkg != null) return target.webDomain == null && pkg == target.packageName
        val domain = target.webDomain?.let { hostOf(it) } ?: return false
        val rh = hostOf(url) ?: return false
        if (target.webScheme.equals("http", ignoreCase = true) && url.startsWith("https://", ignoreCase = true)) return false
        return domainMatches(domain, rh)
    }

    /** Registros que encajan: coincidencia exacta de host primero, luego por título. Sin registros de otros sitios. */
    fun select(target: FillTarget, records: List<Record>): List<Record> {
        val exact = target.webDomain?.let { hostOf(it) }
        return records.filter { matches(target, it) && (it.username.isNotEmpty() || it.password.isNotEmpty()) }
            .sortedWith(compareBy<Record> { if (exact != null && hostOf(it.url) == exact) 0 else 1 }.thenBy { it.title.lowercase() })
    }
}
