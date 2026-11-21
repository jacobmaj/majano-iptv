package com.majano.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class Item(val id: String, val name: String, val icon: String, val cat: String, val kind: String, val ext: String = "mp4", val plot: String = "", val url: String = "")
data class Ep(val id: String, val title: String, val season: Int, val num: Int, val ext: String, val plot: String)

fun JSONObject.s(k: String) = if (isNull(k)) "" else optString(k)
val ADULT = Regex("(?i)xxx|adult|porn|18\\+|\\+18|erot|\\bsex\\b")
private val R_LOGO = Regex("tvg-logo=\"([^\"]*)\"")
private val R_GRP = Regex("group-title=\"([^\"]*)\"")

class Api(url: String, val u: String, val p: String, private val dir: File) {
    val m3u = u.isEmpty()   // sin usuario = lista M3U
    val base = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    private val mem = HashMap<String, Pair<Map<String, String>, List<Item>>>()
    private var all: List<Item>? = null

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 90000
        c.setRequestProperty("User-Agent", "IPTVSmartersPro")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun get(q: String) = http("$base/player_api.php?username=$u&password=$p$q")

    // Caché en disco: abre al instante; si tiene más de 6 h se refresca en segundo plano para la próxima vez
    private fun cached(name: String, load: () -> String): String {
        val f = File(dir, name)
        if (f.exists()) {
            if (System.currentTimeMillis() - f.lastModified() > 6 * 3600_000L) thread { runCatching { f.writeText(load()) } }
            return f.readText()
        }
        return load().also { dir.mkdirs(); f.writeText(it) }
    }

    fun auth(): Boolean =
        if (m3u) http(base).trimStart().startsWith("#EXTM3U")
        else JSONObject(get("")).optJSONObject("user_info")?.optInt("auth", 0) == 1

    fun catalog(kind: String, force: Boolean = false): Pair<Map<String, String>, List<Item>> {
        if (force) { mem.clear(); all = null; dir.listFiles()?.forEach { it.delete() } }
        return mem.getOrPut(kind) { if (m3u) m3uCat(kind) else cats(kind) to items(kind) }
    }
    fun adultIds(kind: String): Set<String> = mem[kind]?.first?.filter { ADULT.containsMatchIn(it.value) }?.keys ?: emptySet()

    private fun cats(kind: String): Map<String, String> {
        val a = JSONArray(cached("cats_$kind.json") { get("&action=" + when (kind) { "live" -> "get_live_categories"; "movie" -> "get_vod_categories"; else -> "get_series_categories" }) })
        return (0 until a.length()).associate { val o = a.getJSONObject(it); o.s("category_id") to o.s("category_name") }
    }

    private fun items(kind: String): List<Item> {
        val a = JSONArray(cached("items_$kind.json") { get("&action=" + when (kind) { "live" -> "get_live_streams"; "movie" -> "get_vod_streams"; else -> "get_series" }) })
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val s = kind == "series"
            Item(o.s(if (s) "series_id" else "stream_id"), o.s("name"), o.s(if (s) "cover" else "stream_icon"), o.s("category_id"), kind,
                o.s("container_extension").ifEmpty { "mp4" }, o.s("plot"))
        }
    }

    // ---- M3U (en lista M3U las series se tratan como películas) ----
    private fun m3uParse(): List<Item> {
        val out = ArrayList<Item>()
        var name = ""; var logo = ""; var grp = ""
        for (line in cached("m3u.txt") { http(base) }.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("#EXTINF")) {
                name = l.substringAfterLast(",").trim()
                logo = R_LOGO.find(l)?.groupValues?.get(1) ?: ""
                grp = R_GRP.find(l)?.groupValues?.get(1) ?: ""
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val kind = if ("/movie/" in l || "/series/" in l) "movie" else "live"
                out += Item("m" + l.hashCode(), name, logo, grp.ifEmpty { "Sin grupo" }, kind, "", "", l)
            }
        }
        return out
    }
    private fun m3uCat(kind: String): Pair<Map<String, String>, List<Item>> {
        val xs = (all ?: m3uParse().also { all = it }).filter { it.kind == kind }
        return xs.map { it.cat }.distinct().associateWith { it } to xs
    }

    fun series(id: String): Pair<String, List<Ep>> {
        val o = JSONObject(get("&action=get_series_info&series_id=$id"))
        val out = ArrayList<Ep>()
        fun add(s: Int, arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                out += Ep(e.s("id"), e.s("title"), s, e.optInt("episode_num", i + 1), e.s("container_extension").ifEmpty { "mp4" }, e.optJSONObject("info")?.s("plot") ?: "")
            }
        }
        val m = o.optJSONObject("episodes")
        if (m != null) m.keys().forEach { k -> add(k.toIntOrNull() ?: 0, m.getJSONArray(k)) }
        else o.optJSONArray("episodes")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { add(i + 1, it) } }
        return (o.optJSONObject("info")?.s("plot") ?: "") to out.sortedWith(compareBy({ it.season }, { it.num }))
    }

    fun vodPlot(id: String) = if (m3u) "" else runCatching { JSONObject(get("&action=get_vod_info&vod_id=$id")).optJSONObject("info")?.s("plot") }.getOrNull() ?: ""

    fun url(kind: String, id: String, ext: String) = when (kind) {
        "live" -> "$base/live/$u/$p/$id.m3u8"
        "movie" -> "$base/movie/$u/$p/$id.$ext"
        else -> "$base/series/$u/$p/$id.$ext"
    }
    fun playUrl(i: Item) = if (i.url.isNotEmpty()) i.url else url(i.kind, i.id, i.ext)
}
