package net.kravos.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import net.kravos.tv.ui.components.Spinner
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.kravos.tv.data.Channel
import net.kravos.tv.data.Geo
import net.kravos.tv.data.Group
import net.kravos.tv.data.GroupKind
import net.kravos.tv.ui.LocalApp
import net.kravos.tv.ui.LocalNav
import net.kravos.tv.ui.components.ChannelLogo
import net.kravos.tv.ui.components.FocusBox
import net.kravos.tv.ui.components.Fmt
import net.kravos.tv.ui.components.Pill
import net.kravos.tv.ui.components.ProgressLine
import net.kravos.tv.ui.components.QualityBadge
import net.kravos.tv.ui.rememberGroups
import net.kravos.tv.ui.rememberNow
import net.kravos.tv.ui.theme.K

/** Picks the group to show: last one used, else favorites if any, else Serbia. */
@Composable
fun currentGroup(groups: List<Group>): Group? {
    val nav = LocalNav.current
    val app = LocalApp.current
    val gid = nav.groupId.value ?: app.prefs.lastGroup
    return groups.firstOrNull { it.id == gid && (it.channels.isNotEmpty() || it.kind == GroupKind.FAVORITES) }
        ?: groups.firstOrNull { it.kind == GroupKind.FAVORITES && it.channels.isNotEmpty() }
        ?: groups.firstOrNull { it.id == "c:RS" }
        ?: groups.firstOrNull { it.channels.isNotEmpty() }
}

@Composable
fun ChannelsTab(wide: Boolean) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val groups = rememberGroups(app)
    val all by app.catalog.all.collectAsState()
    if (all.isEmpty() || groups.isEmpty()) { LoadingPanel(); return }
    val group = currentGroup(groups) ?: run { LoadingPanel(); return }
    var focused by remember { mutableStateOf<Channel?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    val select: (Group) -> Unit = { g -> nav.groupId.value = g.id; app.prefs.lastGroup = g.id }
    val open: (Group) -> Unit = { g -> select(g); focusTick++ }

    // Opening a group checks its channels from this network; dead ones drop out when the check settles.
    LaunchedEffect(group.id) { app.checkGroup(group.channels) }

    val shown = railGroups(groups, group.id)
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            GroupRail(shown, group.id, open, Modifier.width(250.dp).fillMaxHeight(), onFocusSelect = select,
                header = { CountryButton(group, groups, Modifier.fillMaxWidth()) { picker = true } })
            ChannelList(group, Modifier.weight(1f).fillMaxHeight(), focusTick = focusTick, onFocus = { focused = it })
            InfoPanel(focused?.takeIf { f -> group.channels.any { it.id == f.id } } ?: group.channels.firstOrNull(), Modifier.width(340.dp).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CountryButton(group, groups, Modifier.weight(1f)) { picker = true }
            }
            GroupChips(shown.filter { it.kind != GroupKind.COUNTRY || it.id == group.id }, group.id, select)
            ChannelList(group, Modifier.weight(1f), focusTick = focusTick, onFocus = { focused = it })
        }
    }
    if (picker) CountryPicker(groups, group.id, onDismiss = { picker = false }) { g -> picker = false; open(g) }
}

/** Rail keeps the short list: own groups, the region, categories and the currently chosen country. */
fun railGroups(groups: List<Group>, selectedId: String): List<Group> = groups.filter { g ->
    g.kind != GroupKind.COUNTRY || Geo.tier(g.id.removePrefix("c:")) <= 1 || g.id == selectedId
}.let { l ->
    // A country picked from the list shows up right under the region.
    val sel = l.firstOrNull { it.id == selectedId && it.kind == GroupKind.COUNTRY && Geo.tier(it.id.removePrefix("c:")) > 1 }
    if (sel == null) l else l.filter { it.id != sel.id }.toMutableList().apply {
        val at = indexOfLast { it.section == "Region" } + 1
        add(at.coerceIn(0, size), sel.copy(section = "Izabrana zemlja"))
    }
}

