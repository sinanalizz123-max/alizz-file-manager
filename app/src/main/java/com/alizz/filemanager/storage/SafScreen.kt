package com.alizz.filemanager.storage

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.alizz.filemanager.ui.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SafScreen(
    safVm: SafViewModel,
    onBack: () -> Unit,
    /** Open a single file: cache-copy is provided for viewers / external apps. */
    onOpenFile: (SafItem, java.io.File) -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showMkdir by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SafItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<SafItem?>(null) }
    var opening by remember { mutableStateOf(false) }

    safVm.error?.let { msg ->
        LaunchedEffect(msg) {
            scope.launch { snack.showSnackbar(msg) }
            safVm.dismissError()
        }
    }

    fun openItem(item: SafItem) {
        if (opening) return
        opening = true
        scope.launch {
            val copy = safVm.cacheCopy(item)
            opening = false
            if (copy != null) onOpenFile(item, copy)
            else scope.launch { snack.showSnackbar("Cannot open file") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (safVm.selectionActive) "${safVm.selected.size} selected"
                        else safVm.path.lastOrNull()?.name ?: safVm.rootLabel.ifEmpty { "External storage" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (safVm.selectionActive) safVm.clearSelection()
                        else if (!safVm.goUp()) onBack()
                    }) {
                        Icon(
                            if (safVm.selectionActive) Icons.Filled.Close else Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (safVm.selectionActive) {
                        IconButton(onClick = { safVm.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        val single = safVm.items.firstOrNull { it.doc.uri.toString() in safVm.selected }
                        if (safVm.selected.size == 1 && single != null) {
                            IconButton(onClick = { renameTarget = single }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename")
                            }
                            IconButton(onClick = { infoTarget = single }) {
                                Icon(Icons.Filled.Info, contentDescription = "Properties")
                            }
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    } else {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                onClick = { menuOpen = false; showMkdir = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = { menuOpen = false; safVm.refresh() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (safVm.path.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { safVm.goTo(-1) }) { Text(safVm.rootLabel.ifEmpty { "Root" }, maxLines = 1) }
                    for (i in safVm.path.indices) {
                        Text("›", color = MaterialTheme.colorScheme.outline)
                        TextButton(onClick = { safVm.goTo(i) }) {
                            Text(safVm.path[i].name.ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (safVm.items.isEmpty()) {
                Text(
                    if (safVm.busy) "Loading…" else "Empty folder",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn {
                    items(safVm.items, key = { it.doc.uri.toString() }) { item ->
                        val checked = item.doc.uri.toString() in safVm.selected
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (safVm.selectionActive) safVm.toggleSelect(item.doc.uri.toString())
                                        else if (item.isDir) safVm.openDir(item)
                                        else openItem(item)
                                    },
                                    onLongClick = { safVm.toggleSelect(item.doc.uri.toString()) },
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (safVm.selectionActive) {
                                Checkbox(checked = checked, onCheckedChange = { safVm.toggleSelect(item.doc.uri.toString()) })
                            }
                            Icon(
                                if (item.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (item.isDir) "Folder"
                                    else BrowserViewModel.formatSize(item.size) +
                                        (if (item.modified > 0) " · " + BrowserViewModel.formatDate(item.modified) else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showMkdir) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    safVm.createFolder(name) { ok -> if (ok) showMkdir = false }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Cancel") } },
        )
    }
    renameTarget?.let { target ->
        var name by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    safVm.renameOne(target, name) { ok -> if (ok) renameTarget = null }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${safVm.selected.size} item(s)?") },
            text = { Text("SAF deletions are permanent.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    safVm.deleteSelected {}
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
    infoTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { infoTarget = null },
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    "Type: ${if (item.isDir) "Folder" else "File"}\n" +
                        (if (!item.isDir) "Size: ${BrowserViewModel.formatSize(item.size)}\n" else "") +
                        (if (item.modified > 0) "Modified: ${BrowserViewModel.formatDate(item.modified)}\n" else "") +
                        "Writable: ${if (item.doc.canWrite()) "yes" else "no"}",
                )
            },
            confirmButton = { TextButton(onClick = { infoTarget = null }) { Text("OK") } },
        )
    }
}
