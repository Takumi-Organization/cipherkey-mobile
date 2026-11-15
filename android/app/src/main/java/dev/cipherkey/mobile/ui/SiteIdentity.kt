package dev.cipherkey.mobile.ui

/** Identidad visual estable de un sitio, calculada solo con los datos locales (nunca se pide un favicon por la red). */
object SiteIdentity {
    /** Dominio (o paquete de app) sin esquema, `www.` ni ruta; si no hay URL, el título en minúsculas. */
    fun key(title: String, url: String): String {
        var u = url.trim().lowercase()
        if (u.isNotEmpty()) {
            u = u.removePrefix("androidapp://").substringAfter("://").removePrefix("www.").substringBefore('/').substringBefore('?').substringBefore(':')
            if (u.isNotEmpty()) return u
        }
        return title.trim().lowercase()
    }

    /** Inicial en mayúscula del nombre (o del dominio si no hay título). */
    fun initial(title: String, url: String): String =
        (title.trim().ifEmpty { key(title, url) }).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"

    /** Índice estable (0 until n) del tono; el mismo dominio da siempre el mismo. FNV-1a para no depender de `hashCode`. */
    fun toneIndex(key: String, n: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (c in key) { h = h xor c.code; h *= 16777619 }
        return (h and 0x7fffffff) % n
    }
}