@Composable
fun CountryButton(current: Group, groups: List<Group>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val countries = groups.count { it.kind == GroupKind.COUNTRY }
    val label = if (current.kind == GroupKind.COUNTRY) "${current.flag ?: ""} ${current.title}" else "🌍 Izaberi zemlju"
    FocusBox(onClick = onClick, modifier = modifier, idle = K.Card, shape = RoundedCornerShape(10.dp)) { _ ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ZEMLJA", color = K.Faint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(label.trim(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
            Text("▾  $countries", color = K.Dim, fontSize = 13.sp)
        }
    }
}

/** Scrollable list of every country with working channels. */
@Composable
fun CountryPicker(groups: List<Group>, selectedId: String, onDismiss: () -> Unit, onPick: (Group) -> Unit) {
    val app = LocalApp.current
    val countries = groups.filter { it.kind == GroupKind.COUNTRY && it.channels.isNotEmpty() }
    var q by remember { mutableStateOf("") }
    val key = Geo.searchKey(q.trim())
    val list = if (key.isEmpty()) countries else countries.filter { Geo.searchKey(it.title).contains(key) }
    val idx = list.indexOfFirst { it.id == selectedId }
    val state = remember { LazyListState((idx - 3).coerceAtLeast(0)) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    KDialog(onDismiss) {
        Text("Izaberi zemlju", style = MaterialTheme.typography.titleMedium)
        Text("${countries.size} zemalja · broj = kanali koji rade ili još nisu provereni", color = K.Dim, fontSize = 12.sp)
        if (!app.isTv) {
            OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Traži zemlju") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 520.dp), state = state) {
            itemsIndexed(list, key = { _, g -> g.id }) { i, g ->
                if (i == 0 || list[i - 1].section != g.section) {
                    Text(g.section.uppercase(), color = K.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 2.dp else 14.dp, bottom = 4.dp))
                }
                val focusMod = if (i == idx.coerceAtLeast(0)) Modifier.focusRequester(first) else Modifier
                FocusBox(onClick = { onPick(g) }, selected = g.id == selectedId, modifier = focusMod.fillMaxWidth()) { _ ->
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g.flag ?: "", fontSize = 16.sp, modifier = Modifier.width(30.dp))
                        Text(g.title, Modifier.weight(1f), maxLines = 1)
                        Text("${g.channels.size}", color = K.Faint, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun GroupRail(
    groups: List<Group>, selectedId: String, onSelect: (Group) -> Unit, modifier: Modifier = Modifier,
    onFocusSelect: ((Group) -> Unit)? = null, header: (@Composable () -> Unit)? = null,
) {
    val app = LocalApp.current
    val idx = groups.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 3).coerceAtLeast(0))
    LazyColumn(modifier.background(K.Panel), state = state, contentPadding = PaddingValues(vertical = 8.dp, horizontal = 8.dp)) {
        if (header != null) item(key = "header") { Box(Modifier.padding(bottom = 6.dp)) { header() } }
        itemsIndexed(groups, key = { _, g -> g.id }) { i, g ->
            if (i == 0 || groups[i - 1].section != g.section) {
                Text(g.section.uppercase(), color = K.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp, top = if (i == 0) 4.dp else 16.dp, bottom = 6.dp))
            }
            FocusBox(
                onClick = { onSelect(g) },
                // On TV, moving through the rail switches the list right away (like TiviMate).
                // Empty groups (no favorites yet) are not switched to just by passing over them.
                onFocus = if (app.isTv && onFocusSelect != null && g.channels.isNotEmpty()) ({ onFocusSelect(g) }) else null,
                selected = g.id == selectedId,
                modifier = Modifier.fillMaxWidth(),
            ) { f ->
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(g.flag ?: "•", fontSize = 15.sp, modifier = Modifier.width(26.dp), color = if (g.kind == GroupKind.FAVORITES) K.Gold else K.Dim)
                    Text(g.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (f || g.id == selectedId) K.Text else K.Dim, fontWeight = if (g.id == selectedId) FontWeight.SemiBold else FontWeight.Normal)
                    Text("${g.channels.size}", color = K.Faint, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun GroupChips(groups: List<Group>, selectedId: String, onSelect: (Group) -> Unit) {
    val idx = groups.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (idx - 1).coerceAtLeast(0))
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(groups.filter { it.channels.isNotEmpty() || it.kind == GroupKind.FAVORITES }, key = { _, g -> g.id }) { _, g ->
            FocusBox(onClick = { onSelect(g) }, selected = g.id == selectedId, idle = K.Card, shape = RoundedCornerShape(50)) { _ ->
                Text("${g.flag ?: ""} ${g.title}".trim(), Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 13.sp,
                    color = if (g.id == selectedId) K.Text else K.Dim, maxLines = 1)
            }
        }
    }
}

@Composable
fun ChannelList(group: Group, modifier: Modifier = Modifier, focusTick: Int = 0, onFocus: (Channel) -> Unit = {}) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val epgV by app.epg.version.collectAsState()
    val healthV by app.health.version.collectAsState()
    val favs by app.prefs.favs.collectAsState()
    val now = rememberNow(30_000)
    var menu by remember { mutableStateOf<Channel?>(null) }
    val target = nav.focusId
    val targetIdx = group.channels.indexOfFirst { it.id == target }
    val state = remember(group.id) { LazyListState((targetIdx - 3).coerceAtLeast(0)) }
    val focusTarget = remember { FocusRequester() }
    // Row that owns the focus requester: the channel we came back to, else the first one.
    var wantId by remember(group.id) { mutableStateOf(if (targetIdx >= 0) target else group.channels.firstOrNull()?.id) }
    var focusedId by remember(group.id) { mutableStateOf<String?>(null) }
    var focusedIdx by remember(group.id) { mutableIntStateOf(0) }

    suspend fun grabFocus() {
        repeat(20) {
            delay(80)
            if (runCatching { focusTarget.requestFocus() }.getOrDefault(false) != false) return
        }
    }

    // On TV the remote starts in the channel list.
    LaunchedEffect(focusTick, group.id) {
        if (!app.isTv || group.channels.isEmpty()) return@LaunchedEffect
        if (wantId == null || group.channels.none { it.id == wantId }) wantId = group.channels.first().id
        grabFocus()
    }

    // A dead channel disappearing under the cursor must not drop the remote's focus: move it to the row that took its place.
    LaunchedEffect(group.channels) {
        val f = focusedId ?: return@LaunchedEffect
        if (!app.isTv || group.channels.isEmpty() || group.channels.any { it.id == f }) return@LaunchedEffect
        wantId = group.channels[focusedIdx.coerceIn(0, group.channels.lastIndex)].id
        grabFocus()
    }

    if (group.channels.isEmpty()) {
        Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                if (group.kind == GroupKind.FAVORITES) "Još nema omiljenih kanala.\nDrži OK (ili dugo pritisni) na kanalu → „Dodaj u omiljene“."
                else "Nema kanala u ovoj grupi.",
                color = K.Dim,
            )
        }
        return
    }

    val checking by app.health.listProgress.collectAsState()
    LazyColumn(
        modifier.onFocusChanged { if (!it.hasFocus) focusedId = null },
        state = state, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (checking.running && checking.total > 0) item(key = "checking") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(Modifier.size(14.dp), color = K.Dim, stroke = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Proveravam koji kanali rade sa tvoje mreže… ${checking.done}/${checking.total}", color = K.Dim, fontSize = 12.sp)
            }
        }
        itemsIndexed(group.channels, key = { _, c -> c.id }) { i, c ->
            epgV; healthV
            val fr = if (c.id == wantId) Modifier.focusRequester(focusTarget) else Modifier
            ChannelRow(
                c = c, now = now, fav = c.id in favs,
                modifier = fr,
                onClick = { nav.play(c, group.channels) },
                onLong = { menu = c },
                onFocus = { focusedId = c.id; focusedIdx = i; onFocus(c) },
            )
        }
    }
    menu?.let { c -> ChannelMenu(c, group, onDismiss = { menu = null }) }
}

@Composable
fun ChannelRow(c: Channel, now: Long, fav: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, onLong: () -> Unit, onFocus: () -> Unit) {
    val app = LocalApp.current
    val prog = app.epg.now(c.id, now)
    val dead = app.health.isDead(c, now)
    val verified = app.health.isVerified(c)
    FocusBox(onClick = onClick, onLongClick = onLong, onFocus = onFocus, modifier = modifier.fillMaxWidth()) { f ->
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.num.toString(), Modifier.width(48.dp), color = if (f) K.Accent else K.Faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            ChannelLogo(c.logo, c.name, w = 60.dp, h = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (dead) K.Faint else K.Text, modifier = Modifier.weight(1f, fill = false))
                    if (fav) Text("  ★", color = K.Gold, fontSize = 13.sp)
                }
                if (prog != null) {
                    Text(prog.title, color = K.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    ProgressLine(prog.progress(now), Modifier.width(160.dp))
                } else {
                    val sub = listOfNotNull(
                        "${Geo.flag(c.country)} ${Geo.name(c.country)}".takeIf { c.origin == "iptv" } ?: c.group,
                        c.categories.firstOrNull()?.let { Geo.CATEGORIES[it] },
                    ).joinToString(" · ")
                    Text(sub, color = K.Faint, fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (dead) Pill("ne radi", color = K.Live)
            else {
                QualityBadge(app.health.height(c))
                if (verified) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(7.dp).clip(CircleShape).background(K.Good)) }
            }
        }
    }
}

