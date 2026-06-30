package com.kaiser.aiagent.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kaiser.aiagent.R
import org.koin.androidx.compose.koinViewModel

/**
 * Settings screen. v0.3 layout:
 *   1. AI Configuration  (new — API key, endpoint, model, test button)
 *   2. Remote Configuration  (existing)
 *   3. Updater  (existing)
 *   4. General  (auto-check toggle)
 *   5. Debug  (new — link to the hidden Debug screen)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDebug: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save() },
                        enabled = state.dirty
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- AI Configuration ----------------------------------
            item {
                SectionLabel("AI Configuration")
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.endpoint,
                    onValueChange = viewModel::updateEndpoint,
                    label = { Text("API Endpoint") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::updateModel,
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g. deepseek-ai/deepseek-v4-flash") }
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.temperature.toString(),
                        onValueChange = { it.toDoubleOrNull()?.let(viewModel::updateTemperature) },
                        label = { Text("Temperature") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = if (state.topP > 0) state.topP.toString() else "",
                        onValueChange = { it.toDoubleOrNull()?.let(viewModel::updateTopP) },
                        label = { Text("Top P (optional)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = if (state.maxTokens > 0) state.maxTokens.toString() else "",
                    onValueChange = { it.toIntOrNull()?.let(viewModel::updateMaxTokens) },
                    label = { Text("Max tokens (optional, -1 = provider default)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.extraBody,
                    onValueChange = viewModel::updateExtraBody,
                    label = { Text("Extra body JSON (advanced)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    placeholder = {
                        Text("""{"chat_template_kwargs":{"thinking":false}}""")
                    }
                )
                Text(
                    text = "Merged into the request body as top-level fields. Used for " +
                        "provider-specific options like NVIDIA DeepSeek's thinking toggle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.testConnection() },
                        enabled = !state.testingConnection && state.apiKey.isNotBlank()
                    ) {
                        Text(if (state.testingConnection) "Testing…" else "Test Connection")
                    }
                }
                state.connectionStatus?.let { status ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.startsWith("✓"))
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
            }

            // ---- Storage (v0.4.1) ----------------------------------
            item {
                SectionLabel("Storage Access")
                Text(
                    text = if (state.storageAccessGranted) {
                        "✓ All files access granted. The agent can find all your files in Downloads, Documents, etc."
                    } else {
                        "✗ All files access NOT granted. The agent can only see files it created itself — " +
                            "it will NOT find your existing PDFs, DOCXs, etc. in shared storage. " +
                            "Tap the button below to grant access."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.storageAccessGranted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.grantFullStorageAccess()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.storageAccessGranted)
                        "Open system settings (already granted)"
                    else
                        "Grant all files access")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Opens the system 'All files access' page. Find 'AI Agent' in the list " +
                        "and toggle it on. Then come back to this screen and tap Refresh below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { viewModel.refreshStorageAccess() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh — re-check permission status")
                }
            }

            // ---- v0.6.2: Accessibility Service ----------------------
            item {
                SectionLabel("Accessibility Service")
                Text(
                    text = if (state.accessibilityEnabled) {
                        "✓ Accessibility service is ENABLED. The agent can read the screen, " +
                            "tap buttons, type into text fields, scroll, and press Back/Home " +
                            "in other apps on your behalf."
                    } else {
                        "✗ Accessibility service is NOT enabled. Without this, the agent can " +
                            "open apps but CANNOT interact with them (tap, type, scroll, etc.). " +
                            "Tap the button below, find 'AI Agent' in the list, and toggle it ON."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.accessibilityEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.openAccessibilitySettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.accessibilityEnabled)
                        "Open Accessibility settings (already enabled)"
                    else
                        "Open Accessibility settings")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "In the system Accessibility page, find 'AI Agent' (or " +
                        "'installed apps' → 'AI Agent'), tap it, and toggle ON. The agent " +
                        "only acts when you ask it to — it never reads the screen or taps " +
                        "buttons on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { viewModel.refreshAccessibility() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh — re-check accessibility status")
                }
            }

            // ---- v0.6.2: Floating Overlay (SYSTEM_ALERT_WINDOW) -----
            item {
                SectionLabel("Floating Chat Overlay")
                Text(
                    text = if (state.overlayEnabled) {
                        "✓ Draw-over-other-apps permission GRANTED. When the agent opens " +
                            "another app, a floating chat window will stay visible on top " +
                            "so you can keep reading the agent's responses."
                    } else {
                        "✗ Draw-over-other-apps permission NOT granted. Without this, the " +
                            "floating chat window will NOT appear when the agent opens " +
                            "another app — you'll have to switch back to the AI Agent app " +
                            "to see what the agent is doing."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.overlayEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.openOverlaySettings() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.overlayEnabled)
                        "Open overlay settings (already granted)"
                    else
                        "Grant draw-over-other-apps permission")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Opens the system 'Display over other apps' page for AI Agent. " +
                        "Toggle 'Allow display over other apps' ON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { viewModel.refreshOverlay() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Refresh — re-check overlay permission")
                }
            }

            // ---- Remote Configuration ------------------------------
            item {
                SectionLabel(stringResource(R.string.settings_remote_config))
                OutlinedTextField(
                    value = state.remoteConfigUrl,
                    onValueChange = viewModel::updateRemoteConfigUrl,
                    label = { Text(stringResource(R.string.settings_remote_config_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // ---- Updater ------------------------------------------
            item {
                SectionLabel(stringResource(R.string.settings_updater_repo))
                OutlinedTextField(
                    value = state.updaterRepo,
                    onValueChange = viewModel::updateUpdaterRepo,
                    label = { Text(stringResource(R.string.settings_updater_repo)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            // ---- General ------------------------------------------
            item {
                SectionLabel(stringResource(R.string.settings_general))
                ToggleRow(
                    title = stringResource(R.string.settings_auto_update),
                    subtitle = stringResource(R.string.settings_auto_update_subtitle),
                    checked = state.autoCheckUpdates,
                    onCheckedChange = viewModel::setAutoCheckUpdates
                )
            }

            // ---- Save button --------------------------------------
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.save() },
                    enabled = state.dirty,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save changes") }
            }

            // ---- Debug entry --------------------------------------
            item {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onOpenDebug,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.BugReport, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Debug")
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Local logs: app files dir under logs/. " +
                        "Conversations: filesDir/conversations/. " +
                        "Memory: filesDir/memory/.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(bottom = 4.dp, top = 8.dp)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
