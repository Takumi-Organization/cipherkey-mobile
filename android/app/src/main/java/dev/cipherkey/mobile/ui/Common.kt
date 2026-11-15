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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import dev.cipherkey.mobile.security.PasswordGenerator
import java.text.DateFormat
import java.util.Date

private val Square = RoundedCornerShape(4.dp)

/** Superficie plana con borde fino: sin sombras ni degradados; la jerarquía sale del tono y la línea. */
@Composable
fun KCard(modifier: Modifier = Modifier, border: Color = kamiTokens.line2, container: Color = kamiTokens.raised, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, shape = Square, color = container, border = BorderStroke(1.dp, border)) { Column(Modifier.padding(padding.dp), content = content) }
}

/** Marcador de sección en texto: «01 // SEGURIDAD». */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.padding(top = 28.dp, bottom = 10.dp).semantics { heading() }, style = MaterialTheme.typography.labelMedium, color = kamiTokens.inkMuted)
}

/** Título de pantalla grande. */
@Composable
fun ScreenTitle(kicker: String, title: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(kicker, style = MaterialTheme.typography.labelMedium, color = kamiTokens.accentText)
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        trailing()
    }
}

@Composable
fun KIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = kamiTokens.ink, enabled: Boolean = true) {
    Box(modifier.size(48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(22.dp), tint = if (enabled) tint else kamiTokens.inkFaint)
    }
}

enum class KKind { Primary, Secondary, Danger }

@Composable
fun KButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: KKind = KKind.Primary, icon: ImageVector? = null, enabled: Boolean = true, busy: Boolean = false) {
    val k = kamiTokens
    val (bg, fg, border) = when {
        !enabled -> Triple(Color.Transparent, k.inkFaint, k.line1)
        kind == KKind.Primary -> Triple(k.accent, k.onAccent, k.accent)
        kind == KKind.Danger -> Triple(Color.Transparent, k.error, k.error)
        else -> Triple(Color.Transparent, k.ink, k.line3)
    }
    Row(
        modifier.heightIn(min = 48.dp).clip(Square).background(bg).border(1.dp, border, Square)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg, strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = fg); Spacer(Modifier.width(8.dp)) }
            Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Filtro seleccionable: relleno de acento si está activo, borde si no. Zona táctil de 48 dp. */
@Composable
fun KChip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    val k = kamiTokens
    Box(Modifier.heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(34.dp).clip(Square).background(if (selected) k.accent else Color.Transparent)
                .border(1.dp, if (selected) k.accent else k.line2, Square).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { Icon(icon, null, Modifier.size(15.dp), tint = if (selected) k.onAccent else k.inkDim); Spacer(Modifier.width(6.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) k.onAccent else k.inkDim, maxLines = 1)
        }
    }
}

/** Etiqueta pequeña con borde (categoría, estado). El estado nunca va solo en color: lleva texto. */
@Composable
fun Tag(text: String, color: Color = kamiTokens.inkMuted, icon: ImageVector? = null, filled: Boolean = false, modifier: Modifier = Modifier) {
    val k = kamiTokens
    Row(
        modifier.clip(Square).background(if (filled) color else Color.Transparent).border(1.dp, if (filled) color else color.copy(alpha = 0.6f), Square).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(12.dp), tint = if (filled) k.paper else color); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) k.paper else color, maxLines = 1)
    }
}

private val AvatarTones = listOf(
    Color(0xFFE2573B), Color(0xFFD9A441), Color(0xFF3FA37A), Color(0xFF2F8FBF), Color(0xFF7B6CD9),
    Color(0xFFC94F8B), Color(0xFF8A9A3B), Color(0xFFCC7A3D), Color(0xFF4F6D8A), Color(0xFFA3523A),
)

