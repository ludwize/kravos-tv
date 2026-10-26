package net.kravos.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object K {
    val Bg = Color(0xFF0B0D10)
    val Panel = Color(0xFF12151A)
    val Card = Color(0xFF1A1E24)
    val CardHi = Color(0xFF232830)
    val Divider = Color(0xFF262B33)
    val Accent = Color(0xFF4DA3FF)
    val AccentDim = Color(0xFF1B3A5C)
    val Focus = Color(0xFF203A5A)
    val Selected = Color(0xFF1A2633)
    val Text = Color(0xFFF2F4F7)
    val Dim = Color(0xFF9AA2AD)
    val Faint = Color(0xFF5E6672)
    val Live = Color(0xFFFF453A)
    val Good = Color(0xFF30D158)
    val Warn = Color(0xFFFFB020)
    val Gold = Color(0xFFFFD60A)
    val Uhd = Color(0xFFB98CFF)
}

private val Scheme = darkColorScheme(
    primary = K.Accent,
    onPrimary = Color(0xFF001D36),
    primaryContainer = K.AccentDim,
    onPrimaryContainer = Color(0xFFD2E6FF),
    secondary = Color(0xFFB9C1CC),
    background = K.Bg,
    onBackground = K.Text,
    surface = K.Bg,
    onSurface = K.Text,
    surfaceVariant = K.Card,
    onSurfaceVariant = K.Dim,
    surfaceContainerLowest = K.Bg,
    surfaceContainerLow = K.Panel,
    surfaceContainer = K.Card,
    surfaceContainerHigh = K.CardHi,
    surfaceContainerHighest = Color(0xFF2B313A),
    outline = Color(0xFF4A515C),
    outlineVariant = K.Divider,
    error = K.Live,
)

private val Type = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 13.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    )
}

@Composable
fun KravosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type) {
        // No Surface at the root, so set the default text/icon color explicitly (otherwise it is black).
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides K.Text, content = content)
    }
}
