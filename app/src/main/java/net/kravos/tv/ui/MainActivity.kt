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
    }
}