/** Bloque plano de color estable por dominio con la inicial. Local: no se consulta ningún servicio de favicons. */
@Composable
fun SiteAvatar(title: String, url: String, size: Int = 44, registered: Boolean = false) {
    val tone = AvatarTones[SiteIdentity.toneIndex(SiteIdentity.key(title, url), AvatarTones.size)]
    val onTone = if (contrast(Color(0xFF14110F), tone) >= contrast(Color.White, tone)) Color(0xFF14110F) else Color.White
    val ink = kamiTokens.ink
    Box(Modifier.size((size + if (registered) 6 else 0).dp)) {
        if (registered) Box(Modifier.size(size.dp).align(Alignment.TopStart).border(1.5.dp, ink, Square))
        Box(Modifier.size(size.dp).align(Alignment.BottomEnd).clip(Square).background(tone), contentAlignment = Alignment.Center) {
            Text(SiteIdentity.initial(title, url), color = onTone, fontWeight = FontWeight.Bold, fontSize = (size * 0.42).sp)
        }
    }
}

/** Compatibilidad con llamadas antiguas por nombre. */
@Composable
fun Avatar(name: String, size: Int = 44) = SiteAvatar(name, "", size)

@Composable
fun strengthColor(s: PasswordGenerator.Strength): Color = when (s) {
    PasswordGenerator.Strength.VERY_WEAK, PasswordGenerator.Strength.WEAK -> kamiTokens.error
    PasswordGenerator.Strength.FAIR -> kamiTokens.warn
    else -> kamiTokens.ok
}

/** Cinco segmentos planos más etiqueta de texto (la fortaleza nunca depende solo del color). */
@Composable
fun StrengthBar(password: String, modifier: Modifier = Modifier) {
    val s = PasswordGenerator.strength(password)
    val color = strengthColor(s)
    val k = kamiTokens
    val filled = if (password.isEmpty()) 0 else s.ordinal + 1
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(5) { i -> Box(Modifier.weight(1f).fillMaxHeight().background(if (i < filled) color else k.line1)) }
        }
        if (password.isNotEmpty()) {
            Text("${s.label.uppercase()} · ~${PasswordGenerator.entropyBits(password).toInt()} bits (orientativo)", style = MaterialTheme.typography.labelSmall,
                color = color, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Mini indicador de fortaleza para filas: cinco marcas de 3 dp. */
@Composable
fun StrengthTicks(s: PasswordGenerator.Strength, modifier: Modifier = Modifier) {
    val color = strengthColor(s); val off = kamiTokens.line1
    Canvas(modifier.size(width = 30.dp, height = 8.dp).semantics { contentDescription = "Fortaleza: ${s.label}" }) {
        val w = 4.dp.toPx(); val gap = 1.5.dp.toPx()
        for (i in 0 until 5) drawRect(if (i <= s.ordinal) color else off, Offset(i * (w + gap), 0f), Size(w, size.height))
    }
}

@Composable
fun KField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, singleLine: Boolean = true, minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None, trailing: (@Composable () -> Unit)? = null, supporting: String? = null,
    isError: Boolean = false, mono: Boolean = false, placeholder: String? = null, leading: (@Composable () -> Unit)? = null,
) {
    val k = kamiTokens
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(), singleLine = singleLine, minLines = minLines, isError = isError,
        label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } }, trailingIcon = trailing, leadingIcon = leading,
        supportingText = supporting?.let { { Text(it) } }, visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        textStyle = if (mono) MaterialTheme.typography.bodyLarge.merge(Mono) else MaterialTheme.typography.bodyLarge,
        shape = Square,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = k.accent, unfocusedBorderColor = k.line3, errorBorderColor = k.error, disabledBorderColor = k.line1,
            focusedLabelColor = k.accentText, unfocusedLabelColor = k.inkMuted, errorLabelColor = k.error,
            cursorColor = k.accentText, errorCursorColor = k.error,
            focusedTextColor = k.ink, unfocusedTextColor = k.ink,
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
            focusedPlaceholderColor = k.inkFaint, unfocusedPlaceholderColor = k.inkFaint,
            focusedSupportingTextColor = k.inkMuted, unfocusedSupportingTextColor = k.inkMuted, errorSupportingTextColor = k.error,
            focusedLeadingIconColor = k.inkDim, unfocusedLeadingIconColor = k.inkDim, focusedTrailingIconColor = k.inkDim, unfocusedTrailingIconColor = k.inkDim,
        ),
    )
}

