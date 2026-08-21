package com.example.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.localai.Accelerator
import com.example.localai.LocalModel
import com.example.localai.LocalModelConfiguration
import com.example.localai.LocalModelImportPlan
import com.example.localai.ModelCapabilities
import com.example.localai.ModelType
import kotlinx.coroutines.launch

@Composable
fun LocalModelsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val repository = viewModel.localModelRepositoryForUi()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var models by remember { mutableStateOf(emptyList<LocalModel>()) }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var deletingModelId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun reload() { scope.launch { models = repository.listModels() } }
    LaunchedEffect(Unit) { reload() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "model.litertlm"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Storage, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Local AI Models")
                Text("Import and manage LiteRT-LM models")
            }
            Button(onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.padding(2.dp))
                Text("Import")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (models.isEmpty()) {
            Text("No local models imported yet.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models, key = { it.id }) { model ->
                    Card(colors = CardDefaults.cardColors()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(model.fileName)
                                Text("${model.modelType.name} • ${model.accelerator.name}")
                                Text("${model.configuration.maxTokens} tokens • Top K ${model.configuration.topK}")
                            }
                            IconButton(
                                enabled = deletingModelId == null,
                                onClick = {
                                    deletingModelId = model.id
                                    scope.launch {
                                        viewModel.deleteLocalModel(model.id)
                                            .onFailure { error = it.message ?: "Unable to delete model" }
                                        reload()
                                        deletingModelId = null
                                    }
                                }
                            ) {
                                if (deletingModelId == model.id) CircularProgressIndicator() else Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    selectedUri?.let { uri ->
        ModelImportDialog(
            fileName = selectedName,
            importing = importing,
            onCancel = { if (!importing) selectedUri = null },
            onImport = { plan ->
                importing = true
                error = null
                scope.launch {
                    repository.importModel(plan)
                        .onSuccess { selectedUri = null; reload() }
                        .onFailure { error = it.message ?: "Unable to import model" }
                    importing = false
                }
            },
            uri = uri
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Local model error") },
            text = { Text(message) },
            confirmButton = { Button(onClick = { error = null }) { Text("OK") } }
        )
    }
}

@Composable
private fun ModelImportDialog(
    fileName: String,
    uri: android.net.Uri,
    importing: Boolean,
    onCancel: () -> Unit,
    onImport: (LocalModelImportPlan) -> Unit
) {
    var maxTokens by remember { mutableIntStateOf(1024) }
    var topK by remember { mutableIntStateOf(64) }
    var topP by remember { mutableDoubleStateOf(0.95) }
    var temperature by remember { mutableDoubleStateOf(1.0) }
    var image by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var mobileActions by remember { mutableStateOf(false) }
    var speculative by remember { mutableStateOf(false) }
    var accelerator by remember { mutableStateOf(Accelerator.CPU) }

    val plan = LocalModelImportPlan(
        sourceUri = uri,
        displayName = fileName,
        modelType = ModelType.LLM,
        configuration = LocalModelConfiguration(maxTokens, topK, topP, temperature),
        capabilities = ModelCapabilities(image, audio, false, mobileActions, thinking, speculative),
        accelerator = accelerator
    )

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Import Model") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Name") ; Text(fileName) ; Text("Model type: LLM") }
                item { Text("Max tokens: $maxTokens") ; Slider(value = maxTokens.toFloat(), onValueChange = { maxTokens = it.toInt() }, valueRange = 100f..4096f) }
                item { Text("Top K: $topK") ; Slider(value = topK.toFloat(), onValueChange = { topK = it.toInt() }, valueRange = 1f..100f) }
                item { Text("Top P: %.2f".format(topP)) ; Slider(value = topP.toFloat(), onValueChange = { topP = it.toDouble() }, valueRange = 0f..1f) }
                item { Text("Temperature: %.2f".format(temperature)) ; Slider(value = temperature.toFloat(), onValueChange = { temperature = it.toDouble() }, valueRange = 0f..2f) }
                item { CapabilityRow("Support image", image) { image = it } }
                item { CapabilityRow("Support audio", audio) { audio = it } }
                item { CapabilityRow("Support thinking", thinking) { thinking = it } }
                item { CapabilityRow("Support mobile actions", mobileActions) { mobileActions = it } }
                item { CapabilityRow("Support speculative decoding", speculative) { speculative = it } }
                item { Text("Compatible accelerators") ; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Accelerator.values().forEach { value -> FilterChip(selected = accelerator == value, onClick = { accelerator = value }, label = { Text(value.name) }) } } }
            }
        },
        confirmButton = {
            Button(enabled = !importing, onClick = { onImport(plan) }) {
                if (importing) CircularProgressIndicator() else Text("Import")
            }
        },
        dismissButton = { OutlinedButton(enabled = !importing, onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun CapabilityRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
