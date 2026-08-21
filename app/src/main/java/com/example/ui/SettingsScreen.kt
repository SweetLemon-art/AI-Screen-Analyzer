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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.AiProviderType
import com.example.ai.ConnectionTestResult
import com.example.ui.components.DelaySelector
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonViolet
import com.example.ui.theme.NeonVioletLight
import com.example.ui.theme.RoseError
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

internal fun shouldShowGeminiApiKeyCard(providerType: AiProviderType): Boolean = providerType == AiProviderType.GEMINI

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val selectedAiProvider by viewModel.selectedAiProvider.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()
    val maskedApiKey by viewModel.maskedApiKey.collectAsState()
    val isTestingConnection by viewModel.isTestingConnection.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val discoveredModels by viewModel.discoveredModels.collectAsState()
    val modelValidationMessage by viewModel.modelValidationMessage.collectAsState()
    val rateLimitState by viewModel.rateLimitState.collectAsState()

    var inputKeyText by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }

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
                text = "Capture & Security Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                text = if (selectedAiProvider == AiProviderType.LOCAL) "Manage local AI capture settings and local model behavior." else "Manage your Gemini API Key, dynamic model picker, delays, and resolution.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (modelValidationMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22F43F5E))
                    .border(1.dp, RoseError, RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Error, null, tint = RoseError, modifier = Modifier.size(22.dp))
                        Column {
                            Text("Model Selection Updated", fontWeight = FontWeight.Bold, color = RoseError, fontSize = 13.sp)
                            Text(modelValidationMessage ?: "", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = { viewModel.dismissModelValidationMessage() }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Clear, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        if (shouldShowGeminiApiKeyCard(selectedAiProvider)) {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("gemini_api_key_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Key, null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                            Text("GEMINI API KEY (BYOK)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
                        }
                        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (hasApiKey) Color(0x2210B981) else Color(0x22F43F5E)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(if (hasApiKey) "CONFIGURED" else "KEY MISSING", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (hasApiKey) EmeraldSuccess else RoseError)
                        }
                    }
                    if (hasApiKey) {
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Slate800).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Stored Key (Hardware Encrypted)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(maskedApiKey, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = EmeraldSuccess)
                            }
                            IconButton(onClick = { viewModel.clearApiKey(); inputKeyText = ""; Toast.makeText(context, "API Key removed from device", Toast.LENGTH_SHORT).show() }, modifier = Modifier.testTag("clear_key_button")) {
                                Icon(Icons.Default.Clear, "Clear API Key", tint = RoseError)
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = inputKeyText,
                            onValueChange = { inputKeyText = it },
                            modifier = Modifier.fillMaxWidth().testTag("api_key_input"),
                            label = { Text("Gemini API Key") },
                            placeholder = { Text("Paste your Gemini API key") },
                            singleLine = true,
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (inputKeyText.isNotBlank()) viewModel.saveApiKey(inputKeyText.trim()) }),
                            trailingIcon = { IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) { Icon(if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle API key visibility") } },
                            colors = OutlinedTextFieldDefaults.colors()
                        )
                        Button(onClick = { if (inputKeyText.isNotBlank()) viewModel.saveApiKey(inputKeyText.trim()) }, modifier = Modifier.fillMaxWidth().testTag("save_api_key_button")) {
                            Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Save & Test Connection")
                        }
                    }
                }
            }
        }

        // Remaining settings intentionally remain unchanged below this provider-specific section.
        Spacer(Modifier.height(4.dp))
        Text("Selected AI Provider: ${selectedAiProvider.name}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.testTag("selected_provider_label"))
    }
}
