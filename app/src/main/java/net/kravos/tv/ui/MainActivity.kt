package net.kravos.tv.ui

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.kravos.tv.App
import net.kravos.tv.ui.screens.HomeScreen
import net.kravos.tv.ui.screens.PlayerScreen
import net.kravos.tv.ui.theme.K
import net.kravos.tv.ui.theme.KravosTheme

class MainActivity : AppCompatActivity() {
    private val nav = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        if (!app.isTv) {
            enableEdgeToEdge()
            app.cast.init(this)
        }
        setContent {
            KravosTheme {
                CompositionLocalProvider(LocalApp provides app, LocalNav provides nav) {
                    Root()
                }
            }
        }
    }
}

@Composable
private fun Root() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val route = nav.stack.lastOrNull()
    BackHandler(enabled = route != null) { nav.back() }

    // Home: Back returns to Kanali first; on Kanali a second Back within 2 s leaves the app (like TiviMate).
    var lastBack by remember { mutableLongStateOf(0L) }
    var exitHint by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity
    BackHandler(enabled = route == null) {
        val now = System.currentTimeMillis()
        when {
            nav.tab.value != Tab.CHANNELS -> nav.tab.value = Tab.CHANNELS
            now - lastBack < 2_000 -> activity?.finish()
            else -> { lastBack = now; exitHint = true }
        }
    }
    LaunchedEffect(exitHint) { if (exitHint) { delay(2_000); exitHint = false } }

    LaunchedEffect(Unit) {
        if (!app.prefs.settings.value.autoplayLast) return@LaunchedEffect
        val id = app.prefs.lastChannel ?: return@LaunchedEffect
        withTimeoutOrNull(60_000) { app.catalog.all.first { it.isNotEmpty() } }
        app.catalog.byId(id)?.let { nav.play(it, listOf(it)) }
    }

    Box(Modifier.fillMaxSize().background(K.Bg)) {
        when (route) {
            is Route.Player -> PlayerScreen(route)
            null -> HomeScreen()
        }
        if (exitHint) Text(
            "Pritisni Nazad još jednom za izlaz",
            Modifier.align(Alignment.BottomCenter).padding(32.dp).background(K.CardHi, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp),
            color = K.Text,
        )
    }
}
