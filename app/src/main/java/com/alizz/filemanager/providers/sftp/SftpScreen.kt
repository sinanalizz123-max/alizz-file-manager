package com.alizz.filemanager.providers.sftp

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alizz.filemanager.ui.BrowserViewModel
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SftpScreen(
    sftpVm: SftpViewModel,
    store: SftpProfileStore,
    downloadDir: File?,
    onDownloaded: () -> Unit,
    onBack: () -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SftpProfile?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SftpItem?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<SftpItem?>(null) }
    var confirmForget by remember { mutableStateOf<SftpProfile?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            sftpVm.uploadUris(context, uris) {}
        }
    }

    LaunchedEffect(Unit) {
        sftpVm.attach(context)
        sftpVm.loadProfiles(store)
    }

    sftpVm.error?.let { msg ->
        LaunchedEffect(msg) {
            scope.launch { snack.showSnackbar(msg) }
            sftpVm.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            sftpVm.selectionActive -> "${sftpVm.selected.size} selected"
                            !sftpVm.connected -> "SFTP"
                            else -> sftpVm.profile?.displayLabel() ?: "SFTP"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (sftpVm.selectionActive) sftpVm.clearSelection()
                        else if (sftpVm.connected && sftpVm.goUp()) { /* navigated */ }
                        else if (sftpVm.connected) { sftpVm.disconnect(); sftpVm.loadProfiles(store) }
                        else onBack()
                    }) {
                        Icon(
                            if (sftpVm.selectionActive) Icons.Filled.Close else Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (sftpVm.connected && sftpVm.selectionActive) {
                        IconButton(onClick = { sftpVm.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        val single = sftpVm.items.firstOrNull { it.path in sftpVm.selected }
                        if (sftpVm.selected.size == 1 && single != null) {
                            IconButton(onClick = { renameTarget = single }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename")
                            }
                            IconButton(onClick = { infoTarget = single }) {
                                Icon(Icons.Filled.Info, contentDescription = "Properties")
                            }
                        }
                        IconButton(onClick = {
                            val dest = downloadDir
                            if (dest == null) {
                                scope.launch { snack.showSnackbar("Open a local folder first (downloads go there)") }
                            } else {
                                sftpVm.downloadSelected(dest) { onDownloaded() }
                            }
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    } else if (sftpVm.connected) {
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
                                onClick = { menuOpen = false; sftpVm.refresh() },
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                onClick = { menuOpen = false; sftpVm.disconnect(); sftpVm.loadProfiles(store) },
                            )
                        }
                    } else {
                        IconButton(onClick = { editing = null; showForm = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add server")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!sftpVm.connected) {
                if (sftpVm.connecting) {
                    Text("Connecting…", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(24.dp))
                } else if (sftpVm.profiles.isEmpty()) {
                    Text(
                        "No servers yet. Tap + to add an SFTP server.",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn {
                        items(sftpVm.profiles, key = { it.id }) { p ->
                            Card(onClick = { sftpVm.connect(p) {} }) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.displayLabel(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "SFTP · port ${p.port} · " + (if (p.auth == SftpAuth.KEY) "key" else "password"),
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
                if (sftpVm.cwd.isNotEmpty()) {
                    Text(
                        sftpVm.cwd,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                sftpVm.transferText?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp)) }
                if (sftpVm.items.isEmpty()) {
                    Text(
                        if (sftpVm.busy) "Loading…" else "Empty folder",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn {
                        items(sftpVm.items, key = { it.path }) { item ->
                            val checked = item.path in sftpVm.selected
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (sftpVm.selectionActive) sftpVm.toggleSelect(item.path)
                                            else if (item.isDir) sftpVm.openDir(item)
                                        },
                                        onLongClick = { sftpVm.toggleSelect(item.path) },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (sftpVm.selectionActive) {
                                    Checkbox(checked = checked, onCheckedChange = { sftpVm.toggleSelect(item.path) })
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

    sftpVm.trustPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { sftpVm.dismissTrust() },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(if (prompt.changed) "Host key changed!" else "Trust this host?") },
            text = {
                Text(
                    (if (prompt.changed) "The server key does not match the saved one — this can mean an attack. Only continue if you changed it yourself.\n\n" else "First connection to ${prompt.profile.host}. Verify this fingerprint out-of-band before trusting.\n\n") +
                        prompt.fingerprint,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { sftpVm.trustAndReconnect(store) }) { Text("Trust & connect") }
            },
            dismissButton = { TextButton(onClick = { sftpVm.dismissTrust() }) { Text("Cancel") } },
        )
    }

    if (showForm) {
        ProfileForm(
            initial = editing,
            onDismiss = { showForm = false; editing = null },
            onSave = { profile ->
                store.save(profile)
                sftpVm.loadProfiles(store)
                showForm = false
                editing = null
            },
        )
    }
    confirmForget?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("Forget server?") },
            text = { Text(p.displayLabel()) },
            confirmButton = {
                TextButton(onClick = {
                    store.delete(p.id)
                    sftpVm.loadProfiles(store)
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
                    sftpVm.mkdir(name) { ok -> if (ok) showMkdir = false }
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
                    sftpVm.renameOne(target, name) { ok -> if (ok) renameTarget = null }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${sftpVm.selected.size} remote item(s)?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    sftpVm.deleteSelected {}
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
    initial: SftpProfile?,
    onDismiss: () -> Unit,
    onSave: (SftpProfile) -> Unit,
) {
    val context = LocalContext.current
    var label by remember(initial) { mutableStateOf(initial?.label ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var portText by remember(initial) { mutableStateOf(initial?.port?.toString() ?: "22") }
    var user by remember(initial) { mutableStateOf(initial?.user ?: "") }
    var useKey by remember(initial) { mutableStateOf(initial?.auth == SftpAuth.KEY) }
    var secret by remember(initial) { mutableStateOf(initial?.secret ?: "") }
    var keyPath by remember(initial) { mutableStateOf(initial?.keyPath ?: "") }
    var keyName by remember(initial) {
        mutableStateOf(initial?.keyPath?.ifEmpty { null }?.let { File(it).name } ?: "")
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val dir = File(context.filesDir, "keys").apply { mkdirs() }
                val dest = File(dir, "key-${System.currentTimeMillis()}")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, 32 * 1024) }
                }
                dest.setReadable(false, false)
                dest.setReadable(true, true)
                dest.setWritable(false, false)
                dest.setWritable(true, true)
                // Drop the previous staged key if replaced.
                if (keyPath.isNotEmpty() && keyPath != dest.absolutePath) File(keyPath).delete()
                keyPath = dest.absolutePath
                keyName = uri.lastPathSegment?.substringAfterLast('/') ?: dest.name
            } catch (e: Exception) {
                keyName = "Copy failed"
            }
        }
    }
    val valid = host.isNotBlank() && user.isNotBlank() &&
        (portText.toIntOrNull() ?: -1) in 1..65535 &&
        (!useKey || keyPath.isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add SFTP server" else "Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FilterChip(
                    selected = useKey,
                    onClick = { useKey = !useKey },
                    label = { Text(if (useKey) "Private key" else "Password") },
                    leadingIcon = { Icon(Icons.Filled.Key, null) },
                )
                if (useKey) {
                    TextButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                        Text(if (keyName.isEmpty()) "Pick private key file" else "Key: $keyName")
                    }
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("Key passphrase (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
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
                        SftpProfile(
                            id = initial?.id ?: UUID.randomUUID().toString(),
                            label = label.trim(),
                            host = host.trim(),
                            port = portText.toIntOrNull() ?: 22,
                            user = user.trim(),
                            auth = if (useKey) SftpAuth.KEY else SftpAuth.PASSWORD,
                            secret = secret,
                            keyPath = if (useKey) keyPath else "",
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
