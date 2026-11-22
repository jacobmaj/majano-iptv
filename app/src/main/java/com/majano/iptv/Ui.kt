@file:OptIn(ExperimentalFoundationApi::class)

package com.majano.iptv

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

object Ui {
    val bg = Color(0xFF0A0E17); val s1 = Color(0xFF111827); val s2 = Color(0xFF1A2236); val s3 = Color(0xFF263049)
    val acc = Color(0xFF3DDC97); val acc2 = Color(0xFF5B8CFF)
    val txt = Color(0xFFE8ECF4); val dim = Color(0xFF8A94A8); val err = Color(0xFFFF6B6B)
    val grad = Brush.horizontalGradient(listOf(acc2, acc))
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
