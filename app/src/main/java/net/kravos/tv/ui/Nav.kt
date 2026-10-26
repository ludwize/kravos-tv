package net.kravos.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kravos.tv.App
import net.kravos.tv.data.Channel
import net.kravos.tv.data.Group

enum class Tab(val label: String) { CHANNELS("Kanali"), GUIDE("Vodič"), SEARCH("Pretraga"), SETTINGS("Podešavanja") }

sealed class Route {
    data class Player(val channelId: String) : Route()
}

class Navigator {
    val stack = mutableStateListOf<Route>()
    val tab = mutableStateOf(Tab.CHANNELS)
    val groupId = mutableStateOf<String?>(null)
    /** Channels the player zaps through with up/down (the list the channel was opened from). */
    var zap: List<Channel> = emptyList()
    /** Channel to focus in the list when coming back from the player. */
    var focusId: String? = null

    fun play(c: Channel, list: List<Channel>) {
        zap = list.ifEmpty { listOf(c) }
        focusId = c.id
        if (stack.lastOrNull() is Route.Player) stack.removeAt(stack.lastIndex)
        stack.add(Route.Player(c.id))
    }

    fun back(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false
}

val LocalNav = staticCompositionLocalOf { Navigator() }
val LocalApp = staticCompositionLocalOf<App> { error("no app") }

/** Side-rail groups, recomputed off the main thread when the catalog, favorites or settings change. */
@Composable
fun rememberGroups(app: App): List<Group> {
    val all by app.catalog.all.collectAsState()
    val favs by app.prefs.favs.collectAsState()
    val recent by app.prefs.recent.collectAsState()
    val s by app.prefs.settings.collectAsState()
    val running by app.health.progress.collectAsState()
    val pls by app.prefs.playlists.collectAsState()
    val v = produceState(emptyList<Group>(), all, favs, recent, s, running.running, pls) {
        value = withContext(Dispatchers.Default) { app.catalog.groups(favs, recent, s) }
    }
    return v.value
}

@Composable
fun rememberNow(periodMs: Long = 30_000): Long {
    val v = produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return v.value
}
