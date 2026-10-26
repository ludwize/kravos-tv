package net.kravos.tv

import android.app.Application
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.kravos.tv.cast.CastBridge
import net.kravos.tv.data.Catalog
import net.kravos.tv.data.Epg
import net.kravos.tv.data.Geo
import net.kravos.tv.data.Health
import net.kravos.tv.data.Http
import net.kravos.tv.data.Prefs
import net.kravos.tv.play.PlayerController
import net.kravos.tv.work.RefreshWorker

class App : Application(), ImageLoaderFactory {
    lateinit var prefs: Prefs
        private set
    lateinit var health: Health
        private set
    lateinit var catalog: Catalog
        private set
    lateinit var epg: Epg
        private set
    lateinit var player: PlayerController
        private set
    lateinit var cast: CastBridge
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var isTv = false
        private set
    private var startup: Job? = null

    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        isTv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        prefs = Prefs(this)
        health = Health(this, prefs)
        catalog = Catalog(this, prefs, health)
        epg = Epg(this)
        player = PlayerController(this, prefs, health)
        cast = CastBridge(player)
        RefreshWorker.schedule(this)
        startup = scope.launch {
            catalog.loadCached()
            if (catalog.all.value.isEmpty() || catalog.isStale()) catalog.refresh()
            launch { refreshEpg(false) }
            checkPriority()
        }
    }

    /** Countries whose guide we download: the user's choice + countries of favorites and recently watched. */
    fun epgCountries(): Set<String> {
        val s = prefs.settings.value
        val extra = (prefs.favs.value + prefs.recent.value.take(10)).mapNotNull { catalog.byId(it)?.country }
        return s.epgCountries + extra
    }

    suspend fun refreshEpg(force: Boolean) {
        epg.refresh(catalog.all.value, epgCountries(), prefs.favs.value.toSet(), catalog.playlistEpg, force)
    }

    /** Quick check of what matters most on start: favorites, own lists and the ex-YU region. */
    suspend fun checkPriority() {
        val favs = prefs.favs.value.toSet()
        val list = catalog.all.value.filter { it.id in favs || it.origin != "iptv" || Geo.tier(it.country) <= 1 }
        health.checkAll(list, maxAgeMs = 12 * Health.HOUR)
    }

    fun checkEverything() = scope.launch {
        health.checkAll(catalog.all.value, maxAgeMs = 6 * Health.HOUR)
    }

    fun refreshAll() = scope.launch {
        catalog.refresh()
        refreshEpg(true)
        checkPriority()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Http.client() }
            .components { add(SvgDecoder.Factory()) }
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(150L * 1024 * 1024).build() }
            .respectCacheHeaders(false)
            .crossfade(100)
            .build()
}
