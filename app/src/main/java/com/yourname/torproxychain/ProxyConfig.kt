package com.yourname.torproxychain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ProxyType { SOCKS5, HTTP, HTTPS }

/**
 * One hop in the chain. Order in the list = order the connection travels.
 *
 * A hop with isTor=true is Orbot's local SOCKS port on THIS phone (127.0.0.1:9050). Because
 * Tor runs on the phone, it can only be the FIRST hop: phone -> Tor -> proxy 1 -> proxy 2 -> destination.
 * (A remote proxy cannot connect to "127.0.0.1:9050" on your phone.) [ProxyStore.normalize]
 * enforces this ordering.
 */
data class ProxyHop(
    val label: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val isTor: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        put("type", type.name)
        put("host", host)
        put("port", port)
        put("username", username ?: "")
        put("password", password ?: "")
        put("isTor", isTor)
    }

    companion object {
        fun fromJson(o: JSONObject): ProxyHop = ProxyHop(
            label = o.getString("label"),
            type = ProxyType.valueOf(o.getString("type")),
            host = o.getString("host"),
            port = o.getInt("port"),
            username = o.optString("username").ifBlank { null },
            password = o.optString("password").ifBlank { null },
            // "isTorExit" is the key older versions of this app wrote
            isTor = o.optBoolean("isTor", o.optBoolean("isTorExit", false))
        )

        fun torHop(socksPort: Int = OrbotManager.DEFAULT_SOCKS_PORT): ProxyHop = ProxyHop(
            label = "Tor (via Orbot)",
            type = ProxyType.SOCKS5,
            host = "127.0.0.1",
            port = socksPort,
            isTor = true
        )
    }
}

/** Persistence for the saved chain (SharedPreferences, JSON). */
object ProxyStore {
    private const val PREFS = "proxy_store"
    private const val KEY_CHAIN = "chain"

    fun loadChain(ctx: Context): MutableList<ProxyHop> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHAIN, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull {
                try { ProxyHop.fromJson(arr.getJSONObject(it)) } catch (_: Exception) { null }
            }.toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveChain(ctx: Context, chain: List<ProxyHop>) {
        val arr = JSONArray()
        chain.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CHAIN, arr.toString()).apply()
    }

    /** Tor (local) must come first; the relative order of everything else is preserved. */
    fun normalize(chain: List<ProxyHop>): List<ProxyHop> =
        chain.filter { it.isTor }.take(1) + chain.filter { !it.isTor }
}
