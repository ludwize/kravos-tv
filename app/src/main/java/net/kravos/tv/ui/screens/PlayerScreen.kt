package net.kravos.tv.ui.screens

import android.app.Activity
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import net.kravos.tv.data.Channel
import net.kravos.tv.data.Geo
import net.kravos.tv.play.PlayerController
import net.kravos.tv.ui.LocalApp
import net.kravos.tv.ui.LocalNav
import net.kravos.tv.ui.Route
import net.kravos.tv.ui.components.ChannelLogo
import net.kravos.tv.ui.components.FocusBox
import net.kravos.tv.ui.components.Spinner
import net.kravos.tv.ui.components.Fmt
import net.kravos.tv.ui.components.ProgressLine
import net.kravos.tv.ui.components.QualityBadge
import net.kravos.tv.ui.rememberNow
import net.kravos.tv.ui.theme.K

private enum class Overlay { NONE, INFO, LIST, OPTIONS }

@Composable
fun PlayerScreen(route: Route.Player) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val channel = app.catalog.byId(route.channelId)
    if (channel == null) { LaunchedEffect(Unit) { nav.back() }; return }
    val list = nav.zap.ifEmpty { listOf(channel) }
    val st by app.player.state.collectAsState()
    val castMsg by app.cast.message.collectAsState()
    var overlay by remember { mutableStateOf(Overlay.INFO) }
    var infoTick by remember { mutableIntStateOf(0) }
    var numEntry by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val full = app.isTv || landscape

    fun zap(delta: Int) {
        if (list.size < 2) return
        val i = list.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        nav.play(list[(i + delta + list.size) % list.size], list)
    }

    LaunchedEffect(route.channelId) {
        app.player.play(channel)
        overlay = Overlay.INFO
        infoTick++
    }
    LaunchedEffect(infoTick, overlay) {
        if (overlay == Overlay.INFO) { delay(6_000); overlay = Overlay.NONE }
    }
    LaunchedEffect(overlay) {
        if (overlay == Overlay.NONE || overlay == Overlay.INFO) { delay(60); runCatching { rootFocus.requestFocus() } }
    }
    LaunchedEffect(numEntry) {
        if (numEntry.isEmpty()) return@LaunchedEffect
        delay(1_800)
        numEntry.toIntOrNull()?.let { app.catalog.byNum(it) }?.let { c -> nav.play(c, if (list.any { it.id == c.id }) list else listOf(c)) }
        numEntry = ""
    }
    LaunchedEffect(castMsg) { if (castMsg != null) { delay(6_000); app.cast.clearMessage() } }

    // Stop the stream when the app goes to the background; resume when it comes back.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> if (app.player.state.value.castDevice == null) app.player.pauseLocal()
                Lifecycle.Event.ON_START -> if (app.player.state.value.castDevice == null && app.player.state.value.channel != null && !app.player.player().isPlaying) app.player.retry()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) { onDispose { if (app.player.state.value.castDevice == null) app.player.stop() } }

    ImmersiveIf(full && !app.isTv)
    BackHandler(enabled = overlay != Overlay.NONE && overlay != Overlay.INFO) { overlay = Overlay.NONE }

    val keys = Modifier.onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (overlay == Overlay.LIST || overlay == Overlay.OPTIONS) return@onKeyEvent false
        when (e.key) {
            Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { zap(1); true }
            Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { zap(-1); true }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (e.nativeKeyEvent.repeatCount == 0) { overlay = if (overlay == Overlay.INFO) Overlay.LIST else Overlay.INFO; infoTick++ }
                true
            }
            Key.DirectionLeft -> { overlay = Overlay.LIST; true }
            Key.DirectionRight, Key.Menu -> { overlay = Overlay.OPTIONS; true }
            Key.Info, Key.Guide -> { overlay = Overlay.INFO; infoTick++; true }
            else -> {
                val d = digit(e.key)
                if (d != null) { numEntry = (numEntry + d).takeLast(4); true } else false
            }
        }
    }

    if (full) {
        Box(Modifier.fillMaxSize().background(Color.Black).then(keys).focusRequester(rootFocus).focusable()) {
            VideoSurface(app.player, Modifier.fillMaxSize(), onTap = {
                overlay = if (overlay == Overlay.NONE) Overlay.INFO else Overlay.NONE; infoTick++
            })
            if (overlay != Overlay.LIST && overlay != Overlay.OPTIONS) StatusLayer(st, channel) { sources = true }
            AnimatedVisibility(overlay == Overlay.INFO, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
                InfoBanner(channel, st, touch = !app.isTv,
                    onPrev = { zap(-1) }, onNext = { zap(1) }, onList = { overlay = Overlay.LIST }, onMore = { overlay = Overlay.OPTIONS },
                    onBack = { nav.back() })
            }
            if (overlay == Overlay.LIST) {
                ListOverlay(list, channel, Modifier.align(Alignment.CenterStart)) { c -> nav.play(c, list); overlay = Overlay.INFO; infoTick++ }
            }
            if (overlay == Overlay.OPTIONS) {
                OptionsOverlay(channel, st, Modifier.align(Alignment.CenterEnd), onSources = { sources = true }, onClose = { overlay = Overlay.NONE })
            }
            if (numEntry.isNotEmpty()) NumberBox(numEntry, Modifier.align(Alignment.TopEnd))
            castMsg?.let { Toast(it, Modifier.align(Alignment.TopCenter)) }
        }
    } else {
        // Phone, portrait: video on top, now/next and the channel list below.
        Column(Modifier.fillMaxSize().background(K.Bg).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Nazad") { nav.back() }
                Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (app.cast.available.collectAsState().value) CastButton()
                IconBtn(Icons.Filled.MoreVert, "Opcije") { overlay = if (overlay == Overlay.OPTIONS) Overlay.NONE else Overlay.OPTIONS }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                VideoSurface(app.player, Modifier.fillMaxSize(), onTap = {})
                StatusLayer(st, channel) { sources = true }
                if (numEntry.isNotEmpty()) NumberBox(numEntry, Modifier.align(Alignment.TopEnd))
            }
            castMsg?.let { Toast(it, Modifier.align(Alignment.CenterHorizontally)) }
            if (overlay == Overlay.OPTIONS) {
                OptionsOverlay(channel, st, Modifier.fillMaxWidth(), onSources = { sources = true }, onClose = { overlay = Overlay.NONE })
            } else {
                NowNext(channel, st, Modifier.padding(16.dp))
                PhoneList(list, channel) { c -> nav.play(c, list) }
            }
        }
    }

    if (sources) SourcePicker(channel, onDismiss = { sources = false }, onPick = { s -> app.player.play(channel, s) })
}

