package net.kravos.tv.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.kravos.tv.data.Channel
import net.kravos.tv.data.Http
import net.kravos.tv.data.Src
import net.kravos.tv.play.PlayerController

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(NotificationOptions.Builder().setTargetActivityClassName("net.kravos.tv.ui.MainActivity").build())
                .build(),
        )
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Phone -> TV via Google Cast (Chromecast, Shield, Google TV). Uses Google's Default Media Receiver,
 * which plays HLS over HTTPS without custom headers, so sources needing a Referer/User-Agent are skipped.
 */
class CastBridge(private val controller: PlayerController) {
    private var cast: CastContext? = null
    private var candidates: List<Src> = emptyList()
    private var idx = 0
    private var channel: Channel? = null

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun init(context: Context) {
        if (cast != null) return
        runCatching {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
                .addOnSuccessListener { c -> attach(c) }
                .addOnFailureListener { Log.w(Http.TAG, "cast unavailable: ${it.message}") }
        }.onFailure { Log.w(Http.TAG, "cast unavailable: ${it.message}") }
    }

    private fun attach(c: CastContext) {
        if (cast != null) return
        cast = c
        _available.value = true
        c.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        controller.castHandler = { ch, srcs -> if (session()?.isConnected == true) { load(ch, srcs); true } else false }
        session()?.takeIf { it.isConnected }?.let { controller.setCastDevice(it.castDevice?.friendlyName ?: "TV") }
    }

    private fun session(): CastSession? = cast?.sessionManager?.currentCastSession

    fun stopCasting() { cast?.sessionManager?.endCurrentSession(true) }

    fun clearMessage() { _message.value = null }

    private fun load(c: Channel, srcs: List<Src>) {
        channel = c
        // Receiver runs on an HTTPS page: plain-HTTP streams and header-protected streams can't play there.
        candidates = srcs.filter { it.url.startsWith("https://") && it.referrer.isNullOrBlank() && it.ua.isNullOrBlank() } +
            srcs.filter { it.url.startsWith("https://") && !(it.referrer.isNullOrBlank() && it.ua.isNullOrBlank()) }
        idx = 0
        if (candidates.isEmpty()) {
            _message.value = "${c.name} ne može da se pošalje na TV (izvor nije HTTPS). Pusti ga direktno na uređaju."
            return
        }
        loadCurrent()
    }

    private fun loadCurrent() {
        val c = channel ?: return
        val s = candidates.getOrNull(idx) ?: run {
            _message.value = "TV ne može da pusti ${c.name}. Ovaj kanal gledaj direktno u aplikaciji."
            return
        }
        val client = session()?.remoteMediaClient ?: return
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, c.name)
            putString(MediaMetadata.KEY_SUBTITLE, "Kravos TV")
            c.logo?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val kind = PlayerController.guessKind(s.url)
        val info = MediaInfo.Builder(s.url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(if (kind == "dash") "application/dash+xml" else "application/x-mpegURL")
            .setMetadata(meta)
            .build()
        client.unregisterCallback(remoteCallback)
        client.registerCallback(remoteCallback)
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        Log.i(Http.TAG, "cast ${c.name} -> ${s.url.take(100)}")
    }

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val st = session()?.remoteMediaClient?.mediaStatus ?: return
            if (st.playerState == MediaStatus.PLAYER_STATE_IDLE && st.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                idx++
                loadCurrent()
            }
        }
    }

    private val listener = object : SessionManagerListener<CastSession> {
        private fun connected(s: CastSession) {
            controller.setCastDevice(s.castDevice?.friendlyName ?: "TV")
            val st = controller.state.value
            st.channel?.let { c ->
                controller.pauseLocal()
                load(c, listOfNotNull(st.src) + c.srcs.filter { it.url != st.src?.url })
            }
        }

        private fun ended() {
            controller.setCastDevice(null)
            controller.retry()
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = ended()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) { _message.value = "Povezivanje sa TV-om nije uspelo." }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }
}
