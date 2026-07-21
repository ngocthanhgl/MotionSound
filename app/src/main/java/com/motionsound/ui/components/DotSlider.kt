package com.motionsound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.value
import androidx.compose.ui.semantics.rangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    val trackHeight = 3.dp
    val thumbSize = 28.dp
    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant
    val rangeLen = valueRange.endInclusive - valueRange.start
    val fraction = if (rangeLen > 0f)
        ((value - valueRange.start) / rangeLen).coerceIn(0f, 1f) else 0f

    val label = "${(fraction * 100).toInt()}%"

    BoxWithConstraints(
        modifier = modifier
            .height(48.dp)
            .fillMaxWidth()
            .semantics {
                contentDescription = "Seek slider"
                disabled = !enabled
                value = label
                rangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(
                    value,
                    valueRange.start..valueRange.endInclusive,
                    (valueRange.endInclusive - valueRange.start) / 100f
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
            drawRoundRect(
                color = inactiveColor,
                size = size,
                cornerRadius = CornerRadius(h / 2)
            )
            if (fraction > 0f) {
                drawRoundRect(
                    color = color,
                    size = Size(size.width * fraction, h),
                    cornerRadius = CornerRadius(h / 2)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .offset(x = thumbOffsetX)
                .clip(CircleShape)
                .background(color)
        )
    }
}
