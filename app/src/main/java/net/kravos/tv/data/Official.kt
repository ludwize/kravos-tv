package net.kravos.tv.data

/**
 * Broadcasters' own free live streams, added on top of iptv-org with top priority.
 * United Media (N1, Nova S) streams come from their player at n1nova.live; United Media
 * blocks them inside Serbia (they're carried by SBB/EON there), so they work abroad or over a VPN.
 */
object Official {
    data class Entry(val names: Set<String>, val country: String, val title: String, val src: Src, val categories: List<String>, val logo: String? = null)

    private const val UM = "https://best-str.umn.cdn.united.cloud/stream"

    val entries = listOf(
        Entry(
            setOf("n1", "n1srbija"), "RS", "N1",
            Src("$UM?stream=sp1400&sp=n1info&channel=n1srp&u=n1info&p=n1Sh4redSecre7iNf0&player=m3u8", referrer = "https://n1nova.live/", quality = "576p", label = "United Media"),
            listOf("news"),
        ),
        Entry(
            setOf("nova", "novas"), "RS", "Nova S",
            Src("$UM?stream=hp7000&sp=novas&channel=novashd&u=novas&p=n0v43!23t001&player=m3u8", referrer = "https://n1nova.live/", quality = "1080p", label = "United Media"),
            listOf("general"),
        ),
    )

    /** Puts the official source first on the matching channel (or adds the channel). Returns the list and the ids never to hide. */
    fun merge(list: List<Channel>): Pair<List<Channel>, Set<String>> {
        val out = list.toMutableList()
        val pinned = HashSet<String>()
        for (e in entries) {
            val i = out.indexOfFirst { it.origin == "iptv" && it.country == e.country && Geo.norm(it.name) in e.names }
            if (i >= 0) {
                val c = out[i]
                out[i] = c.copy(srcs = listOf(e.src) + c.srcs.filter { s -> s.url.substringBefore("&p=") != e.src.url.substringBefore("&p=") })
                pinned += c.id
            } else {
                val id = "off:" + e.title.lowercase().replace(' ', '-')
                out += Channel(id, e.title, e.country, e.categories, e.logo, listOf(e.src))
                pinned += id
            }
        }
        return out to pinned
    }
}
