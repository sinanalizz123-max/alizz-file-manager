package com.alizz.filemanager.viewer

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.alizz.filemanager.ui.BrowserViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkScreen(
    file: File,
    vm: BrowserViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var label by remember(file) { mutableStateOf(file.name) }
    var pkg by remember(file) { mutableStateOf("") }
    var version by remember(file) { mutableStateOf("") }
    var iconBitmap by remember(file) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                @Suppress("DEPRECATION")
                val info: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
                } else {
                    pm.getPackageArchiveInfo(file.absolutePath, 0)
                }
                val app = info?.applicationInfo
                if (app != null) {
                    app.sourceDir = file.absolutePath
                    app.publicSourceDir = file.absolutePath
                    label = pm.getApplicationLabel(app)?.toString() ?: file.name
                    pkg = info.packageName
                    @Suppress("DEPRECATION")
                    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toString() else info.versionCode.toString()
                    version = (info.versionName ?: "?") + " ($code)"
                    iconBitmap = try {
                        pm.getApplicationIcon(app).toBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                label = file.name
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { onBack(); vm.clearChecksums() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (iconBitmap != null) {
                    Image(bitmap = iconBitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp))
                } else {
                    Icon(Icons.Filled.Android, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (pkg.isNotEmpty()) Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    if (version.isNotEmpty()) Text("Version $version", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "File: ${file.name}\nSize: ${BrowserViewModel.formatSize(file.length())}\nModified: ${BrowserViewModel.formatDate(file.lastModified())}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)),
                        )
                        scope.launch { snack.showSnackbar("Allow installs, then tap Install again") }
                    } else {
                        try {
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            scope.launch { snack.showSnackbar("Cannot start installer") }
                        }
                    }
                }) {
                    Icon(Icons.Filled.InstallMobile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Install")
                }
                OutlinedButton(onClick = {
                    if (!openWith(context, file)) scope.launch { snack.showSnackbar("No app can open this") }
                }) {
                    Icon(Icons.Filled.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open with")
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Checksums", style = MaterialTheme.typography.titleSmall)
                    val checksum = vm.checksumText
                    when {
                        vm.checksumBusy -> Text("Computing…", color = MaterialTheme.colorScheme.primary)
                        checksum != null -> Text(checksum, style = MaterialTheme.typography.bodySmall)
                        else -> OutlinedButton(onClick = { vm.computeChecksums(file) }) { Text("Compute") }
                    }
                }
            }
        }
    }
}
