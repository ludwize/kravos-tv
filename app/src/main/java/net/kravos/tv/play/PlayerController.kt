package net.kravos.tv.play

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.kravos.tv.data.Channel
import net.kravos.tv.data.Health
import net.kravos.tv.data.Http
import net.kravos.tv.data.Prefs
import net.kravos.tv.data.Src

/**
 * One ExoPlayer for the whole app. Tries a channel's sources best-first and fails over automatically:
 * a source that errors or keeps buffering is reported dead and the next one starts.
 */
@OptIn(UnstableApi::class)
class PlayerController(private val ctx: Context, private val prefs: Prefs, private val health: Health) {
    data class State(
        val channel: Channel? = null,
        val src: Src? = null,
        val attempt: Int = 0,
        val attempts: Int = 0,
        val buffering: Boolean = false,
        val ready: Boolean = false,
        val failed: Boolean = false,
        val message: String? = null,
        val height: Int = 0,
        val width: Int = 0,
        val bitrate: Int = 0,
        val castDevice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdog: Job? = null
    private var attempts: List<Pair<Src, String>> = emptyList()
    private var idx = 0
    private var exo: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    /** Set by the cast layer: when casting, a channel change goes to the TV instead of the local player. */
    var castHandler: ((Channel, List<Src>) -> Boolean)? = null

    fun player(): ExoPlayer = exo ?: build().also { exo = it }

    private fun build(): ExoPlayer {
        val ts = DefaultTrackSelector(ctx)
        trackSelector = ts
        applyQuality()
        val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 45_000, 1_200, 2_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(ctx, renderers)
            .setTrackSelector(ts)
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(listener)
        return p
    }

    fun applyQuality() {
        val ts = trackSelector ?: return
        ts.parameters = ts.buildUponParameters()
            .setForceHighestSupportedBitrate(prefs.settings.value.maxQuality)
            .setPreferredAudioLanguages("srp", "hrv", "bos", "eng")
            .build()
    }

    fun play(c: Channel, forceSrc: Src? = null) {
        prefs.pushRecent(c.id)
        val ordered = forceSrc?.let { listOf(it) + health.ordered(c).filter { s -> s.url != it.url } } ?: health.ordered(c)
        castHandler?.let { h -> if (h(c, ordered)) { _state.value = State(channel = c, castDevice = _state.value.castDevice); return } }
        attempts = ordered.flatMap { s ->
            val k = health.kind(s.url) ?: guessKind(s.url)
            // Unknown container: try HLS first, then plain transport stream.
            if (k == "?") listOf(s to "hls", s to "progressive") else listOf(s to k)
        }
        idx = 0
        _state.value = State(channel = c, attempts = attempts.size, castDevice = _state.value.castDevice)
        start()
    }

    fun nextSource() {
        val c = _state.value.channel ?: return
        if (idx + 1 < attempts.size) { idx++; start() } else play(c)
    }

    fun retry() { _state.value.channel?.let { play(it) } }

    fun stop() {
        watchdog?.cancel()
        exo?.stop()
        exo?.clearMediaItems()
        _state.value = State(castDevice = _state.value.castDevice)
    }

    fun pauseLocal() { exo?.stop() }

    fun setCastDevice(name: String?) { _state.value = _state.value.copy(castDevice = name) }

    fun release() {
        watchdog?.cancel()
        exo?.release()
        exo = null
    }

    private fun start() {
        val (src, kind) = attempts.getOrNull(idx) ?: return fail()
        _state.value = _state.value.copy(src = src, attempt = idx + 1, buffering = true, ready = false, failed = false,
            message = if (idx > 0) "Prebacujem na drugi izvor (${idx + 1}/${attempts.size})…" else null, height = 0, width = 0)
        val p = player()
        p.setMediaSource(mediaSource(src, kind))
        p.prepare()
        p.playWhenReady = true
        armWatchdog(25_000)
        Log.i(Http.TAG, "play ${_state.value.channel?.name} [$kind] ${src.url.take(120)}")
    }

    private fun mediaSource(src: Src, kind: String): MediaSource {
        val lenient = prefs.settings.value.lenientTls
        val headers = HashMap<String, String>()
        src.referrer?.takeIf { it.isNotBlank() }?.let { r ->
            headers["Referer"] = r
            runCatching { Uri.parse(r) }.getOrNull()?.let { u -> if (u.scheme != null && u.host != null) headers["Origin"] = "${u.scheme}://${u.host}" }
        }
        val dsf = OkHttpDataSource.Factory(if (lenient) Http.mediaClient() else Http.client())
            .setUserAgent(src.ua?.takeIf { it.isNotBlank() } ?: Http.BROWSER_UA)
            .setDefaultRequestProperties(headers)
        val c = _state.value.channel
        val item = MediaItem.Builder()
            .setUri(src.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(c?.name).setArtworkUri(c?.logo?.let(Uri::parse)).build())
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build())
            .build()
        return when (kind) {
            "hls" -> HlsMediaSource.Factory(dsf).setAllowChunklessPreparation(true).createMediaSource(item)
            "dash" -> DashMediaSource.Factory(dsf).createMediaSource(item)
            else -> ProgressiveMediaSource.Factory(
                dsf,
                DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES),
            ).createMediaSource(item)
        }
    }

    private fun armWatchdog(ms: Long) {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(ms)
            if (!_state.value.ready || _state.value.buffering) {
                Log.w(Http.TAG, "watchdog: no picture after ${ms / 1000}s")
                failCurrent("Izvor ne odgovara")
            }
        }
    }

    private fun failCurrent(reason: String) {
        val cur = attempts.getOrNull(idx) ?: return
        // Only report dead after the last container guess for this URL failed.
        val laterSameUrl = attempts.drop(idx + 1).any { it.first.url == cur.first.url }
        if (!laterSameUrl) health.report(cur.first.url, false)
        Log.w(Http.TAG, "source failed ($reason): ${cur.first.url.take(120)}")
        if (idx + 1 < attempts.size) { idx++; start() } else fail()
    }

    private fun fail() {
        watchdog?.cancel()
        exo?.stop()
        _state.value = _state.value.copy(buffering = false, ready = false, failed = true,
            message = "Kanal trenutno ne radi. Nijedan izvor se ne otvara sa ove mreže.")
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    watchdog?.cancel()
                    val wasReady = _state.value.ready
                    _state.value = _state.value.copy(buffering = false, ready = true, failed = false, message = null)
                    if (!wasReady) attempts.getOrNull(idx)?.let { (s, k) -> health.report(s.url, true, _state.value.height, k) }
                }
                Player.STATE_BUFFERING -> {
                    _state.value = _state.value.copy(buffering = true)
                    // Live stream stuck buffering after it already played: give it time, then fail over.
                    if (_state.value.ready) armWatchdog(30_000)
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = exo
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
                p.seekToDefaultPosition(); p.prepare(); return
            }
            failCurrent(error.errorCodeName)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height <= 0) return
            _state.value = _state.value.copy(height = videoSize.height, width = videoSize.width)
            attempts.getOrNull(idx)?.let { (s, k) -> if (_state.value.ready) health.report(s.url, true, videoSize.height, k) }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val f = exo?.videoFormat ?: return
            _state.value = _state.value.copy(bitrate = f.bitrate.coerceAtLeast(0))
        }
    }

    companion object {
        fun guessKind(url: String): String {
            val u = url.lowercase().substringBefore('#')
            val path = u.substringBefore('?')
            return when {
                "m3u8" in u -> "hls"
                path.endsWith(".mpd") || "manifest(format=mpd" in u -> "dash"
                path.endsWith(".ts") || path.endsWith(".mp4") || path.endsWith(".flv") || path.endsWith(".mkv") -> "progressive"
                else -> "?"
            }
        }
    }
}
