package com.majano.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class Item(val id: String, val name: String, val icon: String, val cat: String, val kind: String, val ext: String = "mp4", val plot: String = "", val url: String = "", val arch: Boolean = false)
data class Ep(val id: String, val title: String, val season: Int, val num: Int, val ext: String, val plot: String)

data class Epg(val title: String, val desc: String, val start: Long, val end: Long, val startStr: String = "", val archive: Boolean = false)
data class Info(val plot: String = "", val year: String = "", val rating: String = "", val genre: String = "", val duration: String = "", val cast: String = "", val director: String = "") {
    fun line() = listOf(year, if (rating.isNotBlank() && rating != "0") "★ $rating" else "", genre, duration).filter { it.isNotBlank() }.joinToString("  ·  ")
}

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
                o.s("container_extension").ifEmpty { "mp4" }, o.s("plot"), "", kind == "live" && o.s("tv_archive") == "1")
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

    private fun infoOf(o: JSONObject?): Info {
        if (o == null) return Info()
        fun first(vararg ks: String) = ks.map { o.s(it) }.firstOrNull { it.isNotBlank() } ?: ""
        return Info(first("plot", "description"), first("releasedate", "releaseDate", "release_date", "year").take(4),
            first("rating", "rating_5based"), first("genre"), first("duration"), first("cast", "actors"), first("director"))
    }
    private fun b64(s: String) = runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) }.getOrDefault(s).ifBlank { s }

    // Guía de programación (EPG) del canal: ahora y siguientes
    fun epg(id: String): List<Epg> {
        if (m3u) return emptyList()
        val a = JSONObject(get("&action=get_short_epg&stream_id=$id&limit=4")).optJSONArray("epg_listings") ?: return emptyList()
        return (0 until a.length()).mapNotNull {
            val o = a.getJSONObject(it)
            val s = o.s("start_timestamp").toLongOrNull(); val e = o.s("stop_timestamp").toLongOrNull()
            if (s == null || e == null) null else Epg(b64(o.s("title")), b64(o.s("description")), s, e)
        }
    }

    private val epgCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Epg>>>()
    // EPG con caché de 5 min (para mostrar "ahora" en la lista de canales sin saturar al proveedor)
    fun epgCached(id: String): List<Epg> {
        val c = epgCache[id]; val now = System.currentTimeMillis()
        if (c != null && now - c.first < 300_000) return c.second
        val l = runCatching { epg(id) }.getOrDefault(emptyList()); epgCache[id] = now to l; return l
    }
    // Programación completa del canal (incluye lo ya emitido) para catch-up
    fun epgFull(id: String): List<Epg> {
        if (m3u) return emptyList()
        val a = JSONObject(get("&action=get_simple_data_table&stream_id=$id")).optJSONArray("epg_listings") ?: return emptyList()
        return (0 until a.length()).mapNotNull {
            val o = a.getJSONObject(it)
            val s = o.s("start_timestamp").toLongOrNull(); val e = o.s("stop_timestamp").toLongOrNull()
            if (s == null || e == null) null else Epg(b64(o.s("title")), b64(o.s("description")), s, e, o.s("start"), o.s("has_archive") == "1")
        }
    }
    fun catchupUrl(i: Item, e: Epg): String {
        val mins = ((e.end - e.start) / 60).coerceAtLeast(1)
        val s = e.startStr
        val ts = s.substring(0, 10) + ":" + s.substring(11, 13) + "-" + s.substring(14, 16)
        return "$base/timeshift/$u/$p/$mins/$ts/${i.id}.ts"
    }

    fun series(id: String): Pair<Info, List<Ep>> {
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
        return infoOf(o.optJSONObject("info")) to out.sortedWith(compareBy({ it.season }, { it.num }))
    }

    fun vodInfo(id: String): Info = if (m3u) Info() else runCatching { infoOf(JSONObject(get("&action=get_vod_info&vod_id=$id")).optJSONObject("info")) }.getOrNull() ?: Info()

    fun url(kind: String, id: String, ext: String) = when (kind) {
        "live" -> "$base/live/$u/$p/$id.m3u8"
        "movie" -> "$base/movie/$u/$p/$id.$ext"
        else -> "$base/series/$u/$p/$id.$ext"
    }
    fun playUrl(i: Item) = if (i.url.isNotEmpty()) i.url else url(i.kind, i.id, i.ext)
}
