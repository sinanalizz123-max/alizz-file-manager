package com.alizz.filemanager.providers.drive

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
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alizz.filemanager.ui.BrowserViewModel
import com.google.android.gms.common.AccountPicker
import java.io.File
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DriveScreen(
    driveVm: DriveViewModel,
    store: DriveAccountStore,
    downloadDir: File?,
    onDownloaded: () -> Unit,
    onBack: () -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showMkdir by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DriveItem?>(null) }
    var showTrashConfirm by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<DriveItem?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }

    val accountPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        if (email != null) {
            driveVm.signIn(email, store)
        } else {
            scope.launch { snack.showSnackbar("No account chosen") }
        }
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            driveVm.retryAfterConsent()
        } else {
            driveVm.dismissConsent()
            scope.launch { snack.showSnackbar("Sign-in not approved") }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            driveVm.uploadUris(context, uris) {}
        }
    }

    LaunchedEffect(Unit) {
        driveVm.attach(context)
        driveVm.loadAccount(store)
    }

    driveVm.consentIntent?.let { intent ->
        LaunchedEffect(intent) {
            if (intent != null) {
                try {
                    consentLauncher.launch(intent)
                } catch (e: Exception) {
                    scope.launch { snack.showSnackbar("Cannot open consent screen") }
                    driveVm.dismissConsent()
                }
            } else {
                // No resolution intent: consent lives in the account picker.
                driveVm.retryAfterConsent()
            }
        }
    }

    driveVm.error?.let { msg ->
        LaunchedEffect(msg) {
            scope.launch { snack.showSnackbar(msg) }
            driveVm.dismissError()
        }
    }

    fun pickAccount() {
        try {
            val intent = AccountPicker.newChooseAccountIntent(
                AccountPicker.AccountChooserOptions.Builder()
                    .setAllowableAccountsTypes(listOf("com.google"))
                    .build(),
            )
            accountPicker.launch(intent)
        } catch (e: Exception) {
            scope.launch { snack.showSnackbar("Google accounts unavailable on this device") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            driveVm.selectionActive -> "${driveVm.selected.size} selected"
                            !driveVm.signedIn -> "Google Drive"
                            else -> driveVm.cwdName
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (driveVm.selectionActive) driveVm.clearSelection()
                        else if (driveVm.signedIn && driveVm.goUp()) { /* navigated */ }
                        else if (driveVm.signedIn) onBack()
                        else onBack()
                    }) {
                        Icon(
                            if (driveVm.selectionActive) Icons.Filled.Close else Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (driveVm.signedIn && driveVm.selectionActive) {
                        IconButton(onClick = { driveVm.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        val single = driveVm.items.firstOrNull { it.id in driveVm.selected }
                        if (driveVm.selected.size == 1 && single != null) {
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
                                driveVm.downloadSelected(dest) { onDownloaded() }
                            }
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = "Download")
                        }
                        IconButton(onClick = { showTrashConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Move to trash")
                        }
                    } else if (driveVm.signedIn) {
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
                                onClick = { menuOpen = false; driveVm.refresh() },
                            )
                            DropdownMenuItem(
                                text = { Text("Switch account") },
                                leadingIcon = { Icon(Icons.Filled.VpnKey, null) },
                                onClick = { menuOpen = false; pickAccount() },
                            )
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                leadingIcon = { Icon(Icons.Filled.Logout, null) },
                                onClick = { menuOpen = false; confirmSignOut = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!driveVm.signedIn) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Cloud, null, modifier = Modifier.padding(top = 48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Connect your Google account to browse Drive.", color = MaterialTheme.colorScheme.outline)
                    Button(onClick = { pickAccount() }) { Text("Choose Google account") }
                    if (driveVm.signingIn) Text("Signing in…", color = MaterialTheme.colorScheme.primary)
                    Text(
                        "The system consent screen appears here — access is granted only after you approve it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else {
                if (driveVm.signingIn) {
                    Text("Signing in…", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(24.dp))
                }
                if (driveVm.crumbs.isNotEmpty() || driveVm.cwdName != "My Drive") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { driveVm.goRoot() }) { Text("My Drive", maxLines = 1) }
                        for ((_, name) in driveVm.crumbs.drop(1)) {
                            Text("›", color = MaterialTheme.colorScheme.outline)
                            Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (driveVm.cwdName != "My Drive") {
                            Text("›", color = MaterialTheme.colorScheme.outline)
                            Text(driveVm.cwdName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Text(
                    driveVm.email ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                driveVm.transferText?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp)) }
                if (driveVm.items.isEmpty()) {
                    Text(
                        if (driveVm.busy) "Loading…" else "Empty folder",
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(24.dp),
                    )
                } else {
                    LazyColumn {
                        items(driveVm.items, key = { it.id }) { item ->
                            val checked = item.id in driveVm.selected
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (driveVm.selectionActive) driveVm.toggleSelect(item.id)
                                            else if (item.isDir) driveVm.openDir(item)
                                        },
                                        onLongClick = { driveVm.toggleSelect(item.id) },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (driveVm.selectionActive) {
                                    Checkbox(checked = checked, onCheckedChange = { driveVm.toggleSelect(item.id) })
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
                                            BrowserViewModel.formatSize(item.size) +
                                                (if (item.modified > 0) " · " + BrowserViewModel.formatDate(item.modified) else ""),
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
                    driveVm.mkdir(name) { ok -> if (ok) showMkdir = false }
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
                    driveVm.renameOne(target, name) { ok -> if (ok) renameTarget = null }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    if (showTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showTrashConfirm = false },
            title = { Text("Move ${driveVm.selected.size} item(s) to Drive trash?") },
            text = { Text("Recoverable from drive.google.com.") },
            confirmButton = {
                TextButton(onClick = {
                    showTrashConfirm = false
                    driveVm.trashSelected()
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { showTrashConfirm = false }) { Text("Cancel") } },
        )
    }
    infoTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { infoTarget = null },
            title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    "Type: ${if (item.isDir) "Folder" else "File"}" +
                        (if (!item.isDir) "\nSize: ${BrowserViewModel.formatSize(item.size)}" else "") +
                        (if (item.modified > 0) "\nModified: ${BrowserViewModel.formatDate(item.modified)}" else ""),
                )
            },
            confirmButton = { TextButton(onClick = { infoTarget = null }) { Text("OK") } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out of Drive?") },
            text = { Text(driveVm.email ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    driveVm.signOut(store)
                    confirmSignOut = false
                }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}
