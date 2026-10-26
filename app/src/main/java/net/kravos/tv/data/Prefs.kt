package net.kravos.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _favs = MutableStateFlow(loadList("favs"))
    /** Ordered favorite channel ids. */
    val favs: StateFlow<List<String>> = _favs

    private val _recent = MutableStateFlow(loadList("recent"))
    val recent: StateFlow<List<String>> = _recent

    private val _playlists = MutableStateFlow(loadPlaylists())
    val playlists: StateFlow<List<Playlist>> = _playlists

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings

    data class Settings(
        val iptvOrg: Boolean = true,
        val hideDead: Boolean = true,
        val maxQuality: Boolean = true,
        val autoplayLast: Boolean = false,
        val lenientTls: Boolean = true,
        val epgCountries: Set<String> = setOf("RS", "BA", "HR"),
        val hiddenCountries: Set<String> = emptySet(),
    )

    private fun readSettings() = Settings(
        iptvOrg = sp.getBoolean("iptvOrg", true),
        hideDead = sp.getBoolean("hideDead", true),
        maxQuality = sp.getBoolean("maxQuality", true),
        autoplayLast = sp.getBoolean("autoplayLast", false),
        lenientTls = sp.getBoolean("lenientTls", true),
        epgCountries = sp.getStringSet("epgCountries", null)?.toSet() ?: setOf("RS", "BA", "HR"),
        hiddenCountries = sp.getStringSet("hiddenCountries", null)?.toSet() ?: emptySet(),
    )

    fun update(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        sp.edit()
            .putBoolean("iptvOrg", s.iptvOrg)
            .putBoolean("hideDead", s.hideDead)
            .putBoolean("maxQuality", s.maxQuality)
            .putBoolean("autoplayLast", s.autoplayLast)
            .putBoolean("lenientTls", s.lenientTls)
            .putStringSet("epgCountries", s.epgCountries)
            .putStringSet("hiddenCountries", s.hiddenCountries)
            .apply()
        _settings.value = s
    }

    var lastChannel: String?
        get() = sp.getString("lastChannel", null)
        set(v) = sp.edit().putString("lastChannel", v).apply()

    var lastGroup: String?
        get() = sp.getString("lastGroup", null)
        set(v) = sp.edit().putString("lastGroup", v).apply()

    var skippedBuild: Int
        get() = sp.getInt("skippedBuild", 0)
        set(v) = sp.edit().putInt("skippedBuild", v).apply()

    /** Source URL the user picked by hand for a channel. */
    fun preferredSrc(channelId: String): String? = sp.getString("src:$channelId", null)
    fun setPreferredSrc(channelId: String, url: String?) = sp.edit().apply { if (url == null) remove("src:$channelId") else putString("src:$channelId", url) }.apply()

    fun isFav(id: String) = id in _favs.value
    fun toggleFav(id: String) {
        val cur = _favs.value
        _favs.value = if (id in cur) cur - id else cur + id
        saveList("favs", _favs.value)
    }
    fun moveFav(id: String, delta: Int) {
        val cur = _favs.value.toMutableList()
        val i = cur.indexOf(id)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, cur.lastIndex)
        cur.removeAt(i); cur.add(j, id)
        _favs.value = cur
        saveList("favs", cur)
    }

    fun pushRecent(id: String) {
        val cur = listOf(id) + _recent.value.filter { it != id }
        _recent.value = cur.take(30)
        saveList("recent", _recent.value)
        lastChannel = id
    }

    fun addPlaylist(p: Playlist) {
        _playlists.value = _playlists.value.filter { it.id != p.id } + p
        savePlaylists()
    }
    fun removePlaylist(id: String) {
        _playlists.value = _playlists.value.filter { it.id != id }
        savePlaylists()
    }

    private fun loadList(key: String): List<String> = runCatching {
        val a = JSONArray(sp.getString(key, "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    private fun saveList(key: String, v: List<String>) = sp.edit().putString(key, JSONArray(v).toString()).apply()

    private fun loadPlaylists(): List<Playlist> = runCatching {
        val a = JSONArray(sp.getString("playlists", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Playlist(it.getString("id"), it.getString("name"), it.getString("url"), it.optString("epg").ifBlank { null }, it.optBoolean("enabled", true))
        }
    }.getOrDefault(emptyList())

    private fun savePlaylists() {
        val a = JSONArray()
        _playlists.value.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("epg", it.epgUrl ?: "").put("enabled", it.enabled)) }
        sp.edit().putString("playlists", a.toString()).apply()
    }
}
