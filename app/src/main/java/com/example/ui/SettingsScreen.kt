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

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
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
        // Top Header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Capture & Security Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                text = "Manage your Gemini API Key, dynamic model picker, delays, and resolution.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Model Validation Banner (if previously selected model became unavailable)
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
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = RoseError,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Model Selection Updated",
                                fontWeight = FontWeight.Bold,
                                color = RoseError,
                                fontSize = 13.sp
                            )
                            Text(
                                text = modelValidationMessage ?: "",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(
                        onClick = { viewModel.dismissModelValidationMessage() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Gemini API Key Management Card (BYOK)
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "GEMINI API KEY (BYOK)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (hasApiKey) Color(0x2210B981) else Color(0x22F43F5E))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (hasApiKey) "CONFIGURED" else "KEY MISSING",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (hasApiKey) EmeraldSuccess else RoseError
                        )
                    }
                }

                if (hasApiKey) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Slate800)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Stored Key (Hardware Encrypted)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = maskedApiKey,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = EmeraldSuccess
                            )
                        }
                        IconButton(
                            onClick = {
                                viewModel.clearApiKey()
                                inputKeyText = ""
                                Toast.makeText(context, "API Key removed from device", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("clear_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear API Key",
                                tint = RoseError
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = inputKeyText,
                    onValueChange = { inputKeyText = it },
                    label = { Text(if (hasApiKey) "Update Gemini API Key" else "Enter Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (inputKeyText.isNotBlank()) {
                                viewModel.saveApiKey(inputKeyText)
                                inputKeyText = ""
                                Toast.makeText(context, "API Key securely stored", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ),
                    trailingIcon = {
                        IconButton(
                            onClick = { isPasswordVisible = !isPasswordVisible },
                            modifier = Modifier.testTag("toggle_key_visibility")
                        ) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) "Hide key" else "Show key",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("api_key_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = Slate700,
                        focusedContainerColor = Slate800,
                        unfocusedContainerColor = Slate800
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (inputKeyText.isNotBlank()) {
                                viewModel.saveApiKey(inputKeyText)
                                inputKeyText = ""
                                Toast.makeText(context, "API Key saved securely!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Please type or paste an API key first", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = inputKeyText.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("save_key_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                tint = Color(0xFF070B14),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Save Key",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF070B14)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { viewModel.testConnection() },
                        enabled = hasApiKey && !isTestingConnection,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("test_connection_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (hasApiKey) NeonVioletLight else Slate700)
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = NeonVioletLight,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiTethering,
                                    contentDescription = null,
                                    tint = if (hasApiKey) NeonVioletLight else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Test & Discover",
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (hasApiKey) NeonVioletLight else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Connection Test Feedback Banner
                if (testResult != null) {
                    when (val result = testResult!!) {
                        is ConnectionTestResult.Success -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x2210B981))
                                    .border(1.dp, EmeraldSuccess, RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = EmeraldSuccess,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = result.message,
                                        fontSize = 12.sp,
                                        color = EmeraldSuccess,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        is ConnectionTestResult.Error -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x22F43F5E))
                                    .border(1.dp, RoseError, RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Error,
                                        contentDescription = null,
                                        tint = RoseError,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = result.message,
                                        fontSize = 12.sp,
                                        color = RoseError,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Gemini API Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "GEMINI API STATUS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Status:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (hasApiKey) "Configured" else "Not Configured",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (hasApiKey) EmeraldSuccess else RoseError
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Rate limit:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val rateLimitText = when (rateLimitState) {
                        com.example.ai.RateLimitState.RATE_LIMITED -> "Active"
                        com.example.ai.RateLimitState.NORMAL -> "Normal"
                        com.example.ai.RateLimitState.UNKNOWN -> "Normal"
                    }
                    val rateLimitColor = when (rateLimitState) {
                        com.example.ai.RateLimitState.RATE_LIMITED -> RoseError
                        else -> EmeraldSuccess
                    }
                    Text(
                        text = rateLimitText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = rateLimitColor
                    )
                }
            }
        }

        // Dynamic Gemini Model Picker Card
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
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = NeonVioletLight,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "GEMINI MODEL SELECTION",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                    }

                    if (hasApiKey) {
                        IconButton(
                            onClick = { viewModel.fetchAvailableModels() },
                            modifier = Modifier.size(32.dp).testTag("refresh_models_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Models",
                                tint = NeonVioletLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (discoveredModels.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Slate800.copy(alpha = 0.5f))
                            .border(1.dp, Slate700.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(16.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No Gemini models discovered yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Save your API key and tap 'Test & Discover' above to dynamically fetch available models.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Slate700.copy(alpha = 0.9f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            if (hasApiKey) {
                                Button(
                                    onClick = { viewModel.fetchAvailableModels() },
                                    modifier = Modifier.padding(top = 6.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonViolet)
                                ) {
                                    Text("Discover Models", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    discoveredModels.forEach { model ->
                        val isSelected = selectedModel == model.canonicalModelId || selectedModel == model.modelId || selectedModel == model.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("model_option_${model.canonicalModelId}")
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Slate800 else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isSelected) NeonVioletLight else Slate700.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    viewModel.selectModel(model.canonicalModelId)
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) NeonVioletLight else Color.White
                                    )
                                }
                                if (model.description.isNotBlank()) {
                                    Text(
                                        text = model.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = NeonVioletLight,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (selectedModel.isBlank()) {
                        Text(
                            text = "⚠ Please select a model above to begin screen analysis.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RoseError,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Delay Selector Component
        DelaySelector(
            selectedDelaySeconds = settings.delaySeconds,
            onDelaySelected = { viewModel.updateDelay(it) },
            modifier = Modifier.fillMaxWidth()
        )

        // Image Resolution Settings Card
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
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = NeonVioletLight,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "FRAME PROCESSING RESOLUTION",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }

                val resolutionOptions = listOf(
                    Pair(1080, "Full HD (1080p) - High fidelity analysis"),
                    Pair(720, "Standard (720p) - Faster uploads & low latency"),
                    Pair(480, "Compact (480p) - Minimal bandwidth usage")
                )

                resolutionOptions.forEach { (res, desc) ->
                    val isSelected = settings.maxResolutionDimension == res
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("res_option_$res")
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Slate800 else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) NeonVioletLight else Slate700.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                viewModel.updateResolution(res)
                            }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${res}p Max Dimension",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) NeonVioletLight else Color.White
                            )
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = NeonVioletLight,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Privacy & Hardware Keystore Notice Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = EmeraldSuccess,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "ON-DEVICE PRIVACY & SECURITY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp
                    )
                }

                Text(
                    text = "• Hardware Encryption: API keys are stored encrypted via Android KeyStore (AES-256 GCM).\n" +
                            "• Strict Zero-Telemetry: Screen captures are transmitted strictly to Google Gemini Vision API; never logged or saved to disk.\n" +
                            "• Sequential Execution: Screen frames are captured 1-by-1 with zero buffer queueing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 19.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}
