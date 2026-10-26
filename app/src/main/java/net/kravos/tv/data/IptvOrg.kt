package net.kravos.tv.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * The public iptv-org index (github.com/iptv-org/iptv): channel metadata, stream URLs and logos.
 * Parsed as a stream because channels.json + logos.json are ~14 MB together.
 */
object IptvOrg {
    private const val API = "https://iptv-org.github.io/api"

    /**
     * URLs that are pay-TV operator feeds leaked through someone's set-top box or panel
     * (multicast relays, Xtream panels, operator tokens) are not loaded.
     */
    private val RESTREAM = listOf(
        Regex("/(udp|rtp)/\\d"),
        Regex("://[^/]*telekomsrbije\\.com"),
        Regex(":\\d+/play/[A-Za-z0-9]{6,}"),
        Regex("/live/[^/]+/[^/]+/\\d+(\\.(ts|m3u8))?$"),
        Regex("[?&](username|password)="),
    )

    fun allowedUrl(url: String): Boolean {
        val u = url.trim()
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false
        return RESTREAM.none { it.containsMatchIn(u) }
    }

    suspend fun load(): List<Channel> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        val blocked = HashSet<String>()
        each("$API/blocklist.json") { o -> (o["channel"] as? String)?.let(blocked::add) }

        val srcs = HashMap<String, MutableList<Src>>(16000)
        each("$API/streams.json") { o ->
            val ch = o["channel"] as? String ?: return@each
            val url = o["url"] as? String ?: return@each
            if (ch in blocked || !allowedUrl(url)) return@each
            val labels = (o["labels"] as? List<*>).orEmpty().filterIsInstance<String>()
            srcs.getOrPut(ch) { ArrayList(2) } += Src(
                url = url.trim(),
                referrer = o["referrer"] as? String,
                ua = o["user_agent"] as? String,
                quality = o["quality"] as? String,
                label = labels.firstOrNull() ?: (o["feed"] as? String)?.takeIf { it != "SD" && it != "HD" },
            )
        }

        val names = HashMap<String, String>()
        val flags = HashMap<String, String>()
        each("$API/countries.json") { o ->
            val code = o["code"] as? String ?: return@each
            (o["name"] as? String)?.let { names[code] = it }
            (o["flag"] as? String)?.let { flags[code] = it }
        }
        Geo.englishNames = names
        Geo.flags = flags

        val logos = HashMap<String, String>(srcs.size)
        each("$API/logos.json") { o ->
            val ch = o["channel"] as? String ?: return@each
            if (ch !in srcs) return@each
            val url = o["url"] as? String ?: return@each
            val main = o["feed"] == null
            val prev = logos[ch]
            // Prefer the channel-level (not per-feed) logo and raster formats.
            if (prev == null || (main && prev.endsWith(".svg", true) && !url.endsWith(".svg", true))) logos[ch] = url
        }

        val out = ArrayList<Channel>(srcs.size)
        each("$API/channels.json") { o ->
            val id = o["id"] as? String ?: return@each
            val list = srcs[id] ?: return@each
            if (o["is_nsfw"] == true || o["closed"] != null) return@each
            val cats = (o["categories"] as? List<*>).orEmpty().filterIsInstance<String>()
            if ("xxx" in cats) return@each
            out += Channel(
                id = id,
                name = o["name"] as? String ?: id,
                country = o["country"] as? String,
                categories = cats,
                logo = logos[id],
                srcs = list.sortedByDescending { it.declaredHeight },
                altNames = (o["alt_names"] as? List<*>).orEmpty().filterIsInstance<String>(),
                website = o["website"] as? String,
            )
        }
        Log.i(Http.TAG, "iptv-org: ${out.size} channels, ${out.sumOf { it.srcs.size }} streams in ${System.currentTimeMillis() - t0} ms")
        out
    }

    private inline fun each(url: String, crossinline f: (Map<String, Any?>) -> Unit) {
        Http.open(url).use { resp ->
            JsonReader(InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8)).use { r ->
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) f(r.obj()) else r.skipValue()
                }
                r.endArray()
            }
        }
    }

    private fun JsonReader.obj(): Map<String, Any?> {
        val m = HashMap<String, Any?>(16)
        beginObject()
        while (hasNext()) {
            val k = nextName()
            m[k] = when (peek()) {
                JsonToken.NULL -> { nextNull(); null }
                JsonToken.STRING, JsonToken.NUMBER -> nextString()
                JsonToken.BOOLEAN -> nextBoolean()
                JsonToken.BEGIN_ARRAY -> {
                    val l = ArrayList<String>(2)
                    beginArray()
                    while (hasNext()) if (peek() == JsonToken.STRING) l.add(nextString()) else skipValue()
                    endArray()
                    l
                }
                else -> { skipValue(); null }
            }
        }
        endObject()
        return m
    }
}
