package com.alizz.filemanager.shizuku

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(
    hub: ShizukuHub,
    enabled: Boolean,
    managerInstalled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRequest: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shizuku", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Status", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when (hub.status) {
                                ShizukuStatus.NOT_INSTALLED -> "Shizuku not available"
                                ShizukuStatus.DENIED -> "Permission not granted"
                                ShizukuStatus.GRANTED -> "Granted (service not bound)"
                                ShizukuStatus.BOUND -> "Active — privileged deletes enabled"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hub.status == ShizukuStatus.BOUND) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
            }
            if (!managerInstalled) {
                Text(
                    "Shizuku manager app is not installed. Install it and start the service before enabling this.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (enabled && hub.status == ShizukuStatus.DENIED) {
                Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                    Text("Request Shizuku permission")
                }
            }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh status")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "When active, deletions the app cannot perform itself are retried with system privileges. " +
                    "Normal permission checks still apply everywhere else, and every privileged call falls back " +
                    "to standard APIs on failure. Disable the switch to turn it fully off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
