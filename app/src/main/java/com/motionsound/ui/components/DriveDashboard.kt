package com.motionsound.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.motionsound.drive.DrivingState
import com.motionsound.sounddrive.GestureType
import com.motionsound.stem.BrakeType

@Composable
fun SpeedGauge(
    speedKmh: Float,
    maxSpeed: Int,
    modifier: Modifier = Modifier,
    gaugeHeight: Dp = 160.dp,
    onClick: (() -> Unit)? = null,
    gaugeBackground: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    trackArcColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val speed = speedKmh.coerceAtLeast(0f)
    val fraction = if (maxSpeed > 0) (speed / maxSpeed).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(300)
    )

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val errorContainer = MaterialTheme.colorScheme.errorContainer

    val cardModifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)
    val cardShape = RoundedCornerShape(24.dp)
    val cardColors = CardDefaults.cardColors(containerColor = gaugeBackground)
    val cardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

    val content = @Composable {
        Box(
            modifier = Modifier.fillMaxWidth().height(gaugeHeight).padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val scale = gaugeHeight / 160.dp
            val strokeWidthDp = 12.dp * scale
            val paddingDp = 8.dp * scale
            val glowAddDp = 8.dp * scale

            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = strokeWidthDp.toPx()
                val pad = strokeWidth / 2 + paddingDp.toPx()
                val arcSize = minOf(size.width, size.height) - pad * 2
                val topLeft = Offset((size.width - arcSize) / 2f, (size.height - arcSize) / 2f + 10.dp.toPx())
                val arcSizePx = androidx.compose.ui.geometry.Size(arcSize, arcSize)

                val arcBrush = Brush.horizontalGradient(
                    colors = listOf(primary, tertiary, errorContainer)
                )

                drawArc(
                    color = trackArcColor,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSizePx,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                if (animatedFraction > 0f) {
                    drawArc(
                        brush = arcBrush,
                        startAngle = 150f,
                        sweepAngle = 240f * animatedFraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSizePx,
                        alpha = 0.2f,
                        style = Stroke(width = strokeWidth + glowAddDp.toPx(), cap = StrokeCap.Round)
                    )
                }

                drawArc(
                    brush = arcBrush,
                    startAngle = 150f,
                    sweepAngle = 240f * animatedFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSizePx,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${speed.toInt()}",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "km/h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (onClick != null) {
        Card(onClick = onClick, modifier = cardModifier, shape = cardShape, colors = cardColors, elevation = cardElevation) { content() }
    } else {
        Card(modifier = cardModifier, shape = cardShape, colors = cardColors, elevation = cardElevation) { content() }
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
fun StemVolumeSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    liveValue: Float? = null
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(6.dp))
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
            val live = liveValue
            val text = if (live != null && abs(live - value) > 0.005f)
                "%.0f%% · applied %.0f%%".format(value * 100, live.coerceIn(0f, 1f) * 100)
            else
                "%.0f%%".format(value * 100)
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        DotSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            color = color
        )
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

@Composable
fun GestureIndicator(
    gesture: GestureType?,
    modifier: Modifier = Modifier
) {
    val visible = gesture != null
    val (label, color) = when (gesture) {
        GestureType.ACCEL_BURST -> "BOOST" to Color(0xFF4CAF50)
        GestureType.BRAKE_HIT -> "BRAKE" to Color(0xFFE53935)
        GestureType.CORNER_PEAK -> "TURN" to Color(0xFF2196F3)
        GestureType.BUMP_HIT -> "BUMP" to Color(0xFFFF9800)
        GestureType.TUNNEL_ENTRY -> "TUNNEL" to Color(0xFF9C27B0)
        null -> "" to Color.Transparent
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(80)),
        exit = fadeOut(tween(200))
    ) {
        Row(
            modifier = modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun RoadRoughnessBar(
    roadRoughness: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "ROAD", style = MaterialTheme.typography.labelMedium)
            Text(
                text = "%.0f%%".format(roadRoughness * 100),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { roadRoughness.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Color(0xFFFF9800),
            trackColor = Color(0xFFFF9800).copy(alpha = 0.15f),
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
fun AmbientMoodBadge(
    ambientMood: Float,
    modifier: Modifier = Modifier
) {
    val (label, color) = when {
        ambientMood < 0.15f -> "NIGHT" to Color(0xFF673AB7)
        ambientMood < 0.35f -> "DARK" to Color(0xFF3F51B5)
        ambientMood < 0.55f -> "DUSK" to Color(0xFFFF9800)
        else -> "DAY" to Color(0xFFFFEB3B)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun HillGradeIndicator(
    hillGrade: Float,
    modifier: Modifier = Modifier
) {
    val (label, color) = when {
        hillGrade > 0.3f -> "CLIMB" to Color(0xFF4CAF50)
        hillGrade < -0.3f -> "DESCENT" to Color(0xFF2196F3)
        else -> "FLAT" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val visible = abs(hillGrade) > 0.1f
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200))
    ) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(12.dp),
            color = color.copy(alpha = 0.15f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
