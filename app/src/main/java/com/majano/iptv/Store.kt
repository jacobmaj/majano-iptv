package com.majano.iptv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Profile(val name: String, val url: String, val user: String, val pass: String)

// La contraseña del proveedor se guarda cifrada con una clave del Android Keystore
object Crypto {
    private const val A = "majano_pw"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(A, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(A, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    fun enc(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    fun dec(s: String): String = runCatching {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        String(c.doFinal(b, 12, b.size - 12))
    }.getOrDefault("")
}

class Store(c: Context) {
    private val sp = c.getSharedPreferences("majano", 0)
    var external: Boolean get() = sp.getBoolean("ext", false); set(v) { sp.edit().putBoolean("ext", v).apply() }
    var subs: Boolean get() = sp.getBoolean("subs", false); set(v) { sp.edit().putBoolean("subs", v).apply() }
    var parental: Boolean get() = sp.getBoolean("parental", true); set(v) { sp.edit().putBoolean("parental", v).apply() }
    var active: Int get() = sp.getInt("active", 0); set(v) { sp.edit().putInt("active", v).apply() }

    private fun obj(k: String) = JSONObject(sp.getString(k, "{}")!!)
    private fun put(k: String, o: JSONObject) = sp.edit().putString(k, o.toString()).apply()
    private fun key(i: Item) = "${i.kind}:${i.id}"

    // ---- proveedores (varios perfiles) ----
    private fun arr() = JSONArray(sp.getString("profiles", "[]")!!)
    fun profiles(): List<Profile> = arr().let { a -> (0 until a.length()).map { val o = a.getJSONObject(it); Profile(o.getString("n"), o.getString("u"), o.getString("us"), Crypto.dec(o.getString("p"))) } }
    val profile: Profile? get() = profiles().getOrNull(active)
    fun addProfile(url: String, user: String, pass: String) {
        val a = arr()
        val name = (runCatching { URI(if (url.startsWith("http")) url else "http://$url").host }.getOrNull() ?: url.take(20)) + if (user.isEmpty()) " (M3U)" else ""
        a.put(JSONObject().put("n", name).put("u", url).put("us", user).put("p", Crypto.enc(pass)))
        sp.edit().putString("profiles", a.toString()).apply(); active = a.length() - 1
    }
    fun removeActive() {
        val a = arr(); val n = JSONArray()
        for (i in 0 until a.length()) if (i != active) n.put(a.get(i))
        sp.edit().putString("profiles", n.toString()).apply(); active = 0
    }

    // ---- control parental ----
    private fun h(p: String) = MessageDigest.getInstance("SHA-256").digest(("majano$p").toByteArray()).joinToString("") { "%02x".format(it) }
    fun hasPin() = sp.contains("pin")
    fun setPin(p: String) { sp.edit().putString("pin", h(p)).apply() }
    fun checkPin(p: String) = sp.getString("pin", null) == h(p)
    fun hidden(kind: String): Set<String> = sp.getStringSet("hid_$kind", emptySet())!!.toSet()
    fun toggleHidden(kind: String, id: String) { val s = hidden(kind).toMutableSet(); if (!s.add(id)) s.remove(id); sp.edit().putStringSet("hid_$kind", s).apply() }
    fun clearHidden(kind: String) { sp.edit().remove("hid_$kind").apply() }

    // ---- favoritos / historial ----
    private fun toJ(i: Item) = JSONObject().put("id", i.id).put("name", i.name).put("icon", i.icon).put("cat", i.cat).put("kind", i.kind).put("ext", i.ext).put("plot", i.plot).put("url", i.url)
    private fun fromJ(o: JSONObject) = Item(o.getString("id"), o.getString("name"), o.getString("icon"), o.getString("cat"), o.getString("kind"), o.getString("ext"), o.getString("plot"), o.optString("url"))
    private fun list(k: String, kind: String): List<Item> {
        val o = obj(k)
        return o.keys().asSequence().map { o.getJSONObject(it) }.filter { it.getString("kind") == kind }.sortedByDescending { it.optLong("t") }.map { fromJ(it) }.toList()
    }
    fun isFav(i: Item) = obj("fav").has(key(i))
    fun toggleFav(i: Item) { val o = obj("fav"); if (o.has(key(i))) o.remove(key(i)) else o.put(key(i), toJ(i)); put("fav", o) }
    fun favs(kind: String) = list("fav", kind)
    fun history(kind: String) = list("hist", kind)
    fun recent(n: Int): List<Item> { val o = obj("hist"); return o.keys().asSequence().map { o.getJSONObject(it) }.sortedByDescending { it.optLong("t") }.take(n).map { fromJ(it) }.toList() }
    fun touch(i: Item, season: Int, ep: String) {
        val o = obj("hist"); o.put(key(i), toJ(i).put("t", System.currentTimeMillis()).put("season", season).put("ep", ep)); put("hist", o)
    }
    fun last(i: Item): JSONObject? = obj("hist").optJSONObject(key(i))

    // ---- posición, progreso y "visto" ----
    fun pos(k: String) = obj("pos").optJSONArray(k)?.optLong(0) ?: 0L
    fun savePos(k: String, ms: Long, dur: Long) { val o = obj("pos"); o.put(k, JSONArray().put(ms).put(dur)); put("pos", o) }
    fun progressOf(i: Item): Float {
        val k = when (i.kind) { "movie" -> "movie:${i.id}"; "series" -> "ep:" + (last(i)?.optString("ep") ?: ""); else -> return 0f }
        val a = obj("pos").optJSONArray(k) ?: return 0f
        val d = a.optLong(1)
        return if (d > 0) (a.optLong(0).toFloat() / d).coerceIn(0f, 1f) else 0f
    }
    fun seen(k: String) = sp.getStringSet("seen", emptySet())!!.contains(k)
    fun setSeen(k: String, b: Boolean) {
        val s = sp.getStringSet("seen", emptySet())!!.toMutableSet()
        if (if (b) s.add(k) else s.remove(k)) sp.edit().putStringSet("seen", s).apply()
    }

    // ---- respaldo (incluye la contraseña en claro: guarda el archivo con cuidado) ----
    fun export(): String {
        val o = JSONObject()
        for ((k, v) in sp.all) { when (v) { is Set<*> -> o.put(k, JSONArray(v.toList())); else -> o.put(k, v) } }
        val a = JSONArray(); profiles().forEach { a.put(JSONObject().put("n", it.name).put("u", it.url).put("us", it.user).put("p", it.pass)) }
        o.put("profiles", a.toString())
        return o.toString()
    }
    fun import(json: String) {
        val o = JSONObject(json); val e = sp.edit()
        o.keys().forEach { k ->
            val v = o.get(k)
            when {
                k == "profiles" -> {
                    val a = JSONArray(v.toString()); val out = JSONArray()
                    for (i in 0 until a.length()) { val x = a.getJSONObject(i); out.put(JSONObject().put("n", x.getString("n")).put("u", x.getString("u")).put("us", x.getString("us")).put("p", Crypto.enc(x.getString("p")))) }
                    e.putString(k, out.toString())
                }
                v is Boolean -> e.putBoolean(k, v)
                v is Int -> e.putInt(k, v)
                v is String -> e.putString(k, v)
                v is JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
            }
        }
        e.apply()
    }
}
