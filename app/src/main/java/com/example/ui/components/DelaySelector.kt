package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CaptureSettings
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DelaySelector(
    selectedDelaySeconds: Int,
    onDelaySelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .testTag("delay_selector")
            .clip(RoundedCornerShape(16.dp))
            .background(Slate900)
            .border(1.dp, Slate700, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Timelapse,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "CAPTURE DELAY AFTER AI ANALYSIS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Explanatory note
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x2238BDF8))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = "Timer strictly begins AFTER Gemini completes analyzing previous frame.",
                fontSize = 11.sp,
                color = NeonCyan
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Preset Chips
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CaptureSettings.DELAY_PRESETS.forEach { preset ->
                val isSelected = selectedDelaySeconds == preset
                val label = if (preset >= 60) "${preset / 60}m" else "${preset}s"
                Box(
                    modifier = Modifier
                        .testTag("delay_preset_${preset}s")
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) NeonCyan else Slate800)
                        .border(
                            1.dp,
                            if (isSelected) NeonCyan else Slate700,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onDelaySelected(preset) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (isSelected) Color(0xFF070B14) else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Slider for Custom Seconds (1 to 600)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Custom Delay (1s – 600s)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val displayTime = if (selectedDelaySeconds >= 60) {
                val mins = selectedDelaySeconds / 60
                val secs = selectedDelaySeconds % 60
                if (secs == 0) "$mins min" else "$mins min $secs sec"
            } else {
                "$selectedDelaySeconds seconds"
            }
            Text(
                text = displayTime,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan
            )
        }

        Slider(
            value = selectedDelaySeconds.toFloat().coerceIn(1f, 600f),
            onValueChange = { onDelaySelected(it.toInt().coerceIn(1, 600)) },
            valueRange = 1f..600f,
            colors = SliderDefaults.colors(
                thumbColor = NeonCyan,
                activeTrackColor = NeonCyan,
                inactiveTrackColor = Slate800
            ),
            modifier = Modifier.testTag("delay_slider")
        )
    }
}
