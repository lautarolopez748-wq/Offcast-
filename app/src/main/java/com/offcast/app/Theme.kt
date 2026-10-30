package com.offcast.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class OffcastColors(
    val isLight: Boolean,
    val backgroundTop: Color,
    val backgroundMid: Color,
    val backgroundBottom: Color,
    val haloTop: Color,
    val haloBottom: Color,
    val surfaceTop: Color,
    val surfaceBottom: Color,
    val surfaceFlat: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val text: Color,
    val textMuted: Color,
    val accentStart: Color,
    val accentEnd: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val navIdle: Color,
    val danger: Color,
    val success: Color
)

private val DarkColors = OffcastColors(
    isLight = false,
    backgroundTop = Color(0xFF04060A),
    backgroundMid = Color(0xFF081426),
    backgroundBottom = Color(0xFF05090F),
    haloTop = Color(0x612F6FE4),
    haloBottom = Color(0x4D1B4C9E),
    surfaceTop = Color(0xEB12243D),
    surfaceBottom = Color(0xE00A1524),
    surfaceFlat = Color(0xFF16273F),
    borderTop = Color(0x477FB0FF),
    borderBottom = Color(0x0AFFFFFF),
    text = Color(0xFFE8F0FF),
    textMuted = Color(0xFFA7B8D4),
    accentStart = Color(0xFF2760F0),
    accentEnd = Color(0xFF1B46C4),
    accentSoft = Color(0xFF9CC2FF),
    onAccent = Color(0xFFFFFFFF),
    navIdle = Color(0xFF8496B8),
    danger = Color(0xFFFF7A8A),
    success = Color(0xFF4FA3FF)
)

private val LightColors = OffcastColors(
    isLight = true,
    backgroundTop = Color(0xFFF5F8FE),
    backgroundMid = Color(0xFFE4EDFC),
    backgroundBottom = Color(0xFFF7FAFF),
    haloTop = Color(0x332F6FE4),
    haloBottom = Color(0x1F1B4C9E),
    surfaceTop = Color(0xF2FFFFFF),
    surfaceBottom = Color(0xE6E9F0FB),
    surfaceFlat = Color(0xFFDCE6F6),
    borderTop = Color(0x331F4FD8),
    borderBottom = Color(0x0F1F4FD8),
    text = Color(0xFF0B1524),
    textMuted = Color(0xFF52627C),
    accentStart = Color(0xFF2760F0),
    accentEnd = Color(0xFF1B46C4),
    accentSoft = Color(0xFF1F4FD8),
    onAccent = Color(0xFFFFFFFF),
    navIdle = Color(0xFF5A6B87),
    danger = Color(0xFFB3261E),
    success = Color(0xFF1F6FE0)
)

@Immutable
data class OffcastType(
    val pageTitle: TextStyle = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    val sectionTitle: TextStyle = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    val itemTitle: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    val caption: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    val label: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    val chip: TextStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
)

object Space {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

object Radius {
    val s: Dp = 8.dp
    val m: Dp = 16.dp
    val l: Dp = 24.dp
    val xl: Dp = 32.dp
}

val LocalOffcastColors = staticCompositionLocalOf { DarkColors }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6FA8FF),
    onPrimary = Color(0xFF04101F),
    secondary = Color(0xFF4D8DFF),
    background = Color(0xFF04060A),
    onBackground = Color(0xFFE8F0FF),
    surface = Color(0xFF0A1220),
    onSurface = Color(0xFFE8F0FF),
    error = Color(0xFFFF7A8A),
    onError = Color(0xFF2A0006)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F4FD8),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2760F0),
    background = Color(0xFFF5F8FE),
    onBackground = Color(0xFF0B1524),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1524),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

object Offcast {
    val colors: OffcastColors
        @Composable get() = LocalOffcastColors.current
    val type: OffcastType = OffcastType()
}

@Composable
fun OffcastTheme(sunMode: Boolean, content: @Composable () -> Unit) {
    val colors = if (sunMode) LightColors else DarkColors
    CompositionLocalProvider(LocalOffcastColors provides colors) {
        MaterialTheme(
            colorScheme = if (sunMode) LightScheme else DarkScheme,
            content = content
        )
    }
}

@Composable
fun accentBrush(): Brush = Brush.horizontalGradient(
    listOf(Offcast.colors.accentStart, Offcast.colors.accentEnd)
)

@Composable
fun ProgressBar(progress: Float, height: Dp = 4.dp, track: Boolean = true) {
    val c = Offcast.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(if (track) c.surfaceFlat else Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(c.accentSoft)
        )
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Offcast.colors
    val shape = RoundedCornerShape(Radius.l)
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(accentBrush())
                else Modifier
                    .background(c.surfaceTop)
                    .border(1.dp, c.borderTop, shape)
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = Offcast.type.caption,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) c.onAccent else c.accentSoft,
            maxLines = 1
        )
    }
}