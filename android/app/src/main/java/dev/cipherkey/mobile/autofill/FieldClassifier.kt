package dev.cipherkey.mobile.autofill

enum class FieldKind { USERNAME, PASSWORD, OTHER }

/** Decide si un campo es de usuario o de contraseña. Lógica pura: recibe valores ya extraídos de la vista. */
object FieldClassifier {
    private const val CLASS_MASK = 0xF
    private const val VARIATION_MASK = 0xFF0
    private const val CLASS_TEXT = 1
    private const val CLASS_NUMBER = 2
    private const val TEXT_PASSWORD = 0x80
    private const val TEXT_VISIBLE_PASSWORD = 0x90
    private const val TEXT_WEB_PASSWORD = 0xE0
    private const val TEXT_EMAIL = 0x20
    private const val TEXT_WEB_EMAIL = 0xD0
    private const val NUMBER_PASSWORD = 0x10

    fun classify(hints: List<String>?, inputType: Int, htmlType: String?, idEntry: String?): FieldKind {
        val h = hints.orEmpty().map { it.lowercase() }
        // "newPassword" es un formulario de registro o cambio: no se rellena con una credencial existente
        if (h.any { it == "newpassword" }) return FieldKind.OTHER
        if (h.any { it == "password" }) return FieldKind.PASSWORD
        if (h.any { it == "username" || it == "emailaddress" || it == "email" }) return FieldKind.USERNAME

        when (htmlType?.lowercase()) {
            "password" -> return FieldKind.PASSWORD
            "email" -> return FieldKind.USERNAME
        }

        val cls = inputType and CLASS_MASK
        val variation = inputType and VARIATION_MASK
        if (cls == CLASS_TEXT && (variation == TEXT_PASSWORD || variation == TEXT_VISIBLE_PASSWORD || variation == TEXT_WEB_PASSWORD)) return FieldKind.PASSWORD
        if (cls == CLASS_NUMBER && variation == NUMBER_PASSWORD) return FieldKind.PASSWORD
        if (cls == CLASS_TEXT && (variation == TEXT_EMAIL || variation == TEXT_WEB_EMAIL)) return FieldKind.USERNAME

        val id = idEntry?.lowercase().orEmpty()
        if (cls == CLASS_TEXT && listOf("user", "login", "email").any { id.contains(it) }) return FieldKind.USERNAME
        return FieldKind.OTHER
    }
}
