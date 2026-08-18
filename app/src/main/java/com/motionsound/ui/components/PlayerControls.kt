package com.motionsound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.motionsound.stem.LoopMode
import com.motionsound.ui.theme.ComicIcons
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.comicBorder

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    canPrevious: Boolean = true,
    canNext: Boolean = true,
    isShuffled: Boolean = false,
    onShuffleToggle: () -> Unit = {},
    loopMode: LoopMode = LoopMode.NONE,
    onLoopCycle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val comic = LocalComicColors.current
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(if (canPrevious) comic.yellow else comic.surfaceAlt)
                    .comicBorder(comic.ink, 3.dp, cornerRadius = 0.dp)
                    .clickable(enabled = canPrevious, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPrevious()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ComicIcons.SkipPrevious,
                    contentDescription = "Previous",
                    tint = if (canPrevious) comic.ink else comic.textMuted,
                    modifier = Modifier.size(28.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(comic.yellow)
                    .comicBorder(comic.ink, 3.dp, cornerRadius = 0.dp)
                    .clickable(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPlayPause()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) ComicIcons.Pause else ComicIcons.PlayArrow,
                    contentDescription = "Play / Pause",
                    tint = comic.ink,
                    modifier = Modifier.size(36.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(if (canNext) comic.yellow else comic.surfaceAlt)
                    .comicBorder(comic.ink, 3.dp, cornerRadius = 0.dp)
                    .clickable(enabled = canNext, onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNext()
                    }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = ComicIcons.SkipNext,
                    contentDescription = "Next",
                    tint = if (canNext) comic.ink else comic.textMuted,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ComicToggleChip(
                selected = isShuffled,
                onClick = onShuffleToggle,
                icon = { Icon(ComicIcons.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = "Shuffle"
            )

            Spacer(Modifier.size(16.dp))

            ComicToggleChip(
                selected = loopMode != LoopMode.NONE,
                onClick = onLoopCycle,
                icon = {
                    Box(modifier = Modifier.size(16.dp)) {
                        Icon(
                            imageVector = ComicIcons.Repeat,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            tint = if (loopMode != LoopMode.NONE) comic.ink else comic.textMuted
                        )
                        if (loopMode == LoopMode.REPEAT_ONE) {
                            Text(
                                "1",
                                fontSize = 8.sp,
                                color = if (loopMode != LoopMode.NONE) comic.ink else comic.textMuted,
                                modifier = Modifier.align(Alignment.BottomEnd)
                            )
                        }
                    }
                },
                label = "Loop"
            )
        }
    }
}

@Composable
private fun ComicToggleChip(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String
) {
    val comic = LocalComicColors.current
    val haptics = LocalHapticFeedback.current
    val bg = if (selected) comic.yellow else comic.surfaceAlt
    val fg = if (selected) comic.ink else comic.textMuted

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(0.dp))
            .background(bg)
            .comicBorder(comic.ink, 2.dp, cornerRadius = 0.dp)
            .clickable(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            })
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}
