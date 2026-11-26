@file:OptIn(ExperimentalFoundationApi::class)

package com.majano.iptv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun Login(st: Store, canCancel: Boolean, ok: (Api) -> Unit, cancel: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf("") }
    var u by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val sc = rememberCoroutineScope()
    BackHandler(enabled = canCancel) { cancel() }
    Box(Modifier.fillMaxSize().background(Ui.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Ui.s1).padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(88.dp))
            Text("MAJANO", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Ui.acc)
            Text(if (canCancel) "Agregar proveedor" else "Conecta tu proveedor IPTV", fontSize = 14.sp, color = Ui.dim)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Enlace (http://url:puerto) o lista M3U") }, singleLine = true)
            OutlinedTextField(u, { u = it }, Modifier.fillMaxWidth(), label = { Text("Usuario (vacío si es lista M3U)") }, singleLine = true)
            OutlinedTextField(p, { p = it }, Modifier.fillMaxWidth(), label = { Text("Contraseña") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            if (err.isNotEmpty()) Text(err, color = Ui.err, fontSize = 13.sp)
            PrimaryBtn(if (busy) "Conectando…" else "ENTRAR", Modifier.fillMaxWidth()) {
                if (!busy && url.isNotBlank()) {
                    busy = true; err = ""
                    sc.launch {
                        val api = Api(url, u, p, File(ctx.cacheDir, "tmp"))
                        val r = withContext(Dispatchers.IO) { runCatching { api.auth() } }
                        busy = false
                        if (r.getOrNull() == true) { st.addProfile(url.trim(), u.trim(), p); ok(mk(ctx, st.profile!!)) }
                        else err = if (r.isFailure) "No se pudo conectar: ${r.exceptionOrNull()?.message}" else "Usuario o contraseña incorrectos"
                    }
                }
            }
            if (canCancel) Chip("Cancelar", onClick = cancel)
        }
    }
}

@Composable
fun Tile(title: String, sub: String, g: List<Color>, m: Modifier, compact: Boolean, onClick: () -> Unit) {
    Box(m.height(if (compact) 96.dp else 130.dp).tv(RoundedCornerShape(18.dp), onClick = onClick).background(Brush.linearGradient(g)).padding(14.dp)) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, fontSize = if (compact) 16.sp else 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (!compact) Text(sub, fontSize = 12.sp, color = Color(0xCCFFFFFF))
        }
    }
}

