package net.kravos.tv.data

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * TV guide from XMLTV files. Country guides come from epgshare01; a user playlist can bring its own (url-tvg).
 * XMLTV channel ids don't match iptv-org ids, so channels are matched by normalized name.
 */
class Epg(context: Context) {
    private val dir = File(context.filesDir, "epg").apply { mkdirs() }
    private val merged = File(dir, "merged.json")
    @Volatile private var data: Map<String, List<Program>> = emptyMap()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    data class Status(val running: Boolean = false, val message: String = "", val channels: Int = 0, val updatedAt: Long = 0)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    init {
        runCatching { if (merged.exists()) { data = readMerged(); _status.value = Status(false, "", data.size, merged.lastModified()) } }
    }

    fun programs(id: String): List<Program> = data[id].orEmpty()
    fun has(id: String) = data.containsKey(id)
    fun now(id: String, t: Long = System.currentTimeMillis()): Program? = data[id]?.firstOrNull { it.isOn(t) }
    fun next(id: String, t: Long = System.currentTimeMillis()): Program? = data[id]?.firstOrNull { it.start >= t }
    fun all(): Map<String, List<Program>> = data

    data class Source(val url: String, val country: String?, val byTvgId: Boolean)

    fun countryUrl(cc: String): String? {
        val code = when (cc) {
            "ME" -> "RS1"
            "UK", "GB" -> "UK1"
            "US" -> "US2"
            "CA" -> "CA2"
            "BE" -> "BE2"
            "MK", "SI", "XK" -> return null
            else -> "${cc}1"
        }
        return "https://epgshare01.online/epgshare01/epg_ripper_$code.xml.gz"
    }

    suspend fun refresh(channels: List<Channel>, countries: Set<String>, favIds: Set<String>, playlistEpg: List<String>, force: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (_status.value.running) return@withContext
            val age = System.currentTimeMillis() - (if (merged.exists()) merged.lastModified() else 0L)
            if (!force && age < 10 * 3_600_000L && data.isNotEmpty()) return@withContext
            _status.value = _status.value.copy(running = true, message = "Preuzimam program…")
            try {
                val sources = ArrayList<Source>()
                playlistEpg.distinct().forEach { sources += Source(it, null, true) }
                countries.sortedBy { Geo.tier(it) }.mapNotNull { cc -> countryUrl(cc)?.let { Source(it, cc, false) } }
                    .distinctBy { it.url }.forEach { sources += it }

                val target = channels.filter { it.country in countries || it.id in favIds || it.origin != "iptv" }
                val byName = HashMap<String, MutableList<Channel>>()
                target.forEach { c -> (listOf(c.name) + c.altNames).map(Geo::norm).distinct().forEach { k -> byName.getOrPut(k) { ArrayList(1) } += c } }
                val byTvg = target.filter { it.tvgId != null }.groupBy { it.tvgId!! }

                val out = HashMap<String, MutableList<Program>>()
                val now = System.currentTimeMillis()
                val from = now - 3 * 3_600_000L
                val to = now + 36 * 3_600_000L
                sources.forEachIndexed { i, src ->
                    _status.value = _status.value.copy(message = "Program ${i + 1}/${sources.size}: ${src.country?.let(Geo::name) ?: "lista"}")
                    runCatching {
                        val f = File(dir, "src_" + src.url.hashCode().toUInt().toString(16) + ".xml.gz")
                        if (force || !f.exists() || now - f.lastModified() > 10 * 3_600_000L) Http.download(src.url, f)
                        parse(f, src, byName, byTvg, out, from, to)
                    }.onFailure { Log.w(Http.TAG, "epg ${src.url}: ${it.message}") }
                }
                out.values.forEach { it.sortBy { p -> p.start } }
                data = out
                writeMerged(out)
                _status.value = Status(false, "", out.size, System.currentTimeMillis())
                _version.value++
                Log.i(Http.TAG, "epg: ${out.size} channels, ${out.values.sumOf { it.size }} programmes")
            } finally {
                if (_status.value.running) _status.value = _status.value.copy(running = false, message = "")
            }
        }