@Composable
private fun InfoPanel(c: Channel?, modifier: Modifier = Modifier) {
    val app = LocalApp.current
    val now = rememberNow(30_000)
    val epgV by app.epg.version.collectAsState()
    epgV
    Column(modifier.background(K.Panel).padding(20.dp)) {
        if (c == null) return@Column
        ChannelLogo(c.logo, c.name, w = 120.dp, h = 72.dp)
        Spacer(Modifier.height(14.dp))
        Text(c.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
        Text("${c.num} · ${Geo.flag(c.country)} ${Geo.name(c.country)}", color = K.Dim, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        val cur = app.epg.now(c.id, now)
        val next = app.epg.next(c.id, now)
        if (cur != null) {
            Pill("SADA", color = K.Live)
            Spacer(Modifier.height(6.dp))
            Text(cur.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Text(Fmt.range(cur.start, cur.stop) + "  ·  još " + Fmt.mins(cur.stop - now), color = K.Dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            ProgressLine(cur.progress(now))
            cur.desc?.let { Spacer(Modifier.height(8.dp)); Text(it, color = K.Dim, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
            next?.let {
                Spacer(Modifier.height(14.dp))
                Text("POSLE", color = K.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("${Fmt.time(it.start)}  ${it.title}", color = K.Text, fontSize = 14.sp, maxLines = 2)
            }
        } else {
            Text("Nema podataka o programu za ovaj kanal.", color = K.Faint, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        val h = app.health.height(c)
        val n = c.srcs.size
        Text(
            "$n ${if (n == 1) "izvor" else "izvora"}" + (if (h > 0) " · do ${h}p" else "") +
                if (app.health.isDead(c)) " · trenutno ne radi" else if (app.health.isVerified(c)) " · provereno" else "",
            color = K.Faint, fontSize = 12.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text("OK gledaj · drži OK za meni", color = K.Faint, fontSize = 12.sp)
    }
}
