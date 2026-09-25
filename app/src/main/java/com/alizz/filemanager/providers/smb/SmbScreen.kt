package com.alizz.filemanager.providers.smb

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alizz.filemanager.ui.BrowserViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SmbScreen(
    smbVm: SmbViewModel,
    store: SmbProfileStore,
    downloadDir: File?,
    onDownloaded: () -> Unit,
    onBack: () -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SmbProfile?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<SmbItem?>(null) }
    var confirmForget by remember { mutableStateOf<SmbProfile?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            smbVm.uploadUris(context, uris) {}
        }
    }

    LaunchedEffect(Unit) { smbVm.loadProfiles(store) }

    smbVm.error?.let { msg ->
        LaunchedEffect(msg) {
            scope.launch { snack.showSnackbar(msg) }
            smbVm.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            smbVm.selectionActive -> "${smbVm.selected.size} selected"
                            !smbVm.connected -> "SMB"
                            else -> smbVm.profile?.displayLabel() ?: "SMB"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (smbVm.selectionActive) smbVm.clearSelection()
                        else if (smbVm.connected && smbVm.goUp()) { /* navigated */ }
                        else if (smbVm.connected) { smbVm.disconnect(); smbVm.loadProfiles(store) }
                        else onBack()
                    }) {
                        Icon(
                            if (smbVm.selectionActive) Icons.Filled.Close else Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (smbVm.connected && smbVm.selectionActive) {
                        IconButton(onClick = { smbVm.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        val single = smbVm.items.firstOrNull { it.path in smbVm.selected }
                        if (smbVm.selected.size == 1 && single != null) {
                            IconButton(onClick = { infoTarget = single }) {
                                Icon(Icons.Filled.Info, contentDescription = "Properties")
                            }
                        }
                        IconButton(onClick = {
                            val dest = downloadDir
                            if (dest == null) {
                                scope.launch { snack.showSnackbar("Open a local folder first (downloads go there)") }
                            } else {
                                smbVm.downloadSelected(dest) { onDownloaded() }
                            }
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    } else if (smbVm.connected) {
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.Upload, contentDescription = "Upload files")
                        }
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
                                onClick = { menuOpen = false; smbVm.refresh() },
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = { menuOpen = false; smbVm.disconnect(); smbVm.loadProfiles(store) },
                            )
                        }
                    } else {
                        IconButton(onClick = { editing = null; showForm = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add share")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!smbVm.connected) {
                if (smbVm.connecting) {
                    Text("Connecting…", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(24.dp))
                } else if (smbVm.profiles.isEmpty()) {
                    Text(
                        "No shares yet. Tap + to add a Windows/Samba share.",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn {
                        items(smbVm.profiles, key = { it.id }) { p ->
                            Card(onClick = { smbVm.connect(p) {} }) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.displayLabel(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            (if (p.anonymous) "Guest" else p.user.ifEmpty { "?" }) + " · SMB2/3",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                    TextButton(onClick = { editing = p; showForm = true }) { Text("Edit") }
                                    TextButton(onClick = { confirmForget = p }) {
                                        Text("Forget", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (smbVm.cwd.isNotEmpty()) {
                    Text(
                        smbVm.cwd,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                smbVm.transferText?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp)) }
                if (smbVm.items.isEmpty()) {
                    Text(
                        if (smbVm.busy) "Loading…" else "Empty folder",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn {
                        items(smbVm.items, key = { it.path }) { item ->
                            val checked = item.path in smbVm.selected
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (smbVm.selectionActive) smbVm.toggleSelect(item.path)
                                            else if (item.isDir) smbVm.openDir(item)
                                        },
                                        onLongClick = { smbVm.toggleSelect(item.path) },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (smbVm.selectionActive) {
                                    Checkbox(checked = checked, onCheckedChange = { smbVm.toggleSelect(item.path) })
                                }
                                Icon(
                                    if (item.isDir) Icons.Filled.Folder else Icons.Filled.Description,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!item.isDir) {
                                        Text(
                                            BrowserViewModel.formatSize(item.size),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    } else {
                                        Text("Folder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForm) {
        ProfileForm(
            initial = editing,
            onDismiss = { showForm = false; editing = null },
            onSave = { profile ->
                store.save(profile)
                smbVm.loadProfiles(store)
                showForm = false
                editing = null
            },
        )
    }
    confirmForget?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("Forget share?") },
            text = { Text(p.displayLabel()) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(p.id)
                    smbVm.loadProfiles(store)
                    confirmForget = null
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = null }) { Text("Cancel") } },
        )
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
                    smbVm.mkdir(name) { ok -> if (ok) showMkdir = false }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Cancel") } },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${smbVm.selected.size} remote item(s)?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    smbVm.deleteSelected {}
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
                    "Path: ${item.path}\nType: ${if (item.isDir) "Folder" else "File"}" +
                        (if (!item.isDir) "\nSize: ${BrowserViewModel.formatSize(item.size)}" else ""),
                )
            },
            confirmButton = { TextButton(onClick = { infoTarget = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun ProfileForm(
    initial: SmbProfile?,
    onDismiss: () -> Unit,
    onSave: (SmbProfile) -> Unit,
) {
    var label by remember(initial) { mutableStateOf(initial?.label ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var share by remember(initial) { mutableStateOf(initial?.share ?: "") }
    var domain by remember(initial) { mutableStateOf(initial?.domain ?: "") }
    var user by remember(initial) { mutableStateOf(initial?.user ?: "") }
    var pass by remember(initial) { mutableStateOf(initial?.password ?: "") }
    var anonymous by remember(initial) { mutableStateOf(initial?.anonymous ?: false) }
    val valid = host.isNotBlank() && share.isNotBlank() && (anonymous || user.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add SMB share" else "Edit share") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host (IP or name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = share, onValueChange = { share = it }, label = { Text("Share name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FilterChip(
                    selected = anonymous,
                    onClick = { anonymous = !anonymous },
                    label = { Text(if (anonymous) "Guest access" else "Username + password") },
                )
                if (!anonymous) {
                    OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("Domain / workgroup (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        SmbProfile(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            label = label.trim(),
                            host = host.trim(),
                            share = share.trim(),
                            domain = domain.trim(),
                            user = user.trim(),
                            password = pass,
                            anonymous = anonymous,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
