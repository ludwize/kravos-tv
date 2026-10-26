package net.kravos.tv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dead-stream detection. Every stream URL gets a status: does the playlist open from THIS network
 * (geo-blocking matters: a Serbian stream may only work from Serbia), and the best resolution offered.
 * The player also reports real results, which override the probe.
 */
class Health(context: Context, private val prefs: Prefs) {
    private val file = File(context.filesDir, "health.json")
    private val map = ConcurrentHashMap<String, StreamStatus>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    data class Progress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val ok: Int = 0)
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    init { load() }

    fun get(url: String): StreamStatus? = map[url]

    private fun fresh(s: StreamStatus, now: Long) = now - s.checkedAt < 3 * DAY

    fun isDead(c: Channel, now: Long = System.currentTimeMillis()): Boolean =
        c.srcs.isNotEmpty() && c.srcs.all { s -> map[s.url]?.let { !it.ok && fresh(it, now) } ?: false }

    fun isVerified(c: Channel): Boolean = c.srcs.any { map[it.url]?.ok == true }

    /** Best known video height for the channel (probed/played beats declared). */
    fun height(c: Channel): Int {
        val known = c.srcs.mapNotNull { s -> map[s.url]?.takeIf { it.ok }?.height?.takeIf { it > 0 } }.maxOrNull()
        return known ?: if (isDead(c)) 0 else c.srcs.maxOfOrNull { it.declaredHeight } ?: 0
    }

    /** Order in which the player tries the sources. */
    fun ordered(c: Channel): List<Src> {
        val pref = prefs.preferredSrc(c.id)
        val now = System.currentTimeMillis()
        return c.srcs.sortedWith(
            compareBy<Src> { if (it.url == pref) 0 else 1 }
                .thenBy { s ->
                    val st = map[s.url]
                    when {
                        st == null -> 1
                        st.ok -> 0
                        !fresh(st, now) -> 1
                        else -> 2
                    }
                }
                .thenByDescending { s -> map[s.url]?.height?.takeIf { it > 0 } ?: s.declaredHeight }
                .thenBy { map[it.url]?.fails ?: 0 },
        )
    }

    fun kind(url: String): String? = map[url]?.kind

    /** Called by the player with what really happened. */
    fun report(url: String, ok: Boolean, height: Int = 0, kind: String? = null) {
        val prev = map[url]
        map[url] = StreamStatus(
            ok = ok,
            height = if (height > 0) height else prev?.height ?: 0,
            checkedAt = System.currentTimeMillis(),
            kind = kind ?: prev?.kind,
            fails = if (ok) 0 else (prev?.fails ?: 0) + 1,
        )
        _version.value++
        save()
    }

    suspend fun checkAll(channels: List<Channel>, maxAgeMs: Long = 20 * HOUR, parallel: Int = 16) = coroutineScope {
        if (_progress.value.running) return@coroutineScope
        val now = System.currentTimeMillis()
        val todo = channels.filter { c -> c.srcs.any { s -> map[s.url]?.let { now - it.checkedAt > maxAgeMs } ?: true } }
        _progress.value = Progress(true, 0, todo.size, 0)
        val done = AtomicInteger()
        val okCount = AtomicInteger()
        val gate = Semaphore(parallel)
        val lenient = prefs.settings.value.lenientTls
        try {
            todo.map { c ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        var any = false
                        for (s in c.srcs) {
                            val st = map[s.url]
                            val r = if (st != null && now - st.checkedAt <= maxAgeMs) st else probe(s, lenient).also { map[s.url] = it }
                            if (r.ok) any = true
                        }
                        if (any) okCount.incrementAndGet()
                        val d = done.incrementAndGet()
                        if (d % 25 == 0 || d == todo.size) {
                            _progress.value = Progress(true, d, todo.size, okCount.get())
                            _version.value++
                        }
                        if (d % 200 == 0) save()
                    }
                }
            }.awaitAll()
        } finally {
            _progress.value = _progress.value.copy(running = false)
            _version.value++
            save()
            Log.i(Http.TAG, "health: checked ${todo.size} channels, ${okCount.get()} work")
        }
    }

    suspend fun checkChannel(c: Channel): Boolean = withContext(Dispatchers.IO) {
        val lenient = prefs.settings.value.lenientTls
        var any = false
        for (s in c.srcs) {
            val r = probe(s, lenient)
            map[s.url] = r
            if (r.ok) any = true
        }
        _version.value++
        save()
        any
    }

    fun probe(src: Src, lenient: Boolean): StreamStatus {
        val now = System.currentTimeMillis()
        val prevFails = map[src.url]?.fails ?: 0
        fun fail() = StreamStatus(false, 0, now, map[src.url]?.kind, prevFails + 1)
        return try {
            val client = if (lenient) Http.mediaClient() else Http.client()
            Http.open(src.url, src.headers(), client).use { resp ->
                val ctype = resp.header("Content-Type").orEmpty().lowercase()
                val finalUrl = resp.request.url
                val bytes = readUpTo(resp.body!!.byteStream(), 256 * 1024)
                val text = String(bytes, Charsets.UTF_8)
                when {
                    text.contains("#EXTM3U") -> {
                        val variants = Regex("#EXT-X-STREAM-INF:([^\\n]*)\\n\\s*([^\\n#][^\\n]*)").findAll(text).map { m ->
                            val h = Regex("RESOLUTION=\\d+x(\\d+)").find(m.groupValues[1])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val bw = Regex("[^-]BANDWIDTH=(\\d+)").find("," + m.groupValues[1])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                            Triple(h, bw, m.groupValues[2].trim())
                        }.toList()
                        if (variants.isEmpty()) {
                            StreamStatus(text.contains("#EXTINF") || text.contains("#EXT-X-MAP"), 0, now, "hls")
                        } else {
                            val best = variants.maxWith(compareBy<Triple<Int, Long, String>> { it.first }.thenBy { it.second })
                            val media = finalUrl.resolve(best.third)?.toString() ?: return@use fail()
                            val mediaOk = runCatching {
                                Http.open(media, src.headers(), client).use { r2 ->
                                    val t2 = String(readUpTo(r2.body!!.byteStream(), 128 * 1024), Charsets.UTF_8)
                                    t2.contains("#EXTINF") || t2.contains("#EXT-X-MAP") || t2.contains("#EXT-X-STREAM-INF")
                                }
                            }.getOrDefault(false)
                            StreamStatus(mediaOk, if (mediaOk) best.first else 0, now, "hls", if (mediaOk) 0 else prevFails + 1)
                        }
                    }
                    text.contains("<MPD") -> {
                        val h = Regex("height=\"(\\d+)\"").findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
                        StreamStatus(true, h, now, "dash")
                    }
                    bytes.isNotEmpty() && (bytes[0] == 0x47.toByte() || ctype.startsWith("video/") || ctype.contains("mp2t") || ctype.contains("octet-stream")) ->
                        StreamStatus(true, 0, now, "progressive")
                    else -> fail()
                }
            }
        } catch (e: Exception) {
            fail()
        }
    }

    private fun readUpTo(inp: InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var n = 0
        while (n < max) {
            val r = inp.read(buf, n, max - n)
            if (r < 0) break
            n += r
        }
        return buf.copyOf(n)
    }

    private fun load() = runCatching {
        if (!file.exists()) return@runCatching
        val o = JSONObject(file.readText())
        o.keys().forEach { k ->
            val a = o.getJSONArray(k)
            map[k] = StreamStatus(a.getInt(0) == 1, a.getInt(1), a.getLong(2), a.optString(3).ifEmpty { null }, a.optInt(4))
        }
    }

    @Synchronized
    private fun save() {
        runCatching {
            val o = JSONObject()
            val cutoff = System.currentTimeMillis() - 14 * DAY
            map.forEach { (k, v) ->
                if (v.checkedAt > cutoff) o.put(k, org.json.JSONArray().put(if (v.ok) 1 else 0).put(v.height).put(v.checkedAt).put(v.kind ?: "").put(v.fails))
            }
            val tmp = File(file.parentFile, "health.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    companion object {
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}
