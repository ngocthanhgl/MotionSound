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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
        fraction < 0.5f -> MaterialTheme.colorScheme.primary
        fraction < 0.8f -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        else -> MaterialTheme.colorScheme.error
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
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
                    color = trackColor,
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
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
fun DrivingStateIndicator(
    state: DrivingState,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    val sv = MaterialTheme.colorScheme.onSurfaceVariant
    val p = MaterialTheme.colorScheme.primary
    val t = MaterialTheme.colorScheme.tertiary
    val e = MaterialTheme.colorScheme.error
    val s = MaterialTheme.colorScheme.secondary

    val (label, color) = remember(state, sv, p, t, e, s) {
        when (state) {
            DrivingState.IDLE -> "Idle" to sv
            DrivingState.SLOW_MANEUVERING -> "Maneuvering" to p
            DrivingState.ACCELERATING -> "Accelerating" to t
            DrivingState.CRUISING -> "Cruising" to t
            DrivingState.DECELERATING -> "Decelerating" to e
            DrivingState.CORNERING -> "Cornering" to s
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, RoundedCornerShape(5.dp))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
fun EQVisualizer(bands: List<Float>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        bands.forEachIndexed { index, gain ->
            val fraction = (gain / DrivingConfig.MAX_BOOST_DB).coerceIn(-1f, 1f)
            val absFrac = kotlin.math.abs(fraction).coerceIn(0.05f, 1f)
            val barColor = if (fraction >= 0)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f + 0.5f * fraction)
            else
                MaterialTheme.colorScheme.error.copy(alpha = 0.5f + 0.5f * (-fraction))

            Box(
                modifier = Modifier
                    .width(24.dp)
                    .fillMaxHeight(0.6f * absFrac + 0.1f)
                    .padding(horizontal = 2.dp)
                    .background(barColor, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (index == 0 || absFrac > 0.2f) {
                    Text(
                        text = "%.0f".format(gain),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
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
        DotSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
