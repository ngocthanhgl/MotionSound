package com.motionsound.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motionsound.drive.DspDebugConfig
import com.motionsound.drive.EqStateStore

@Composable
fun DspDebugPanel() {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "DSP Debug",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    DebugToggle(
                        label = "Bypass All",
                        checked = EqStateStore.debugConfig.bypassAll,
                        onCheckedChange = { EqStateStore.debugConfig = EqStateStore.debugConfig.copy(bypassAll = it) }
                    )
                    DebugToggle(
                        label = "EQ",
                        checked = EqStateStore.debugConfig.enableEQ,
                        onCheckedChange = { EqStateStore.debugConfig = EqStateStore.debugConfig.copy(enableEQ = it) }
                    )
                    DebugToggle(
                        label = "Volume Duck",
                        checked = EqStateStore.debugConfig.enableVolumeDuck,
                        onCheckedChange = { EqStateStore.debugConfig = EqStateStore.debugConfig.copy(enableVolumeDuck = it) }
                    )
                    DebugToggle(
                        label = "Volume Ramp",
                        checked = EqStateStore.debugConfig.enableVolumeRamp,
                        onCheckedChange = { EqStateStore.debugConfig = EqStateStore.debugConfig.copy(enableVolumeRamp = it) }
                    )
                    DebugToggle(
                        label = "Reverb",
                        checked = EqStateStore.debugConfig.enableReverb,
                        onCheckedChange = { EqStateStore.debugConfig = EqStateStore.debugConfig.copy(enableReverb = it) }
                    )
                    DebugToggle(
                        label = "Panning",
                        checked = EqStateStore.debugConfig.enablePanning,
                        onCheckedChange = { EqStateStore.debugConfig = EqStateStore.debugConfig.copy(enablePanning = it) }
                    )
                    DebugToggle(
                        label = "Stereo Width",
                        checked = EqStateStore.debugConfig.enableStereoWidth,
                        onCheckedChange = { EqStateStore.debugConfig = EqStateStore.debugConfig.copy(enableStereoWidth = it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DebugToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
