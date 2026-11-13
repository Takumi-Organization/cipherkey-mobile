package dev.cipherkey.mobile.security

/** Reglas para cambiar la contraseña maestra (lógica pura, probada en la JVM). */
object MasterPasswordPolicy {
    const val MIN_LENGTH = 10

    /** Devuelve el mensaje de error o null si el cambio es aceptable. */
    fun validateChange(old: String, new: String, repeat: String): String? = when {
        old.isEmpty() -> "Escribe tu contraseña actual"
        new.length < MIN_LENGTH -> "La nueva debe tener al menos $MIN_LENGTH caracteres"
        new == old -> "La nueva debe ser distinta de la actual"
        new != repeat -> "Las contraseñas nuevas no coinciden"
        else -> null
    }
}
