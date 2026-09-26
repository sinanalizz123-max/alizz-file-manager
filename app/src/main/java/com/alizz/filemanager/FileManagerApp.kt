package com.alizz.filemanager

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.ViewList
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alizz.filemanager.data.HistoryEntry
import com.alizz.filemanager.data.HistoryStore
import com.alizz.filemanager.ui.BrowserView
import com.alizz.filemanager.ui.BrowserViewModel
import com.alizz.filemanager.ui.BrowserViewModel.RenamePreview
import com.alizz.filemanager.ui.FileItem
import com.alizz.filemanager.ui.RecycleStore
import com.alizz.filemanager.ui.RecycleViewModel
import com.alizz.filemanager.ui.SearchScope
import com.alizz.filemanager.ui.SortMode
import com.alizz.filemanager.archive.ArchiveScreen
import com.alizz.filemanager.providers.cloud.BoxProvider
import com.alizz.filemanager.providers.cloud.CloudProvider
import com.alizz.filemanager.providers.cloud.DropboxAccountStore
import com.alizz.filemanager.providers.cloud.DropboxScreen
import com.alizz.filemanager.providers.cloud.DropboxViewModel
import com.alizz.filemanager.providers.cloud.GenericCloudScreen
import com.alizz.filemanager.providers.cloud.GenericCloudViewModel
import com.alizz.filemanager.providers.cloud.OneDriveAccountStore
import com.alizz.filemanager.providers.cloud.OneDriveScreen
import com.alizz.filemanager.providers.cloud.OneDriveViewModel
import com.alizz.filemanager.providers.cloud.PCloudProvider
import com.alizz.filemanager.providers.cloud.YandexProvider
import com.alizz.filemanager.providers.drive.DriveAccountStore
import com.alizz.filemanager.providers.drive.DriveScreen
import com.alizz.filemanager.providers.drive.DriveViewModel
import com.alizz.filemanager.server.ServerScreen
import com.alizz.filemanager.shizuku.ShizukuHub
import com.alizz.filemanager.shizuku.ShizukuScreen
import com.alizz.filemanager.shizuku.ShizukuStatus
import com.alizz.filemanager.providers.ftp.FtpProfile
import com.alizz.filemanager.providers.ftp.FtpProfileStore
import com.alizz.filemanager.providers.ftp.FtpScreen
import com.alizz.filemanager.providers.ftp.FtpSecurity
import com.alizz.filemanager.providers.ftp.FtpViewModel
import com.alizz.filemanager.providers.sftp.SftpAuth
import com.alizz.filemanager.providers.sftp.SftpProfile
import com.alizz.filemanager.providers.sftp.SftpProfileStore
import com.alizz.filemanager.providers.sftp.SftpScreen
import com.alizz.filemanager.providers.sftp.SftpViewModel
import com.alizz.filemanager.providers.smb.SmbProfile
import com.alizz.filemanager.providers.smb.SmbProfileStore
import com.alizz.filemanager.providers.smb.SmbScreen
import com.alizz.filemanager.providers.smb.SmbViewModel
import com.alizz.filemanager.providers.webdav.WebdavProfile
import com.alizz.filemanager.providers.webdav.WebdavProfileStore
import com.alizz.filemanager.providers.webdav.WebdavScreen
import com.alizz.filemanager.providers.webdav.WebdavViewModel
import com.alizz.filemanager.storage.SafRoot
import com.alizz.filemanager.storage.SafScreen
import com.alizz.filemanager.storage.SafStore
import com.alizz.filemanager.storage.SafViewModel
import com.alizz.filemanager.ui.TrashedItem
import com.alizz.filemanager.viewer.ApkScreen
import com.alizz.filemanager.viewer.ImageScreen
import com.alizz.filemanager.viewer.PdfScreen
import com.alizz.filemanager.viewer.PlayerScreen
import com.alizz.filemanager.viewer.TextScreen
import com.alizz.filemanager.viewer.ViewerKind
import com.alizz.filemanager.viewer.kindOf
import com.alizz.filemanager.viewer.openWith
import com.alizz.filemanager.ui.theme.FileManagerTheme
import com.alizz.filemanager.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerApp(onRequestStorageAccess: () -> Unit, startInRecycle: Boolean = false) {
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    FileManagerTheme(mode = themeMode) {
        FileManagerScaffold(themeMode = themeMode, onThemeChange = { themeMode = it }, onRequestStorageAccess = onRequestStorageAccess, startInRecycle = startInRecycle)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FileManagerScaffold(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onRequestStorageAccess: () -> Unit,
    startInRecycle: Boolean = false,
    vm: BrowserViewModel = viewModel(),
    safVm: SafViewModel = viewModel(),
    ftpVm: FtpViewModel = viewModel(),
    sftpVm: SftpViewModel = viewModel(),
    smbVm: SmbViewModel = viewModel(),
    webdavVm: WebdavViewModel = viewModel(),
    driveVm: DriveViewModel = viewModel(),
    dropboxVm: DropboxViewModel = viewModel(),
    oneDriveVm: OneDriveViewModel = viewModel(),
    cloudVm: GenericCloudViewModel = viewModel(),
) {
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showMkdir by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showBatchRename by remember { mutableStateOf(false) }
    var showCompress by remember { mutableStateOf(false) }
    var infoTarget by remember { mutableStateOf<File?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var showRecycle by remember(startInRecycle) { mutableStateOf(startInRecycle) }
    var showSaf by remember { mutableStateOf(false) }
    var showFtp by remember { mutableStateOf(false) }
    var showSftp by remember { mutableStateOf(false) }
    var showSmb by remember { mutableStateOf(false) }
    var showWebdav by remember { mutableStateOf(false) }
    var showDrive by remember { mutableStateOf(false) }
    var showDropbox by remember { mutableStateOf(false) }
    var showOneDrive by remember { mutableStateOf(false) }
    var cloudProvider by remember { mutableStateOf<CloudProvider?>(null) }
    var showServer by remember { mutableStateOf(false) }
    var showShizuku by remember { mutableStateOf(false) }
    var showPermPopup by remember { mutableStateOf(!hasStoragePermission(context)) }
    var autoOpened by remember { mutableStateOf(false) }
    var viewerTarget by remember { mutableStateOf<File?>(null) }
    var viewerReadOnly by remember { mutableStateOf(false) }
    val recycleStore = remember(context) { RecycleStore(context) }
    val recycleVm = remember { RecycleViewModel() }
    val historyStore = remember(context) { HistoryStore(context) }
    val safStore = remember(context) { SafStore(context) }
    var safRoots by remember { mutableStateOf(listOf<SafRoot>()) }
    vm.history = historyStore
    val bookmarkSet by historyStore.bookmarks.collectAsState(initial = emptySet())
    val historyList by historyStore.history.collectAsState(initial = emptyList())
    val ftpStore = remember(context) { FtpProfileStore(context) }
    var ftpProfiles by remember { mutableStateOf(listOf<FtpProfile>()) }
    val sftpStore = remember(context) { SftpProfileStore(context) }
    var sftpProfiles by remember { mutableStateOf(listOf<SftpProfile>()) }
    val smbStore = remember(context) { SmbProfileStore(context) }
    var smbProfiles by remember { mutableStateOf(listOf<SmbProfile>()) }
    val webdavStore = remember(context) { WebdavProfileStore(context) }
    var webdavProfiles by remember { mutableStateOf(listOf<WebdavProfile>()) }
    val driveStore = remember(context) { DriveAccountStore(context) }
    var driveEmail by remember { mutableStateOf<String?>(null) }
    val dropboxStore = remember(context) { DropboxAccountStore(context) }
    var dropboxEmail by remember { mutableStateOf<String?>(null) }
    val oneDriveStore = remember(context) { OneDriveAccountStore(context) }
    var oneDriveEmail by remember { mutableStateOf<String?>(null) }
    val shizukuHub = remember(context) { ShizukuHub(context) }
    var shizukuEnabled by remember {
        mutableStateOf(
            context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                .getBoolean("shizuku_enabled", false),
        )
    }
    vm.privilegedDelete = if (shizukuEnabled && shizukuHub.status == ShizukuStatus.BOUND) {
        { f ->
            try {
                shizukuHub.boundService?.deleteRecursively(f.absolutePath) == true
            } catch (e: Exception) {
                false
            }
        }
    } else {
        null
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        shizukuHub.start()
        onDispose { shizukuHub.stop() }
    }

    BackHandler(enabled = showRecycle && viewerTarget == null) { showRecycle = false }
    BackHandler(enabled = showSaf && viewerTarget == null) { showSaf = false; safVm.goHome() }
    BackHandler(enabled = showFtp && viewerTarget == null) {
        if (ftpVm.connected && ftpVm.goUp()) {
            // navigated remote
        } else if (ftpVm.connected) {
            ftpVm.disconnect()
        } else {
            showFtp = false
        }
    }
    BackHandler(enabled = showSftp && viewerTarget == null) {
        if (sftpVm.connected && sftpVm.goUp()) {
            // navigated remote
        } else if (sftpVm.connected) {
            sftpVm.disconnect()
        } else {
            showSftp = false
        }
    }
    BackHandler(enabled = showSmb && viewerTarget == null) {
        if (smbVm.connected && smbVm.goUp()) {
            // navigated remote
        } else if (smbVm.connected) {
            smbVm.disconnect()
        } else {
            showSmb = false
        }
    }
    BackHandler(enabled = showWebdav && viewerTarget == null) {
        if (webdavVm.connected && webdavVm.goUp()) {
            // navigated remote
        } else if (webdavVm.connected) {
            webdavVm.disconnect()
        } else {
            showWebdav = false
        }
    }
    BackHandler(enabled = showDrive && viewerTarget == null) {
        if (driveVm.signedIn && driveVm.goUp()) {
            // navigated remote
        } else {
            showDrive = false
        }
    }
    BackHandler(enabled = showDropbox) { showDropbox = false }
    BackHandler(enabled = showOneDrive) { showOneDrive = false }
    BackHandler(enabled = cloudProvider != null) { cloudProvider = null }
    BackHandler(enabled = showServer) { showServer = false }
    BackHandler(enabled = showShizuku) { showShizuku = false }
    BackHandler(enabled = viewerTarget != null) { viewerTarget = null; viewerReadOnly = false; vm.clearChecksums() }
    BackHandler(
        enabled = viewerTarget == null && !showRecycle && !showSaf && !showFtp && !showSftp &&
            !showSmb && !showWebdav && !showDrive && !showDropbox && !showOneDrive &&
            cloudProvider == null && !showServer && !showShizuku &&
            (vm.selectionActive || vm.path.isNotEmpty()),
    ) {
        // Phone back navigates: clear selection, then up, then home. At home root the press falls through and exits.
        if (vm.selectionActive) vm.clearSelection()
        else if (vm.path.isNotEmpty() && !vm.goUp()) vm.goHome()
    }

    val legacyPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            showPermPopup = false
            vm.loadRoots(context)
            safRoots = safStore.roots()
        }
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            onRequestStorageAccess()
        } else {
            legacyPermLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun refreshLocalRoots() {
        vm.loadRoots(context)
        safRoots = safStore.roots()
        recycleVm.load(recycleStore)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshLocalRoots()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(vm.roots) {
        // Jump straight into storage once permission lands and exactly one root exists.
        if (!autoOpened && vm.path.isEmpty() && vm.roots.size == 1) {
            autoOpened = true
            vm.openRoot(vm.roots.first())
        }
        if (vm.roots.size != 1) autoOpened = true
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val label = uri.lastPathSegment?.substringAfter(':')?.ifEmpty { null } ?: "External storage"
            if (safStore.grant(uri, label)) {
                scope.launch { snack.showSnackbar("Storage added") }
            } else {
                scope.launch { snack.showSnackbar("Grant failed") }
            }
            safRoots = safStore.roots()
        }
    }

    /** Open a dir, jumping across folders for global-search results. */
    fun openBrowserItem(item: FileItem) {
        if (vm.selectionActive) {
            vm.toggleSelect(item.file.absolutePath)
        } else if (item.file.isDirectory) {
            val sameParent = item.file.parentFile?.absolutePath == vm.currentDir?.absolutePath
            if (sameParent) vm.openDir(item.file) else vm.openPath(item.file)
        } else {
            openFileEntry(item.file)
        }
    }

    fun openFileEntry(f: File) {        viewerReadOnly = false
        when (kindOf(f)) {
            ViewerKind.IMAGE, ViewerKind.VIDEO, ViewerKind.AUDIO, ViewerKind.TEXT,
            ViewerKind.APK, ViewerKind.ARCHIVE, ViewerKind.PDF -> viewerTarget = f
            ViewerKind.OTHER -> {
                var opened = false
                try {
                    opened = openWith(context, f)
                } catch (e: Exception) {
                    opened = false
                }
                if (!opened) infoTarget = f
            }
        }
    }

    LaunchedEffect(Unit) {
        if (hasStoragePermission(context)) vm.loadRoots(context)
        recycleVm.load(recycleStore)
        safRoots = safStore.roots()
        ftpProfiles = ftpStore.all()
        sftpProfiles = sftpStore.all()
        smbProfiles = smbStore.all()
        webdavProfiles = webdavStore.all()
        driveEmail = driveStore.accountEmail()
        dropboxEmail = dropboxStore.account()
        oneDriveEmail = oneDriveStore.account()
    }

    LaunchedEffect(vm.currentDir?.absolutePath) {
        val dir = vm.currentDir
        if (dir != null && dir.isDirectory) {
            val observer = object : FileObserver(dir.absolutePath, CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE) {
                override fun onEvent(event: Int, path: String?) { vm.refresh() }
            }
            observer.startWatching()
            try { kotlinx.coroutines.awaitCancellation() } finally { observer.stopWatching() }
        }
    }

    vm.error?.let { msg ->
        LaunchedEffect(msg) {
            scope.launch { snack.showSnackbar(msg) }
            vm.dismissError()
        }
    }
    recycleVm.error?.let { msg ->
        LaunchedEffect(msg) {
            scope.launch { snack.showSnackbar(msg) }
            recycleVm.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            if (viewerTarget != null) {
                // Viewers provide their own bar and actions — no file-manager chrome.
            } else if (cloudProvider != null) {
                // GenericCloudScreen provides its own bar.
            } else if (showDropbox || showOneDrive) {
                // Cloud screens provide their own bars.
            } else if (showShizuku) {
                // ShizukuScreen provides its own bar.
            } else if (showServer) {
                // ServerScreen provides its own bar.
            } else if (showDrive) {
                // DriveScreen provides its own bar.
            } else if (showWebdav) {
                // WebdavScreen provides its own bar.
            } else if (showSmb) {
                // SmbScreen provides its own bar.
            } else if (showSftp) {
                // SftpScreen provides its own bar.
            } else if (showFtp) {
                // FtpScreen provides its own bar.
            } else if (showSaf) {
                // SafScreen provides its own bar.
            } else if (showRecycle) {
                TopAppBar(
                    title = { Text(if (recycleVm.selectionActive) "${recycleVm.selected.size} selected" else "Recycle bin") },
                    navigationIcon = {
                        IconButton(onClick = { showRecycle = false; recycleVm.clearSelection() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (recycleVm.selectionActive) {
                            IconButton(onClick = { recycleVm.selectAll() }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                            }
                            IconButton(onClick = {
                                recycleVm.restoreSelected(recycleStore, vm.currentDir ?: vm.roots.firstOrNull() ?: File("/"))
                            }) {
                                Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Restore")
                            }
                            IconButton(onClick = { recycleVm.deleteSelectedPermanently(recycleStore) }) {
                                Icon(Icons.Filled.DeleteForever, contentDescription = "Delete forever")
                            }
                        } else if (recycleVm.items.isNotEmpty()) {
                            TextButton(onClick = { recycleVm.emptyAll(recycleStore) }) { Text("Empty") }
                        }
                    },
                )
            } else if (vm.selectionActive) {
                TopAppBar(
                    title = { Text("${vm.selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.selectAll() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = "Select all")
                        }
                        if (vm.selected.size == 1) {
                            IconButton(onClick = {
                                renameTarget = File(vm.selected.first())
                            }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename")
                            }
                            IconButton(onClick = {
                                infoTarget = File(vm.selected.first())
                            }) {
                                Icon(Icons.Filled.Info, contentDescription = "Properties")
                            }
                        }
                        if (vm.selected.size >= 1) {
                            IconButton(onClick = { showBatchRename = true }) {
                                Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = "Batch rename")
                            }
                            IconButton(onClick = { showCompress = true }) {
                                Icon(Icons.Filled.Archive, contentDescription = "Compress to ZIP")
                            }
                        }
                        IconButton(onClick = { vm.copySelected() }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = { vm.cutSelected() }) {
                            Icon(Icons.Filled.ContentCut, contentDescription = "Cut")
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(if (vm.path.isEmpty()) "File Manager" else (vm.currentDir?.name ?: "")) },
                    navigationIcon = {
                        if (vm.path.isNotEmpty()) {
                            IconButton(onClick = {
                                if (!vm.goUp()) vm.goHome()
                            }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Up")
                            }
                        } else {
                            IconButton(onClick = { /* already home */ }) {
                                Icon(Icons.Filled.Home, contentDescription = "Home")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.toggleBookmarkCurrent() }) {
                            Icon(
                                if (vm.bookmarkedCurrent) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = if (vm.bookmarkedCurrent) "Remove bookmark" else "Bookmark this folder",
                            )
                        }
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = {
                            vm.view = if (vm.view == BrowserView.LIST) BrowserView.GRID else BrowserView.LIST
                        }) {
                            Icon(
                                if (vm.view == BrowserView.LIST) Icons.Filled.GridView else Icons.Filled.ViewList,
                                contentDescription = "Toggle view",
                            )
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (vm.path.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                                    onClick = { menuOpen = false; showMkdir = true },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (vm.showHidden) "Hide hidden files" else "Show hidden files") },
                                onClick = {
                                    menuOpen = false
                                    vm.showHidden = !vm.showHidden
                                    vm.refresh()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Theme: ${themeLabel(themeMode)}") },
                                leadingIcon = { Icon(themeIcon(themeMode), null) },
                                onClick = {
                                    menuOpen = false
                                    onThemeChange(nextTheme(themeMode))
                                },
                            )
                            if (vm.canPaste) {
                                DropdownMenuItem(
                                    text = { Text("Paste " + vm.clipboard.size + " item(s)") },
                                    onClick = { menuOpen = false; vm.paste() },
                                )
                            }
                            if (vm.clipboard.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Clear clipboard") },
                                    onClick = { menuOpen = false; vm.clearClipboard() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Clear history") },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        try { historyStore.clearHistory() } catch (e: Exception) { }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                onClick = {
                                    menuOpen = false
                                    if (vm.path.isEmpty()) vm.loadRoots(context) else vm.refresh()
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val viewing = viewerTarget
            if (viewing != null) {
                val siblings = vm.items.map { it.file }
                when (kindOf(viewing)) {
                    ViewerKind.IMAGE -> ImageScreen(
                        file = viewing,
                        siblings = siblings,
                        onBack = { viewerTarget = null },
                    )
                    ViewerKind.VIDEO -> PlayerScreen(file = viewing, isAudio = false, onBack = { viewerTarget = null })
                    ViewerKind.AUDIO -> PlayerScreen(file = viewing, isAudio = true, onBack = { viewerTarget = null })
                    ViewerKind.TEXT -> TextScreen(file = viewing, onBack = { viewerTarget = null }, readOnly = viewerReadOnly)
                    ViewerKind.APK -> ApkScreen(file = viewing, vm = vm, onBack = { viewerTarget = null })
                    ViewerKind.PDF -> PdfScreen(file = viewing, onBack = { viewerTarget = null })
                    ViewerKind.ARCHIVE -> ArchiveScreen(
                        file = viewing,
                        destDir = vm.currentDir ?: File("/"),
                        onBack = { viewerTarget = null },
                        onExtracted = { vm.refresh() },
                    )
                    ViewerKind.OTHER -> {
                        LaunchedEffect(viewing) { viewerTarget = null }
                    }
                }
            } else if (showFtp) {
                FtpScreen(
                    ftpVm = ftpVm,
                    store = ftpStore,
                    downloadDir = vm.currentDir,
                    onDownloaded = { vm.refresh() },
                    onBack = { showFtp = false; ftpProfiles = ftpStore.all() },
                )
            } else if (showSftp) {
                SftpScreen(
                    sftpVm = sftpVm,
                    store = sftpStore,
                    downloadDir = vm.currentDir,
                    onDownloaded = { vm.refresh() },
                    onBack = { showSftp = false; sftpProfiles = sftpStore.all() },
                )
            } else if (showSmb) {
                SmbScreen(
                    smbVm = smbVm,
                    store = smbStore,
                    downloadDir = vm.currentDir,
                    onDownloaded = { vm.refresh() },
                    onBack = { showSmb = false; smbProfiles = smbStore.all() },
                )
            } else if (showWebdav) {
                WebdavScreen(
                    webdavVm = webdavVm,
                    store = webdavStore,
                    downloadDir = vm.currentDir,
                    onDownloaded = { vm.refresh() },
                    onBack = { showWebdav = false; webdavProfiles = webdavStore.all() },
                )
            } else if (showDrive) {
                DriveScreen(
                    driveVm = driveVm,
                    store = driveStore,
                    downloadDir = vm.currentDir,
                    onDownloaded = { vm.refresh() },
                    onBack = { showDrive = false; driveEmail = driveStore.accountEmail() },
                )
            } else if (showDropbox) {
                DropboxScreen(
                    vm = dropboxVm,
                    store = dropboxStore,
                    downloadDir = vm.currentDir,
                    onDownloaded = { vm.refresh() },
                    onBack = { showDropbox = false; dropboxEmail = dropboxStore.account() },
                )
            } else if (showOneDrive) {
                OneDriveScreen(
                    vm = oneDriveVm,
                    store = oneDriveStore,
                    downloadDir = vm.currentDir,
                    onDownloaded = { vm.refresh() },
                    onBack = { showOneDrive = false; oneDriveEmail = oneDriveStore.account() },
                )
            } else if (cloudProvider != null) {
                GenericCloudScreen(
                    vm = cloudVm,
                    provider = cloudProvider!!,
                    downloadDir = vm.currentDir,
                    onDownloaded = { vm.refresh() },
                    onBack = { cloudProvider = null },
                )
            } else if (showServer) {
                ServerScreen(
                    localDir = vm.currentDir,
                    onBack = { showServer = false },
                )
            } else if (showShizuku) {
                ShizukuScreen(
                    hub = shizukuHub,
                    enabled = shizukuEnabled,
                    managerInstalled = ShizukuHub.isManagerInstalled(context),
                    onEnabledChange = { on ->
                        shizukuEnabled = on
                        context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("shizuku_enabled", on).apply()
                        if (on) shizukuHub.requestPermission() else shizukuHub.refresh()
                    },
                    onRequest = { shizukuHub.requestPermission() },
                    onRefresh = { shizukuHub.refresh() },
                    onBack = { showShizuku = false },
                )
            } else if (showSaf) {
                SafScreen(
                    safVm = safVm,
                    onBack = { showSaf = false; safVm.goHome() },
                    onOpenFile = { item, cacheFile ->
                        when (kindOf(cacheFile)) {
                            ViewerKind.IMAGE, ViewerKind.VIDEO, ViewerKind.AUDIO, ViewerKind.APK, ViewerKind.PDF ->
                                viewerTarget = cacheFile
                            ViewerKind.TEXT -> {
                                viewerReadOnly = !item.doc.canWrite()
                                viewerTarget = cacheFile
                            }
                            ViewerKind.ARCHIVE -> {
                                viewerReadOnly = true
                                viewerTarget = cacheFile
                            }
                            ViewerKind.OTHER -> {
                                if (!openWith(context, cacheFile)) {
                                    scope.launch { snack.showSnackbar("No app can open this file") }
                                }
                            }
                        }
                    },
                )
            } else if (showRecycle) {
                RecycleScreen(
                    recycleVm = recycleVm,
                    onReload = { recycleVm.load(recycleStore) },
                )
            } else {
            if (searchOpen && vm.path.isNotEmpty()) {
                OutlinedTextField(
                    value = vm.query,
                    onValueChange = vm::setQuery,
                    label = { Text(if (vm.searchScope == SearchScope.PHONE) "Search all files on phone" else "Search in folder") },
                    trailingIcon = {
                        if (vm.query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = vm.searchScope == SearchScope.FOLDER,
                        onClick = { vm.searchScope = SearchScope.FOLDER; vm.refresh() },
                        label = { Text("This folder") },
                    )
                    FilterChip(
                        selected = vm.searchScope == SearchScope.PHONE,
                        onClick = { vm.searchScope = SearchScope.PHONE; vm.refresh() },
                        label = { Text("All files") },
                    )
                    if (vm.searchCapped) {
                        Text(
                            "Top 500",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
            if (vm.path.isEmpty()) {
                HomeContent(
                    roots = vm.roots,
                    hasPermission = hasStoragePermission(context),
                    onGrant = { requestStorageAccess() },
                    onOpenRoot = { vm.openRoot(it) },
                    recycleCount = recycleVm.items.size,
                    onOpenRecycle = { recycleVm.load(recycleStore); showRecycle = true },
                    bookmarks = bookmarkSet,
                    history = historyList,
                    onOpenPath = { vm.openPath(it) },
                    onRemoveBookmark = { abs ->
                        scope.launch {
                            try { historyStore.removeBookmark(abs) } catch (e: Exception) { }
                        }
                    },
                    safRoots = safRoots,
                    onAddStorage = { treeLauncher.launch(null) },
                    onOpenSaf = { root ->
                        safVm.openRoot(context, root)
                        showSaf = true
                    },
                    onRegrantSaf = { root ->
                        scope.launch {
                            treeLauncher.launch(root.uri)
                        }
                    },
                    onForgetSaf = { root ->
                        safStore.forget(root.uri)
                        safRoots = safStore.roots()
                    },
                    ftpProfiles = ftpProfiles,
                    onOpenFtp = {
                        ftpVm.loadProfiles(ftpStore)
                        showFtp = true
                    },
                    onQuickFtp = { profile ->
                        showFtp = true
                        ftpVm.connect(profile) {}
                    },
                    sftpProfiles = sftpProfiles,
                    onOpenSftp = {
                        sftpVm.loadProfiles(sftpStore)
                        showSftp = true
                    },
                    onQuickSftp = { profile ->
                        sftpVm.attach(context)
                        showSftp = true
                        sftpVm.connect(profile) {}
                    },
                    smbProfiles = smbProfiles,
                    onOpenSmb = {
                        smbVm.loadProfiles(smbStore)
                        showSmb = true
                    },
                    onQuickSmb = { profile ->
                        showSmb = true
                        smbVm.connect(profile) {}
                    },
                    webdavProfiles = webdavProfiles,
                    onOpenWebdav = {
                        webdavVm.loadProfiles(webdavStore)
                        showWebdav = true
                    },
                    onQuickWebdav = { profile ->
                        showWebdav = true
                        webdavVm.connect(profile) {}
                    },
                    driveEmail = driveEmail,
                    onOpenDrive = { showDrive = true },
                    dropboxEmail = dropboxEmail,
                    onOpenDropbox = { showDropbox = true },
                    oneDriveEmail = oneDriveEmail,
                    onOpenOneDrive = { showOneDrive = true },
                    onOpenCloud = { cloudProvider = it },
                    shizukuSummary = when (shizukuHub.status) {
                        ShizukuStatus.BOUND -> "Active"
                        ShizukuStatus.GRANTED -> "Granted"
                        ShizukuStatus.DENIED -> "Permission needed"
                        ShizukuStatus.NOT_INSTALLED -> "Not available"
                    },
                    onOpenShizuku = { showShizuku = true },
                    serverRunning = com.alizz.filemanager.server.ServerStatus.running,
                    serverUrl = com.alizz.filemanager.server.ServerStatus.url,
                    onOpenServer = { showServer = true },
                )
            } else {
                PathBar(path = vm.path.map { it.name.ifEmpty { "/" } }, onJump = { vm.goTo(it) })
                SortRow(sort = vm.sort, onSort = { vm.sort = it; vm.refresh() })
                if (vm.items.isEmpty()) {
                    Text(
                        "Empty folder",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(24.dp),
                    )
                } else if (vm.view == BrowserView.LIST) {
                    LazyColumn {
                        items(vm.items, key = { it.file.absolutePath }) { item ->
                            FileRow(
                                item = item,
                                checked = item.file.absolutePath in vm.selected,
                                selecting = vm.selectionActive,
                                onOpen = { openBrowserItem(item) },
                                onLongPress = { vm.toggleSelect(item.file.absolutePath) },
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(vm.items, key = { it.file.absolutePath }) { item ->
                            FileGridCell(
                                item = item,
                                checked = item.file.absolutePath in vm.selected,
                                selecting = vm.selectionActive,
                                onOpen = { openBrowserItem(item) },
                                onLongPress = { vm.toggleSelect(item.file.absolutePath) },
                            )
                        }
                    }
                }
            }
            }
        }
    }

    vm.operation?.let { op ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text(op.kind.name.lowercase().replaceFirstChar { it.uppercase() }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(op.current.ifEmpty { "Working…" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (op.total > 0) op.completed.toString() + " / " + op.total else "Working…")
                }
            },
            confirmButton = { TextButton(onClick = { vm.cancelOperation() }) { Text("Cancel") } },
        )
    }

    if (showMkdir) {
        NameDialog(title = "New folder", initial = "", confirm = "Create", onDismiss = { showMkdir = false }) { name ->
            if (vm.createFolder(name)) showMkdir = false
        }
    }
    renameTarget?.let { target ->
        NameDialog(title = "Rename", initial = target.name, confirm = "Rename", onDismiss = { renameTarget = null }) { name ->
            if (vm.renameOne(target, name)) renameTarget = null
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${vm.selected.size} item(s)?") },
            text = { Text("Move to the recycle bin, or delete forever? Deleted-forever items cannot be restored.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteSelected(recycleStore)
                    recycleVm.load(recycleStore)
                }) { Text("Recycle") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        vm.deleteSelectedPermanently()
                    }) { Text("Forever", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    if (showPermPopup && !hasStoragePermission(context)) {
        AlertDialog(
            onDismissRequest = { showPermPopup = false },
            title = { Text("Storage access") },
            text = { Text("File Manager needs storage access to show your files and storages. Grant it now to continue.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermPopup = false
                    requestStorageAccess()
                }) { Text("Grant access") }
            },
            dismissButton = { TextButton(onClick = { showPermPopup = false }) { Text("Later") } },
        )
    }
    infoTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { infoTarget = null; vm.clearChecksums() },
            title = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Path: ${f.absolutePath}\n" +
                            "Type: ${if (f.isDirectory) "Folder" else "File"}\n" +
                            (if (f.isDirectory) "Items: ${(f.listFiles()?.size ?: 0)}\n"
                            else "Size: ${BrowserViewModel.formatSize(f.length())}\n") +
                            "Modified: ${BrowserViewModel.formatDate(f.lastModified())}",
                    )
                    if (!f.isDirectory) {
                        val checksum = vm.checksumText
                        when {
                            vm.checksumBusy -> Text("Computing checksums…", color = MaterialTheme.colorScheme.primary)
                            checksum != null -> Text(checksum, style = MaterialTheme.typography.bodySmall)
                            else -> TextButton(onClick = { vm.computeChecksums(f) }) { Text("Compute checksums") }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (!f.isDirectory) {
                        TextButton(onClick = {
                            if (!openWith(context, f)) {
                                scope.launch { snack.showSnackbar("No app can open this file") }
                            }
                        }) { Text("Open with") }
                    }
                    TextButton(onClick = { infoTarget = null; vm.clearChecksums() }) { Text("OK") }
                }
            },
        )
    }
    if (showCompress) {
        val fallback = vm.currentDir?.name?.ifEmpty { "Archive" } ?: "Archive"
        NameDialog(
            title = "Compress ${vm.selected.size} item(s)",
            initial = "$fallback.zip",
            confirm = "Compress",
            onDismiss = { showCompress = false },
        ) { name ->
            if (vm.compressSelected(name)) showCompress = false
        }
    }
    if (showBatchRename) {
        BatchRenameDialog(
            count = vm.selected.size,
            onDismiss = { showBatchRename = false },
            onPreview = { pattern, start -> vm.previewBatchRename(pattern, start) },
            onApply = { preview ->
                showBatchRename = false
                vm.applyBatchRename(preview)
            },
        )
    }
}

@Composable
private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
private fun themeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.LIGHT -> Icons.Filled.LightMode
    ThemeMode.DARK -> Icons.Filled.DarkMode
    ThemeMode.SYSTEM -> Icons.Filled.SettingsBrightness
}

private fun nextTheme(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> ThemeMode.LIGHT
    ThemeMode.LIGHT -> ThemeMode.DARK
    ThemeMode.DARK -> ThemeMode.SYSTEM
}

@Composable
private fun HomeContent(
    roots: List<File>,
    hasPermission: Boolean,
    onGrant: () -> Unit,
    onOpenRoot: (File) -> Unit,
    recycleCount: Int,
    onOpenRecycle: () -> Unit,
    bookmarks: Set<String>,
    history: List<HistoryEntry>,
    onOpenPath: (File) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    safRoots: List<SafRoot>,
    onAddStorage: () -> Unit,
    onOpenSaf: (SafRoot) -> Unit,
    onRegrantSaf: (SafRoot) -> Unit,
    onForgetSaf: (SafRoot) -> Unit,
    ftpProfiles: List<FtpProfile>,
    onOpenFtp: () -> Unit,
    onQuickFtp: (FtpProfile) -> Unit,
    sftpProfiles: List<SftpProfile>,
    onOpenSftp: () -> Unit,
    onQuickSftp: (SftpProfile) -> Unit,
    smbProfiles: List<SmbProfile>,
    onOpenSmb: () -> Unit,
    onQuickSmb: (SmbProfile) -> Unit,
    webdavProfiles: List<WebdavProfile>,
    onOpenWebdav: () -> Unit,
    onQuickWebdav: (WebdavProfile) -> Unit,
    driveEmail: String?,
    onOpenDrive: () -> Unit,
    dropboxEmail: String?,
    onOpenDropbox: () -> Unit,
    oneDriveEmail: String?,
    onOpenOneDrive: () -> Unit,
    onOpenCloud: (CloudProvider) -> Unit,
    shizukuSummary: String,
    onOpenShizuku: () -> Unit,
    serverRunning: Boolean,
    serverUrl: String,
    onOpenServer: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        if (!hasPermission) {
            Card(onClick = onGrant) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Grant storage access to browse files")
                }
            }
        }
        if (roots.isEmpty()) {
            Text(
                if (hasPermission) "No readable storage found." else "Storage list needs permission.",
                color = MaterialTheme.colorScheme.outline,
            )
        }
        for (root in roots) {
            val removable = Environment.isExternalStorageRemovable(root)
            Card(onClick = { onOpenRoot(root) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (removable) Icons.Filled.SdStorage else Icons.Filled.Storage, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(root.name.ifEmpty { "Storage" }, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${BrowserViewModel.formatSize(root.usableSpace)} free of ${BrowserViewModel.formatSize(root.totalSpace)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (bookmarks.isNotEmpty()) {
            Text("Bookmarks", style = MaterialTheme.typography.titleMedium)
            val liveBookmarks = bookmarks.map(::File).filter { it.exists() }.take(10)
            for (bm in liveBookmarks) {
                Card(onClick = { onOpenPath(bm) }) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(bm.name.ifEmpty { "/" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(bm.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemoveBookmark(bm.absolutePath) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove bookmark")
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        val liveHistory = history.map { File(it.path) }.filter { it.exists() && it.isDirectory }.take(10)
        if (liveHistory.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleMedium)
            for (h in liveHistory) {
                Card(onClick = { onOpenPath(h) }) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.name.ifEmpty { "/" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Text("External storages", style = MaterialTheme.typography.titleMedium)
        for (root in safRoots) {
            Card(onClick = { if (!root.stale) onOpenSaf(root) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Usb, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(root.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (root.stale) "Access expired — tap Grant" else "Document storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (root.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (root.stale) {
                        TextButton(onClick = { onRegrantSaf(root) }) { Text("Grant") }
                    }
                    IconButton(onClick = { onForgetSaf(root) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Forget storage")
                    }
                }
            }
        }
        OutlinedButton(onClick = onAddStorage, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CreateNewFolder, null)
            Spacer(Modifier.width(8.dp))
            Text("Add SD card / USB storage")
        }
        Spacer(Modifier.height(4.dp))
        Text("Network", style = MaterialTheme.typography.titleMedium)
        for (profile in ftpProfiles.take(5)) {
            Card(onClick = { onQuickFtp(profile) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayLabel(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (profile.security == FtpSecurity.FTPS_EXPLICIT) "FTPS" else "FTP",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
        }
        OutlinedButton(onClick = onOpenFtp, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Cloud, null)
            Spacer(Modifier.width(8.dp))
            Text(if (ftpProfiles.isEmpty()) "Add FTP / FTPS server" else "Manage FTP servers")
        }
        for (profile in sftpProfiles.take(5)) {
            Card(onClick = { onQuickSftp(profile) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Key, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayLabel(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "SFTP · " + (if (profile.auth == SftpAuth.KEY) "key" else "password"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
        }
        OutlinedButton(onClick = onOpenSftp, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Key, null)
            Spacer(Modifier.width(8.dp))
            Text(if (sftpProfiles.isEmpty()) "Add SFTP server" else "Manage SFTP servers")
        }
        for (profile in smbProfiles.take(5)) {
            Card(onClick = { onQuickSmb(profile) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayLabel(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "SMB · " + (if (profile.anonymous) "guest" else profile.user.ifEmpty { "?" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
        }
        OutlinedButton(onClick = onOpenSmb, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Storage, null)
            Spacer(Modifier.width(8.dp))
            Text(if (smbProfiles.isEmpty()) "Add SMB share" else "Manage SMB shares")
        }
        for (profile in webdavProfiles.take(5)) {
            Card(onClick = { onQuickWebdav(profile) }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayLabel(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "WebDAV · " + profile.auth.name.lowercase(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
        }
        OutlinedButton(onClick = onOpenWebdav, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Cloud, null)
            Spacer(Modifier.width(8.dp))
            Text(if (webdavProfiles.isEmpty()) "Add WebDAV server" else "Manage WebDAV servers")
        }
        Card(onClick = onOpenDrive) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Google Drive", style = MaterialTheme.typography.titleSmall)
                    Text(
                        driveEmail ?: "Tap to sign in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null)
            }
        }
        Card(onClick = onOpenDropbox) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Dropbox", style = MaterialTheme.typography.titleSmall)
                    Text(
                        dropboxEmail ?: "Tap to sign in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null)
            }
        }
        Card(onClick = onOpenOneDrive) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("OneDrive", style = MaterialTheme.typography.titleSmall)
                    Text(
                        oneDriveEmail ?: "Tap to sign in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null)
            }
        }
        for (service in listOf("box", "pcloud", "yandex")) {
            val title = when (service) {
                "box" -> "Box"
                "pcloud" -> "pCloud"
                else -> "Yandex Disk"
            }
            Card(onClick = {
                onOpenCloud(
                    when (service) {
                        "box" -> BoxProvider(context)
                        "pcloud" -> PCloudProvider(context)
                        else -> YandexProvider(context)
                    },
                )
            }) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Tap to sign in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
        }
        Card(onClick = onOpenServer) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("File server", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (serverRunning) serverUrl.ifEmpty { "Running" } else "Share a folder over Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (serverRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null)
            }
        }
        Card(onClick = onOpenShizuku) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Shizuku", style = MaterialTheme.typography.titleSmall)
                    Text(
                        shizukuSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Tools", style = MaterialTheme.typography.titleMedium)
        Card(onClick = onOpenRecycle) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.RestoreFromTrash, null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Recycle bin", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (recycleCount == 0) "Empty" else "$recycleCount item(s), auto-deleted after 30 days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Icon(Icons.Filled.ChevronRight, null)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Simple Material 3 file manager — original design. Long-press to select.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleScreen(
    recycleVm: RecycleViewModel,
    onReload: () -> Unit,
) {
    LaunchedEffect(Unit) { onReload() }
    if (recycleVm.busy && recycleVm.items.isEmpty()) {
        Text(
            "Loading…",
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(24.dp),
        )
    } else if (recycleVm.items.isEmpty()) {
        Text(
            "Recycle bin is empty",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(24.dp),
        )
    } else {
        LazyColumn {
            items(recycleVm.items, key = { it.file.absolutePath }) { item ->
                val checked = item.file.absolutePath in recycleVm.selected
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .combinedClickable(
                            onClick = { recycleVm.toggleSelect(item.file.absolutePath) },
                            onLongClick = { recycleVm.toggleSelect(item.file.absolutePath) },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { recycleVm.toggleSelect(item.file.absolutePath) })
                    Icon(
                        if (item.file.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "From: ${(item.origin?.parent ?: "unknown")}\nTrashed: ${BrowserViewModel.formatDate(item.trashedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PathBar(path: List<String>, onJump: (Int) -> Unit) {
    LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        items(path.size) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(16.dp))
                TextButton(onClick = { onJump(i) }) {
                    Text(path[i].ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SortRow(sort: SortMode, onSort: (SortMode) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = sort == SortMode.NAME_AZ || sort == SortMode.NAME_ZA, onClick = {
                onSort(if (sort == SortMode.NAME_AZ) SortMode.NAME_ZA else SortMode.NAME_AZ)
            }, label = { Text("Name") })
        }
        item {
            FilterChip(selected = sort == SortMode.NEWEST || sort == SortMode.OLDEST, onClick = {
                onSort(if (sort == SortMode.NEWEST) SortMode.OLDEST else SortMode.NEWEST)
            }, label = { Text("Date") })
        }
        item {
            FilterChip(selected = sort == SortMode.LARGEST, onClick = { onSort(SortMode.LARGEST) }, label = { Text("Size") })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: FileItem,
    checked: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) Checkbox(checked = checked, onCheckedChange = { onOpen() })
        Icon(fileIcon(item), null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (item.isDir) "Folder · ${BrowserViewModel.formatDate(item.modified)}"
                else "${BrowserViewModel.formatSize(item.size)} · ${BrowserViewModel.formatDate(item.modified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    item: FileItem,
    checked: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (selecting) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Icon(if (checked) Icons.Filled.Check else Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                }
            }
            Icon(fileIcon(item), null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun mimeType(name: String): String = when {
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
    name.endsWith(".png", true) -> "image/png"
    name.endsWith(".gif", true) -> "image/gif"
    name.endsWith(".webp", true) -> "image/webp"
    name.endsWith(".mp4", true) -> "video/mp4"
    name.endsWith(".mkv", true) -> "video/x-matroska"
    name.endsWith(".webm", true) -> "video/webm"
    name.endsWith(".mp3", true) -> "audio/mpeg"
    name.endsWith(".wav", true) -> "audio/wav"
    name.endsWith(".ogg", true) -> "audio/ogg"
    name.endsWith(".pdf", true) -> "application/pdf"
    name.endsWith(".txt", true) -> "text/plain"
    else -> "*/*"
}

private fun fileIcon(item: FileItem) = when {
    item.isDir -> Icons.Filled.Folder
    item.name.endsWith(".png", true) || item.name.endsWith(".jpg", true) ||
        item.name.endsWith(".jpeg", true) || item.name.endsWith(".gif", true) ||
        item.name.endsWith(".webp", true) -> Icons.Filled.Image
    item.name.endsWith(".mp4", true) || item.name.endsWith(".mkv", true) ||
        item.name.endsWith(".webm", true) -> Icons.Filled.Movie
    item.name.endsWith(".mp3", true) || item.name.endsWith(".wav", true) ||
        item.name.endsWith(".ogg", true) || item.name.endsWith(".flac", true) -> Icons.Filled.Audiotrack
    item.name.endsWith(".zip", true) || item.name.endsWith(".rar", true) ||
        item.name.endsWith(".7z", true) || item.name.endsWith(".tar", true) -> Icons.Filled.Archive
    item.name.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
    else -> Icons.Filled.Description
}

@Composable
private fun BatchRenameDialog(
    count: Int,
    onDismiss: () -> Unit,
    onPreview: (String, Int) -> List<RenamePreview>,
    onApply: (List<RenamePreview>) -> Unit,
) {
    var pattern by remember { mutableStateOf("File {n}") }
    var startText by remember { mutableStateOf("1") }
    val start = startText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    var preview by remember { mutableStateOf(listOf<RenamePreview>()) }
    var previewBusy by remember { mutableStateOf(false) }
    LaunchedEffect(pattern, start, count) {
        previewBusy = true
        preview = withContext(Dispatchers.IO) { onPreview(pattern, start) }
        previewBusy = false
    }
    val collisions = preview.count { it.collision }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Batch rename ($count)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text("Pattern: {n} number, {name} old name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it.filter(Char::isDigit).take(6) },
                    label = { Text("Start number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (collisions > 0) {
                    Text(
                        "$collisions name(s) already exist and will be skipped",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (previewBusy) {
                    Text("Previewing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                preview.take(8).forEach { entry ->
                    Text(
                        "${entry.file.name} → ${entry.newName}" + (if (entry.collision) " (!)" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (entry.collision) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (preview.size > 8) Text("…and ${preview.size - 8} more", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !previewBusy && preview.isNotEmpty() && preview.any { !it.collision && it.newName != it.file.name },
                onClick = { onApply(preview) },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


private fun hasStoragePermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}



