package dev.cipherkey.mobile

import android.content.Context

/** Versión de release: no existe ningún mecanismo para saltarse FLAG_SECURE. */
object DebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun captureAllowed(ctx: Context): Boolean = false
}
