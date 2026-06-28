package com.kaiser.aiagent.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import com.kaiser.aiagent.data.chat.MessageRole
import com.kaiser.aiagent.domain.tools.PermissionManager
import org.koin.androidx.compose.koinViewModel
import org.koin.core.context.GlobalContext

/**
 * Full chat surface. Renders a message list + input bar + top app bar
 * with new / delete actions. Streams assistant responses in place.
 *
 * v0.4: observes [PermissionManager.pendingConfirmation] and shows an
 * AlertDialog whenever a CONFIRMATION_REQUIRED tool is requested. The
 * user's Approve/Deny decision resolves the deferred and the agent
 * loop resumes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    // Resolve the PermissionManager singleton from Koin's global context.
    val permissionManager: PermissionManager = remember { GlobalContext.get().get() }
    val pendingConfirmation by permissionManager.pendingConfirmation.collectAsState()

    // Auto-scroll to bottom when messages change or streaming text grows.
    LaunchedEffect(state.messages.size, state.streamingText) {
        if (state.messages.isNotEmpty() || state.streamingText.isNotEmpty()) {
            val target = state.messages.size + (if (state.streamingText.isNotEmpty()) 1 else 0)
            if (target > 0) listState.animateScrollToItem(target - 1)
        }
    }

    // Surface toasts as Snackbars.
    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // Auto-create a conversation on first entry if none is active.
    LaunchedEffect(Unit) {
        if (state.conversation == null) {
            viewModel.startNewConversation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.conversation?.title ?: "New conversation",
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.startNewConversation() },
                        enabled = !state.busy
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New conversation")
                    }
                    IconButton(
                        onClick = { viewModel.deleteActiveConversation() },
                        enabled = !state.busy && state.conversation != null
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete conversation")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Message list.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(
                        message = msg,
                        onCopy = { clipboard.setText(AnnotatedString(msg.content)) }
                    )
                }
                if (state.streamingText.isNotEmpty()) {
                    item(key = "__streaming__") {
                        StreamingBubble(text = state.streamingText)
                    }
                }
                if (state.busy && state.streamingText.isEmpty()) {
                    item(key = "__thinking__") {
                        ThinkingBubble()
                    }
                }
            }

            // Input bar.
            ChatInputBar(
                text = input,
                onTextChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        viewModel.sendMessage(input)
                        input = ""
                    }
                },
                enabled = !state.busy,
                busy = state.busy,
                onCancel = { viewModel.cancel() }
            )
        }
    }

    // v0.4: Confirmation dialog for CONFIRMATION_REQUIRED tools.
    // Shown whenever PermissionManager.pendingConfirmation is non-null.
    pendingConfirmation?.let { pc ->
        AlertDialog(
            onDismissRequest = { permissionManager.deny() },
            title = { Text("Permission required") },
            text = {
                Column {
                    Text(
                        text = "The agent wants to run:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pc.toolName,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pc.toolDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Arguments:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = pc.argumentsJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { permissionManager.approve() }) {
                    Text("Approve")
                }
            },
            dismissButton = {
                TextButton(onClick = { permissionManager.deny() }) {
                    Text("Deny")
                }
            }
        )
    }
}

@Composable
private fun MessageBubble(
    message: ChatViewModel.UiMessage,
    onCopy: () -> Unit
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (isUser) {
                Text(text = message.content, color = textColor)
            } else {
                MarkdownText(
                    text = message.content,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 2.dp, end = 4.dp, start = 4.dp)
        ) {
            Text(
                text = formatHm(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isUser) {
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            MarkdownText(text = text, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = "typing…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

@Composable
private fun ThinkingBubble() {
    Row(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    busy: Boolean = false,
    onCancel: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message…") },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            enabled = enabled
        )
        if (busy) {
            // v0.4.5: Stop button replaces Send when the agent is working.
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop"
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = enabled && text.isNotBlank()
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private fun formatHm(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))
