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
    var config by remember { mutableStateOf(EqStateStore.debugConfig) }

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
                        checked = config.bypassAll,
                        onCheckedChange = {
                            config = config.copy(bypassAll = it)
                            EqStateStore.debugConfig = config
                        }
                    )
                    DebugToggle(
                        label = "EQ",
                        checked = config.enableEQ,
                        onCheckedChange = {
                            config = config.copy(enableEQ = it)
                            EqStateStore.debugConfig = config
                        }
                    )
                    DebugToggle(
                        label = "LowPass",
                        checked = config.enableLowPass,
                        onCheckedChange = {
                            config = config.copy(enableLowPass = it)
                            EqStateStore.debugConfig = config
                        }
                    )
                    DebugToggle(
                        label = "Reverb",
                        checked = config.enableReverb,
                        onCheckedChange = {
                            config = config.copy(enableReverb = it)
                            EqStateStore.debugConfig = config
                        }
                    )
                    DebugToggle(
                        label = "Volume Duck",
                        checked = config.enableVolumeDuck,
                        onCheckedChange = {
                            config = config.copy(enableVolumeDuck = it)
                            EqStateStore.debugConfig = config
                        }
                    )
                    DebugToggle(
                        label = "Volume Ramp",
                        checked = config.enableVolumeRamp,
                        onCheckedChange = {
                            config = config.copy(enableVolumeRamp = it)
                            EqStateStore.debugConfig = config
                        }
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
