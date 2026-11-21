@file:OptIn(ExperimentalFoundationApi::class)

package com.majano.iptv

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PI(val title: String, val url: String, val key: String, val live: Boolean, val item: Item, val season: Int = 0, val epId: String = "")

sealed class S {
    object Login : S(); object Home : S()
    class Sec(val kind: String) : S()
    class Det(val item: Item) : S()
    class Play(val q: List<PI>, val i: Int) : S()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val st = Store(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0F1A)) { App(st) } } }
    }
}

// Resaltado de foco: escala + borde blanco. OK del control = onClick; OK sostenido = onLong.
@Composable
fun Modifier.tv(shape: Shape = RoundedCornerShape(14.dp), onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.07f else 1f, label = "f")
    return this.onFocusChanged { f = it.isFocused }.scale(sc)
        .then(if (f) Modifier.border(3.dp, Color.White, shape) else Modifier)
        .clip(shape).combinedClickable(onLongClick = onLong, onClick = onClick)
}

@Composable
fun Chip(label: String, sel: Boolean = false, m: Modifier = Modifier, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(m.tv(RoundedCornerShape(10.dp), onLong, onClick).background(if (sel) Color(0xFF2E9E6A) else Color(0xFF2A3147)).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(label, fontSize = 15.sp, maxLines = 1)
    }
}

fun mk(ctx: Context, p: Profile) = Api(p.url, p.user, p.pass, File(ctx.cacheDir, "cat_" + (p.url + p.user).hashCode()))

@Composable
fun App(st: Store) {
    val ctx = LocalContext.current
    var api by remember { mutableStateOf(st.profile?.let { mk(ctx, it) }) }
    var s by remember { mutableStateOf<S>(if (api == null) S.Login else S.Home) }
    var ver by remember { mutableStateOf(0) }
    fun use() { val p = st.profile; api = p?.let { mk(ctx, it) }; s = if (p == null) S.Login else S.Home; ver++ }
    val a = api
    when (val c = s) {
        is S.Login -> Login(st, st.profiles().isNotEmpty(), { api = it; s = S.Home; ver++ }, { s = S.Home })
        is S.Home -> if (a != null) key(ver) {
            Home(st, a, st.profiles(), st.active, { s = S.Sec(it) },
                { x -> s = if (x.kind == "live") S.Play(listOf(PI(x.name, a.playUrl(x), "live:${x.id}", true, x)), 0) else S.Det(x) },
                { i -> st.active = i; use() }, { s = S.Login }, { st.removeActive(); use() }, { use() })
        }
        is S.Sec -> if (a != null) {
            BackHandler { s = S.Home }
            Section(c.kind, a, st, { s = S.Det(it) }, { q, i -> s = S.Play(q, i) })
        }
        is S.Det -> if (a != null) {
            BackHandler { s = S.Sec(c.item.kind) }
            Detail(c.item, a, st) { q, i -> s = S.Play(q, i) }
        }
        is S.Play -> PlayerScreen(c.q, c.i, st) { val it = c.q[0].item; s = if (it.kind == "live") S.Sec("live") else S.Det(it) }
    }
}

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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(500.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1B2133)).padding(28.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (canCancel) "Agregar proveedor" else "Majano IPTV", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Enlace (http://url:puerto) o lista M3U") }, singleLine = true)
            OutlinedTextField(u, { u = it }, Modifier.fillMaxWidth(), label = { Text("Usuario (vacío si es una lista M3U)") }, singleLine = true)
            OutlinedTextField(p, { p = it }, Modifier.fillMaxWidth(), label = { Text("Contraseña") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            if (err.isNotEmpty()) Text(err, color = Color(0xFFFF8080), fontSize = 13.sp)
            Box(Modifier.fillMaxWidth().tv {
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
            }.background(Brush.horizontalGradient(listOf(Color(0xFFFF9A7A), Color(0xFFE0203C)))).padding(14.dp), contentAlignment = Alignment.Center) {
                Text(if (busy) "Conectando…" else "ENTRAR", fontWeight = FontWeight.Bold)
            }
            if (canCancel) Chip("Cancelar", onClick = cancel)
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
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF1B2133)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(t, { t = it.filter { c -> c.isDigit() }.take(6); bad = false }, Modifier.focusRequester(fr), singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if (bad) Text("PIN incorrecto o muy corto", color = Color(0xFFFF8080), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Chip("Aceptar") { bad = !ok(t) }; Chip("Cancelar", onClick = close) }
        }
    }
}

@Composable
fun Card(x: Item, fav: Boolean, progress: Float = 0f, ph: Dp = 210.dp, onLong: () -> Unit = {}, onClick: () -> Unit) {
    val live = x.kind == "live"
    Column(Modifier.tv(onLong = onLong, onClick = onClick).background(Color(0xFF161C2D)).padding(6.dp)) {
        Box {
            AsyncImage(model = x.icon, contentDescription = null, modifier = Modifier.fillMaxWidth().height(if (live) 100.dp else ph),
                contentScale = if (live) ContentScale.Fit else ContentScale.Crop)
            if (fav) Text("⭐", Modifier.align(Alignment.TopEnd))
        }
        if (progress > 0f) Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) { Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFF00E5A0))) }
        Text(x.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
    }
}

