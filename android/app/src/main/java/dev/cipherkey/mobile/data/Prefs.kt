package dev.cipherkey.mobile.data

import android.content.Context

/** Ajustes no secretos (ninguna contraseña ni clave pasa por aquí). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Bloqueo por inactividad con la app abierta. */
    var autoLockSeconds: Int
        get() = sp.getInt("autolock", 120)
        set(v) = sp.edit().putInt("autolock", v).apply()

    /** Bloquear en cuanto la app deja de estar en primer plano. */
    var lockOnLeave: Boolean
        get() = sp.getBoolean("lockOnLeave", true)
        set(v) = sp.edit().putBoolean("lockOnLeave", v).apply()

    /** FLAG_SECURE: oculta el contenido en capturas y en la vista de apps recientes. */
    var secureScreen: Boolean
        get() = sp.getBoolean("secureScreen", true)
        set(v) = sp.edit().putBoolean("secureScreen", v).apply()

    var clipboardClearSeconds: Int
        get() = sp.getInt("clipClear", 30)
        set(v) = sp.edit().putInt("clipClear", v).apply()

    /** Apariencia (no secreta): 0 sistema, 1 Grafito (oscuro), 2 Washi (claro). */
    var themeMode: Int
        get() = sp.getInt("themeMode", 1)
        set(v) = sp.edit().putInt("themeMode", v).apply()

    /** Acento: índice de la lista predefinida, o -1 para el tono personalizado. */
    var accentIndex: Int
        get() = sp.getInt("accentIndex", 4)
        set(v) = sp.edit().putInt("accentIndex", v).apply()

    /** Tono (0..360) del acento personalizado. */
    var accentHue: Float
        get() = sp.getFloat("accentHue", 150f)
        set(v) = sp.edit().putFloat("accentHue", v).apply()
}
