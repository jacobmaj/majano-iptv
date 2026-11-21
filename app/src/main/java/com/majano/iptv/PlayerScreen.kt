@file:OptIn(UnstableApi::class)

package com.majano.iptv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay

private val DIGITS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val MODES = listOf("Ajustar" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Llenar" to AspectRatioFrameLayout.RESIZE_MODE_FILL)
private val SPEEDS = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
private val SIZES = listOf(0.75f, 1f, 1.5f, 2f)

fun fmt(ms: Long): String {
    if (ms < 0) return "--:--"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun PlayerScreen(q: List<PI>, start: Int, st: Store, back: () -> Unit) {
    val ctx = LocalContext.current
    var idx by remember { mutableStateOf(start) }
    var last by remember { mutableStateOf(-1) }
    var attempt by remember { mutableStateOf(0) }
    val cur = q[idx]
    val idxS = rememberUpdatedState(idx)
    val attS = rememberUpdatedState(attempt)
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val low = am.isLowRamDevice || am.memoryClass <= 128   // buffers adaptados a la RAM
    val ex = remember {
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (low) 10_000 else 30_000, if (low) 30_000 else 90_000, 2_500, 5_000)
            .setTargetBufferBytes((if (low) 24 else 80) * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false).build()
        val rf = DefaultRenderersFactory(ctx).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER).setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, rf).setLoadControl(lc).setHandleAudioBecomingNoisy(true).setAudioAttributes(AudioAttributes.DEFAULT, true).build().also {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(3840, 2160).setForceHighestSupportedBitrate(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !st.subs).build()
        }
    }
    var show by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    var panel by remember { mutableStateOf(0) }   // 0 nada, 1 audio, 2 subtítulos, 3 canales
    var subs by remember { mutableStateOf(st.subs) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var buf by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf("") }
    var num by remember { mutableStateOf("") }
    var rm by remember { mutableStateOf(0) }
    var si by remember { mutableStateOf(0) }
    var ss by remember { mutableStateOf(1) }
    val fr = remember { FocusRequester() }
    val pf = remember { FocusRequester() }

    fun save(c: PI) {
        if (c.live || ex.currentPosition <= 0) return
        val d = ex.duration; val p = ex.currentPosition
        st.savePos(c.key, p, if (d > 0) d else 0)
        val done = d > 0 && p > d * 0.92
        if (st.seen(c.key) != done) st.setSeen(c.key, done)
    }
    fun jump(n: Int) { save(cur); last = idx; attempt = 0; idx = n }
    fun go(d: Int) {
        val n = if (cur.live) (idx + d + q.size) % q.size else idx + d
        if (n in q.indices) jump(n)
    }
    fun seek(ms: Long) {
        if (!cur.live) { ex.seekTo((ex.currentPosition + ms).coerceAtLeast(0)); msg = if (ms > 0) "⏩ ${ms / 1000}s" else "⏪ ${-ms / 1000}s" }
    }
    fun external(c: PI) {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(c.url), "video/*").putExtra("title", c.title), "Abrir con"))
    }

    DisposableEffect(ex) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buf = s == Player.STATE_BUFFERING
                if (s == Player.STATE_ENDED) {
                    val c = q[idxS.value]
                    if (c.live) { attempt = attS.value + 1; return }
                    st.setSeen(c.key, true); st.savePos(c.key, 0, 0)   // fin del capítulo -> siguiente automático
                    if (idxS.value < q.lastIndex) { attempt = 0; idx = idxS.value + 1 } else back()
                }
            }
            override fun onPlayWhenReadyChanged(p: Boolean, r: Int) { playing = p }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) {
                val c = q[idxS.value]
                if (c.live && attS.value < 4) { attempt = attS.value + 1; err = "" } // reconexión automática
                else err = "No se pudo reproducir (${e.errorCodeName}). Prueba otro canal o el reproductor externo."
            }
        }
        ex.addListener(l)
        onDispose {
            ex.removeListener(l)
            if (ex.playbackState != Player.STATE_ENDED) save(q[idxS.value])
            ex.release()
        }
    }
    LaunchedEffect(idx) {
        val c = q[idx]
        st.touch(c.item, c.season, c.epId)   // recuerda temporada/capítulo
        if (st.external) { external(c); back() }
    }
    LaunchedEffect(idx, attempt) {   // carga; en vivo reintenta y a la 3.ª prueba cambia a .ts
        if (st.external) return@LaunchedEffect
        val c = q[idx]
        if (attempt > 0) delay(1500) else err = ""
        val u = if (c.live && attempt >= 2) c.url.replace(".m3u8", ".ts") else c.url
        ex.setMediaItem(MediaItem.fromUri(u), if (c.live) 0L else st.pos(c.key))   // reanuda donde lo dejó
        ex.prepare(); ex.playWhenReady = true
        tick++
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val c = q[idxS.value]; val p = ex.currentPosition
            pos = if (c.live) "EN VIVO" else "${fmt(p)} / ${fmt(ex.duration)}"
            if (!c.live && p > 0 && (p / 1000) % 10 == 0L) save(c)
        }
    }
    LaunchedEffect(tick) { show = true; delay(5000); if (panel == 0 || panel == 3) show = false }
    LaunchedEffect(show) { delay(50); runCatching { if (show) pf.requestFocus() else fr.requestFocus() } }
    LaunchedEffect(msg) { if (msg.isNotEmpty()) { delay(900); msg = "" } }
    LaunchedEffect(num) { if (num.isNotEmpty()) { delay(1800); val n = num.toIntOrNull() ?: 0; num = ""; if (n in 1..q.size) jump(n - 1) } }
    BackHandler { if (panel != 0) panel = 0 else back() }

    Box(Modifier.fillMaxSize().background(Color.Black)
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val dg = DIGITS.indexOf(e.key)
            if (dg >= 0 && cur.live) { num += dg; true }   // salto por número de canal
            else when (e.key) {
                Key.MediaPlayPause -> { ex.playWhenReady = !ex.playWhenReady; tick++; true }
                Key.MediaPlay -> { ex.playWhenReady = true; true }
                Key.MediaPause -> { ex.playWhenReady = false; true }
                Key.MediaFastForward -> { seek(30_000); true }
                Key.MediaRewind -> { seek(-30_000); true }
                Key.MediaNext, Key.ChannelUp -> { go(1); true }
                Key.MediaPrevious, Key.ChannelDown -> { go(-1); true }
                Key.Menu -> { panel = if (panel == 0) 1 else 0; tick++; true }
                else -> if (panel == 3) false else if (show || panel != 0) { tick++; false } else when (e.key) {
                    Key.DirectionLeft -> { if (cur.live) panel = 3 else seek(-10_000); true }
                    Key.DirectionRight -> { if (cur.live) tick++ else seek(10_000); true }
                    Key.DirectionUp -> { if (cur.live) go(1) else tick++; true }
                    Key.DirectionDown -> { if (cur.live) go(-1) else tick++; true }
                    else -> { tick++; true }
                }
            }
        }.focusRequester(fr).focusable()) {
        AndroidView({ PlayerView(it).apply { player = ex; useController = false; keepScreenOn = true; isFocusable = false } }, Modifier.fillMaxSize(),
            update = { v -> v.resizeMode = MODES[rm].second; v.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SIZES[ss]) })
        if (buf) Text("Cargando…", Modifier.align(Alignment.Center))
        if (err.isNotEmpty()) Text(err, Modifier.align(Alignment.Center), color = Color(0xFFFF8080))
        if (num.isNotEmpty()) Text("Canal $num", Modifier.align(Alignment.TopEnd).padding(32.dp), fontSize = 36.sp)
        else if (msg.isNotEmpty()) Text(msg, Modifier.align(Alignment.TopCenter).padding(top = 40.dp), fontSize = 28.sp)
        if (show) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))).padding(28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(cur.title, fontSize = 20.sp, maxLines = 1)
            Text(pos + if (cur.live) "   ·   canal ${idx + 1}/${q.size}" else "", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("⏮ Anterior") { go(-1) }
                if (!cur.live) Chip("⏪ 10s") { seek(-10_000) }
                Chip(if (playing) "⏸ Pausa" else "▶ Play", m = Modifier.focusRequester(pf)) { ex.playWhenReady = !ex.playWhenReady }
                if (!cur.live) Chip("10s ⏩") { seek(10_000) }
                Chip("Siguiente ⏭") { go(1) }
                if (cur.live) Chip("↩ Último canal") { if (last in q.indices) jump(last) }
                if (cur.live) Chip("Canales") { panel = 3; show = false }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("Subtítulos", sel = panel == 2) { panel = if (panel == 2) 0 else 2; tick++ }
                Chip("Audio", sel = panel == 1) { panel = if (panel == 1) 0 else 1; tick++ }
                Chip("Imagen: ${MODES[rm].first}") { rm = (rm + 1) % MODES.size }
                if (!cur.live) Chip("Velocidad: ${SPEEDS[si]}x") { si = (si + 1) % SPEEDS.size; ex.setPlaybackSpeed(SPEEDS[si]) }
                Chip("Externo") { ex.playWhenReady = false; external(cur) }
            }
            if (panel == 1) {
                val gs = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (gs.isEmpty()) Text("Sin pistas de audio alternas", fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(gs) { i, g ->
                        val f = g.getTrackFormat(0)
                        val name = (if (g.isSelected) "● " else "") + (f.label ?: f.language ?: "Pista ${i + 1}") + " · " + (f.sampleMimeType?.substringAfter('/') ?: "") + (if (f.channelCount > 0) " ${f.channelCount}ch" else "")
                        Chip(name) { ex.trackSelectionParameters = ex.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build() }
                    }
                }
            }
            if (panel == 2) {
                val gs = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Chip(if (subs) "Subtítulos: Sí" else "Subtítulos: No") {
                            subs = !subs; st.subs = subs
                            ex.trackSelectionParameters = ex.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subs).build()
                        }
                    }
                    item { Chip("Tamaño: ${SIZES[ss]}x") { ss = (ss + 1) % SIZES.size } }
                    itemsIndexed(gs) { i, g ->
                        val f = g.getTrackFormat(0)
                        Chip((if (g.isSelected && subs) "● " else "") + (f.label ?: f.language ?: "Pista ${i + 1}")) {
                            subs = true; st.subs = true
                            ex.trackSelectionParameters = ex.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                        }
                    }
                }
                if (gs.isEmpty()) Text("Este contenido no trae subtítulos", fontSize = 13.sp)
            }
        }
        if (panel == 3) {
            val ls = rememberLazyListState()
            val cr = remember { FocusRequester() }
            LaunchedEffect(Unit) { ls.scrollToItem(maxOf(idx - 2, 0)); delay(150); runCatching { cr.requestFocus() } }
            LazyColumn(Modifier.align(Alignment.CenterEnd).width(320.dp).fillMaxHeight().background(Color(0xEE0B0F1A)).padding(12.dp), ls, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(q) { i, x ->
                    Box(Modifier.fillMaxWidth().then(if (i == idx) Modifier.focusRequester(cr) else Modifier)
                        .tv(RoundedCornerShape(8.dp)) { jump(i); panel = 0; tick++ }
                        .background(if (i == idx) Color(0xFF2E9E6A) else Color(0xFF2A3147)).padding(10.dp)) {
                        Text("${i + 1}. ${x.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
