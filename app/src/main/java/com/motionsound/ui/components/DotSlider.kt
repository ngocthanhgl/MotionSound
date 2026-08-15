package com.motionsound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.comicBorder

@Composable
fun DotSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val comic = LocalComicColors.current
    val trackHeight = 7.dp
    val thumbSize = 22.dp
    val inactiveColor = comic.surfaceAlt
    val borderColor = comic.ink
    val rangeLen = valueRange.endInclusive - valueRange.start
    val fraction = if (rangeLen > 0f)
        ((value - valueRange.start) / rangeLen).coerceIn(0f, 1f) else 0f

    val label = "${(fraction * 100).toInt()}%"

    BoxWithConstraints(
        modifier = modifier
            .height(40.dp)
            .fillMaxWidth()
            .semantics {
                contentDescription = "Seek slider"
                if (!enabled) disabled()
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                    value,
                    valueRange.start..valueRange.endInclusive,
                    100
                )
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(
                            (valueRange.start + f * (valueRange.endInclusive - valueRange.start))
                                .coerceIn(valueRange)
                        )
                    },
                    onDragEnd = { onValueChangeFinished?.invoke() },
                    onDragCancel = { onValueChangeFinished?.invoke() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(
                            (valueRange.start + f * (valueRange.endInclusive - valueRange.start))
                                .coerceIn(valueRange)
                        )
                    }
                )
            }
    ) {
        val width = maxWidth
        val thumbOffsetX: Dp = (width * fraction - thumbSize / 2)
            .coerceIn(0.dp, (width - thumbSize).coerceAtLeast(0.dp))

        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.CenterStart)
        ) {
            val h = size.height
            // track outline
            drawRoundRect(
                color = borderColor,
                size = Size(size.width, h),
                cornerRadius = CornerRadius(0f, 0f)
            )
            drawRoundRect(
                color = inactiveColor,
                size = Size(size.width - 2.dp.toPx(), h - 2.dp.toPx()),
                topLeft = androidx.compose.ui.geometry.Offset(1.dp.toPx(), 1.dp.toPx()),
                cornerRadius = CornerRadius(0f, 0f)
            )
            if (fraction > 0f) {
                val fillWidth = (size.width - 2.dp.toPx()) * fraction
                drawRoundRect(
                    color = color,
                    size = Size(fillWidth, h - 2.dp.toPx()),
                    topLeft = androidx.compose.ui.geometry.Offset(1.dp.toPx(), 1.dp.toPx()),
                    cornerRadius = CornerRadius(0f, 0f)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .offset(x = thumbOffsetX)
                .clip(RoundedCornerShape(0.dp))
                .background(Color.White)
                .comicBorder(borderColor, 2.5.dp, cornerRadius = 0.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(0.dp))
                    .background(color)
            )
        }
    }
}