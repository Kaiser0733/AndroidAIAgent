package com.kaiser.aiagent.ui.screens.launch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kaiser.aiagent.R
import kotlinx.coroutines.delay

/**
 * Splash / launch screen. v0.1 only shows branding and waits briefly before
 * navigating to the home screen. The wait is short so the user does not
 * perceive artificial latency.
 *
 * A future version will use this window to:
 *   - Fetch and validate remote config
 *   - Show a maintenance / disabled screen if remote config requires it
 *   - Check for forced updates
 */
@Composable
fun LaunchScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        // Keep the splash visible for ~1 second — long enough to feel
        // intentional, short enough not to annoy.
        delay(1_000L)
        onTimeout()
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        CircularProgressIndicator()
    }
}
