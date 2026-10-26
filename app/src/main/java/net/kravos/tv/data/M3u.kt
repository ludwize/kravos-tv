package net.kravos.tv.data

/** Parser for M3U/M3U8 IPTV playlists (the format Kodi PVR IPTV Simple and TiviMate read). */
object M3u {
    data class Parsed(val channels: List<Channel>, val epgUrl: String?)

    private val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun parse(text: String, playlistId: String): Parsed {
        var epg: String? = null
        val out = ArrayList<Channel>()
        var attrs: Map<String, String> = emptyMap()
        var name: String? = null
        var ref: String? = null
        var ua: String? = null
        var grp: String? = null
        val seen = HashMap<String, Int>()

        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", true) -> {
                    val a = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    epg = (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.ifBlank { null }
                }
                line.startsWith("#EXTINF", true) -> {
                    attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = line.substringAfterLast(',', "").trim().ifEmpty { attrs["tvg-name"] }
                    ref = attrs["http-referrer"] ?: attrs["referrer"]
                    ua = attrs["http-user-agent"] ?: attrs["user-agent"]
                }
                line.startsWith("#EXTGRP:", true) -> grp = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val kv = line.substringAfter(':')
                    when {
                        kv.startsWith("http-referrer=", true) -> ref = kv.substringAfter('=')
                        kv.startsWith("http-user-agent=", true) -> ua = kv.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val n = name ?: line.substringAfterLast('/').take(40)
                    val count = (seen[n] ?: 0) + 1
                    seen[n] = count
                    val id = "pl:$playlistId:$n" + if (count > 1) "#$count" else ""
                    val group = attrs["group-title"]?.split(';')?.firstOrNull()?.ifBlank { null } ?: grp
                    val country = attrs["tvg-country"]?.split(';', ',')?.firstOrNull()?.trim()?.uppercase()?.takeIf { it.length == 2 }
                    out += Channel(
                        id = id,
                        name = n,
                        country = country,
                        categories = emptyList(),
                        logo = attrs["tvg-logo"]?.ifBlank { null },
                        srcs = listOf(Src(line, ref, ua)),
                        group = group,
                        tvgId = attrs["tvg-id"]?.ifBlank { null },
                        origin = playlistId,
                    )
                    attrs = emptyMap(); name = null; ref = null; ua = null; grp = null
                }
            }
        }
        return Parsed(out, epg)
    }
}
