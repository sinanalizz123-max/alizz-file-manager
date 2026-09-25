package com.alizz.filemanager.archive

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alizz.filemanager.ui.BrowserViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ArchiveScreen(
    file: File,
    destDir: File,
    onBack: () -> Unit,
    onExtracted: () -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var entries by remember(file) { mutableStateOf<List<ArchiveEntry>?>(null) }
    var loadError by remember(file) { mutableStateOf<String?>(null) }
    var cwd by remember(file) { mutableStateOf("") }
    var selected by remember(file) { mutableStateOf<Set<String>>(emptySet()) }
    var extracting by remember(file) { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var showExtractConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        try {
            entries = withContext(Dispatchers.IO) { listArchive(file) }
        } catch (e: Exception) {
            loadError = e.message ?: "Cannot read archive"
        }
    }

    val visible = remember(entries, cwd) {
        entries.orEmpty().filter { parentOf(it.path) == cwd }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }
    val crumbs = remember(cwd) {
        if (cwd.isEmpty()) emptyList() else cwd.split('/').scan("") { acc, s -> if (acc.isEmpty()) s else "$acc/$s" }.drop(1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(if (selected.isEmpty()) file.name else "${selected.size} selected", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (cwd.isNotEmpty()) {
                            cwd = parentOf(cwd)
                            selected = emptySet()
                        } else onBack()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { showExtractConfirm = true }) {
                            Icon(Icons.Filled.Unarchive, contentDescription = "Extract selected")
                        }
                    } else if (!entries.isNullOrEmpty()) {
                        TextButton(onClick = { showExtractConfirm = true }) { Text("Extract all") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (cwd.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(cwd, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${crumbs.size} deep", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            if (extracting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(progressText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                loadError != null -> Text(loadError ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                entries == null -> Text("Reading archive…", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(24.dp))
                visible.isEmpty() -> Text("Empty folder", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(24.dp))
                else -> LazyColumn {
                    items(visible, key = { it.path }) { entry ->
                        val checked = entry.path in selected
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty() || checked) {
                                            selected = if (checked) selected - entry.path else selected + entry.path
                                        } else if (entry.isDir) {
                                            cwd = entry.path.trimEnd('/')
                                        }
                                    },
                                    onLongClick = {
                                        selected = if (checked) selected - entry.path else selected + entry.path
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selected.isNotEmpty()) Checkbox(checked = checked, onCheckedChange = {
                                selected = if (checked) selected - entry.path else selected + entry.path
                            })
                            Icon(
                                if (entry.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name.ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!entry.isDir) {
                                    Text(
                                        BrowserViewModel.formatSize(entry.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            if (entry.isDir) Icon(Icons.Filled.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }

    if (showExtractConfirm) {
        val count = if (selected.isEmpty()) entries.orEmpty().size else selected.size
        AlertDialog(
            onDismissRequest = { showExtractConfirm = false },
            title = { Text("Extract $count item(s)?") },
            text = { Text("To: ${destDir.absolutePath}") },
            confirmButton = {
                TextButton(onClick = {
                    showExtractConfirm = false
                    extracting = true
                    progressText = "Starting…"
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                extractArchive(file, destDir, selected) { name, done, total ->
                                    scope.launch {
                                        progressText = if (total > 0) "$done / $total" else name
                                    }
                                }
                            }
                            selected = emptySet()
                            onExtracted()
                            scope.launch {
                                snack.showSnackbar(
                                    "Extracted ${result.extracted}" +
                                        (if (result.skipped > 0) ", skipped ${result.skipped} unsafe" else ""),
                                )
                            }
                        } catch (e: Exception) {
                            scope.launch { snack.showSnackbar(e.message ?: "Extract failed") }
                        } finally {
                            extracting = false
                        }
                    }
                }) { Text("Extract") }
            },
            dismissButton = { TextButton(onClick = { showExtractConfirm = false }) { Text("Cancel") } },
        )
    }
}