@Composable
fun kSwitchColors(): SwitchColors {
    val k = kamiTokens
    return SwitchDefaults.colors(
        checkedThumbColor = k.onAccent, checkedTrackColor = k.accent, checkedBorderColor = k.accent,
        uncheckedThumbColor = k.inkMuted, uncheckedTrackColor = Color.Transparent, uncheckedBorderColor = k.line3,
    )
}

/** Fila con interruptor: toda la fila es el control (≥48 dp) y TalkBack la lee como interruptor. */
@Composable
fun KSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = kamiTokens.inkMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, null, colors = kSwitchColors())
    }
}

/** Barra superior plana con línea inferior. */
@Composable
fun KTopBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, backIcon: ImageVector = KI.Back, backDescription: String = "Volver", actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) KIconButton(backIcon, backDescription, onBack) else Spacer(Modifier.width(12.dp))
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            actions()
        }
        HorizontalDivider(color = kamiTokens.line1)
    }
}

/** Rejilla de puntos de 16 dp (fondo de las pantallas de desbloqueo y estados vacíos). */
fun Modifier.dotGrid(color: Color): Modifier = drawBehind {
    val step = 16.dp.toPx(); val r = 1.dp.toPx()
    var y = step / 2
    while (y < size.height) { var x = step / 2; while (x < size.width) { drawCircle(color, r, Offset(x, y)); x += step }; y += step }
}

/** Emblema: cámara acorazada plana, relleno de acento desplazado y trazo de tinta encima. */
@Composable
fun VaultEmblem(size: Int = 96, open: Boolean = false) {
    val k = kamiTokens
    Canvas(Modifier.size(size.dp).semantics { contentDescription = "CipherKey" }) {
        val s = this.size.minDimension; val off = s * 0.09f; val body = s - off
        val sw = (s * 0.035f).coerceAtLeast(2.dp.toPx())
        drawRect(k.accent, Offset(off, off), Size(body, body))
        drawRect(k.ink, Offset.Zero, Size(body, body), style = Stroke(sw))
        val c = Offset(body / 2, body * 0.44f)
        drawCircle(k.ink, body * 0.13f, c, style = Stroke(sw))
        drawLine(k.ink, Offset(c.x, c.y + body * 0.13f), Offset(c.x, c.y + body * 0.30f), sw)
        drawRect(k.ink, Offset(body * 0.12f, body * 0.12f), Size(body * 0.07f, body * 0.07f))
        drawRect(k.ink, Offset(body * 0.81f, body * 0.12f), Size(body * 0.07f, body * 0.07f))
        if (open) drawLine(k.ink, Offset(body * 0.1f, body * 0.9f), Offset(body * 0.9f, body * 0.9f), sw)
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

/** Contraseña coloreada por clase de carácter (letras en tinta, números en acento, símbolos en secundario) para leerla sin confundir O/0 o l/1. */
@Composable
fun colorizedPassword(pwd: String): androidx.compose.ui.text.AnnotatedString {
    val k = kamiTokens
    return androidx.compose.ui.text.buildAnnotatedString {
        pwd.forEach { c ->
            val col = when { c.isDigit() -> k.accentText; c.isLetter() -> k.ink; else -> k.secondary }
            withStyle(androidx.compose.ui.text.SpanStyle(color = col)) { append(c) }
        }
    }
}

/** Botón de texto de los diálogos: acento legible (AA) y zona táctil de 48 dp. */
@Composable
fun KTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = Square,
        colors = ButtonDefaults.textButtonColors(contentColor = kamiTokens.accentText, disabledContentColor = kamiTokens.inkFaint), content = content)
}
