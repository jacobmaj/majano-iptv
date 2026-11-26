@file:OptIn(ExperimentalFoundationApi::class, UnstableApi::class)

package com.majano.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Estado que sobrevive al reproductor: al volver con "atrás" todo queda donde estaba
class BrowseState {
    var data by mutableStateOf<Pair<Map<String, String>, List<Item>>?>(null)
    var cat by mutableStateOf<String?>(null)     // null = Todos, "#fav" = favoritos, "#seen" = vistos
    var sel by mutableStateOf<Item?>(null)
    var q by mutableStateOf("")
    var season by mutableStateOf(-1)
    var detail by mutableStateOf(false)          // solo celular: pantalla de detalle
    var fv by mutableStateOf(0)
    var hv by mutableStateOf(0)
    var returned by mutableStateOf(0)
    val list = LazyListState()
    val grid = LazyGridState()
    val cats = LazyListState()
}

class BCtx(val kind: String, val bs: BrowseState, val api: Api, val st: Store, val items: List<Item>,
           val d: Pair<Map<String, String>, List<Item>>?, val blocked: Set<String>, val counts: Map<String, Int>)

@Composable
fun Browser(kind: String, bs: BrowseState, api: Api, st: Store, active: Boolean, back: () -> Unit, play: (List<PI>, Int) -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        if (bs.data == null || refresh > 0) {
            bs.data = null
            bs.data = withContext(Dispatchers.IO) { runCatching { api.catalog(kind, refresh > 0) }.getOrNull() ?: (emptyMap<String, String>() to emptyList()) }
        }
    }
    val d = bs.data
    val hidden = remember(bs.hv) { st.hidden(kind) }
    val hiddenI = remember(bs.hv) { st.hiddenItems(kind) }
    val blocked = remember(d, bs.hv) { hidden + (if (st.parental) api.adultIds(kind) else emptySet()) }
    val items = remember(d, bs.cat, bs.q, bs.fv, blocked, hiddenI) {
        if (d == null) emptyList() else {
            val base = when (bs.cat) { "#fav" -> st.favs(kind); "#seen" -> st.history(kind); null -> d.second; else -> d.second.filter { it.cat == bs.cat } }
            base.filter { it.cat !in blocked && it.id !in hiddenI && (bs.q.isBlank() || it.name.contains(bs.q, true)) }
        }
    }
    val counts = remember(d) { d?.second?.groupingBy { it.cat }?.eachCount() ?: emptyMap() }
    val b = BCtx(kind, bs, api, st, items, d, blocked, counts)
    var menu by remember { mutableStateOf<Item?>(null) }
    var catMenu by remember { mutableStateOf<String?>(null) }
    var catchup by remember { mutableStateOf<Item?>(null) }
    var renaming by remember { mutableStateOf<Item?>(null) }
    val selFr = remember { FocusRequester() }
    val epFr = remember { FocusRequester() }
    val catFr = remember { FocusRequester() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ui.bg)) {
        val compact = maxWidth < 600.dp
        val leftW = if (maxWidth < 800.dp) 170.dp else 230.dp
        val rightW = if (maxWidth < 800.dp) 270.dp else 390.dp
        BackHandler(enabled = active) { if (compact && bs.detail) bs.detail = false else back() }

        // Vista previa (solo Live en pantallas anchas)
        val pv = remember(kind, active, compact) { if (kind == "live" && active && !compact) newPlayer(ctx, st, true) else null }
        DisposableEffect(pv) { onDispose { pv?.release() } }
        LaunchedEffect(bs.sel, pv) {
            val p = pv ?: return@LaunchedEffect
            val x = bs.sel ?: return@LaunchedEffect
            delay(400)
            p.setMediaItem(MediaItem.fromUri(api.playUrl(x))); p.prepare(); p.playWhenReady = true
        }

        fun playLive(x: Item) = play(items.map { PI(it.name, api.playUrl(it), "live:${it.id}", true, it) }, items.indexOfFirst { it.id == x.id }.coerceAtLeast(0))
        fun playMovie(x: Item) = play(listOf(PI(x.name, api.playUrl(x), "movie:${x.id}", false, x)), 0)
        fun pick(x: Item) {
            if (kind == "live" && bs.sel?.id == x.id) { playLive(x); return }   // segundo OK sobre el mismo canal = pantalla completa
            bs.sel = x; bs.season = -1
            if (compact) { if (kind == "live") playLive(x) else bs.detail = true }
        }

        // Al volver del reproductor: mismo elemento seleccionado, misma posición, mismo foco
        LaunchedEffect(active) {
            if (!active) return@LaunchedEffect
            delay(250)
            val i = items.indexOfFirst { it.id == bs.sel?.id }
            if (i >= 0) runCatching { if (kind == "live") bs.list.scrollToItem(i) else bs.grid.scrollToItem(i) }
            delay(120)
            runCatching {
                if (bs.sel == null) catFr.requestFocus()
                else if (kind == "series" && bs.returned > 0 && !compact) epFr.requestFocus()
                else selFr.requestFocus()
            }
        }

        val sel = bs.sel
        if (compact && bs.detail && sel != null) {
            Column(Modifier.fillMaxSize()) {
                Chip("‹ Volver", m = Modifier.padding(12.dp)) { bs.detail = false }
                DetailPane(b, pv, epFr, play, { playLive(it) }, { catchup = it }, Modifier.weight(1f).fillMaxWidth())
            }
        } else if (compact) {
            Column(Modifier.fillMaxSize()) {
                TopBar(b) { refresh++ }
                LazyRow(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), bs.cats, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("Todos", sel = bs.cat == null) { bs.cat = null } }
                    item { Chip("★ Favoritos", sel = bs.cat == "#fav") { bs.cat = "#fav" } }
                    item { Chip("↺ Vistos", sel = bs.cat == "#seen") { bs.cat = "#seen" } }
                    if (d != null) items(d.first.entries.filter { it.key !in blocked }.toList()) { e ->
                        Chip(e.value.ifEmpty { "Sin grupo" }, sel = bs.cat == e.key, onLong = { catMenu = e.key }) { bs.cat = e.key }
                    }
                }
                ItemList(b, selFr, { pick(it) }, { menu = it }, Modifier.weight(1f).fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                CatPane(b, catFr, { catMenu = it }, Modifier.width(leftW).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(b) { refresh++ }
                    ItemList(b, selFr, { pick(it) }, { menu = it }, Modifier.weight(1f).fillMaxWidth())
                }
                DetailPane(b, pv, epFr, play, { playLive(it) }, { catchup = it }, Modifier.width(rightW).fillMaxHeight())
            }
        }

        // OK sostenido: Reproducir / Favoritos / Renombrar / Ocultar / Catch-up
        menu?.let { x ->
            val fav = st.isFav(x)
            val acts = ArrayList<Pair<String, () -> Unit>>()
            acts += act(when (kind) { "live" -> "▶ Reproducir"; "movie" -> "▶ Reproducir película"; else -> "▶ Ver temporadas y capítulos" }) {
                bs.sel = x
                if (kind == "live") playLive(x)
                else if (kind == "movie") playMovie(x)
                else { bs.season = -1; if (compact) bs.detail = true }
                Unit
            }
            acts += act(if (fav) "★ Quitar de favoritos" else "☆ Agregar a favoritos") { st.toggleFav(x); bs.fv++ }
            if (bs.cat == "#fav" && fav) {
                acts += act("▲ Subir en favoritos") { st.moveFav(x, -1); bs.fv++ }
                acts += act("▼ Bajar en favoritos") { st.moveFav(x, 1); bs.fv++ }
            }
            if (kind == "live" && x.arch) acts += act("⏪ Catch-up (programas pasados)") { bs.sel = x; catchup = x }
            acts += act("✎ Renombrar") { renaming = x }
            acts += act("Ocultar este elemento") { st.toggleHiddenItem(kind, x.id); if (bs.sel?.id == x.id) bs.sel = null; bs.hv++ }
            ActionDialog(st.nameOf(x), acts) { menu = null }
        }
        renaming?.let { x -> TextDialog("Renombrar", st.nameOf(x), { renaming = null }) { n -> st.rename(x, n); bs.fv++ } }
        catchup?.let { x ->
            CatchupDialog(api, x, { catchup = null }) { e ->
                play(listOf(PI("${x.name} · ${e.title}", api.catchupUrl(x, e), "cu:${x.id}:${e.start}", false, x)), 0)
            }
        }
        catMenu?.let { id ->
            ActionDialog(d?.first?.get(id) ?: "Categoría", listOf(act("Ocultar esta categoría") { st.toggleHidden(kind, id); if (bs.cat == id) bs.cat = null; bs.hv++ })) { catMenu = null }
        }
    }
}

