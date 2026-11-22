package com.majano.iptv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.io.File

data class PI(val title: String, val url: String, val key: String, val live: Boolean, val item: Item, val season: Int = 0, val epId: String = "")

sealed class S {
    object Login : S(); object Home : S()
    class Browse(val kind: String) : S()
    class Play(val kind: String, val q: List<PI>, val i: Int) : S()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val st = Store(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Ui.acc, background = Ui.bg, surface = Ui.s1)) {
                Surface(Modifier.fillMaxSize(), color = Ui.bg) { App(st) }
            }
        }
    }
}

fun mk(ctx: Context, p: Profile) = Api(p.url, p.user, p.pass, File(ctx.cacheDir, "cat_" + (p.url + p.user).hashCode()))

@Composable
fun App(st: Store) {
    val ctx = LocalContext.current
    var api by remember { mutableStateOf(st.profile?.let { mk(ctx, it) }) }
    var s by remember { mutableStateOf<S>(if (api == null) S.Login else S.Home) }
    var ver by remember { mutableStateOf(0) }
    val a = api
    val states = remember(a) { HashMap<String, BrowseState>() }
    fun use() { val p = st.profile; api = p?.let { mk(ctx, it) }; s = if (p == null) S.Login else S.Home; ver++ }
    when (val c = s) {
        is S.Login -> Login(st, st.profiles().isNotEmpty(), { api = it; s = S.Home; ver++ }, { s = S.Home })
        is S.Home -> if (a != null) key(ver) {
            Home(st, a, st.profiles(), st.active, { s = S.Browse(it) },
                { x ->
                    if (x.kind == "live") s = S.Play("live", listOf(PI(x.name, a.playUrl(x), "live:${x.id}", true, x)), 0)
                    else { states.getOrPut(x.kind) { BrowseState() }.apply { sel = x; season = -1 }; s = S.Browse(x.kind) }
                },
                { i -> st.active = i; use() }, { s = S.Login }, { st.removeActive(); use() }, { use() })
        }
        is S.Browse, is S.Play -> if (a != null) {
            val kind = (c as? S.Browse)?.kind ?: (c as S.Play).kind
            val bs = states.getOrPut(kind) { BrowseState() }
            Box(Modifier.fillMaxSize()) {
                Browser(kind, bs, a, st, c is S.Browse, { s = S.Home }, { q, i -> s = S.Play(kind, q, i) })
                if (c is S.Play) PlayerScreen(c.q, c.i, st) { bs.returned++; bs.season = -1; s = S.Browse(kind) }
            }
        }
    }
}
