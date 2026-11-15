package dev.cipherkey.mobile

import android.content.Context

/** Solo en builds debug: permite desactivar FLAG_SECURE para capturar pantallas de pruebas (lo activa DemoSeedReceiver). */
object DebugHooks {
    private const val FILE = "debug-hooks"
    fun captureAllowed(ctx: Context): Boolean = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("capture", false)
    fun setCapture(ctx: Context, on: Boolean) { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("capture", on).apply() }
}
