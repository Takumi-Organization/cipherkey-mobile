package dev.cipherkey.mobile.security

import java.security.SecureRandom
import kotlin.math.ln

/** Generador con SecureRandom (sin sesgo de módulo: nextInt(bound) es uniforme) y estimación orientativa de fortaleza. */
object PasswordGenerator {
    data class Options(
        val length: Int = 20,
        val upper: Boolean = true,
        val lower: Boolean = true,
        val digits: Boolean = true,
        val symbols: Boolean = true,
        val excludeAmbiguous: Boolean = false,
    )

    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?/~"
    private const val AMBIGUOUS = "O0oIl1|`'\"B8S5Z2"
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 64

    private val random = SecureRandom()

    private fun pools(o: Options): List<String> {
        fun clean(s: String) = if (o.excludeAmbiguous) s.filterNot { it in AMBIGUOUS } else s
        return buildList {
            if (o.upper) add(clean(UPPER))
            if (o.lower) add(clean(LOWER))
            if (o.digits) add(clean(DIGITS))
            if (o.symbols) add(clean(SYMBOLS))
        }.filter { it.isNotEmpty() }
    }

    fun generate(o: Options, rng: SecureRandom = random): String {
        val pools = pools(o)
        require(pools.isNotEmpty()) { "Selecciona al menos un tipo de carácter" }
        val len = o.length.coerceIn(MIN_LENGTH, MAX_LENGTH)
        val all = pools.joinToString("")
        val chars = ArrayList<Char>(len)
        for (p in pools) chars.add(p[rng.nextInt(p.length)]) // al menos uno de cada tipo elegido
        while (chars.size < len) chars.add(all[rng.nextInt(all.length)])
        for (i in chars.indices.reversed()) { // Fisher-Yates
            val j = rng.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return chars.joinToString("")
    }

    enum class Strength(val label: String) { VERY_WEAK("Muy débil"), WEAK("Débil"), FAIR("Aceptable"), STRONG("Fuerte"), VERY_STRONG("Muy fuerte") }

    /** Entropía estimada en bits: tamaño del alfabeto usado × longitud, con penalización por repeticiones y secuencias. */
    fun entropyBits(pwd: String): Double {
        if (pwd.isEmpty()) return 0.0
        var pool = 0
        if (pwd.any { it in 'a'..'z' }) pool += 26
        if (pwd.any { it in 'A'..'Z' }) pool += 26
        if (pwd.any { it in '0'..'9' }) pool += 10
        if (pwd.any { !it.isLetterOrDigit() }) pool += 32
        var bits = pwd.length * ln(pool.coerceAtLeast(1).toDouble()) / ln(2.0)
        val distinct = pwd.toSet().size
        if (distinct < pwd.length / 2) bits *= 0.6
        var run = 0
        for (i in 1 until pwd.length) if (pwd[i] == pwd[i - 1] || pwd[i].code - pwd[i - 1].code == 1) run++
        bits -= run * 2.0
        if (COMMON.any { pwd.lowercase().contains(it) }) bits = minOf(bits, 28.0)
        return bits.coerceAtLeast(0.0)
    }

    fun strength(pwd: String): Strength {
        val b = entropyBits(pwd)
        return when {
            b < 28 -> Strength.VERY_WEAK
            b < 40 -> Strength.WEAK
            b < 60 -> Strength.FAIR
            b < 80 -> Strength.STRONG
            else -> Strength.VERY_STRONG
        }
    }

    private val COMMON = listOf("password", "contraseña", "123456", "qwerty", "letmein", "admin", "welcome", "abc123")
}
