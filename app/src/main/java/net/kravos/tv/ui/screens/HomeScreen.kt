package net.kravos.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import net.kravos.tv.ui.LocalApp
import net.kravos.tv.ui.LocalNav
import net.kravos.tv.ui.Tab
import net.kravos.tv.ui.components.FocusBox
import net.kravos.tv.ui.components.Spinner
import net.kravos.tv.ui.components.Fmt
import net.kravos.tv.ui.rememberNow
import net.kravos.tv.ui.theme.K

@Composable
fun isWide(): Boolean {
    val app = LocalApp.current
    return app.isTv || LocalConfiguration.current.screenWidthDp >= 720
}

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val tab by nav.tab
    val wide = isWide()
    Column(Modifier.fillMaxSize().background(K.Bg).then(if (wide) Modifier else Modifier.statusBarsPadding())) {
        TopBar(wide)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.CHANNELS -> ChannelsTab(wide)
                Tab.GUIDE -> GuideTab(wide)
                Tab.SEARCH -> SearchTab()
                Tab.SETTINGS -> SettingsTab()
            }
        }
        if (!wide) BottomBar()
    }
}

private val tabIcons = mapOf(
    Tab.CHANNELS to Icons.Filled.Tv,
    Tab.GUIDE to Icons.Filled.DateRange,
    Tab.SEARCH to Icons.Filled.Search,
    Tab.SETTINGS to Icons.Filled.Settings,
)

@Composable
private fun TopBar(wide: Boolean) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val now = rememberNow(15_000)
    val catalog by app.catalog.status.collectAsState()
    val check by app.health.progress.collectAsState()
    val epg by app.epg.status.collectAsState()
    val castOk by app.cast.available.collectAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = if (wide) 14.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Kravos", style = MaterialTheme.typography.titleLarge)
        Text(" TV", style = MaterialTheme.typography.titleLarge, color = K.Accent)
        if (wide) {
            Spacer(Modifier.width(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tab.entries.forEach { t ->
                    FocusBox(onClick = { nav.tab.value = t }, selected = nav.tab.value == t, shape = RoundedCornerShape(50)) { f ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(tabIcons[t]!!, null, Modifier.size(18.dp), tint = if (nav.tab.value == t || f) K.Accent else K.Dim)
                            Spacer(Modifier.width(8.dp))
                            Text(t.label, color = if (nav.tab.value == t || f) K.Text else K.Dim, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val busy = when {
            catalog.loading -> catalog.message
            epg.running -> epg.message
            check.running -> "Proveravam kanale ${check.done}/${check.total}"
            else -> null
        }
        if (busy != null) {
            Spinner(Modifier.size(14.dp), color = K.Dim, stroke = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(busy, color = K.Dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(end = 16.dp).then(if (wide) Modifier else Modifier.width(150.dp)))
        }
        if (!app.isTv && castOk) CastButton()
        if (wide) Text(Fmt.time(now), color = K.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx -> MediaRouteButton(ctx).also { runCatching { CastButtonFactory.setUpMediaRouteButton(ctx, it) } } },
        modifier = modifier.size(44.dp),
    )
}

@Composable
private fun BottomBar() {
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().background(K.Panel).navigationBarsPadding().padding(vertical = 4.dp)) {
        Tab.entries.forEach { t ->
            val sel = nav.tab.value == t
            FocusBox(onClick = { nav.tab.value = t }, modifier = Modifier.weight(1f)) { _ ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(tabIcons[t]!!, null, Modifier.size(22.dp), tint = if (sel) K.Accent else K.Dim)
                    Text(t.label, fontSize = 11.sp, color = if (sel) K.Text else K.Dim)
                }
            }
        }
    }
}

@Composable
fun LoadingPanel() {
    val app = LocalApp.current
    val st by app.catalog.status.collectAsState()
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (st.error != null && !st.loading) {
            Text("Lista kanala nije preuzeta", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(st.error ?: "", color = K.Dim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            FocusBox(onClick = { app.refreshAll() }, idle = K.Card, shape = RoundedCornerShape(50)) {
                Text("Pokušaj ponovo", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontWeight = FontWeight.SemiBold)
            }
        } else {
            Spinner()
            Spacer(Modifier.height(20.dp))
            Text(st.message.ifEmpty { "Pripremam kanale…" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Prvo pokretanje preuzima listu od desetak hiljada kanala iz celog sveta.\nTraje do pola minuta, posle se otvara odmah.",
                color = K.Dim, textAlign = TextAlign.Center)
        }
    }
}
