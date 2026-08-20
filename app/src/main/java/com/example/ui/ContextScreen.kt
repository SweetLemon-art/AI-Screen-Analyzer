package com.example.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AnalysisContext
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonVioletLight
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun ContextScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentContext by viewModel.currentContext.collectAsState()
    val savedContexts by viewModel.savedContexts.collectAsState()

    var nameText by remember(currentContext) { mutableStateOf(currentContext.name) }
    var instructionsText by remember(currentContext) { mutableStateOf(currentContext.instructions) }
    var languageText by remember(currentContext) { mutableStateOf(currentContext.language) }

    val languages = listOf("English", "Indonesian", "Spanish", "Japanese", "German", "French", "Chinese", "Arabic")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Analysis Context",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                text = "Define the instructions and purpose for what Gemini should analyze on your screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        tint = NeonVioletLight,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "READY PRESETS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }

                savedContexts.forEach { preset ->
                    val isSelected = currentContext.id == preset.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preset_${preset.id}")
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Slate800 else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) NeonCyan else Slate700.copy(alpha = 0.6f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                viewModel.selectContext(preset)
                                nameText = preset.name
                                instructionsText = preset.instructions
                                languageText = preset.language
                                Toast.makeText(context, "Loaded \"${preset.name}\"", Toast.LENGTH_SHORT).show()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) NeonCyan else Color.White
                            )
                            Text(
                                text = preset.instructions,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Active Context",
                                tint = EmeraldSuccess,
                                modifier = Modifier.size(20.dp).padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "CUSTOMIZE CONTEXT",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }

                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("Context Name") },
                    placeholder = { Text("e.g. Chart Analysis, UI QA, Document Reader") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("context_name_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Slate700,
                        focusedContainerColor = Slate800,
                        unfocusedContainerColor = Slate800
                    )
                )

                OutlinedTextField(
                    value = instructionsText,
                    onValueChange = { instructionsText = it },
                    label = { Text("Analysis Instructions for Gemini") },
                    placeholder = { Text("Describe exactly what the AI should focus on...") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("context_instructions_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Slate700,
                        focusedContainerColor = Slate800,
                        unfocusedContainerColor = Slate800
                    )
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = NeonVioletLight,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "AI Response Language",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    languages.chunked(4).forEach { languageRow ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            languageRow.forEach { lang ->
                                val isSelected = languageText.equals(lang, ignoreCase = true)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("lang_chip_$lang")
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) NeonVioletLight else Slate800)
                                        .clickable { languageText = lang }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = lang,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFF070B14) else Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Button(
                    onClick = {
                        viewModel.saveAndSelectContext(
                            name = nameText,
                            instructions = instructionsText,
                            language = languageText
                        )
                        Toast.makeText(context, "Context \"$nameText\" saved & applied!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("save_context_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            tint = Color(0xFF070B14)
                        )
                        Text(
                            text = "Save & Apply Context",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF070B14)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}