@Composable
fun TopBar(b: BCtx, onRefresh: () -> Unit) {
    val bs = b.bs
    val voice = rememberVoice { bs.q = it }
    val narrow = LocalConfiguration.current.screenWidthDp < 600
    val name = when (bs.cat) { null -> "Todos"; "#fav" -> "Favoritos"; "#seen" -> "Vistos"; else -> b.d?.first?.get(bs.cat) ?: "" }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name.ifEmpty { "Sin grupo" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${b.items.size} elementos", fontSize = 11.sp, color = Ui.dim)
        }
        OutlinedTextField(bs.q, { bs.q = it }, Modifier.width(if (narrow) 110.dp else 170.dp), placeholder = { Text("Buscar…", fontSize = 13.sp) }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp))
        Chip("Voz", onClick = voice)
        Chip("↻", onClick = onRefresh)
    }
}

@Composable
fun CatPane(b: BCtx, catFr: FocusRequester, onLong: (String) -> Unit, m: Modifier) {
    val bs = b.bs
    val d = b.d
    Column(m.background(Ui.s1).padding(vertical = 12.dp)) {
        Text(when (b.kind) { "live" -> "Live TV"; "movie" -> "Películas"; else -> "Series" }, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(state = bs.cats, contentPadding = PaddingValues(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { CatRow("Todos", d?.second?.size, bs.cat == null, Modifier.focusRequester(catFr), null) { bs.cat = null } }
            item { CatRow("★ Favoritos", null, bs.cat == "#fav", Modifier, null) { bs.cat = "#fav" } }
            item { CatRow("↺ Vistos", null, bs.cat == "#seen", Modifier, null) { bs.cat = "#seen" } }
            if (d == null) item { Text("Cargando…", color = Ui.dim, modifier = Modifier.padding(16.dp)) }
            else {
                items(d.first.entries.filter { it.key !in b.blocked }.toList()) { e ->
                    CatRow(e.value.ifEmpty { "Sin grupo" }, b.counts[e.key], bs.cat == e.key, Modifier, { onLong(e.key) }) { bs.cat = e.key }
                }
                val h = b.st.hidden(b.kind)
                if (h.isNotEmpty()) item { CatRow("Mostrar ocultas (${h.size})", null, false, Modifier, null) { b.st.clearHidden(b.kind); bs.hv++ } }
            }
        }
    }
}

@Composable
fun CatRow(label: String, count: Int?, sel: Boolean, m: Modifier, onLong: (() -> Unit)?, onClick: () -> Unit) {
    Row(m.fillMaxWidth().tv(RoundedCornerShape(10.dp), onLong, onClick).background(if (sel) Ui.s3 else Color.Transparent).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(16.dp).background(if (sel) Ui.acc else Color.Transparent))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            color = if (sel) Ui.txt else Ui.dim, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
        if (count != null) Text("$count", fontSize = 11.sp, color = Ui.dim)
    }
}

@Composable
fun ItemList(b: BCtx, selFr: FocusRequester, onPick: (Item) -> Unit, onMenu: (Item) -> Unit, m: Modifier) {
    val bs = b.bs
    val tick = bs.fv
    if (b.d == null) { Box(m, contentAlignment = Alignment.Center) { Text("Cargando…", color = Ui.dim) }; return }
    if (b.items.isEmpty()) { Box(m, contentAlignment = Alignment.Center) { Text("Sin resultados", color = Ui.dim) }; return }
    if (b.kind == "live") {
        LazyColumn(m, state = bs.list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(b.items) { i, x ->
                val s = bs.sel?.id == x.id
                ChannelRow(i + 1, x, s, tick >= 0 && b.st.isFav(x), if (s) Modifier.focusRequester(selFr) else Modifier, { onMenu(x) }, if (tick >= 0) b.st.nameOf(x) else x.name, b.api) { onPick(x) }
            }
        }
    } else {
        LazyVerticalGrid(GridCells.Adaptive((112 * Ui.textScale).dp), m, state = bs.grid, contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(b.items) { x ->
                val s = bs.sel?.id == x.id
                PosterCard(x, s, tick >= 0 && b.st.isFav(x), if (bs.cat == "#seen") b.st.progressOf(x) else 0f,
                    if (s) Modifier.focusRequester(selFr) else Modifier, { onMenu(x) }, if (tick >= 0) b.st.nameOf(x) else x.name) { onPick(x) }
            }
        }
    }
}

@Composable
fun ChannelRow(n: Int, x: Item, sel: Boolean, fav: Boolean, m: Modifier, onLong: () -> Unit, name: String, api: Api, onClick: () -> Unit) {
    // "ahora" de la guía, solo para filas visibles y con un pequeño retraso para no saturar al proveedor al desplazarse
    val nowT by produceState("", x.id) {
        delay(300)
        val t = System.currentTimeMillis() / 1000
        value = withContext(Dispatchers.IO) { api.epgCached(x.id).firstOrNull { t in it.start until it.end }?.title ?: "" }
    }
    Row(m.fillMaxWidth().tv(RoundedCornerShape(12.dp), onLong, onClick).background(if (sel) Ui.s3 else Ui.s1).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$n", fontSize = 12.sp, color = Ui.dim, modifier = Modifier.width(30.dp))
        Logo(x, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(Ui.s2).padding(3.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (nowT.isNotEmpty()) Text(nowT, fontSize = 11.sp, color = Ui.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (x.arch) Text("⏪", fontSize = 12.sp, color = Ui.dim)
        if (fav) Text("★", color = Ui.acc)
    }
}

@Composable
fun PosterCard(x: Item, sel: Boolean, fav: Boolean, prog: Float, m: Modifier, onLong: () -> Unit, name: String = x.name, onClick: () -> Unit) {
    Column(m.tv(RoundedCornerShape(12.dp), onLong, onClick).background(if (sel) Ui.s3 else Ui.s1)) {
        Box {
            Logo(x, Modifier.fillMaxWidth().aspectRatio(0.68f), ContentScale.Crop)
            if (fav) Text("★", color = Ui.acc, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (prog > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(prog).fillMaxHeight().background(Ui.acc))
            }
        }
        Text(name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
    }
}

@Composable
fun DetailPane(b: BCtx, pv: ExoPlayer?, epFr: FocusRequester, play: (List<PI>, Int) -> Unit, playLive: (Item) -> Unit, onCatchup: (Item) -> Unit, m: Modifier) {
    val x = b.bs.sel
    Box(m.background(Ui.s1)) {
        // Fondo difuminado con la carátula (estilo streaming)
        if (x != null && b.kind != "live" && x.icon.isNotBlank()) {
            AsyncImage(model = x.icon, contentDescription = null, modifier = Modifier.fillMaxSize().blur(24.dp), contentScale = ContentScale.Crop, alpha = 0.30f)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33000000), Ui.s1))))
        }
        Column(Modifier.fillMaxSize()) {
            if (x == null) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(when (b.kind) { "live" -> "Elige un canal para ver la vista previa"; "movie" -> "Elige una película"; else -> "Elige una serie" }, color = Ui.dim)
                }
            } else when (b.kind) {
                "live" -> LivePane(b, x, pv, onCatchup) { playLive(x) }
                "movie" -> MoviePane(b, x, play)
                else -> SeriesPane(b, x, epFr, play)
            }
        }
    }
}

