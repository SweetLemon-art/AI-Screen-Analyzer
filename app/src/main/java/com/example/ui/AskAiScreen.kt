package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.example.ai.AiProviderType
import com.example.ui.components.AnalysisResultCard
import com.example.ui.components.ScreenPreviewCard
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun AskAiScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val latestBitmap by viewModel.latestBitmap.collectAsState()
    val latestCaptureTimestamp by viewModel.lastCaptureTimestamp.collectAsState()
    val selectedProvider by viewModel.selectedAiProvider.collectAsState()
    val result by viewModel.askAiResult.collectAsState()
    val isAsking by viewModel.isAskingAi.collectAsState()
    var prompt by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Ask AI", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(
            "Ask a question about the latest captured screen.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI PROVIDER", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiProviderType.values().forEach { type ->
                        FilterChip(
                            selected = selectedProvider == type,
                            onClick = { viewModel.selectAiProvider(type) },
                            label = { Text(if (type == AiProviderType.GEMINI) "Gemini" else "Local AI") }
                        )
                    }
                }
            }
        }

        ScreenPreviewCard(
            bitmap = latestBitmap,
            lastCaptureTimestamp = latestCaptureTimestamp,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth().testTag("ask_ai_prompt"),
            enabled = !isAsking,
            label = { Text("Question") },
            placeholder = { Text("What is happening on this screen?") },
            minLines = 3,
            shape = RoundedCornerShape(14.dp)
        )

        Button(
            onClick = { viewModel.askAi(prompt) },
            enabled = !isAsking && prompt.isNotBlank() && latestBitmap != null,
            modifier = Modifier.fillMaxWidth().height(54.dp).testTag("ask_ai_button"),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (isAsking) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            } else {
                Text("ASK ${if (selectedProvider == AiProviderType.GEMINI) "GEMINI" else "LOCAL AI"}")
            }
        }

        if (latestBitmap == null) {
            Text(
                "No captured screen is available yet. Start monitoring once to capture the current screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        result?.let {
            AnalysisResultCard(result = it, isAnalyzing = isAsking, modifier = Modifier.fillMaxWidth())
        }
    }
}
