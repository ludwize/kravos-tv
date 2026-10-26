package net.kravos.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import net.kravos.tv.ui.theme.K
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clickable that works the same with a TV remote and a touch screen:
 * OK = click, OK held = long click (channel menu), tap / long-press on phones.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    selected: Boolean = false,
    idle: Color = Color.Transparent,
    focusedScale: Float = 1f,
    onFocus: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val focusCb by rememberUpdatedState(onFocus)
    val bg = when {
        focused -> K.Focus
        selected -> K.Selected
        else -> idle
    }
    Box(
        modifier
            .scale(if (focused) focusedScale else 1f)
            .clip(shape)
            .background(bg)
            .border(2.dp, if (focused) K.Accent else Color.Transparent, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) focusCb?.invoke() else down = false
            }
            .onKeyEvent { e ->
                if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        val rep = e.nativeKeyEvent.repeatCount
                        if (rep == 0) { down = true; longFired = false }
                        if (rep >= 1 && down && !longFired && longClick != null) { longFired = true; longClick?.invoke() }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        // Only a press that started here counts (OK on the previous screen must not click this).
                        if (down && !longFired) click()
                        down = false
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { click() },
                    onLongPress = { longClick?.invoke() },
                )
            }
            .focusable(),
    ) { content(focused) }
}

@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier, w: Dp = 64.dp, h: Dp = 40.dp) {
    Box(modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(Color(0xFF20252D)), contentAlignment = Alignment.Center) {
        val fallback: @Composable () -> Unit = {
            Text(
                initials(name), color = K.Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1,
            )
        }
        if (url.isNullOrBlank()) fallback()
        else SubcomposeAsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(3.dp),
            loading = { fallback() }, error = { fallback() },
        )
    }
}

private fun initials(n: String): String {
    val parts = n.split(' ', '-', '.').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "TV"
        parts.size == 1 -> parts[0].take(3).uppercase()
        else -> (parts[0].take(1) + parts[1].take(2)).uppercase()
    }
}

/** Resolution badge: SD / HD / FHD / 4K. */
@Composable
fun QualityBadge(height: Int, modifier: Modifier = Modifier) {
    if (height <= 0) return
    val (label, color) = when {
        height >= 2000 -> "4K" to K.Uhd
        height >= 1000 -> "FHD" to K.Good
        height >= 700 -> "HD" to K.Accent
        else -> "SD" to K.Faint
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = K.Accent) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = K.Dim, bg: Color = K.Card) {
    Box(modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

object Fmt {
    private val hm = SimpleDateFormat("HH:mm", Locale.forLanguageTag("sr-Latn-RS"))
    private val day = SimpleDateFormat("EEE d. MMM", Locale.forLanguageTag("sr-Latn-RS"))
    fun time(ms: Long): String = hm.format(Date(ms))
    fun range(a: Long, b: Long) = "${time(a)} – ${time(b)}"
    fun day(ms: Long): String = day.format(Date(ms))
    fun ago(ms: Long): String {
        if (ms <= 0) return "nikad"
        val m = (System.currentTimeMillis() - ms) / 60_000
        return when {
            m < 1 -> "upravo"
            m < 60 -> "pre $m min"
            m < 60 * 24 -> "pre ${m / 60} h"
            else -> "pre ${m / 1440} d"
        }
    }
    fun mins(ms: Long): String = "${(ms / 60_000).coerceAtLeast(0)} min"
}

/** Spinner that stays still when system animations are off (emulator tests: uiautomator can't dump a moving screen). */
@Composable
fun Spinner(modifier: Modifier = Modifier, color: Color = K.Accent, stroke: Dp = 3.dp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val still = remember {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (still) androidx.compose.material3.CircularProgressIndicator(progress = { 0.3f }, modifier = modifier, color = color, strokeWidth = stroke)
    else androidx.compose.material3.CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = stroke)
}
