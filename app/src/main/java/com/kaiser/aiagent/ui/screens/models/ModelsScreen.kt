package com.kaiser.aiagent.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

/**
 * Model Management screen — download, select, and manage on-device AI models.
 *
 * This is the headline feature of v0.5: the user can download a Gemma
 * model (~1.6 GB) and run it entirely on-device with no API key, no
 * rate limits, and no network needed (after download).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    viewModel: ModelsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("On-Device Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Device info + support check
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Device Info", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("RAM: ${state.deviceRamMb} MB", style = MaterialTheme.typography.bodyMedium)
                        if (state.deviceRamMb < 4096) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "⚠ Your device has less than 4 GB RAM. The E2B model may be slow. " +
                                    "Consider the 1B model for better performance.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Recommended: ${state.recommendedModelId}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!state.isLocalAiSupported) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "✗ On-device AI requires Android 12+ (API 32). Your device is not supported.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Backend toggle
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Active Backend", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (state.activeBackend == com.kaiser.aiagent.data.ai.AiBackend.LOCAL)
                                "✓ On-Device (no internet needed, no rate limits)"
                            else
                                "Cloud API (needs internet + API key)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.activeBackend == com.kaiser.aiagent.data.ai.AiBackend.LOCAL)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        if (state.activeBackend == com.kaiser.aiagent.data.ai.AiBackend.LOCAL) {
                            OutlinedButton(
                                onClick = { viewModel.switchToCloud() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Cloud, contentDescription = null)
                                Spacer(Modifier.height(0.dp))
                                Text("  Switch to Cloud API")
                            }
                        }
                    }
                }
            }

            // Model list
            item {
                Text(
                    "Available Models",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(state.models, key = { it.info.id }) { model ->
                ModelCard(
                    model = model,
                    onDownload = { viewModel.downloadModel(model.info) },
                    onSelect = { viewModel.selectModel(model.info.id) },
                    onDelete = { viewModel.deleteModel(model.info.id) }
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Models are downloaded from HuggingFace (litert-community) — the same source " +
                        "as Google's Edge Gallery. Download happens once; after that, the model " +
                        "runs entirely on-device with no internet needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelsViewModel.ModelUiState,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val isRecommended = model.info.id == "gemma-4-e2b-it"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (model.isActive)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.info.displayName,
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (isRecommended) {
                        Text(
                            "⭐ Recommended for your device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (model.isActive) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Active",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                model.info.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Size: ${model.info.sizeHuman}", style = MaterialTheme.typography.bodySmall)
                Text("Min RAM: ${model.info.minRamMb} MB", style = MaterialTheme.typography.bodySmall)
                if (model.info.supportsToolCalling) {
                    Text("Tools: ✓", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Download progress
            if (model.isDownloading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { model.downloadPercent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Downloading... ${model.downloadPercent}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Action buttons
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    model.isDownloading -> {
                        // No buttons while downloading
                    }
                    model.isDownloaded -> {
                        if (!model.isActive) {
                            OutlinedButton(
                                onClick = onSelect,
                                modifier = Modifier.weight(1f)
                            ) { Text("Use This Model") }
                        }
                        OutlinedButton(
                            onClick = onDelete
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                    else -> {
                        Button(
                            onClick = onDownload,
                            enabled = model.info.minRamMb <= 2048 || true, // allow all, warn in text
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.height(0.dp))
                            Text("  Download (${model.info.sizeHuman})")
                        }
                    }
                }
            }
        }
    }
}
