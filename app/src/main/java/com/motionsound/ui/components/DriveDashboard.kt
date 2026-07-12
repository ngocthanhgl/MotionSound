package com.motionsound.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motionsound.drive.DrivingConfig
import com.motionsound.drive.DrivingState

@Composable
fun SpeedGauge(speedKmh: Float, maxSpeed: Int, modifier: Modifier = Modifier) {
    val speed = speedKmh.coerceAtLeast(0f)
    val fraction = (speed / maxSpeed).coerceIn(0f, 1f)
    val speedColor = when {
        fraction < 0.5f -> Color(0xFF4CAF50)
        fraction < 0.8f -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(160.dp).padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 12.dp.toPx()
                val padding = strokeWidth / 2 + 8.dp.toPx()
                val arcSize = minOf(size.width, size.height) - padding * 2
                val topLeft = Offset((size.width - arcSize) / 2f, (size.height - arcSize) / 2f + 10.dp.toPx())
                val arcSizePx = androidx.compose.ui.geometry.Size(arcSize, arcSize)

                drawArc(
                    color = Color(0xFF333333),
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSizePx,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = speedColor,
                    startAngle = 150f,
                    sweepAngle = 240f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSizePx,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${speed.toInt()}",
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    color = speedColor
                )
                Text(
                    text = "km/h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun IntensityBar(
    label: String,
    value: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = "%.2f".format(value),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
        )
    }
}

@Composable
fun DrivingStateIndicator(
    state: DrivingState,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    val (label, color) = remember(state) {
        when (state) {
            DrivingState.IDLE -> "Idle" to Color(0xFF9E9E9E)
            DrivingState.SLOW_MANEUVERING -> "Maneuvering" to Color(0xFF2196F3)
            DrivingState.ACCELERATING -> "Accelerating" to Color(0xFF4CAF50)
            DrivingState.CRUISING -> "Cruising" to Color(0xFF00BCD4)
            DrivingState.DECELERATING -> "Decelerating" to Color(0xFFF44336)
            DrivingState.CORNERING -> "Cornering" to Color(0xFFFF9800)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
            Text(
                text = "confidence: %.0f%%".format(confidence * 100),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EQVisualizer(bands: FloatArray, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        bands.forEachIndexed { index, gain ->
            val fraction = (gain / DrivingConfig.MAX_BOOST_DB).coerceIn(-1f, 1f)
            val absFrac = kotlin.math.abs(fraction).coerceIn(0.05f, 1f)
            val barColor = if (fraction >= 0)
                Color(0xFF4CAF50).copy(alpha = 0.5f + 0.5f * fraction)
            else
                Color(0xFFF44336).copy(alpha = 0.5f + 0.5f * (-fraction))

            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight(0.6f * absFrac + 0.1f)
                    .padding(horizontal = 2.dp)
                    .background(barColor, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (index == 0 || absFrac > 0.2f) {
                    Text(
                        text = "%.0f".format(gain),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontSize = 8.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    valueLabel: String? = null
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueLabel ?: "%.2f".format(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}
