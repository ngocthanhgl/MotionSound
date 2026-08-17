package com.motionsound.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class ComicColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val ink: Color,
    val textMuted: Color,
    val yellow: Color,
    val red: Color,
    val blue: Color,
    val green: Color,
    val orange: Color,
    val purple: Color,
    val shadow: Color
)

fun comicColors(): ComicColors = ComicColors(
    isDark = false,
    background = Color(0xFFF0E6D2),
    surface = Color(0xFFF7EFDF),
    surfaceAlt = Color(0xFFE6D9B8),
    ink = Color(0xFF1B1B1B),
    textMuted = Color(0xFF6E675B),
    yellow = Color(0xFFB04A2E),
    red = Color(0xFFE53935),
    blue = Color(0xFF2196F3),
    green = Color(0xFF43A047),
    orange = Color(0xFFFF9800),
    purple = Color(0xFF9C27B0),
    shadow = Color(0xFF1B1B1B)
)

fun comicDarkColors(): ComicColors = ComicColors(
    isDark = true,
    background = Color(0xFF191612),
    surface = Color(0xFF211D17),
    surfaceAlt = Color(0xFF2C2720),
    ink = Color(0xFFF0E8D6),
    textMuted = Color(0xFFB2A98F),
    yellow = Color(0xFFFF4138),
    red = Color(0xFFFF3B30),
    blue = Color(0xFF42A5F5),
    green = Color(0xFF66BB6A),
    orange = Color(0xFFFFA726),
    purple = Color(0xFFBA68C8),
    shadow = Color(0xFF000000)
)

val LocalComicColors = staticCompositionLocalOf { comicColors() }

private fun Modifier.comicShadow(
    color: Color,
    offset: Dp,
    cornerRadius: Dp
): Modifier = this.drawBehind {
    val r = cornerRadius.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(offset.toPx(), offset.toPx()),
        size = Size(size.width, size.height),
        cornerRadius = CornerRadius(r, r)
    )
}

private fun Modifier.comicFill(
    color: Color,
    cornerRadius: Dp
): Modifier = this.drawBehind {
    val r = cornerRadius.toPx()
    drawRoundRect(color = color, cornerRadius = CornerRadius(r, r))
}

private fun Modifier.comicStroke(
    color: Color,
    width: Dp,
    cornerRadius: Dp
): Modifier = this.drawBehind {
    val r = cornerRadius.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = width.toPx())
    )
}

fun Modifier.comicPanel(
    containerColor: Color,
    borderColor: Color,
    shadowColor: Color,
    borderWidth: Dp = 2.5.dp,
    shadowOffset: Dp = 4.dp,
    cornerRadius: Dp = 0.dp
): Modifier = this
    .comicShadow(shadowColor, shadowOffset, cornerRadius)
    .comicFill(containerColor, cornerRadius)
    .comicStroke(borderColor, borderWidth, cornerRadius)

fun Modifier.comicBorder(
    color: Color,
    width: Dp = 2.5.dp,
    cornerRadius: Dp = 0.dp
): Modifier = this.comicStroke(color, width, cornerRadius)

private fun burstPath(size: Size, spikes: Int, innerRatio: Float, scale: Float): Path {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outer = min(size.width, size.height) / 2f * scale
    val inner = outer * innerRatio
    val path = Path()
    val total = spikes * 2
    val step = 360.0 / total
    var i = 0
    while (i < total) {
        val r = if (i % 2 == 0) outer else inner
        val a = Math.toRadians(-90.0 + i * step)
        val x = cx + (cos(a) * r).toFloat()
        val y = cy + (sin(a) * r).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        i++
    }
    path.close()
    return path
}

@Composable
fun ComicBurst(
    color: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    spikes: Int = 10,
    innerRatio: Float = 0.45f
) {
    Canvas(modifier) {
        drawPath(burstPath(size, spikes, innerRatio, 1f), color = borderColor)
        drawPath(burstPath(size, spikes, innerRatio, 0.78f), color = color)
    }
}


@Composable
fun ComicProgressBar(
    progress: Float,
    color: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    trackColor: Color? = null,
    indeterminate: Boolean = false
) {
    val track = trackColor ?: color.copy(alpha = 0.18f)
    val animated by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart)
    )
    val effectiveProgress = if (indeterminate) animated else progress
    Canvas(modifier) {
        val h = height.toPx()
        drawRoundRect(
            color = borderColor,
            size = Size(size.width, h),
            cornerRadius = CornerRadius(0f, 0f)
        )
        drawRoundRect(
            color = track,
            topLeft = Offset(1f, 1f),
            size = Size(size.width - 2f, h - 2f),
            cornerRadius = CornerRadius(0f, 0f)
        )
        val w = (size.width - 2f) * effectiveProgress.coerceIn(0f, 1f)
        if (w > 1f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(1f, 1f),
                size = Size(w, h - 2f),
                cornerRadius = CornerRadius(0f, 0f)
            )
            drawRoundRect(
                color = borderColor.copy(alpha = 0.5f),
                topLeft = Offset(1f, 1f),
                size = Size(w, h - 2f),
                cornerRadius = CornerRadius(0f, 0f),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

@Composable
fun ComicTag(
    text: String,
    color: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    cornerRadius: Dp = 0.dp
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.comicPanel(
            containerColor = color,
            borderColor = borderColor,
            shadowColor = borderColor.copy(alpha = 0.35f),
            shadowOffset = 2.dp,
            borderWidth = 2.dp,
            cornerRadius = cornerRadius
        )
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = textColor,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
