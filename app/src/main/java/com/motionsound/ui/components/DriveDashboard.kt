package com.motionsound.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.motionsound.drive.DrivingState
import com.motionsound.sounddrive.GestureType
import com.motionsound.stem.BrakeType
import com.motionsound.ui.theme.ComicBurst
import com.motionsound.ui.theme.ComicProgressBar
import com.motionsound.ui.theme.ComicTag
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.comicBorder
import com.motionsound.ui.theme.comicPanel

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
    val comic = LocalComicColors.current
    val speed = speedKmh.coerceAtLeast(0f)
    val fraction = if (maxSpeed > 0) (speed / maxSpeed).coerceIn(0f, 1f) else 0f
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(300)
    )

    val borderColor = comic.ink
    val accent = comic.yellow

    val panelModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .comicPanel(
            containerColor = gaugeBackground,
            borderColor = borderColor,
            shadowColor = comic.shadow,
            borderWidth = 3.dp,
            shadowOffset = 5.dp,
            cornerRadius = 0.dp
        )
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }

    Box(modifier = panelModifier) {
        Box(
            modifier = Modifier.fillMaxWidth().height(gaugeHeight).padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${speed.toInt()}",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val segments = 10
                val filledSegments = (animatedFraction * segments).toInt()
                Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
                    val gap = 4.dp.toPx()
                    val w = (size.width - gap * (segments - 1)) / segments
                    var i = 0
                    while (i < segments) {
                        val x = i * (w + gap)
                        drawRect(color = borderColor, topLeft = Offset(x, 0f), size = Size(w, size.height))
                        drawRect(
                            color = if (i < filledSegments) accent else trackArcColor,
                            topLeft = Offset(x + 1.5f, 1.5f),
                            size = Size(w - 3f, size.height - 3f)
                        )
                        i++
                    }
                }
                Text(
                    text = "KM/H!",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
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
    val comic = LocalComicColors.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = "%.2f".format(value),
                style = MaterialTheme.typography.labelSmall,
                color = comic.textMuted
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        ComicProgressBar(
            progress = value,
            color = color,
            borderColor = comic.ink,
            modifier = Modifier.fillMaxWidth(),
            height = 12.dp
        )
    }
}

@Composable
fun DrivingStateIndicator(
    state: DrivingState,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    val comic = LocalComicColors.current
    val sv = comic.textMuted
    val p = comic.blue
    val t = comic.yellow
    val e = comic.red
    val s = comic.purple

    val (label, color) = remember(state, sv, p, t, e, s) {
        when (state) {
            DrivingState.IDLE -> "IDLE" to sv
            DrivingState.SLOW_MANEUVERING -> "MANEUVERING" to p
            DrivingState.ACCELERATING -> "ACCELERATING" to t
            DrivingState.CRUISING -> "CRUISING" to t
            DrivingState.DECELERATING -> "DECELERATING" to e
            DrivingState.CORNERING -> "CORNERING" to s
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .comicPanel(
                containerColor = comic.surfaceAlt,
                borderColor = comic.ink,
                shadowColor = comic.shadow,
                borderWidth = 2.5.dp,
                shadowOffset = 3.dp,
                cornerRadius = 0.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(color)
                    .comicBorder(comic.ink, 2.dp, cornerRadius = 0.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
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
    manualValue: Float? = null
) {
    val comic = LocalComicColors.current
    var dragOverride by remember { mutableStateOf<Float?>(null) }
    val displayed = dragOverride ?: value.coerceIn(0f, 1f)
    val manual = manualValue
    val text = if (dragOverride == null && manual != null && abs(manual - displayed) > 0.005f)
        "%.0f%% · set %.0f%%".format(displayed * 100, manual * 100)
    else
        "%.0f%%".format(displayed * 100)
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(0.dp))
                        .background(color)
                        .comicBorder(comic.ink, 2.dp, cornerRadius = 0.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = comic.textMuted
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        DotSlider(
            value = displayed,
            onValueChange = {
                dragOverride = it
                onValueChange(it)
            },
            onValueChangeFinished = { dragOverride = null },
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
    val comic = LocalComicColors.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueLabel ?: "%.2f".format(value),
                style = MaterialTheme.typography.bodySmall,
                color = comic.textMuted
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
    val comic = LocalComicColors.current
    val visible = gesture != null
    val (label, burstColor, textColor) = when (gesture) {
        GestureType.ACCEL_BURST -> Triple("BOOST!", comic.yellow, comic.ink)
        GestureType.BRAKE_HIT -> Triple("BRAKE!", comic.red, Color.White)
        GestureType.CORNER_PEAK -> Triple("TURN!", comic.blue, Color.White)
        GestureType.BUMP_HIT -> Triple("BUMP!", comic.orange, Color.White)
        GestureType.TUNNEL_ENTRY -> Triple("TUNNEL!", comic.purple, Color.White)
        null -> Triple("", Color.Transparent, Color.Transparent)
    }
    Box(
        modifier = modifier.fillMaxWidth().height(48.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(80)),
            exit = fadeOut(tween(200))
        ) {
            Box(contentAlignment = Alignment.Center) {
                ComicBurst(
                    color = burstColor,
                    borderColor = comic.ink,
                    modifier = Modifier.size(width = 108.dp, height = 40.dp),
                    spikes = 12,
                    innerRatio = 0.5f
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun RoadRoughnessBar(
    roadRoughness: Float,
    modifier: Modifier = Modifier
) {
    val comic = LocalComicColors.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "ROAD", style = MaterialTheme.typography.labelMedium)
            Text(
                text = "%.0f%%".format(roadRoughness * 100),
                style = MaterialTheme.typography.labelSmall,
                color = comic.textMuted
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        ComicProgressBar(
            progress = roadRoughness,
            color = comic.orange,
            borderColor = comic.ink,
            modifier = Modifier.fillMaxWidth(),
            height = 10.dp
        )
    }
}

@Composable
fun AmbientMoodBadge(
    ambientMood: Float,
    modifier: Modifier = Modifier
) {
    val comic = LocalComicColors.current
    val (label, color, textColor) = when {
        ambientMood < 0.15f -> Triple("NIGHT", comic.purple, Color.White)
        ambientMood < 0.35f -> Triple("DARK", comic.blue, Color.White)
        ambientMood < 0.55f -> Triple("DUSK", comic.orange, Color.White)
        else -> Triple("DAY", comic.yellow, comic.ink)
    }
    ComicTag(
        text = label,
        color = color,
        borderColor = comic.ink,
        textColor = textColor,
        modifier = modifier
    )
}

@Composable
fun HillGradeIndicator(
    hillGrade: Float,
    modifier: Modifier = Modifier
) {
    val comic = LocalComicColors.current
    val (label, color, textColor) = when {
        hillGrade > 0.3f -> Triple("CLIMB!", comic.green, Color.White)
        hillGrade < -0.3f -> Triple("DESCENT!", comic.blue, Color.White)
        else -> Triple("FLAT", comic.surfaceAlt, comic.textMuted)
    }
    val visible = abs(hillGrade) > 0.1f
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200))
    ) {
        ComicTag(
            text = label,
            color = color,
            borderColor = comic.ink,
            textColor = textColor,
            modifier = modifier
        )
    }
}