package net.kravos.tv.data

import java.text.Normalizer

/** Country names (Serbian Latin), regions and the order channels are numbered in. */
object Geo {
    val EX_YU = listOf("RS", "BA", "ME", "HR", "MK", "SI")

    val EUROPE = setOf(
        "AD", "AL", "AT", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FO", "FR", "UK", "GB", "GI",
        "GR", "HR", "HU", "IE", "IS", "IT", "XK", "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT", "NL", "NO", "PL",
        "PT", "RO", "RS", "RU", "SE", "SI", "SK", "SM", "UA", "VA", "GE", "AM", "AZ", "TR",
    )
    val NORTH_AMERICA = setOf("US", "CA", "MX", "PR")

    private val names = mapOf(
        "RS" to "Srbija", "BA" to "Bosna i Hercegovina", "ME" to "Crna Gora", "HR" to "Hrvatska", "MK" to "Severna Makedonija",
        "SI" to "Slovenija", "AL" to "Albanija", "AT" to "Austrija", "BE" to "Belgija", "BG" to "Bugarska", "BY" to "Belorusija",
        "CH" to "Švajcarska", "CY" to "Kipar", "CZ" to "Češka", "DE" to "Nemačka", "DK" to "Danska", "EE" to "Estonija",
        "ES" to "Španija", "FI" to "Finska", "FR" to "Francuska", "UK" to "Velika Britanija", "GB" to "Velika Britanija",
        "GR" to "Grčka", "HU" to "Mađarska", "IE" to "Irska", "IS" to "Island", "IT" to "Italija", "XK" to "Kosovo",
        "LT" to "Litvanija", "LU" to "Luksemburg", "LV" to "Letonija", "MC" to "Monako", "MD" to "Moldavija", "MT" to "Malta",
        "NL" to "Holandija", "NO" to "Norveška", "PL" to "Poljska", "PT" to "Portugalija", "RO" to "Rumunija", "RU" to "Rusija",
        "SE" to "Švedska", "SK" to "Slovačka", "SM" to "San Marino", "UA" to "Ukrajina", "TR" to "Turska", "GE" to "Gruzija",
        "AM" to "Jermenija", "AZ" to "Azerbejdžan", "AD" to "Andora", "FO" to "Farska Ostrva", "LI" to "Lihtenštajn",
        "US" to "SAD", "CA" to "Kanada", "MX" to "Meksiko", "PR" to "Portoriko",
        "AU" to "Australija", "BR" to "Brazil", "AR" to "Argentina", "CN" to "Kina", "JP" to "Japan", "IN" to "Indija",
        "IL" to "Izrael", "EG" to "Egipat", "AE" to "UAE", "SA" to "Saudijska Arabija", "ZA" to "Južna Afrika", "KR" to "Južna Koreja",
        "NZ" to "Novi Zeland", "CL" to "Čile", "CO" to "Kolumbija", "PE" to "Peru", "VE" to "Venecuela", "ID" to "Indonezija",
    )

    /** Extra names from countries.json (English) when we don't have a Serbian one. */
    @Volatile var englishNames: Map<String, String> = emptyMap()
    @Volatile var flags: Map<String, String> = emptyMap()

    fun name(cc: String?): String = when (cc) {
        null, "" -> "Ostalo"
        else -> names[cc] ?: englishNames[cc] ?: cc
    }

    fun flag(cc: String?): String {
        if (cc == null) return "🌐"
        flags[cc]?.let { return it }
        val code = if (cc == "UK") "GB" else cc
        if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐"
        return String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
    }

    /** 0 = Serbia, 1 = ex-YU, 2 = Europe, 3 = North America, 4 = rest. */
    fun tier(cc: String?): Int = when {
        cc == "RS" -> 0
        cc in EX_YU -> 1
        cc in EUROPE -> 2
        cc in NORTH_AMERICA -> 3
        else -> 4
    }

    fun section(cc: String?): String = when (tier(cc)) {
        0, 1 -> "Region"
        2 -> "Evropa"
        3 -> "Severna Amerika"
        else -> "Ostatak sveta"
    }

    /** Big Serbian channels first, in the order a viewer from Belgrade expects. */
    private val SERBIAN_ORDER = listOf(
        "rts1", "rts2", "rts3", "pink", "prva", "b92", "o2", "novas", "nova", "n1", "happy", "kurir", "informer", "euronewsserbia",
        "rtssvet", "studiob", "rtv1", "rtv2", "prvaplus", "prvaworld", "prvamax", "prvalife", "prvakick", "prvafiles",
        "insajdertvkanal", "blictv", "k1", "pinkplus", "pinkextra",
    )
    private val serbianRank = SERBIAN_ORDER.withIndex().associate { it.value to it.index }
    fun rank(c: Channel): Int = if (c.country == "RS") serbianRank[norm(c.name)] ?: 1000 else 1000

    val CATEGORIES = linkedMapOf(
        "news" to "Vesti", "sports" to "Sport", "movies" to "Filmovi", "series" to "Serije", "music" to "Muzika",
        "kids" to "Deca", "documentary" to "Dokumentarni", "entertainment" to "Zabava", "general" to "Opšti",
        "lifestyle" to "Životni stil", "cooking" to "Kuvanje", "travel" to "Putovanja", "culture" to "Kultura",
        "business" to "Biznis", "religious" to "Religija", "education" to "Obrazovanje", "comedy" to "Komedija",
        "classic" to "Klasici", "animation" to "Animacija", "auto" to "Auto", "outdoor" to "Priroda", "weather" to "Vreme",
    )

    /** Name normalization used to match EPG channels and for search: "RTS 1 HD (RS)" -> "rts1". */
    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t = t.replace('đ', 'd').replace("&", "and")
        t = t.replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\[[^\\]]*\\]"), " ")
        t = t.replace(Regex("\\.(rs|ba|hr|me|mk|si|de|uk|us|ca|fr|it|es|at|gr|nl)$"), " ")
        t = t.replace(Regex("\\b(hd|fhd|uhd|sd|4k|hevc|h265|tv|televizija|television|channel|kanal|live|eu|int|international)\\b"), " ")
        val r = t.replace(Regex("[^a-z0-9+]"), "")
        return r.ifEmpty { s.lowercase().replace(Regex("[^a-z0-9+]"), "") }
    }

    fun searchKey(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('đ', 'd')
}
