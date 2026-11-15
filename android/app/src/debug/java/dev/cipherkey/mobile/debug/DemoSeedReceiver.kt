package dev.cipherkey.mobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cipherkey.core.model.Record
import dev.cipherkey.mobile.CipherKeyApp
import dev.cipherkey.mobile.DebugHooks
import dev.cipherkey.mobile.data.VaultSession
import dev.cipherkey.mobile.ui.ThemeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SOLO debug. Acciones (`am broadcast -n dev.cipherkey.mobile/.debug.DemoSeedReceiver -a ...`):
 *  - SEED: crea la bóveda DEMO (contraseña maestra «demo-bobeda-2026») con entradas ficticias; solo si NO existe ninguna bóveda.
 *  - UNLOCK: desbloquea la bóveda demo.  - LOCK: bloquea.
 *  - THEME (--ei mode 0|1|2, --ei accent -1..4, --ef hue): apariencia.  - CAPTURE (--ez on true|false): FLAG_SECURE off/on.
 * Nunca abre ni modifica una bóveda real: SEED se niega si ya hay una.
 */
class DemoSeedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as CipherKeyApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action?.substringAfterLast('.')) {
                    "SEED" -> if (!app.session.hasVault()) seed(app.session)
                    "UNLOCK" -> try { app.session.unlock(DEMO_PASSWORD.toCharArray()) } catch (_: Exception) {}
                    "LOCK" -> app.session.lock()
                    "THEME" -> {
                        ThemeState.updateMode(intent.getIntExtra("mode", ThemeState.mode))
                        if (intent.hasExtra("hue")) ThemeState.updateHue(intent.getFloatExtra("hue", 200f))
                        else ThemeState.updateAccent(intent.getIntExtra("accent", ThemeState.accentIndex))
                    }
                    "CAPTURE" -> DebugHooks.setCapture(ctx, intent.getBooleanExtra("on", true))
                }
            } finally { pending.finish() }
        }
    }

    private suspend fun seed(s: VaultSession) {
        s.create(DEMO_PASSWORD.toCharArray())
        DEMO.forEach { e ->
            s.upsert(Record().apply {
                title = e.title; username = e.user; password = e.pwd; url = e.url; category = e.cat; favorite = e.fav
                tags = e.tags.split(',').filter { it.isNotBlank() }.toMutableList()
                if (e.title in setOf("GitHub", "Cloudflare", "Proton Mail", "Binance")) totpSecret = "JBSWY3DPEHPK3PXP"
                if (e.title == "Nómina y RRHH") notes = "Portal del empleado. Soporte: rrhh@empresa.example"
            })
        }
    }

    companion object {
        const val DEMO_PASSWORD = "demo-bobeda-2026"
        private val STRONG = listOf("Vk7#qLm2!xR9pT", "hN4\$wZ8&bY1cQe", "Jt6@uD3^sF5mKa", "pX2%gB9*nW4vHr", "Lc8!eR5#yT7zMq", "Qd3&oV6@kJ1fNs")
        private fun strong(i: Int) = STRONG[i % STRONG.size] + (i * 7).toString().padStart(2, '0') + "zK"
        // título, usuario, contraseña, url, categoría, favorita, etiquetas
        private class D(val title: String, val user: String, val pwd: String, val url: String, val cat: String, val fav: Boolean, val tags: String)
        private fun d(t: String, u: String, p: String, url: String, c: String, f: Boolean, tags: String) = D(t, u, p, url, c, f, tags)
        private val DEMO = listOf(
            d("GitHub", "marta.ruiz", strong(0), "https://github.com", "Desarrollo", true, "trabajo"),
            d("GitLab", "marta.ruiz", strong(1), "https://gitlab.com", "Desarrollo", false, "trabajo"),
            d("Cloudflare", "marta@ejemplo.dev", strong(2), "https://dash.cloudflare.com", "Desarrollo", false, "infra"),
            d("Hetzner", "marta@ejemplo.dev", strong(3), "https://accounts.hetzner.com", "Desarrollo", false, "infra"),
            d("Docker Hub", "martaruiz", "docker123", "https://hub.docker.com", "Desarrollo", false, ""),
            d("Figma", "marta@ejemplo.dev", strong(4), "https://figma.com", "Trabajo", false, "diseño"),
            d("Notion", "marta@ejemplo.dev", strong(5), "https://notion.so", "Trabajo", false, "trabajo"),
            d("Slack", "marta.ruiz", "Equipo2024", "https://slack.com", "Trabajo", false, "trabajo"),
            d("Nómina y RRHH", "m.ruiz", strong(6), "https://rrhh.empresa.example", "Trabajo", false, ""),
            d("Gmail", "marta.ruiz@gmail.com", strong(7), "https://mail.google.com", "Correo", true, "personal"),
            d("Proton Mail", "marta.ruiz@proton.me", strong(8), "https://proton.me", "Correo", true, "personal"),
            d("Outlook", "marta.ruiz@outlook.es", "Marta1990", "https://outlook.live.com", "Correo", false, "personal"),
            d("Netflix", "marta.ruiz@gmail.com", "Verano2023!", "https://netflix.com", "Ocio", false, "familia"),
            d("Spotify", "martaruiz", "Verano2023!", "https://spotify.com", "Ocio", false, "familia"),
            d("Disney+", "marta.ruiz@gmail.com", "Verano2023!", "https://disneyplus.com", "Ocio", false, "familia"),
            d("Steam", "mruiz_games", strong(9), "https://store.steampowered.com", "Ocio", false, "juegos"),
            d("Twitch", "mruiz_games", strong(10), "https://twitch.tv", "Ocio", false, "juegos"),
            d("Discord", "mruiz#4821", strong(11), "https://discord.com", "Social", false, "juegos"),
            d("Reddit", "marta_r", "qwerty123", "https://reddit.com", "Social", false, ""),
            d("Instagram", "marta.ruiz", strong(12), "https://instagram.com", "Social", false, "personal"),
            d("Mastodon", "marta@fosstodon.org", strong(13), "https://fosstodon.org", "Social", false, ""),
            d("Amazon", "marta.ruiz@gmail.com", strong(14), "https://amazon.es", "Compras", true, "familia"),
            d("AliExpress", "marta.ruiz@gmail.com", "123456", "https://aliexpress.com", "Compras", false, ""),
            d("Wallapop", "marta_r", strong(15), "https://wallapop.com", "Compras", false, ""),
            d("Banco Ejemplo", "49271833K", strong(16), "https://banco.ejemplo.es", "Finanzas", true, "banco"),
            d("PayPal", "marta.ruiz@gmail.com", strong(17), "https://paypal.com", "Finanzas", false, "banco"),
            d("Binance", "marta.ruiz@proton.me", strong(18), "https://binance.com", "Finanzas", false, ""),
            d("Hacienda", "49271833K", strong(19), "https://sede.agenciatributaria.example", "Finanzas", false, "oficial"),
            d("Wi-Fi de casa", "MOVISTAR_4F2A", "casa12345", "", "Hogar", false, "red"),
            d("Router", "admin", "admin", "http://192.168.1.1", "Hogar", false, "red"),
            d("NAS Synology", "marta", strong(20), "https://nas.local:5001", "Hogar", false, "red"),
            d("Cámara del portal", "admin", "password1", "http://192.168.1.40", "Hogar", false, "red"),
            d("Seguro del coche", "49271833K", strong(21), "https://seguros.ejemplo.es", "Hogar", false, ""),
            d("Gimnasio", "marta.ruiz", "gym2024", "https://gimnasio.ejemplo.es", "Salud", false, ""),
            d("Mi Salud (app)", "marta.ruiz", strong(22), "androidapp://es.ejemplo.misalud", "Salud", false, ""),
            d("Wikipedia", "MartaRuiz", strong(23), "https://wikipedia.org", "Social", false, ""),
        )
    }
}
