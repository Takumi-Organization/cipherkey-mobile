package dev.cipherkey.mobile.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import dev.cipherkey.mobile.security.PasswordGenerator
import java.text.DateFormat
import java.util.Date

/** Tarjeta plana con borde fino (misma lógica que el escritorio: sin sombras ni degradados). */
@Composable
fun FlatCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
}

/** Inicial sobre un círculo de tono estable por nombre (acento y neutros: nada de arcoíris). */
@Composable
fun Avatar(name: String, size: Int = 44) {
    val tones = listOf(Color(0xFF1B8AC9), Color(0xFF1F9D6B), Color(0xFF7A5BD0), Color(0xFFD98A00), Color(0xFFC2527A), Color(0xFF4F6A85))
    val tone = tones[(name.lowercase().hashCode() and 0x7fffffff) % tones.size]
    Box(Modifier.size(size.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = tone, fontWeight = FontWeight.Bold, fontSize = (size * 0.4).sp)
    }
}

@Composable
fun StrengthBar(password: String, modifier: Modifier = Modifier) {
    val s = PasswordGenerator.strength(password)
    val frac = animateFloatAsState((s.ordinal + 1) / 5f, label = "strength").value
    val color = when (s) {
        PasswordGenerator.Strength.VERY_WEAK, PasswordGenerator.Strength.WEAK -> MaterialTheme.colorScheme.error
        PasswordGenerator.Strength.FAIR -> warningColor()
        else -> successColor()
    }
    Column(modifier) {
        LinearProgressIndicator(progress = { if (password.isEmpty()) 0f else frac }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
        if (password.isNotEmpty()) {
            Text("${s.label} · ~${PasswordGenerator.entropyBits(password).toInt()} bits (orientativo)", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

fun formatDate(ms: Long): String = if (ms <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** Copia al portapapeles marcando el contenido como sensible y lo borra pasado el plazo si sigue siendo lo mismo. */
fun copySecret(ctx: Context, text: String, clearAfterSeconds: Int) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("CipherKey", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    cm.setPrimaryClip(clip)
    if (clearAfterSeconds > 0) {
        Handler(Looper.getMainLooper()).postDelayed({
            val same = try { cm.primaryClip?.getItemAt(0)?.text?.toString() == text } catch (_: Exception) { true }
            if (same) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, clearAfterSeconds * 1000L)
    }
}

tailrec fun Context.findActivity(): FragmentActivity = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No hay actividad")
}
