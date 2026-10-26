package net.kravos.tv.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** All channels from iptv-org + the user's own M3U playlists, numbered in a stable order. */
class Catalog(context: Context, private val prefs: Prefs, private val health: Health) {
    private val cache = File(context.filesDir, "catalog.json")
    private val epgFile = File(context.filesDir, "playlist_epg.txt")

    private val _all = MutableStateFlow<List<Channel>>(emptyList())
    val all: StateFlow<List<Channel>> = _all
    @Volatile private var index: Map<String, Channel> = emptyMap()

    data class Status(val loading: Boolean = false, val message: String = "", val error: String? = null, val updatedAt: Long = 0)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** EPG URLs declared by user playlists (url-tvg) or entered by hand. */
    @Volatile var playlistEpg: List<String> = emptyList()
        private set

    fun byId(id: String?): Channel? = id?.let { index[it] }
    fun byNum(n: Int): Channel? = _all.value.firstOrNull { it.num == n }

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        if (_all.value.isNotEmpty() || !cache.exists()) return@withContext
        runCatching {
            playlistEpg = runCatching { epgFile.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
            val list = readCache()
            publish(list)
            _status.value = Status(false, "", null, cache.lastModified())
            Log.i(Http.TAG, "catalog: ${list.size} channels from cache")
        }.onFailure { Log.w(Http.TAG, "catalog cache: ${it.message}") }
    }