private fun digit(k: Key): Int? = when (k) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(controller: PlayerController, modifier: Modifier, onTap: () -> Unit) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                keepScreenOn = true
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setOnClickListener { onTap() }
                player = controller.player()
            }
        },
        update = { it.setOnClickListener { onTap() } },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

@Composable
private fun BoxScope.StatusLayer(st: PlayerController.State, channel: Channel, onSources: () -> Unit) {
    val app = LocalApp.current
    when {
        st.castDevice != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Emituje se na", color = K.Dim)
            Text(st.castDevice, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            FocusBox(onClick = { app.cast.stopCasting() }, idle = K.CardHi, shape = RoundedCornerShape(50)) { _ ->
                Text("Prekini emitovanje", Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
            }
        }
        st.failed -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(st.message ?: "Kanal ne radi.", color = K.Dim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
                FocusBox(onClick = { app.player.retry() }, idle = K.AccentDim, shape = RoundedCornerShape(50), modifier = Modifier.focusRequester(first)) { _ ->
                    Text("Pokušaj ponovo", Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontWeight = FontWeight.SemiBold)
                }
                FocusBox(onClick = onSources, idle = K.CardHi, shape = RoundedCornerShape(50)) { _ ->
                    Text("Izvori", Modifier.padding(horizontal = 18.dp, vertical = 9.dp))
                }
            }
        }
        st.buffering && !st.ready -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Spinner()
            st.message?.let { Spacer(Modifier.height(10.dp)); Text(it, color = K.Dim, fontSize = 13.sp) }
        }
        st.buffering -> Spinner(Modifier.align(Alignment.Center).size(36.dp), color = Color(0x99FFFFFF))
        else -> Unit
    }
}