@Composable
fun Tile(t: String, g: List<Color>, m: Modifier, onClick: () -> Unit) {
    Box(m.height(120.dp).tv(onClick = onClick).background(Brush.linearGradient(g)), contentAlignment = Alignment.Center) {
        Text(t, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Home(st: Store, a: Api, profiles: List<Profile>, act: Int, open: (String) -> Unit, resume: (Item) -> Unit, pick: (Int) -> Unit,
         add: () -> Unit, remove: () -> Unit, imported: () -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    var ext by remember { mutableStateOf(st.external) }
    var par by remember { mutableStateOf(st.parental) }
    var flow by remember { mutableStateOf(0) }   // 1 PIN para desactivar, 2 PIN actual, 3 nuevo PIN
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val expL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) runCatching { ctx.contentResolver.openOutputStream(u)?.use { it.write(st.export().toByteArray()) } }
    }
    val impL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null && runCatching { ctx.contentResolver.openInputStream(u)?.use { st.import(it.readBytes().decodeToString()) } }.isSuccess) imported()
    }
    val rec = remember { st.recent(12).filter { !(st.parental && (ADULT.containsMatchIn(it.name) || it.cat in a.adultIds(it.kind))) } }
    if (flow != 0) key(flow) {
        PinDialog(when (flow) { 1 -> "PIN para desactivar"; 2 -> "PIN actual"; else -> "Nuevo PIN (mínimo 4 dígitos)" }, { flow = 0 }) { p ->
            when (flow) {
                1 -> if (st.checkPin(p)) { st.parental = false; par = false; flow = 0; true } else false
                2 -> if (st.checkPin(p)) { flow = 3; true } else false
                else -> if (p.length >= 4) { st.setPin(p); flow = 0; true } else false
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("MAJANO IPTV  ·  ${profiles.getOrNull(act)?.name ?: ""}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Tile("Live TV", listOf(Color(0xFF00E5A0), Color(0xFF2B2BFF)), Modifier.weight(1.3f).focusRequester(fr)) { open("live") }
            Tile("Películas", listOf(Color(0xFFFF4F8B), Color(0xFFFF9A3D)), Modifier.weight(1f)) { open("movie") }
            Tile("Series", listOf(Color(0xFFB04BFF), Color(0xFFFF5FA8)), Modifier.weight(1f)) { open("series") }
        }
        if (rec.isNotEmpty()) {
            Text("Continuar viendo", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rec) { x -> Box(Modifier.width(130.dp)) { Card(x, false, st.progressOf(x), 150.dp) { resume(x) } } }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Reproductor: " + if (ext) "Externo" else "Interno") { ext = !ext; st.external = ext }
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
fun Section(kind: String, api: Api, st: Store, open: (Item) -> Unit, play: (List<PI>, Int) -> Unit) {
    var refresh by remember { mutableStateOf(0) }
    val data by produceState<Pair<Map<String, String>, List<Item>>?>(null, refresh) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { api.catalog(kind, refresh > 0) }.getOrNull() ?: (emptyMap<String, String>() to emptyList()) }
    }
    var tab by remember { mutableStateOf(0) }
    var cat by remember { mutableStateOf<String?>(null) }
    var fv by remember { mutableStateOf(0) }
    var hv by remember { mutableStateOf(0) }
    var q by remember { mutableStateOf("") }
    val d = data
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (kind) { "live" -> "Live TV"; "movie" -> "Películas"; else -> "Series" }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            listOf("Todos", "⭐ Favoritos", "🕘 Vistos").forEachIndexed { i, t -> Chip(t, sel = tab == i) { tab = i } }
            OutlinedTextField(q, { q = it }, Modifier.width(200.dp), placeholder = { Text("Buscar…") }, singleLine = true)
            Chip("↻ Catálogo") { refresh++ }
        }
        Text("OK sostenido: favorito (elemento) u ocultar (categoría)", fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (d == null) { Text("Cargando…", Modifier.padding(20.dp)); return@Column }
        val hidden = remember(hv) { st.hidden(kind) }
        val blocked = remember(d, hv) { hidden + if (st.parental) api.adultIds(kind) else emptySet() }
        if (tab == 0) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            item { Chip("Todas", sel = cat == null) { cat = null } }
            items(d.first.entries.filter { it.key !in blocked }) { e ->
                Chip(e.value.ifEmpty { "Sin grupo" }, sel = cat == e.key, onLong = { st.toggleHidden(kind, e.key); if (cat == e.key) cat = null; hv++ }) { cat = e.key }
            }
            if (hidden.isNotEmpty()) item { Chip("Mostrar ocultas (${hidden.size})") { st.clearHidden(kind); hv++ } }
        }
        val list = remember(d, tab, cat, fv, q, hv) {
            val base = when (tab) { 1 -> st.favs(kind); 2 -> st.history(kind); else -> d.second }
            base.filter { it.cat !in blocked && (tab != 0 || cat == null || it.cat == cat) && (q.isBlank() || it.name.contains(q, true)) }
        }
        if (list.isEmpty()) Text("Nada por aquí todavía", Modifier.padding(20.dp))
        val tick = fv
        LazyVerticalGrid(GridCells.Adaptive(if (kind == "live") 190.dp else 150.dp), Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(list) { i, x ->
                Card(x, tick >= 0 && st.isFav(x), if (tab == 2) st.progressOf(x) else 0f, onLong = { st.toggleFav(x); fv++ }) {
                    if (kind == "live") play(list.map { PI(it.name, api.playUrl(it), "live:${it.id}", true, it) }, i) else open(x)
                }
            }
        }
    }
}

