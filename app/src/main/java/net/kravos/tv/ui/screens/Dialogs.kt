package net.kravos.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kravos.tv.data.Channel
import net.kravos.tv.data.Group
import net.kravos.tv.data.GroupKind
import net.kravos.tv.data.Playlist
import net.kravos.tv.data.Program
import net.kravos.tv.data.Src
import net.kravos.tv.ui.LocalApp
import net.kravos.tv.ui.LocalNav
import net.kravos.tv.ui.components.ChannelLogo
import net.kravos.tv.ui.components.FocusBox
import net.kravos.tv.ui.components.Fmt
import net.kravos.tv.ui.components.QualityBadge
import net.kravos.tv.ui.theme.K

@Composable
fun KDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(min = 320.dp, max = 560.dp).clip(RoundedCornerShape(16.dp)).background(K.Card).padding(20.dp),
        ) { content() }
    }
}

@Composable
fun MenuItem(text: String, modifier: Modifier = Modifier, sub: String? = null, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier.fillMaxWidth()) { _ ->
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(text, fontWeight = FontWeight.Medium)
            sub?.let { Text(it, color = K.Dim, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun rememberFirstFocus(): FocusRequester {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    return fr
}

/** Long-press menu on a channel. */
@Composable
fun ChannelMenu(c: Channel, group: Group?, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val favs by app.prefs.favs.collectAsState()
    var sources by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (sources) { SourcePicker(c, onDismiss = { sources = false; onDismiss() }); return }
    val first = rememberFirstFocus()
    KDialog(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(c.logo, c.name, w = 72.dp, h = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(c.name, style = MaterialTheme.typography.titleMedium)
                Text("Kanal ${c.num}", color = K.Dim, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        MenuItem("Gledaj", Modifier.focusRequester(first)) { onDismiss(); nav.play(c, group?.channels ?: listOf(c)) }
        val isFav = c.id in favs
        MenuItem(if (isFav) "Ukloni iz omiljenih" else "Dodaj u omiljene") { app.prefs.toggleFav(c.id); onDismiss() }
        if (isFav && group?.kind == GroupKind.FAVORITES) {
            MenuItem("Pomeri gore") { app.prefs.moveFav(c.id, -1) }
            MenuItem("Pomeri dole") { app.prefs.moveFav(c.id, 1) }
        }
        MenuItem("Izaberi izvor", sub = "${c.srcs.size} ${if (c.srcs.size == 1) "izvor" else "izvora"}") { sources = true }
        MenuItem("Proveri da li radi", sub = checking) {
            checking = "Proveravam…"
            scope.launch { checking = if (app.health.checkChannel(c)) "Radi ✓" else "Ne otvara se sa ove mreže" }
        }
    }
}

@Composable
fun SourcePicker(c: Channel, onDismiss: () -> Unit, onPick: ((Src) -> Unit)? = null) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val first = rememberFirstFocus()
    val pref = app.prefs.preferredSrc(c.id)
    KDialog(onDismiss) {
        Text("Izvori za ${c.name}", style = MaterialTheme.typography.titleMedium)
        Text("Izabrani izvor se pamti i pušta prvi.", color = K.Dim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(app.health.ordered(c).withIndex().toList(), key = { it.value.url }) { (i, s) ->
                val st = app.health.get(s.url)
                val status = when {
                    st == null -> "nije provereno"
                    st.ok -> "radi"
                    else -> "ne radi (${Fmt.ago(st.checkedAt)})"
                }
                val h = st?.height?.takeIf { it > 0 } ?: s.declaredHeight
                FocusBox(
                    onClick = {
                        app.prefs.setPreferredSrc(c.id, s.url)
                        onDismiss()
                        if (onPick != null) onPick(s) else nav.play(c, nav.zap.ifEmpty { listOf(c) })
                    },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                ) { _ ->
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (s.url.substringAfter("://").substringBefore('/')) + (s.label?.let { "  · $it" } ?: ""),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (s.url == pref) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(status + if (s.url == pref) " · izabran" else "", color = if (st?.ok == true) K.Good else K.Dim, fontSize = 12.sp)
                        }
                        QualityBadge(h)
                    }
                }
            }
        }
        if (pref != null) {
            Spacer(Modifier.height(6.dp))
            MenuItem("Automatski izbor izvora") { app.prefs.setPreferredSrc(c.id, null); onDismiss() }
        }
    }
}

@Composable
fun ProgramDialog(c: Channel, p: Program, onDismiss: () -> Unit, onWatch: () -> Unit) {
    val first = rememberFirstFocus()
    val now = System.currentTimeMillis()
    KDialog(onDismiss) {
        Text(c.name, color = K.Dim, fontSize = 13.sp)
        Text(p.title, style = MaterialTheme.typography.titleLarge)
        Text("${Fmt.day(p.start)} · ${Fmt.range(p.start, p.stop)}" + (p.category?.let { " · $it" } ?: ""), color = K.Dim, fontSize = 13.sp)
        p.desc?.let { Spacer(Modifier.height(10.dp)); Text(it, fontSize = 14.sp, maxLines = 12, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusBox(onClick = { onDismiss(); onWatch() }, idle = K.AccentDim, shape = RoundedCornerShape(50), modifier = Modifier.focusRequester(first)) { _ ->
                Text(if (p.isOn(now)) "Gledaj uživo" else "Gledaj kanal", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold)
            }
            FocusBox(onClick = onDismiss, idle = K.CardHi, shape = RoundedCornerShape(50)) { _ ->
                Text("Zatvori", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
fun AddPlaylistDialog(onDismiss: () -> Unit) {
    val app = LocalApp.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    val first = rememberFirstFocus()
    KDialog(onDismiss) {
        Text("Dodaj M3U listu", style = MaterialTheme.typography.titleMedium)
        Text("Link na M3U/M3U8 listu (npr. od tvog IPTV operatera). EPG link nije obavezan ako ga lista već sadrži.", color = K.Dim, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(name, { name = it }, label = { Text("Naziv") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(first))
        OutlinedTextField(url, { url = it.trim() }, label = { Text("M3U link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(epg, { epg = it.trim() }, label = { Text("EPG (XMLTV) link — opciono") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusBox(
                onClick = {
                    if (url.startsWith("http")) {
                        val id = "p" + (url.hashCode().toUInt().toString(16))
                        app.prefs.addPlaylist(Playlist(id, name.ifBlank { "Moja lista" }, url, epg.ifBlank { null }))
                        app.refreshAll()
                        onDismiss()
                    }
                },
                idle = K.AccentDim, shape = RoundedCornerShape(50),
            ) { _ -> Text("Sačuvaj", Modifier.padding(horizontal = 20.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold) }
            FocusBox(onClick = onDismiss, idle = K.CardHi, shape = RoundedCornerShape(50)) { _ ->
                Text("Otkaži", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
    }
}