    private fun parse(
        f: File, src: Source,
        byName: Map<String, List<Channel>>, byTvg: Map<String, List<Channel>>,
        out: HashMap<String, MutableList<Program>>, from: Long, to: Long,
    ) {
        val already = out.keys.toHashSet()
        val map = HashMap<String, List<String>>() // xmltv id -> our channel ids
        open(f).use { inp ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(InputStreamReader(inp, Charsets.UTF_8))
            var ev = p.eventType
            var chId: String? = null
            val names = ArrayList<String>()
            var prog: Triple<String, Long, Long>? = null
            var title: String? = null
            var desc: String? = null
            var cat: String? = null
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> { chId = p.getAttributeValue(null, "id"); names.clear() }
                        "display-name" -> if (chId != null) names += p.nextText()
                        "programme" -> {
                            val c = p.getAttributeValue(null, "channel")
                            val s = time(p.getAttributeValue(null, "start"))
                            val e = time(p.getAttributeValue(null, "stop"))
                            prog = if (c != null && s > 0 && e > s && e > from && s < to && map.containsKey(c)) Triple(c, s, e) else null
                            title = null; desc = null; cat = null
                        }
                        "title" -> if (prog != null && title == null) title = p.nextText()
                        "desc" -> if (prog != null && desc == null) desc = p.nextText().take(500)
                        "category" -> if (prog != null && cat == null) cat = p.nextText()
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (p.name) {
                        "channel" -> {
                            val id = chId
                            if (id != null) {
                                val ours = match(id, names, src, byName, byTvg).filter { it !in already }
                                if (ours.isNotEmpty()) map[id] = ours
                            }
                            chId = null
                        }
                        "programme" -> {
                            val pr = prog
                            if (pr != null && !title.isNullOrBlank()) {
                                val program = Program(pr.second, pr.third, title!!.trim(), desc?.trim()?.ifEmpty { null }, cat)
                                map[pr.first]?.forEach { out.getOrPut(it) { ArrayList() } += program }
                            }
                            prog = null
                        }
                    }
                }
                ev = p.next()
            }
        }
    }

    private fun match(xid: String, names: List<String>, src: Source, byName: Map<String, List<Channel>>, byTvg: Map<String, List<Channel>>): List<String> {
        if (src.byTvgId) byTvg[xid]?.let { l -> return l.map { it.id } }
        val keys = (names + xid.substringBeforeLast('.').replace('.', ' ')).map(Geo::norm).filter { it.length >= 2 }.distinct()
        for (k in keys) {
            val hits = byName[k] ?: continue
            // Same name in several countries: keep the one from this guide's country (or region).
            val same = hits.filter { it.country == src.country }
            val pick = same.ifEmpty { hits.filter { Geo.tier(it.country) <= 1 && Geo.tier(src.country) <= 1 } }.ifEmpty { if (src.country == null) hits else emptyList() }
            if (pick.isNotEmpty()) return pick.map { it.id }
        }
        return emptyList()
    }

    private fun open(f: File): InputStream {
        val b = BufferedInputStream(f.inputStream(), 64 * 1024)
        b.mark(4)
        val m1 = b.read(); val m2 = b.read()
        b.reset()
        return if (m1 == 0x1f && m2 == 0x8b) BufferedInputStream(GZIPInputStream(b, 64 * 1024), 64 * 1024) else b
    }

    /** "20261026060000 +0100" -> epoch ms. */
    private fun time(s: String?): Long {
        if (s == null || s.length < 14) return 0
        return runCatching {
            val dt = LocalDateTime.of(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), s.substring(12, 14).toInt())
            val z = s.substring(14).trim()
            val off = if (z.length >= 5 && (z[0] == '+' || z[0] == '-')) {
                val sign = if (z[0] == '-') -1 else 1
                ZoneOffset.ofHoursMinutes(sign * z.substring(1, 3).toInt(), sign * z.substring(3, 5).toInt())
            } else ZoneOffset.UTC
            dt.toEpochSecond(off) * 1000
        }.getOrDefault(0)
    }

    private fun writeMerged(m: Map<String, List<Program>>) {
        val o = JSONObject()
        m.forEach { (k, l) ->
            val a = JSONArray()
            l.forEach { p -> a.put(JSONArray().put(p.start).put(p.stop).put(p.title).put(p.desc ?: "").put(p.category ?: "")) }
            o.put(k, a)
        }
        val tmp = File(dir, "merged.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(merged)
    }

    private fun readMerged(): Map<String, List<Program>> {
        val o = JSONObject(merged.readText())
        val out = HashMap<String, List<Program>>()
        val cutoff = System.currentTimeMillis() - 3 * 3_600_000L
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            out[k] = (0 until a.length()).map { a.getJSONArray(it) }.map {
                Program(it.getLong(0), it.getLong(1), it.getString(2), it.optString(3).ifEmpty { null }, it.optString(4).ifEmpty { null })
            }.filter { it.stop > cutoff }
        }
        return out
    }
}