@Composable
fun WideCard(x: Item, name: String, prog: Float, onClick: () -> Unit) {
    Box(Modifier.width(230.dp).height(130.dp).tv(RoundedCornerShape(14.dp), onClick = onClick).background(Ui.s2)) {
        Logo(x, Modifier.fillMaxSize(), if (x.kind == "live") ContentScale.Fit else ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))
        Text(name, Modifier.align(Alignment.BottomStart).padding(10.dp).padding(bottom = if (prog > 0f) 6.dp else 0.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (prog > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
            Box(Modifier.fillMaxWidth(prog).fillMaxHeight().background(Ui.acc))
        }
    }
}

@Composable
fun Home(st: Store, a: Api, profiles: List<Profile>, act: Int, open: (String) -> Unit, resume: (Item) -> Unit, pick: (Int) -> Unit,
         add: () -> Unit, remove: () -> Unit, imported: () -> Unit, search: () -> Unit) {
    val ctx = LocalContext.current
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val fr = remember { FocusRequester() }
    var ext by remember { mutableStateOf(st.external) }
    var par by remember { mutableStateOf(st.parental) }
    var th by remember { mutableStateOf(st.theme) }
    var oled by remember { mutableStateOf(st.oled) }
    var sc by remember { mutableStateOf(st.uiScale) }
    var flow by remember { mutableStateOf(0) }   // 1 PIN para desactivar, 2 PIN actual, 3 nuevo PIN
    var newUser by remember { mutableStateOf(false) }
    var delUser by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val expL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) runCatching { ctx.contentResolver.openOutputStream(u)?.use { it.write(st.export().toByteArray()) } }
    }
    val impL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null && runCatching { ctx.contentResolver.openInputStream(u)?.use { st.import(it.readBytes().decodeToString()) } }.isSuccess) imported()
    }
    val rec = remember { st.recent(12).filter { !(st.parental && (ADULT.containsMatchIn(it.name) || it.cat in a.adultIds(it.kind))) } }
    val favs = remember { st.favsAll().filter { !(st.parental && (ADULT.containsMatchIn(it.name) || it.cat in a.adultIds(it.kind))) } }
    if (flow != 0) key(flow) {
        PinDialog(when (flow) { 1 -> "PIN para desactivar"; 2 -> "PIN actual"; else -> "Nuevo PIN (mínimo 4 dígitos)" }, { flow = 0 }) { p ->
            when (flow) {
                1 -> if (st.checkPin(p)) { st.parental = false; par = false; flow = 0; true } else false
                2 -> if (st.checkPin(p)) { flow = 3; true } else false
                else -> if (p.length >= 4) { st.setPin(p); flow = 0; true } else false
            }
        }
    }
    if (newUser) TextDialog("Nombre del nuevo usuario", "", { newUser = false }) { n -> if (n.isNotBlank()) { st.addUser(n.trim()); imported() } }
    if (delUser) ActionDialog("¿Borrar a ${st.userName()} y todos sus datos?", listOf(act("Sí, borrar") { st.removeUser(); imported() })) { delUser = false }
    Column(Modifier.fillMaxSize().background(Ui.bg).verticalScroll(rememberScrollState()).padding(if (compact) 16.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(if (compact) 48.dp else 60.dp))
            Column(Modifier.weight(1f)) {
                Text("MAJANO", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ui.acc)
                Text((profiles.getOrNull(act)?.name ?: "") + "  ·  " + st.userName(), fontSize = 13.sp, color = Ui.dim, maxLines = 1)
            }
            PrimaryBtn("Buscar", onClick = search)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile("Live TV", "Canales en vivo", listOf(Color(0xFF14B8A6), Color(0xFF2563EB)), Modifier.weight(1.2f).focusRequester(fr), compact) { open("live") }
            Tile("Películas", "Cine y estrenos", listOf(Color(0xFFEC4899), Color(0xFFF59E0B)), Modifier.weight(1f), compact) { open("movie") }
            Tile("Series", "Temporadas y capítulos", listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)), Modifier.weight(1f), compact) { open("series") }
        }
        if (rec.isNotEmpty()) {
            Text("Continuar viendo", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rec) { x -> WideCard(x, st.nameOf(x), st.progressOf(x)) { resume(x) } }
            }
        }
        if (favs.isNotEmpty()) {
            Text("Favoritos", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(favs) { x -> Box(Modifier.width(120.dp)) { PosterCard(x, true, false, 0f, Modifier, {}, st.nameOf(x)) { resume(x) } } }
            }
        }
        Text("Ajustes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Color: ${Ui.themes[th].first}") { th = (th + 1) % Ui.themes.size; st.theme = th; Ui.apply(th, oled, sc) }
            Chip("Fondo negro: " + if (oled) "Sí" else "No") { oled = !oled; st.oled = oled; Ui.apply(th, oled, sc) }
            Chip("Texto: " + listOf("Normal", "Grande", "Muy grande")[sc]) { sc = (sc + 1) % 3; st.uiScale = sc; Ui.apply(th, oled, sc) }
            Chip("Reproductor: " + if (ext) "Externo" else "Interno") { ext = !ext; st.external = ext }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Usuario: ${st.userName()} ▸") { st.nextUser(); imported() }
            Chip("Nuevo usuario") { newUser = true }
            if (st.uid != 0) Chip("Borrar usuario") { delUser = true }
            Chip("Control parental: " + if (par) "Sí" else "No") {
                if (par) { if (st.hasPin()) flow = 1 else { st.parental = false; par = false } } else { st.parental = true; par = true }
            }
            Chip(if (st.hasPin()) "Cambiar PIN" else "Crear PIN") { flow = if (st.hasPin()) 2 else 3 }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (profiles.size > 1) Chip("Cambiar proveedor ▸") { pick((act + 1) % profiles.size) }
            Chip("Agregar proveedor", onClick = add)
            Chip("Quitar proveedor", onClick = remove)
            Chip("Exportar respaldo") { Toast.makeText(ctx, "El respaldo incluye tu contraseña: guárdalo con cuidado", Toast.LENGTH_LONG).show(); expL.launch("majano-respaldo.json") }
            Chip("Importar respaldo") { impL.launch(arrayOf("*/*")) }
        }
    }
}

@Composable
fun SearchScreen(api: Api, st: Store, back: () -> Unit, pick: (Item) -> Unit) {
    BackHandler { back() }
    var q by remember { mutableStateOf("") }
    val voice = rememberVoice { q = it }
    val all by produceState<List<Item>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { listOf("live", "movie", "series").flatMap { k -> api.catalog(k).second } }.getOrNull() ?: emptyList() }
    }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(200); runCatching { fr.requestFocus() } }
    val res = remember(all, q) {
        val src = all
        if (src == null || q.length < 2) emptyList()
        else src.filter { it.name.contains(q, true) && !(st.parental && (ADULT.containsMatchIn(it.name) || it.cat in api.adultIds(it.kind))) }.take(120)
    }
    Column(Modifier.fillMaxSize().background(Ui.bg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("‹ Volver", onClick = back)
            OutlinedTextField(q, { q = it }, Modifier.weight(1f).focusRequester(fr), placeholder = { Text("Buscar canales, películas y series…") }, singleLine = true)
            Chip("Voz", onClick = voice)
        }
        when {
            all == null -> Text("Cargando catálogos…", color = Ui.dim)
            q.length < 2 -> Text("Escribe al menos 2 letras", color = Ui.dim)
            res.isEmpty() -> Text("Sin resultados", color = Ui.dim)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(res) { x ->
                    Row(Modifier.fillMaxWidth().tv(RoundedCornerShape(12.dp)) { pick(x) }.background(Ui.s1).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Logo(x, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
                        Text(x.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(when (x.kind) { "live" -> "Canal"; "movie" -> "Película"; else -> "Serie" }, fontSize = 12.sp, color = Ui.acc)
                    }
                }
            }
        }
    }
}
