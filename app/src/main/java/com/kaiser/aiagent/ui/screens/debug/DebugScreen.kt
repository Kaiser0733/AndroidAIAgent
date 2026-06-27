package com.kaiser.aiagent.ui.screens.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

/**
 * Hidden debug screen. Accessed from the About screen (long-press on
 * the version label) — not surfaced in the main navigation.
 *
 * Shows live snapshots of everything an engineer would want to see
 * while debugging the agent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SectionCard("AI Configuration") {
                InfoRow("Endpoint", state.endpoint)
                InfoRow("Model", state.model)
                InfoRow("API key", if (state.apiKeySet) "set ✓" else "NOT SET ✗")
                Button(
                    onClick = { viewModel.testConnection() },
                    enabled = !state.testing && state.apiKeySet
                ) {
                    Text(if (state.testing) "Testing…" else "Test connection")
                }
                state.apiStatus?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }}

            item { SectionCard("Agent Runtime State") {
                InfoRow("Busy", if (state.agentBusy) "yes" else "no")
                InfoRow("Last tool call", state.lastToolCall ?: "(none)")
                InfoRow("Last tool result", when (state.lastToolResultSuccess) {
                    true -> "OK: ${state.lastToolResultData ?: ""}"
                    false -> "FAILED: ${state.lastToolResultError ?: ""}"
                    null -> "(none)"
                })
                InfoRow("Last error", state.lastError ?: "(none)")
            }}

            item { SectionCard("Tool Stats") {
                InfoRow("Total registered", state.toolStats.total.toString())
                InfoRow("SAFE", state.toolStats.safe.toString())
                InfoRow("CONFIRMATION_REQUIRED", state.toolStats.confirmationRequired.toString())
                InfoRow("BLOCKED", state.toolStats.blocked.toString())
            }}

            item { SectionCard("Registered Tools (${state.tools.size})") {
                if (state.tools.isEmpty()) {
                    Text("(none)", style = MaterialTheme.typography.bodyMedium)
                } else {
                    state.tools.forEach { t ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(
                                text = "${t.name}  [${t.permissionLevel}]",
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = t.description,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "args: ${t.argumentsSchema}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }}

            item { SectionCard("Memory") {
                InfoRow("Entries", state.memoryCount.toString())
            }}
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(max = 220.dp)
        )
    }
}
