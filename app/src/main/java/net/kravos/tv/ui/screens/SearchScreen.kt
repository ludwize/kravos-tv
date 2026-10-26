package net.kravos.tv.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kravos.tv.data.Channel
import net.kravos.tv.data.Geo
import net.kravos.tv.data.Program
import net.kravos.tv.ui.LocalApp
import net.kravos.tv.ui.LocalNav
import net.kravos.tv.ui.components.ChannelLogo
import net.kravos.tv.ui.components.FocusBox
import net.kravos.tv.ui.components.Fmt
import net.kravos.tv.ui.rememberNow
import net.kravos.tv.ui.theme.K

private data class Results(val channels: List<Channel> = emptyList(), val programs: List<Pair<Channel, Program>> = emptyList())

@Composable
fun SearchTab() {
    val app = LocalApp.current
    val nav = LocalNav.current
    var q by rememberSaveable { mutableStateOf("") }
    val all by app.catalog.all.collectAsState()
    val favs by app.prefs.favs.collectAsState()
    val now = rememberNow(60_000)
    var menu by remember { mutableStateOf<Channel?>(null) }
    var detail by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { field.requestFocus() } }

    val res by produceState(Results(), q, all) {
        delay(200)
        value = withContext(Dispatchers.Default) {
            val k = Geo.searchKey(q.trim())
            if (k.length < 2) return@withContext Results()
            val ch = all.filter { c -> Geo.searchKey(c.name).contains(k) || c.altNames.any { Geo.searchKey(it).contains(k) } || Geo.searchKey(Geo.name(c.country)) == k }
                .sortedWith(compareBy<Channel> { !Geo.searchKey(it.name).startsWith(k) }.thenBy { it.num })
                .take(150)
            val t = System.currentTimeMillis()
            val progs = app.epg.all().flatMap { (id, l) ->
                val c = app.catalog.byId(id) ?: return@flatMap emptyList()
                l.filter { it.stop > t && Geo.searchKey(it.title).contains(k) }.map { c to it }
            }.sortedBy { it.second.start }.take(60)
            Results(ch, progs)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            q, { q = it },
            label = { Text("Kanal, zemlja ili emisija") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(field),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (res.channels.isNotEmpty()) item { Header("Kanali (${res.channels.size})") }
            items(res.channels, key = { "c" + it.id }) { c ->
                ChannelRow(c, now, c.id in favs, onClick = { nav.play(c, res.channels) }, onLong = { menu = c }, onFocus = {})
            }
            if (res.programs.isNotEmpty()) item { Header("U programu") }
            items(res.programs, key = { "p" + it.first.id + it.second.start }) { (c, p) ->
                FocusBox(onClick = { if (p.isOn(now)) nav.play(c, listOf(c)) else detail = c to p }, modifier = Modifier.fillMaxWidth()) { _ ->
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (p.isOn(now)) "SADA" else Fmt.time(p.start), Modifier.width(56.dp), color = if (p.isOn(now)) K.Live else K.Accent,
                            fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        ChannelLogo(c.logo, c.name, w = 48.dp, h = 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text("${c.name} · ${Fmt.day(p.start)}", color = K.Dim, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (q.trim().length >= 2 && res.channels.isEmpty() && res.programs.isEmpty()) item {
                Text("Ništa nije pronađeno.", color = K.Dim, modifier = Modifier.padding(16.dp))
            }
        }
    }
    menu?.let { c -> ChannelMenu(c, null, onDismiss = { menu = null }) }
    detail?.let { (c, p) -> ProgramDialog(c, p, onDismiss = { detail = null }, onWatch = { nav.play(c, listOf(c)) }) }
}

@Composable
private fun Header(t: String) {
    Text(t.uppercase(), color = K.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp))
}
