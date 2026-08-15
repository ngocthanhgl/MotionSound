package com.motionsound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.motionsound.ui.theme.ComicIcons
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.SkipNext
import androidx.compose.material.icons.automirrored.filled.SkipPrevious
import androidx.compose.material.icons.sharp.Pause
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.motionsound.ui.theme.LocalComicColors
import com.motionsound.ui.theme.comicBorder

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    isShuffled: Boolean = false,
    onShuffleToggle: () -> Unit = {},
    isLoop: Boolean = false,
    onLoopToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val comic = LocalComicColors.current

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
                    .background(comic.yellow)
                    .comicBorder(comic.ink, 3.dp, cornerRadius = 0.dp)
                    .clickable(onClick = onPrevious),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.SkipPrevious,
                    contentDescription = "Previous",
                    tint = comic.ink,
                    modifier = Modifier.size(28.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(comic.yellow)
                    .comicBorder(comic.ink, 3.dp, cornerRadius = 0.dp)
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Sharp.Pause else Icons.Sharp.PlayArrow,
                    contentDescription = "Play / Pause",
                    tint = comic.ink,
                    modifier = Modifier.size(36.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(0.dp))
                    .background(comic.yellow)
                    .comicBorder(comic.ink, 3.dp, cornerRadius = 0.dp)
                    .clickable(onClick = onNext),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = comic.ink,
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
                selected = isLoop,
                onClick = onLoopToggle,
                icon = { Icon(ComicIcons.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) },
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
    val bg = if (selected) comic.yellow else comic.surfaceAlt
    val fg = if (selected) comic.ink else comic.textMuted

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(0.dp))
            .background(bg)
            .comicBorder(comic.ink, 2.dp, cornerRadius = 0.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}