@Composable
fun Detail(item: Item, api: Api, st: Store, play: (List<PI>, Int) -> Unit) {
    val isS = item.kind == "series"
    val info by produceState<Pair<String, List<Ep>>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { if (isS) api.series(item.id) else api.vodPlot(item.id) to emptyList<Ep>() }.getOrNull() ?: (item.plot to emptyList())
        }
    }
    val last = st.last(item)
    val lastEp = last?.optString("ep") ?: ""
    var season by remember { mutableStateOf(last?.optInt("season", -1) ?: -1) }
    var fav by remember { mutableStateOf(st.isFav(item)) }
    var sv by remember { mutableStateOf(0) }
    val eps = info?.second ?: emptyList()
    val seasons = eps.map { it.season }.distinct()
    val sel = if (season in seasons) season else seasons.firstOrNull() ?: 0
    val shown = eps.filter { it.season == sel }
    val fr = remember { FocusRequester() }
    val ls = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    LaunchedEffect(sel, eps.size) { val k = shown.indexOfFirst { it.id == lastEp }; if (k > 0) ls.scrollToItem(k) }
    fun queue() = eps.map { PI("${item.name} T${it.season}E${it.num} ${it.title}", api.url("series", it.id, it.ext), "ep:${it.id}", false, item, it.season, it.id) }
    val mkey = "movie:${item.id}"
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        AsyncImage(model = item.icon, contentDescription = null, modifier = Modifier.width(240.dp).height(340.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.name, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Text((info?.first ?: "").ifEmpty { "Sin sinopsis del proveedor" }, fontSize = 14.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isS) {
                    val li = eps.indexOfFirst { it.id == lastEp }
                    val ci = if (li >= 0 && st.seen("ep:$lastEp") && li < eps.lastIndex) li + 1 else li   // si ya terminó ese capítulo, sigue el siguiente
                    Chip(if (ci >= 0) "▶ Continuar" else "▶ Reproducir", m = Modifier.focusRequester(fr)) { if (eps.isNotEmpty()) play(queue(), maxOf(ci, 0)) }
                } else {
                    Chip("▶ Reproducir / Continuar", m = Modifier.focusRequester(fr)) { play(listOf(PI(item.name, api.playUrl(item), mkey, false, item)), 0) }
                    Chip(if (sv >= 0 && st.seen(mkey)) "✓ Visto (quitar)" else "Marcar visto") { st.setSeen(mkey, !st.seen(mkey)); sv++ }
                }
                Chip(if (fav) "⭐ Quitar favorito" else "☆ Favorito") { st.toggleFav(item); fav = !fav }
            }
            if (isS) {
                Text("OK sostenido en un capítulo: marcar visto / no visto", fontSize = 11.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(seasons) { n -> Chip("Temporada $n", sel = n == sel) { season = n } } }
                LazyColumn(Modifier.fillMaxHeight(), ls, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown) { e ->
                        val k = "ep:${e.id}"
                        val mark = if (sv < 0) "" else if (st.seen(k)) "  ✓" else if (st.pos(k) > 0) "  ◐" else ""
                        Column(Modifier.fillMaxWidth().tv(RoundedCornerShape(8.dp), { st.setSeen(k, !st.seen(k)); sv++ }) { play(queue(), eps.indexOf(e)) }
                            .background(Color(0xFF161C2D)).padding(10.dp)) {
                            Text("E${e.num}  ${e.title}$mark", maxLines = 1)
                            if (e.plot.isNotEmpty()) Text(e.plot, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