    fun isStale() = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 20 * 3_600_000L

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (_status.value.loading) return@withContext
        _status.value = _status.value.copy(loading = true, message = "Preuzimam listu kanala…", error = null)
        try {
            val s = prefs.settings.value
            val own = ArrayList<Channel>()
            val epgs = ArrayList<String>()
            prefs.playlists.value.filter { it.enabled }.forEach { pl ->
                _status.value = _status.value.copy(message = "Učitavam listu ${pl.name}…")
                runCatching {
                    val parsed = M3u.parse(Http.get(pl.url), pl.id)
                    own += parsed.channels
                    (pl.epgUrl ?: parsed.epgUrl)?.let(epgs::add)
                }.onFailure { Log.w(Http.TAG, "playlist ${pl.name}: ${it.message}") }
            }
            playlistEpg = epgs
            runCatching { epgFile.writeText(epgs.joinToString("\n")) }
            val iptv = if (s.iptvOrg) {
                _status.value = _status.value.copy(message = "Preuzimam kanale iz celog sveta…")
                IptvOrg.load()
            } else emptyList()
            val list = own + sortIptv(iptv)
            publish(list)
            writeCache(list)
            _status.value = Status(false, "", null, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(Http.TAG, "catalog refresh: $e")
            _status.value = _status.value.copy(loading = false, message = "", error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun sortIptv(l: List<Channel>): List<Channel> = l.sortedWith(
        compareBy<Channel> { Geo.tier(it.country) }
            .thenBy { if (Geo.tier(it.country) <= 1) Geo.EX_YU.indexOf(it.country) else 0 }
            .thenBy { Geo.name(it.country) }
            .thenBy { Geo.rank(it) }
            .thenBy { it.name.lowercase() },
    )

    private fun publish(list: List<Channel>) {
        list.forEachIndexed { i, c -> c.num = i + 1 }
        index = list.associateBy { it.id }
        _all.value = list
    }

    /** Groups for the side rail. Favorites keep their order; dead channels are hidden if the user wants that. */
    fun groups(favs: List<String>, recent: List<String>, s: Prefs.Settings): List<Group> {
        val all = _all.value
        val now = System.currentTimeMillis()
        fun keep(c: Channel) = !(s.hideDead && health.isDead(c, now)) && c.country !in s.hiddenCountries
        val visible = all.filter(::keep)
        val out = ArrayList<Group>()
        out += Group("fav", "Omiljeni", GroupKind.FAVORITES, "Moje", "★", favs.mapNotNull { index[it] })
        out += Group("recent", "Nedavno gledani", GroupKind.RECENT, "Moje", "⟲", recent.mapNotNull { index[it] })

        prefs.playlists.value.filter { it.enabled }.forEach { pl ->
            val chans = visible.filter { it.origin == pl.id }
            if (chans.isEmpty()) return@forEach
            val byGroup = chans.groupBy { it.group ?: "Ostalo" }
            if (byGroup.size <= 1) out += Group("pl:${pl.id}", pl.name, GroupKind.PLAYLIST, "Moje liste", "▤", chans)
            else {
                out += Group("pl:${pl.id}", "${pl.name} · sve", GroupKind.PLAYLIST, "Moje liste", "▤", chans)
                byGroup.forEach { (g, l) -> out += Group("pl:${pl.id}:$g", g, GroupKind.PLAYLIST, "Moje liste", "▤", l) }
            }
        }

        val iptv = visible.filter { it.origin == "iptv" }
        val byCountry = iptv.groupBy { it.country }
        val region = iptv.filter { Geo.tier(it.country) <= 1 }
        byCountry["RS"]?.let { out += Group("c:RS", "Srbija", GroupKind.COUNTRY, "Region", Geo.flag("RS"), it) }
        if (region.isNotEmpty()) out += Group("region:exyu", "Ceo region", GroupKind.REGION, "Region", "◎", region)
        Geo.EX_YU.drop(1).forEach { cc -> byCountry[cc]?.let { out += Group("c:$cc", Geo.name(cc), GroupKind.COUNTRY, "Region", Geo.flag(cc), it) } }

        val near = iptv.filter { Geo.tier(it.country) <= 3 }
        Geo.CATEGORIES.forEach { (id, title) ->
            val l = near.filter { id in it.categories }
            if (l.size >= 3) out += Group("cat:$id", title, GroupKind.CATEGORY, "Kategorije (Evropa i S. Amerika)", null, l)
        }

        listOf(2, 3, 4).forEach { tier ->
            byCountry.filterKeys { Geo.tier(it) == tier }.toList().sortedBy { Geo.name(it.first) }.forEach { (cc, l) ->
                out += Group("c:$cc", Geo.name(cc), GroupKind.COUNTRY, Geo.section(cc), Geo.flag(cc), l)
            }
        }
        out += Group("all", "Svi kanali", GroupKind.ALL, "Ostalo", "∞", visible)
        return out
    }

    // --- compact on-disk cache -------------------------------------------------------------

    private fun writeCache(list: List<Channel>) {
        val tmp = File(cache.parentFile, "catalog.tmp")
        JsonWriter(tmp.bufferedWriter()).use { w ->
            w.beginArray()
            list.forEach { c ->
                w.beginObject()
                w.name("i").value(c.id)
                w.name("n").value(c.name)
                c.country?.let { w.name("c").value(it) }
                if (c.categories.isNotEmpty()) { w.name("k"); w.beginArray(); c.categories.forEach { w.value(it) }; w.endArray() }
                c.logo?.let { w.name("l").value(it) }
                if (c.altNames.isNotEmpty()) { w.name("a"); w.beginArray(); c.altNames.forEach { w.value(it) }; w.endArray() }
                c.group?.let { w.name("g").value(it) }
                c.tvgId?.let { w.name("t").value(it) }
                if (c.origin != "iptv") w.name("o").value(c.origin)
                c.website?.let { w.name("w").value(it) }
                w.name("s"); w.beginArray()
                c.srcs.forEach { s ->
                    w.beginArray(); w.value(s.url); w.value(s.referrer ?: ""); w.value(s.ua ?: ""); w.value(s.quality ?: ""); w.value(s.label ?: ""); w.endArray()
                }
                w.endArray()
                w.endObject()
            }
            w.endArray()
        }
        tmp.renameTo(cache)
    }

    private fun readCache(): List<Channel> {
        val out = ArrayList<Channel>(14000)
        JsonReader(cache.bufferedReader()).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                var id = ""; var name = ""; var cc: String? = null; var logo: String? = null; var grp: String? = null
                var tvg: String? = null; var origin = "iptv"; var web: String? = null
                var cats: List<String> = emptyList(); var alts: List<String> = emptyList(); val srcs = ArrayList<Src>(2)
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "i" -> id = r.nextString()
                        "n" -> name = r.nextString()
                        "c" -> cc = r.nextString()
                        "l" -> logo = r.nextString()
                        "g" -> grp = r.nextString()
                        "t" -> tvg = r.nextString()
                        "o" -> origin = r.nextString()
                        "w" -> web = r.nextString()
                        "k" -> cats = r.strings()
                        "a" -> alts = r.strings()
                        "s" -> {
                            r.beginArray()
                            while (r.hasNext()) {
                                val v = r.strings()
                                if (v.isNotEmpty()) srcs += Src(v[0], v.getOrNull(1)?.ifEmpty { null }, v.getOrNull(2)?.ifEmpty { null }, v.getOrNull(3)?.ifEmpty { null }, v.getOrNull(4)?.ifEmpty { null })
                            }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                out += Channel(id, name, cc, cats, logo, srcs, alts, grp, tvg, origin, web)
            }
            r.endArray()
        }
        return out
    }

    private fun JsonReader.strings(): List<String> {
        val l = ArrayList<String>(4)
        beginArray()
        while (hasNext()) if (peek() == JsonToken.STRING) l += nextString() else skipValue()
        endArray()
        return l
    }
}