@Composable
fun LivePane(b: BCtx, x: Item, pv: ExoPlayer?, onCatchup: (Item) -> Unit, onFull: () -> Unit) {
    val fav = b.bs.fv >= 0 && b.st.isFav(x)
    val epg by produceState<List<Epg>>(emptyList(), x.id) { value = withContext(Dispatchers.IO) { runCatching { b.api.epg(x.id) }.getOrDefault(emptyList()) } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            if (pv != null) AndroidView({ c -> PlayerView(c).apply { useController = false; isFocusable = false; isFocusableInTouchMode = false } }, Modifier.fillMaxSize(), update = { it.player = pv })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Logo(x, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Ui.s2))
            Text(b.st.nameOf(x), fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val now = System.currentTimeMillis() / 1000
        val ci = epg.indexOfFirst { now in it.start until it.end }
        if (ci >= 0) {
            val e = epg[ci]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ahora: ${e.title}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(4.dp).background(Ui.s3)) {
                    Box(Modifier.fillMaxWidth(((now - e.start).toFloat() / (e.end - e.start).coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxHeight().background(Ui.acc))
                }
                Text("${hhmm(e.start)} – ${hhmm(e.end)}" + (epg.getOrNull(ci + 1)?.let { "   ·   Luego: ${it.title}" } ?: ""), fontSize = 11.sp, color = Ui.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryBtn("▶ Pantalla completa", onClick = onFull)
            Chip(if (fav) "★ Favorito" else "☆ Favorito") { b.st.toggleFav(x); b.bs.fv++ }
            if (x.arch) Chip("⏪ Catch-up") { onCatchup(x) }
        }
        Text("Mantén OK sobre un canal para ver más opciones", fontSize = 11.sp, color = Ui.dim)
    }
}

@Composable
fun MoviePane(b: BCtx, x: Item, play: (List<PI>, Int) -> Unit) {
    val st = b.st
    val key = "movie:${x.id}"
    val info by produceState(Info(plot = x.plot), x.id) { value = withContext(Dispatchers.IO) { b.api.vodInfo(x.id) }.let { if (it.plot.isEmpty()) it.copy(plot = x.plot) else it } }
    var sv by remember(x.id) { mutableStateOf(0) }
    val fav = b.bs.fv >= 0 && st.isFav(x)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Logo(x, Modifier.width(110.dp).height(160.dp).clip(RoundedCornerShape(12.dp)), ContentScale.Crop)
            Text(x.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (info.line().isNotEmpty()) Text(info.line(), fontSize = 12.sp, color = Ui.acc)
        Text(info.plot.ifEmpty { "Sin sinopsis del proveedor" }, fontSize = 13.sp, color = Ui.dim)
        if (info.cast.isNotEmpty()) Text("Reparto: ${info.cast}", fontSize = 12.sp, color = Ui.dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (info.director.isNotEmpty()) Text("Director: ${info.director}", fontSize = 12.sp, color = Ui.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryBtn(if (st.pos(key) > 0) "▶ Continuar" else "▶ Reproducir") { play(listOf(PI(x.name, b.api.playUrl(x), key, false, x)), 0) }
            Chip(if (fav) "★ Favorito" else "☆ Favorito") { st.toggleFav(x); b.bs.fv++ }
            Chip(if (sv >= 0 && st.seen(key)) "✓ Visto" else "Marcar visto") { st.setSeen(key, !st.seen(key)); sv++ }
        }
    }
}

@Composable
fun SeriesPane(b: BCtx, x: Item, epFr: FocusRequester, play: (List<PI>, Int) -> Unit) {
    val api = b.api; val st = b.st; val bs = b.bs
    val info by produceState<Pair<Info, List<Ep>>?>(null, x.id) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { api.series(x.id) }.getOrNull() ?: (Info(plot = x.plot) to emptyList<Ep>()) }
    }
    val rt = bs.returned
    val last = remember(x.id, rt) { st.last(x) }
    val lastEp = last?.optString("ep") ?: ""
    val eps = info?.second ?: emptyList()
    val seasons = eps.map { it.season }.distinct()
    val cur = if (bs.season in seasons) bs.season else (last?.optInt("season", -1)?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 0)
    var sd by remember(x.id) { mutableStateOf(0) }
    var seasonDlg by remember { mutableStateOf(false) }
    var epMenu by remember { mutableStateOf<Ep?>(null) }
    val shown = eps.filter { it.season == cur }
    val ls = rememberLazyListState()
    val fav = bs.fv >= 0 && st.isFav(x)
    LaunchedEffect(cur, eps.size, rt) { val k = shown.indexOfFirst { it.id == lastEp }; if (k > 0) ls.scrollToItem(k) }
    fun queue() = eps.map { PI("${x.name} T${it.season}E${it.num} ${it.title}", api.url("series", it.id, it.ext), "ep:${it.id}", false, x, it.season, it.id) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Logo(x, Modifier.width(80.dp).height(116.dp).clip(RoundedCornerShape(10.dp)), ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(x.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val si = info?.first
                if (si != null && si.line().isNotEmpty()) Text(si.line(), fontSize = 11.sp, color = Ui.acc, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((si?.plot ?: x.plot).ifEmpty { "Sin sinopsis del proveedor" }, fontSize = 12.sp, color = Ui.dim, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (si != null && si.cast.isNotEmpty()) Text("Reparto: ${si.cast}", fontSize = 11.sp, color = Ui.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val li = eps.indexOfFirst { it.id == lastEp }
            val ci = if (li >= 0 && st.seen("ep:$lastEp") && li < eps.lastIndex) li + 1 else li   // si ya terminó ese capítulo, sigue el siguiente
            PrimaryBtn(if (ci >= 0) "▶ Continuar" else "▶ Reproducir") { if (eps.isNotEmpty()) play(queue(), maxOf(ci, 0)) }
            Chip(if (fav) "★ Favorito" else "☆ Favorito") { st.toggleFav(x); bs.fv++ }
        }
        if (info == null) Text("Cargando capítulos…", color = Ui.dim)
        else if (eps.isEmpty()) Text("Esta serie no trae capítulos", color = Ui.dim)
        else {
            Chip("Temporada $cur   ▾", sel = false) { seasonDlg = true }
            LazyColumn(state = ls, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown) { e ->
                    val k = "ep:${e.id}"
                    val mark = if (sd < 0) "" else if (st.seen(k)) "✓" else if (st.pos(k) > 0) "◐" else ""
                    EpisodeRow(e, mark, if (e.id == lastEp) Modifier.focusRequester(epFr) else Modifier, { epMenu = e }) { play(queue(), eps.indexOf(e)) }
                }
            }
        }
    }
    if (seasonDlg) ActionDialog("Elegir temporada", seasons.map { n -> act((if (n == cur) "● " else "") + "Temporada $n") { bs.season = n } }) { seasonDlg = false }
    epMenu?.let { e ->
        val k = "ep:${e.id}"
        ActionDialog("T${e.season}E${e.num}  ${e.title}", listOf(
            act("▶ Reproducir") { play(queue(), eps.indexOf(e)) },
            act(if (st.seen(k)) "Marcar como no visto" else "Marcar como visto") { st.setSeen(k, !st.seen(k)); sd++ }
        )) { epMenu = null }
    }
}

@Composable
fun EpisodeRow(e: Ep, mark: String, m: Modifier, onLong: () -> Unit, onClick: () -> Unit) {
    Row(m.fillMaxWidth().tv(RoundedCornerShape(10.dp), onLong, onClick).background(Ui.s2).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("E${e.num}", fontSize = 12.sp, color = Ui.dim, modifier = Modifier.width(38.dp))
        Text(e.title.ifEmpty { "Capítulo ${e.num}" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(mark, color = Ui.acc)
    }
}

@Composable
fun CatchupDialog(api: Api, x: Item, close: () -> Unit, pick: (Epg) -> Unit) {
    val list by produceState<List<Epg>?>(null, x.id) { value = withContext(Dispatchers.IO) { runCatching { api.epgFull(x.id) }.getOrDefault(emptyList()) } }
    val now = System.currentTimeMillis() / 1000
    val past = remember(list) { (list ?: emptyList()).filter { it.archive && it.end < now }.sortedByDescending { it.start } }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = close) {
        LaunchedEffect(past.size) { delay(120); runCatching { fr.requestFocus() } }
        Column(Modifier.clip(RoundedCornerShape(20.dp)).background(Ui.s1).padding(18.dp).widthIn(min = 300.dp, max = 460.dp).heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Catch-up · ${x.name}", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                list == null -> Text("Cargando programas…", color = Ui.dim)
                past.isEmpty() -> Text("Este canal no tiene programas grabados disponibles", color = Ui.dim)
                else -> LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(past) { i, e ->
                        Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(fr) else Modifier).tv(RoundedCornerShape(10.dp)) { close(); pick(e) }.background(Ui.s2).padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(dayHhmm(e.start), fontSize = 12.sp, color = Ui.dim, modifier = Modifier.width(92.dp))
                            Text(e.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Chip("Cerrar", m = Modifier.fillMaxWidth(), onClick = close)
        }
    }
}
