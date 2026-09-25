package com.alizz.filemanager.server

import android.content.Context
import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    /** Folder currently open in the local browser (default share root). */
    localDir: File?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var portText by remember { mutableStateOf("8080") }
    var password by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(ServerStatus.running) }
    var url by remember { mutableStateOf(ServerStatus.url) }
    var confirmStop by remember { mutableStateOf(false) }

    fun refresh() {
        running = ServerStatus.running
        url = ServerStatus.url
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("File server", maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (running) "Running\n$url" else "Stopped",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    if (running && ServerStatus.rootPath.isNotEmpty()) {
                        Text(
                            "Sharing: ${ServerStatus.rootPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!running) {
                Text(
                    "Folder: ${localDir?.absolutePath ?: "—"}\nOpen a local folder first to choose what to share.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (required for upload; empty = read-only open)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (password.isEmpty()) {
                    Text(
                        "Without a password anyone on your network can read the shared folder. Set one to require login (and enable uploads).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    enabled = localDir != null && (portText.toIntOrNull() ?: -1) in 1..65535,
                    onClick = {
                        val dir = localDir ?: return@Button
                        val port = portText.toIntOrNull() ?: return@Button
                        val intent = Intent(context, FileServerService::class.java).apply {
                            action = ACTION_START_SERVER
                            putExtra(EXTRA_ROOT, dir.absolutePath)
                            putExtra(EXTRA_PORT, port)
                            putExtra(EXTRA_PASSWORD, password)
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                        } catch (e: Exception) {
                            scope.launch { snack.showSnackbar("Cannot start server: ${e.message}") }
                            return@Button
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refresh() }, 800)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start server")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(url))
                        scope.launch { snack.showSnackbar("URL copied") }
                    }) {
                        Icon(Icons.Filled.ContentCopy, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy URL")
                    }
                    Button(onClick = { confirmStop = true }) {
                        Icon(Icons.Filled.Stop, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Keep the app in foreground notification; stopping kills sharing immediately. Only files under the shared folder are reachable — path escapes are blocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop server?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    context.stopService(Intent(context, FileServerService::class.java).setAction(ACTION_STOP_SERVER))
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ refresh() }, 500)
                }) { Text("Stop", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Cancel") } },
        )
    }
}
