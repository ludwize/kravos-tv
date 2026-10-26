package net.kravos.tv.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kravos.tv.data.Channel
import net.kravos.tv.data.Group
import net.kravos.tv.data.Program
import net.kravos.tv.ui.LocalApp
import net.kravos.tv.ui.LocalNav
import net.kravos.tv.ui.components.ChannelLogo
import net.kravos.tv.ui.components.FocusBox
import net.kravos.tv.ui.components.Fmt
import net.kravos.tv.ui.rememberGroups
import net.kravos.tv.ui.rememberNow
import net.kravos.tv.ui.theme.K

private const val HOURS = 8
private const val SLOT_MIN = 30

/** TV guide grid: channels down, time across; all rows scroll together. */
@Composable
fun GuideTab(wide: Boolean) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val groups = rememberGroups(app)
    val all by app.catalog.all.collectAsState()
    if (all.isEmpty() || groups.isEmpty()) { LoadingPanel(); return }
    val group = currentGroup(groups) ?: return
    val select: (Group) -> Unit = { g -> nav.groupId.value = g.id; app.prefs.lastGroup = g.id }
    var picker by remember { mutableStateOf(false) }
    val shown = railGroups(groups, group.id)
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            GroupRail(shown, group.id, select, Modifier.width(220.dp).fillMaxHeight(),
                header = { CountryButton(group, groups, Modifier.fillMaxWidth()) { picker = true } })
            Grid(group, Modifier.weight(1f))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            CountryButton(group, groups, Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp)) { picker = true }
            GroupChips(shown.filter { it.kind != net.kravos.tv.data.GroupKind.COUNTRY || it.id == group.id }, group.id, select)
            Grid(group, Modifier.weight(1f))
        }
    }
    if (picker) CountryPicker(groups, group.id, onDismiss = { picker = false }) { g -> picker = false; select(g) }
}

@Composable
private fun Grid(group: Group, modifier: Modifier) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val epgV by app.epg.version.collectAsState()
    val epgStatus by app.epg.status.collectAsState()
    val now = rememberNow(60_000)
    val wide = isWide()
    val perMin: Dp = if (wide) 5.dp else 3.5.dp
    val nameW: Dp = if (wide) 200.dp else 120.dp
    val start = remember(now / (SLOT_MIN * 60_000L)) { (now / (SLOT_MIN * 60_000L)) * SLOT_MIN * 60_000L - SLOT_MIN * 60_000L }
    val end = start + HOURS * 3_600_000L
    val scroll = rememberScrollState()
    var detail by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    val density = LocalDensity.current

    // Start with "now" near the left edge.
    LaunchedEffect(start) { scroll.scrollTo(with(density) { (perMin * 25).roundToPx() }) }

    // Channels with a guide first, so the grid isn't a wall of empty rows.
    val rows = remember(group.id, epgV) { group.channels.sortedByDescending { app.epg.has(it.id) } }
    val withGuide = rows.count { app.epg.has(it.id) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(34.dp).background(K.Panel)) {
            Box(Modifier.width(nameW).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Text(Fmt.day(now), color = K.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
            }
            TimeHeader(start, end, perMin, scroll, now)
        }
        if (withGuide == 0) {
            Text(
                if (epgStatus.running) epgStatus.message.ifEmpty { "Preuzimam program…" }
                else "Za ovu grupu još nema programa. Program se preuzima za zemlje izabrane u Podešavanjima i za omiljene kanale.",
                color = K.Dim, modifier = Modifier.padding(16.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(rows, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().height(if (wide) 62.dp else 56.dp).padding(vertical = 2.dp)) {
                    Row(Modifier.width(nameW).fillMaxHeight().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (wide) { ChannelLogo(c.logo, c.name, w = 52.dp, h = 32.dp); Spacer(Modifier.width(8.dp)) }
                        Column {
                            Text(c.num.toString(), color = K.Faint, fontSize = 11.sp)
                            Text(c.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                        ProgramRow(c, app.epg.programs(c.id), start, end, perMin, now,
                            onOpen = { p -> if (p == null || p.isOn(now)) nav.play(c, rows) else detail = c to p })
                    }
                }
            }
        }
    }
    detail?.let { (c, p) -> ProgramDialog(c, p, onDismiss = { detail = null }, onWatch = { nav.play(c, rows) }) }
}

@Composable
private fun TimeHeader(start: Long, end: Long, perMin: Dp, scroll: ScrollState, now: Long) {
    Row(Modifier.horizontalScroll(scroll).fillMaxHeight()) {
        var t = start
        while (t < end) {
            Box(Modifier.width(perMin * SLOT_MIN).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                Text(Fmt.time(t), color = if (now in t until t + SLOT_MIN * 60_000L) K.Accent else K.Dim, fontSize = 12.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            }
            t += SLOT_MIN * 60_000L
        }
    }
}

@Composable
private fun ProgramRow(c: Channel, programs: List<Program>, start: Long, end: Long, perMin: Dp, now: Long, onOpen: (Program?) -> Unit) {
    val total = perMin * ((end - start) / 60_000L).toInt()
    val visible = programs.filter { it.stop > start && it.start < end }
    Box(Modifier.width(total).fillMaxHeight()) {
        if (visible.isEmpty()) {
            Cell("Nema podataka o programu — OK za gledanje", null, Modifier.width(total), dim = true) { onOpen(null) }
        } else {
            var cursor = start
            Row {
                visible.forEach { p ->
                    val s = maxOf(p.start, start)
                    val e = minOf(p.stop, end)
                    if (s > cursor) Spacer(Modifier.width(perMin * ((s - cursor) / 60_000L).toInt()))
                    val w = perMin * ((e - s) / 60_000L).toInt().coerceAtLeast(1)
                    Cell(p.title, Fmt.time(p.start), Modifier.width(w), live = p.isOn(now)) { onOpen(p) }
                    cursor = e
                }
            }
        }
        // "Now" marker
        val x = perMin * ((now - start) / 60_000L).toInt()
        Box(Modifier.offset(x = x).width(2.dp).fillMaxHeight().background(K.Live))
    }
}

@Composable
private fun Cell(title: String, time: String?, modifier: Modifier, live: Boolean = false, dim: Boolean = false, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier.fillMaxHeight().padding(horizontal = 1.dp), shape = RoundedCornerShape(6.dp),
        idle = if (live) K.CardHi else K.Card) { f ->
        Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (dim) K.Faint else if (f || live) K.Text else K.Dim, fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal)
            time?.let { Text(it, fontSize = 11.sp, color = K.Faint, maxLines = 1) }
        }
    }
}
