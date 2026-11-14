package com.cwtrainer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Palette: near-black panel, amber "dial lamp" accent. */
object Cw {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val SurfaceHi = Color(0xFF222936)
    val Outline = Color(0xFF2E3644)
    val Accent = Color(0xFFFFB454)
    val Good = Color(0xFF5BD68B)
    val Bad = Color(0xFFFF6B6B)
    val Text = Color(0xFFE6EAF2)
    val Dim = Color(0xFF8B95A7)
}

@Composable
fun CwTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cw.Accent,
            onPrimary = Color.Black,
            secondary = Cw.Accent,
            onSecondary = Color.Black,
            background = Cw.Bg,
            onBackground = Cw.Text,
            surface = Cw.Surface,
            onSurface = Cw.Text,
            surfaceVariant = Cw.SurfaceHi,
            onSurfaceVariant = Cw.Dim,
            error = Cw.Bad,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides Cw.Text, content = content)
    }
}

/** Page frame: title bar with a large back target, then the content. */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    scroll: Boolean = true,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Cw.Bg).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Text("←", fontSize = 24.sp, color = Cw.Text) }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Cw.Text,
                modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1,
            )
            trailing()
        }
        val body = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
        Column(
            modifier = if (scroll) body.verticalScroll(rememberScrollState()) else body,
            content = content,
        )
    }
}

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Cw.Accent,
    textColor: Color = Color.Black,
    height: Dp = 60.dp,
    fontSize: TextUnit = 18.sp,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) color else Cw.SurfaceHi)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (enabled) textColor else Cw.Dim, fontSize = fontSize,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

/** Secondary button: same size, quiet colours. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 60.dp) {
    BigButton(text, onClick, modifier, enabled, color = Cw.SurfaceHi, textColor = Cw.Text, height = height)
}

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Cw.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color)
    Column(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base).padding(16.dp),
        content = content,
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) Cw.Accent else Cw.SurfaceHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.Black else Cw.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A row of equally wide chips: a segmented control. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) Chip(label(o), o == selected, { onSelect(o) }, Modifier.weight(1f))
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Cw.Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp, modifier = modifier)
}

@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Color = Cw.Accent, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(Cw.SurfaceHi)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(height / 2)).background(color))
    }
}

/** One key of an on-screen keyboard or choice grid. Use inside a Row with Modifier.weight. */
@Composable
fun Key(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Cw.SurfaceHi,
    textColor: Color = Cw.Text,
    height: Dp = 52.dp,
    fontSize: TextUnit = 20.sp,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .border(1.dp, Cw.Outline, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

fun percent(fraction: Double): String = "${Math.round(fraction * 100)} %"

fun wpmText(wpm: Double): String = if (wpm == Math.floor(wpm)) wpm.toInt().toString() else "%.1f".format(wpm)