@Composable
private fun InfoBanner(
    c: Channel, st: PlayerController.State, touch: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onList: () -> Unit, onMore: () -> Unit, onBack: () -> Unit,
) {
    val app = LocalApp.current
    val now = rememberNow(15_000)
    val favs by app.prefs.favs.collectAsState()
    val cur = app.epg.now(c.id, now)
    val next = app.epg.next(c.id, now)
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000))))
            .padding(start = 40.dp, end = 40.dp, top = 60.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.num.toString(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = K.Accent)
            Spacer(Modifier.width(18.dp))
            ChannelLogo(c.logo, c.name, w = 84.dp, h = 52.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (c.id in favs) Text("  ★", color = K.Gold)
                    Spacer(Modifier.width(10.dp))
                    QualityBadge(st.height)
                    if (st.height > 0) Text("  ${st.height}p", color = K.Dim, fontSize = 12.sp)
                    if (st.attempts > 1 && st.attempt > 0) Text("  · izvor ${st.attempt}/${st.attempts}", color = K.Faint, fontSize = 12.sp)
                }
                if (cur != null) {
                    Text(cur.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Fmt.range(cur.start, cur.stop), color = K.Dim, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        ProgressLine(cur.progress(now), Modifier.width(220.dp))
                    }
                    next?.let { Text("Posle: ${Fmt.time(it.start)} ${it.title}", color = K.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else {
                    Text("${Geo.flag(c.country)} ${Geo.name(c.country)}", color = K.Dim, fontSize = 13.sp)
                }
            }
            Text(Fmt.time(now), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        if (touch) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Nazad", onBack)
                IconBtn(Icons.Filled.KeyboardArrowDown, "Prethodni", onPrev)
                IconBtn(Icons.Filled.KeyboardArrowUp, "Sledeći", onNext)
                IconBtn(Icons.AutoMirrored.Filled.List, "Lista", onList)
                IconBtn(if (c.id in favs) Icons.Filled.Star else Icons.Filled.StarBorder, "Omiljeni") { app.prefs.toggleFav(c.id) }
                IconBtn(Icons.Filled.MoreVert, "Opcije", onMore)
                if (app.cast.available.collectAsState().value) CastButton()
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Text("▲▼ kanali   ◀ lista   ▶ opcije   OK lista   0–9 broj kanala", color = K.Faint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ListOverlay(list: List<Channel>, current: Channel, modifier: Modifier, onPick: (Channel) -> Unit) {
    val app = LocalApp.current
    val favs by app.prefs.favs.collectAsState()
    val now = rememberNow(30_000)
    val idx = list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val state = remember { LazyListState((idx - 4).coerceAtLeast(0)) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    LazyColumn(
        modifier.width(460.dp).fillMaxHeight().background(Color(0xF0101318)),
        state = state, contentPadding = PaddingValues(vertical = 16.dp, horizontal = 10.dp),
    ) {
        itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
            ChannelRow(c, now, c.id in favs, modifier = if (i == idx) Modifier.focusRequester(fr) else Modifier,
                onClick = { onPick(c) }, onLong = { app.prefs.toggleFav(c.id) }, onFocus = {})
        }
    }
}

@Composable
private fun OptionsOverlay(c: Channel, st: PlayerController.State, modifier: Modifier, onSources: () -> Unit, onClose: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val favs by app.prefs.favs.collectAsState()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Column(modifier.then(if (app.isTv || LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) Modifier.width(380.dp).fillMaxHeight() else Modifier)
        .background(Color(0xF0101318)).padding(16.dp)) {
        Text(c.name, style = MaterialTheme.typography.titleMedium)
        val host = st.src?.url?.substringAfter("://")?.substringBefore('/') ?: "—"
        Text(
            listOfNotNull(
                if (st.width > 0) "${st.width}×${st.height}" else null,
                if (st.bitrate > 0) "%.1f Mbps".format(st.bitrate / 1_000_000f) else null,
                host,
            ).joinToString(" · "),
            color = K.Dim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        MenuItem(if (c.id in favs) "Ukloni iz omiljenih" else "Dodaj u omiljene", Modifier.focusRequester(fr)) { app.prefs.toggleFav(c.id) }
        MenuItem("Izaberi izvor", sub = "${c.srcs.size} ${if (c.srcs.size == 1) "izvor" else "izvora"} · sada ${st.attempt}/${st.attempts}") { onSources() }
        if (st.attempts > 1) MenuItem("Sledeći izvor") { app.player.nextSource(); onClose() }
        MenuItem("Ponovo učitaj") { app.player.retry(); onClose() }
        MenuItem("Lista kanala") { onClose(); nav.back() }
        MenuItem("Zatvori") { onClose() }
    }
}

@Composable
private fun NowNext(c: Channel, st: PlayerController.State, modifier: Modifier) {
    val app = LocalApp.current
    val now = rememberNow(15_000)
    val cur = app.epg.now(c.id, now)
    val next = app.epg.next(c.id, now)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${c.num}", color = K.Accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("${Geo.flag(c.country)} ${Geo.name(c.country)}", color = K.Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            QualityBadge(st.height)
        }
        if (cur != null) {
            Spacer(Modifier.height(6.dp))
            Text(cur.title, style = MaterialTheme.typography.titleMedium)
            Text(Fmt.range(cur.start, cur.stop), color = K.Dim, fontSize = 12.sp)
            ProgressLine(cur.progress(now), Modifier.padding(top = 4.dp))
            next?.let { Text("Posle: ${Fmt.time(it.start)} ${it.title}", color = K.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun PhoneList(list: List<Channel>, current: Channel, onPick: (Channel) -> Unit) {
    val app = LocalApp.current
    val favs by app.prefs.favs.collectAsState()
    val now = rememberNow(30_000)
    val idx = list.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
    val state = remember(list) { LazyListState((idx - 2).coerceAtLeast(0)) }
    LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
        itemsIndexed(list, key = { _, c -> c.id }) { _, c ->
            Box(if (c.id == current.id) Modifier.background(K.Selected, RoundedCornerShape(10.dp)) else Modifier) {
                ChannelRow(c, now, c.id in favs, onClick = { onPick(c) }, onLong = { app.prefs.toggleFav(c.id) }, onFocus = {})
            }
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50)) { _ ->
        Icon(icon, desc, Modifier.padding(10.dp).size(24.dp), tint = K.Text)
    }
}

@Composable
private fun NumberBox(n: String, modifier: Modifier) {
    val app = LocalApp.current
    val c = n.toIntOrNull()?.let { app.catalog.byNum(it) }
    Column(modifier.padding(24.dp).background(Color(0xE6101318), RoundedCornerShape(12.dp)).padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.End) {
        Text(n, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = K.Accent)
        Text(c?.name ?: "—", color = K.Dim, fontSize = 13.sp)
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier) {
    Text(text, modifier.padding(16.dp).background(Color(0xE6202833), RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 13.sp)
}

/** Hides status/navigation bars while the phone plays full screen. */
@Composable
private fun ImmersiveIf(on: Boolean) {
    val ctx = LocalContext.current
    DisposableEffect(on) {
        val win = (ctx as? Activity)?.window
        val ctl = win?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (on && ctl != null) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
