package com.kaiser.aiagent.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaiser.aiagent.BuildConfig
import com.kaiser.aiagent.R
import org.koin.androidx.compose.koinViewModel

/**
 * Home screen — main landing surface for the app. Shows agent status, the
 * list of future-agent modules (each rendered as a placeholder card), and
 * entry points to settings + the update flow.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: HomeViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Push the installed version into the VM once.
    LaunchedEffect(Unit) {
        viewModel.setInstalledVersion(BuildConfig.VERSION_NAME)
    }

    // Show transient toast strings as Snackbars.
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    TextButton(onClick = onOpenAbout) {
                        Text("About")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusHeader(state) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.checkForUpdate() },
                        enabled = !state.checking,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.height(0.dp))
                            Text("  " + stringResource(R.string.home_check_updates))
                        } else {
                            Icon(Icons.Filled.SystemUpdateAlt, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.home_check_updates))
                        }
                    }
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.home_open_settings))
                    }
                }
            }

            item {
                Text(
                    text = stringResource(R.string.home_modules_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(FutureModules) { mod ->
                ModuleCard(mod)
            }
        }
    }

    // Update-available dialog. Triggered by the VM after a successful check.
    if (state.updateAvailable) {
        AlertDialog(
            onDismissRequest = { /* keep it modal until user picks */ },
            title = { Text("Update Available") },
            text = {
                Column {
                    Text(
                        "Version ${state.latestVersion ?: "?"} is available. " +
                            "You are currently on v${state.installedVersion}."
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.releaseNotes ?: "No release notes provided.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (state.updateAssetUrl == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.update_toast_no_release),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The actual download/install is wired up in the
                        // UpdateRepository. For v0.1 we simply dismiss here;
                        // a follow-up will launch the system installer.
                        state.updateAssetUrl?.let { /* TODO: trigger download */ }
                    }
                ) { Text(stringResource(R.string.update_dialog_download)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    // dismiss
                }) { Text(stringResource(R.string.update_dialog_later)) }
            }
        )
    }
}

@Composable
private fun StatusHeader(state: HomeViewModel.UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = "v${state.installedVersion}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_status_active),
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.agentDisabled) {
                Text(
                    "Agent is remotely disabled.",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (state.maintenanceMode) {
                Text(
                    "Maintenance mode is active.",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private data class FutureModule(
    val name: String,
    val description: String,
    val icon: ImageVector
)

private val FutureModules = listOf(
    FutureModule("Chat", "Conversational interface (GLM / LLM)", Icons.Filled.Chat),
    FutureModule("Tools", "Pluggable tool/function calls", Icons.Filled.Build),
    FutureModule("Memory", "Long-term + working memory", Icons.Filled.Memory),
    FutureModule("Automation", "UI automation pipelines", Icons.Filled.PrecisionManufacturing),
    FutureModule("Accessibility", "Accessibility Service bridge", Icons.Filled.SmartToy),
    FutureModule("Updater", "Self-update subsystem", Icons.Filled.CloudDownload)
)

@Composable
private fun ModuleCard(mod: FutureModule) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = mod.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = mod.name,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = mod.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_module_status_placeholder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
