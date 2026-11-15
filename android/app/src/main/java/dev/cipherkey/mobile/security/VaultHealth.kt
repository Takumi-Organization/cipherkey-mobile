package dev.cipherkey.mobile.security

import dev.cipherkey.core.model.Record

/**
 * Salud de la bóveda: todo se calcula en local con la estimación orientativa de `PasswordGenerator` (nada sale del dispositivo).
 * Puntuación 0-100: media de puntos por fortaleza, menos penalización por repetidas y por contraseñas sin cambiar en un año.
 */
object VaultHealth {
    const val OLD_AFTER_MS = 365L * 24 * 3600 * 1000

    data class Report(
        val total: Int,
        val score: Int,
        /** Recuento por fortaleza, en el orden de `PasswordGenerator.Strength` (muy débil … muy fuerte). */
        val byStrength: List<Int>,
        val weak: Set<String>,
        val repeated: Set<String>,
        val repeatedGroups: Int,
        val old: Set<String>,
        val withoutPassword: Int,
    ) {
        val level: Level get() = when {
            total == 0 -> Level.EMPTY
            score >= 85 -> Level.SOLID
            score >= 65 -> Level.GOOD
            score >= 40 -> Level.IMPROVABLE
            else -> Level.AT_RISK
        }
    }

    enum class Level(val label: String) { EMPTY("SIN DATOS"), SOLID("SÓLIDA"), GOOD("BUENA"), IMPROVABLE("MEJORABLE"), AT_RISK("EN RIESGO") }

    private val POINTS = intArrayOf(0, 25, 60, 90, 100)

    fun analyze(records: List<Record>, now: Long = System.currentTimeMillis()): Report {
        val withPwd = records.filter { !it.deleted && it.password.isNotEmpty() }
        val counts = IntArray(PasswordGenerator.Strength.entries.size)
        val weak = HashSet<String>()
        var points = 0.0
        for (r in withPwd) {
            val s = PasswordGenerator.strength(r.password)
            counts[s.ordinal]++
            points += POINTS[s.ordinal]
            if (s.ordinal <= 1) weak += r.uuid
        }
        val groups = withPwd.groupBy { it.password }.values.filter { it.size > 1 }
        val repeated = groups.flatten().map { it.uuid }.toSet()
        val old = withPwd.filter { it.updatedAt > 0 && now - it.updatedAt > OLD_AFTER_MS }.map { it.uuid }.toSet()
        val n = withPwd.size
        val score = if (n == 0) 0 else (points / n - 15.0 * repeated.size / n - 10.0 * old.size / n).toInt().coerceIn(0, 100)
        return Report(n, score, counts.toList(), weak, repeated, groups.size, old, records.count { !it.deleted && it.password.isEmpty() })
    }
}
