package net.kravos.tv.data

/** One playable URL for a channel. A channel can have several (fallbacks, different qualities). */
data class Src(
    val url: String,
    val referrer: String? = null,
    val ua: String? = null,
    /** Declared quality from the source list, e.g. "1080p". */
    val quality: String? = null,
    val label: String? = null,
) {
    val declaredHeight: Int get() = quality?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    fun headers(): Map<String, String> = buildMap {
        referrer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        ua?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    }
}

data class Channel(
    /** Stable key: iptv-org id ("RTS1.rs") or "pl:<playlistId>:<name>" for user playlists. */
    val id: String,
    val name: String,
    /** ISO country as used by iptv-org (note: "UK" for Great Britain). */
    val country: String?,
    val categories: List<String>,
    val logo: String?,
    val srcs: List<Src>,
    val altNames: List<String> = emptyList(),
    /** Playlist group-title for user playlists. */
    val group: String? = null,
    /** XMLTV id from a user playlist (tvg-id). */
    val tvgId: String? = null,
    /** "iptv" or a playlist id. */
    val origin: String = "iptv",
    val website: String? = null,
) {
    var num: Int = 0
}

data class Program(val start: Long, val stop: Long, val title: String, val desc: String? = null, val category: String? = null) {
    fun progress(now: Long): Float = if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
    fun isOn(now: Long) = now in start until stop
}

data class Playlist(val id: String, val name: String, val url: String, val epgUrl: String?, val enabled: Boolean = true)

enum class GroupKind { FAVORITES, RECENT, COUNTRY, REGION, CATEGORY, PLAYLIST, ALL }

data class Group(val id: String, val title: String, val kind: GroupKind, val section: String, val flag: String? = null, val channels: List<Channel>)

data class StreamStatus(
    val ok: Boolean,
    /** Best video height seen (from the master playlist or from actual playback). 0 = unknown. */
    val height: Int,
    val checkedAt: Long,
    /** "hls", "dash" or "progressive". */
    val kind: String? = null,
    val fails: Int = 0,
)
