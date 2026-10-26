package net.kravos.tv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.kravos.tv.BuildConfig
import net.kravos.tv.data.Geo
import net.kravos.tv.ui.LocalApp
import net.kravos.tv.ui.components.FocusBox
import net.kravos.tv.ui.components.Fmt
import net.kravos.tv.ui.theme.K
import net.kravos.tv.update.Release
import net.kravos.tv.update.Updater

private val EPG_CHOICES = listOf("RS", "BA", "HR", "DE", "AT", "CH", "UK", "IE", "FR", "IT", "ES", "PT", "NL", "BE", "GR", "HU", "RO", "BG", "PL", "CZ", "SK", "SE", "NO", "DK", "FI", "TR", "US", "CA", "MX")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab() {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val s by app.prefs.settings.collectAsState()
    val cat by app.catalog.status.collectAsState()
    val all by app.catalog.all.collectAsState()
    val check by app.health.progress.collectAsState()
    val epg by app.epg.status.collectAsState()
    val pls by app.prefs.playlists.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var updMsg by remember { mutableStateOf<String?>(null) }
    var dl by remember { mutableFloatStateOf(-1f) }
    val release by produceState<Release?>(null) { value = Updater.latest() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
        item { Section("Ažuriranje aplikacije") }
        item {
            val r = release
            val newer = r != null && Updater.isNewer(r)
            Action(
                if (newer) "Instaliraj ${r!!.name}" else "Verzija ${BuildConfig.VERSION_NAME}",
                updMsg ?: if (newer) "Nova verzija je spremna" else if (r == null) "Proveravam…" else "Imaš najnoviju verziju",
            ) {
                if (!newer || dl >= 0f) return@Action
                scope.launch {
                    runCatching {
                        dl = 0f
                        val f = Updater.download(ctx, r!!) { dl = it; updMsg = "Preuzimam ${(it * 100).toInt()}%" }
                        updMsg = if (Updater.install(ctx, f)) "Pokrećem instalaciju…" else "Dozvoli instalaciju iz ove aplikacije, pa pritisni ponovo"
                    }.onFailure { updMsg = "Greška: ${it.message}" }
                    dl = -1f
                }
            }
            if (dl >= 0f) LinearProgressIndicator(progress = { dl }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
        }

        item { Section("Kanali") }
        item {
            Action("Osveži listu kanala", if (cat.loading) cat.message else "${all.size} kanala · ažurirano ${Fmt.ago(cat.updatedAt)}") { app.refreshAll() }
        }
        item {
            Toggle("Kanali iz celog sveta (iptv-org)", "Javni indeks besplatnih TV strimova", s.iptvOrg) {
                app.prefs.update { p -> p.copy(iptvOrg = it) }; app.refreshAll()
            }
        }
        item {
            Toggle("Sakrij kanale koji ne rade", "Na osnovu provere sa tvoje mreže", s.hideDead) { app.prefs.update { p -> p.copy(hideDead = it) } }
        }
        item {
            val sub = if (check.running) "Provereno ${check.done}/${check.total} · radi ${check.ok}" else "Otvara svaki strim sa ove mreže i čita rezoluciju (traje 10–20 min za ceo svet)"
            Action("Proveri sve kanale sada", sub) { app.checkEverything() }
            if (check.running && check.total > 0) LinearProgressIndicator(progress = { check.done.toFloat() / check.total }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
        }

        item { Section("Moje M3U liste") }
        pls.forEach { pl ->
            item(key = "pl" + pl.id) {
                Action(pl.name, pl.url.take(60) + (pl.epgUrl?.let { " · EPG" } ?: "") + " — OK za brisanje") {
                    app.prefs.removePlaylist(pl.id); app.refreshAll()
                }
            }
        }
        item { Action("+ Dodaj M3U listu", "Lista tvog IPTV operatera ili bilo koja M3U/M3U8 lista") { adding = true } }

        item { Section("Reprodukcija") }
        item { Toggle("Uvek najviši kvalitet", "Ne spuštaj rezoluciju zbog brzine mreže", s.maxQuality) { app.prefs.update { p -> p.copy(maxQuality = it) }; app.player.applyQuality() } }
        item { Toggle("Pokreni poslednji kanal pri startu", "Kao kod set-top boksa", s.autoplayLast) { app.prefs.update { p -> p.copy(autoplayLast = it) } } }
        item {
            Toggle("Prihvati neispravne TLS sertifikate za video", "Mnoge male TV stanice imaju istekle sertifikate. Važi samo za video strim, ne za prijave.", s.lenientTls) {
                app.prefs.update { p -> p.copy(lenientTls = it) }
            }
        }

        item { Section("TV vodič (EPG)") }
        item {
            Action("Osveži program", if (epg.running) epg.message else "${epg.channels} kanala sa programom · ${Fmt.ago(epg.updatedAt)}") {
                scope.launch { app.refreshEpg(true) }
            }
        }
        item {
            Text("Zemlje za koje se preuzima program (plus zemlje omiljenih kanala):", color = K.Dim, fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EPG_CHOICES.forEach { cc ->
                    val on = cc in s.epgCountries
                    FocusBox(onClick = {
                        app.prefs.update { p -> p.copy(epgCountries = if (on) p.epgCountries - cc else p.epgCountries + cc) }
                    }, selected = on, idle = K.Card, shape = RoundedCornerShape(50)) { _ ->
                        Text("${Geo.flag(cc)} ${Geo.name(cc)}", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 13.sp, color = if (on) K.Text else K.Dim)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.width(1.dp))
            Text(
                "Kravos TV · ${BuildConfig.VERSION_NAME}\nKanali: javni iptv-org indeks (github.com/iptv-org/iptv) i tvoje liste. Program: epgshare01.",
                color = K.Faint, fontSize = 12.sp, modifier = Modifier.padding(14.dp),
            )
        }
    }
    if (adding) AddPlaylistDialog(onDismiss = { adding = false })
}

@Composable
private fun Section(t: String) {
    Text(t.uppercase(), color = K.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp, top = 22.dp, bottom = 6.dp))
}

@Composable
private fun Action(title: String, sub: String?, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp)) { _ ->
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            sub?.let { Text(it, color = K.Dim, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    FocusBox(onClick = { onChange(!value) }, modifier = Modifier.fillMaxWidth()) { _ ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                sub?.let { Text(it, color = K.Dim, fontSize = 12.sp) }
            }
            Switch(checked = value, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = K.Accent))
        }
    }
}
