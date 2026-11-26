@file:OptIn(ExperimentalFoundationApi::class)

package com.majano.iptv

import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay

object Ui {
    var bg by mutableStateOf(Color(0xFF0A0E17)); var s1 by mutableStateOf(Color(0xFF111827))
    var s2 by mutableStateOf(Color(0xFF1A2236)); var s3 by mutableStateOf(Color(0xFF263049))
    var acc by mutableStateOf(Color(0xFF4DA3FF)); var acc2 by mutableStateOf(Color(0xFF7A5CFF))
    var textScale by mutableStateOf(1f)
    val txt = Color(0xFFE8ECF4); val dim = Color(0xFF8A94A8); val err = Color(0xFFFF6B6B)
    val grad: Brush get() = Brush.horizontalGradient(listOf(acc2, acc))
    val themes = listOf(
        "Azul" to (Color(0xFF4DA3FF) to Color(0xFF7A5CFF)), "Verde" to (Color(0xFF3DDC97) to Color(0xFF5B8CFF)),
        "Morado" to (Color(0xFFB57CFF) to Color(0xFFFF5FA8)), "Naranja" to (Color(0xFFFFA94D) to Color(0xFFFF5F6D)),
        "Rosa" to (Color(0xFFFF6FAE) to Color(0xFF8F6CFF)))
    fun apply(theme: Int, oled: Boolean, scale: Int) {
        val p = themes[theme.coerceIn(0, themes.lastIndex)].second
        acc = p.first; acc2 = p.second
        if (oled) { bg = Color(0xFF000000); s1 = Color(0xFF0B0B10); s2 = Color(0xFF15151D); s3 = Color(0xFF23232F) }
        else { bg = Color(0xFF0A0E17); s1 = Color(0xFF111827); s2 = Color(0xFF1A2236); s3 = Color(0xFF263049) }
        textScale = when (scale) { 1 -> 1.15f; 2 -> 1.3f; else -> 1f }
    }
}

// Foco muy visible: zoom con rebote + borde verde + tinte. OK = onClick, OK sostenido (o toque largo) = onLong.
@Composable
fun Modifier.tv(shape: Shape = RoundedCornerShape(12.dp), onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.05f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow), label = "z")
    val a by animateFloatAsState(if (f) 1f else 0f, tween(160), label = "a")
    return this.onFocusChanged { f = it.isFocused }.scale(sc).clip(shape)
        .drawWithContent { drawContent(); if (a > 0f) drawRect(Ui.acc.copy(alpha = 0.18f * a)) }
        .combinedClickable(onLongClick = onLong, onClick = onClick)
        .then(if (a > 0f) Modifier.border(3.dp, Ui.acc.copy(alpha = a), shape) else Modifier)
}

fun act(label: String, f: () -> Unit): Pair<String, () -> Unit> = label to f

@Composable
fun Chip(label: String, sel: Boolean = false, m: Modifier = Modifier, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(m.tv(RoundedCornerShape(50), onLong, onClick).background(if (sel) Ui.acc else Ui.s2).padding(horizontal = 16.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, maxLines = 1, fontWeight = FontWeight.Medium, color = if (sel) Color(0xFF06281B) else Ui.txt)
    }
}

@Composable
fun PrimaryBtn(label: String, m: Modifier = Modifier, onClick: () -> Unit) {
    Box(m.tv(RoundedCornerShape(50), onClick = onClick).background(Ui.grad).padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, maxLines = 1, fontWeight = FontWeight.Bold, color = Color(0xFF04141F))
    }
}

@Composable
fun ActionDialog(title: String, actions: List<Pair<String, () -> Unit>>, close: () -> Unit) {
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = close) {
        LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Ui.s1).padding(22.dp).widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            actions.forEachIndexed { i, a ->
                Chip(a.first, m = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(fr) else Modifier)) { close(); a.second() }
            }
            Chip("Cancelar", m = Modifier.fillMaxWidth(), onClick = close)
        }
    }
}

@Composable
fun PinDialog(title: String, close: () -> Unit, ok: (String) -> Boolean) {
    var t by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = close) {
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Ui.s1).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(t, { t = it.filter { c -> c.isDigit() }.take(6); bad = false }, Modifier.focusRequester(fr), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if (bad) Text("PIN incorrecto o muy corto", color = Ui.err, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { PrimaryBtn("Aceptar") { bad = !ok(t) }; Chip("Cancelar", onClick = close) }
        }
    }
}

@Composable
fun TextDialog(title: String, initial: String, close: () -> Unit, ok: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = close) {
        LaunchedEffect(Unit) { delay(100); runCatching { fr.requestFocus() } }
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Ui.s1).padding(22.dp).widthIn(min = 300.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(t, { t = it }, Modifier.focusRequester(fr), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { PrimaryBtn("Aceptar") { close(); ok(t) }; Chip("Cancelar", onClick = close) }
        }
    }
}

// Cuando un canal/póster no trae imagen: círculo de color con iniciales
@Composable
fun Initials(name: String, m: Modifier = Modifier) {
    val hue = name.hashCode().rem(360).let { if (it < 0) it + 360 else it }.toFloat()
    val letters = name.split(' ', '-', '|').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "TV" }
    Box(m.background(Color.hsv(hue, 0.45f, 0.55f)), contentAlignment = Alignment.Center) {
        Text(letters, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    }
}

@Composable
fun Logo(x: Item, m: Modifier, scale: ContentScale = ContentScale.Fit) {
    if (x.icon.isBlank()) Initials(x.name, m)
    else SubcomposeAsyncImage(model = x.icon, contentDescription = null, modifier = m, contentScale = scale,
        loading = { Box(Modifier.fillMaxSize().background(Ui.s2)) }, error = { Initials(x.name, Modifier.fillMaxSize()) })
}

// Búsqueda por voz (micrófono del control o del celular)
@Composable
fun rememberVoice(onText: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    return {
        runCatching {
            l.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES").putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres ver?"))
        }.onFailure { Toast.makeText(ctx, "Este dispositivo no tiene búsqueda por voz", Toast.LENGTH_SHORT).show() }
    }
}
