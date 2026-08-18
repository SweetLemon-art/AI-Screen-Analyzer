package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.monitoring.MonitoringState
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonVioletLight
import com.example.ui.theme.RoseError
import com.example.ui.theme.Slate400

@Composable
fun StatusIndicatorBadge(
    state: MonitoringState,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val (label, dotColor, bgColor, borderColor) = when (state) {
        is MonitoringState.Idle -> StatusConfig(
            label = "IDLE",
            dotColor = Slate400,
            bgColor = Color(0x2264748B),
            borderColor = Color(0x4464748B)
        )
        is MonitoringState.RequestingPermission -> StatusConfig(
            label = "REQUESTING PERMISSION",
            dotColor = AmberAccent,
            bgColor = Color(0x22F59E0B),
            borderColor = Color(0x44F59E0B)
        )
        is MonitoringState.Starting -> StatusConfig(
            label = "STARTING CAPTURE",
            dotColor = NeonCyan,
            bgColor = Color(0x2200E5FF),
            borderColor = Color(0x5500E5FF)
        )
        is MonitoringState.Capturing -> StatusConfig(
            label = "CAPTURING SCREEN...",
            dotColor = NeonCyan,
            bgColor = Color(0x3300E5FF),
            borderColor = NeonCyan
        )
        is MonitoringState.Analyzing -> StatusConfig(
            label = "AI ANALYZING...",
            dotColor = NeonVioletLight,
            bgColor = Color(0x337C3AED),
            borderColor = NeonVioletLight
        )
        is MonitoringState.Waiting -> StatusConfig(
            label = "WAITING ${state.remainingSeconds}s...",
            dotColor = EmeraldSuccess,
            bgColor = Color(0x2210B981),
            borderColor = EmeraldSuccess
        )
        is MonitoringState.Stopping -> StatusConfig(
            label = "STOPPING...",
            dotColor = AmberAccent,
            bgColor = Color(0x22F59E0B),
            borderColor = Color(0x55F59E0B)
        )
        is MonitoringState.Error -> StatusConfig(
            label = "ERROR",
            dotColor = RoseError,
            bgColor = Color(0x22F43F5E),
            borderColor = RoseError
        )
    }

    val isLive = state is MonitoringState.Capturing ||
            state is MonitoringState.Analyzing ||
            state is MonitoringState.Waiting

    Row(
        modifier = modifier
            .testTag("status_indicator_badge")
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .then(if (isLive) Modifier.scale(pulseScale) else Modifier)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            color = if (dotColor == Slate400) MaterialTheme.colorScheme.onSurfaceVariant else dotColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

private data class StatusConfig(
    val label: String,
    val dotColor: Color,
    val bgColor: Color,
    val borderColor: Color
)
