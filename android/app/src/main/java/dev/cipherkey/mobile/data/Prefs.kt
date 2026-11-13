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
}
