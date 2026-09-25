package com.alizz.filemanager.providers.cloud

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One ViewModel drives Box, pCloud and Yandex through [CloudProvider]. */
class GenericCloudViewModel : ViewModel() {
    var provider: CloudProvider? = null
        private set
    var account: String? by mutableStateOf(null)
        private set
    var signedIn: Boolean by mutableStateOf(false)
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var waitingBrowser: Boolean by mutableStateOf(false)
        private set
    var hasPendingCode: Boolean by mutableStateOf(false)
        private set
    var crumbs: List<Pair<String, String>> by mutableStateOf(emptyList())
        private set
    var items: List<CItem> by mutableStateOf(emptyList())
        private set
    var selected: Set<String> by mutableStateOf(emptySet())
        private set
    var transferText: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var app: Context? = null
    val selectionActive get() = selected.isNotEmpty()
    val cwdKey: String get() = crumbs.lastOrNull()?.first ?: rootKey()

    private fun rootKey(): String = "ROOT"

    fun attach(context: Context, provider: CloudProvider) {
        if (app == null) app = context.applicationContext
        if (this.provider !== provider) {
            this.provider = provider
            crumbs = emptyList()
            items = emptyList()
            selected = emptySet()
            waitingBrowser = false
            hasPendingCode = false
        }
        reload()
    }

    fun reload() {
        val p = provider ?: return
        account = p.account()
        signedIn = p.signedIn()
        if (signedIn) refresh()
        refreshPending()
    }

    fun refreshPending() {
        val ctx = app ?: return
        val p = provider ?: return
        hasPendingCode = OAuthPending.peek(ctx, p.tag) != null
    }

    fun startBrowser() {
        val ctx = app ?: return
        val p = provider ?: return
        try {
            OAuthPkce.openBrowser(ctx, p.authorizeUrl())
            waitingBrowser = true
        } catch (e: Exception) {
            error = e.message ?: "Cannot start sign-in"
        }
        refreshPending()
    }

    fun continueAfterBrowser() {
        val ctx = app ?: return
        val p = provider ?: return
        val code = OAuthPending.take(ctx, p.tag)
        if (code == null) {
            error = "No approval found — complete the browser step first"
            return
        }
        waitingBrowser = false
        hasPendingCode = false
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { p.finishAuth(code) }
                reload()
            } catch (e: Exception) {
                error = e.message ?: "Sign-in failed"
            } finally {
                busy = false
            }
        }
    }

    fun cancelBrowser() {
        waitingBrowser = false
        hasPendingCode = false
    }

    fun signOut() {
        provider?.signOut()
        crumbs = emptyList()
        items = emptyList()
        selected = emptySet()
        waitingBrowser = false
        hasPendingCode = false
        reload()
    }

    fun openDir(item: CItem) {
        if (!item.dir) return
        crumbs = crumbs + (item.key to item.name)
        selected = emptySet()
        refresh()
    }

    fun goUp(): Boolean {
        if (crumbs.isEmpty()) return false
        crumbs = crumbs.dropLast(1)
        selected = emptySet()
        refresh()
        return true
    }

    fun goRoot() {
        crumbs = emptyList()
        selected = emptySet()
        refresh()
    }

    fun refresh() {
        val p = provider ?: return
        if (!p.signedIn()) return
        val dir = cwdKey
        busy = true
        viewModelScope.launch {
            try {
                items = withContext(Dispatchers.IO) { p.list(if (dir == "ROOT") rootDirKey(p) else dir) }
            } catch (e: Exception) {
                error = e.message ?: "List failed"
            } finally {
                busy = false
            }
        }
    }

    private fun rootDirKey(p: CloudProvider): String = when (p.tag) {
        "yandex" -> ""
        else -> "0"
    }

    fun toggleSelect(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    fun selectAll() {
        val ids = items.map { it.key }.toSet()
        selected = if (selected == ids && ids.isNotEmpty()) emptySet() else ids
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun mkdir(name: String, onDone: (Boolean) -> Unit) {
        val p = provider ?: return onDone(false)
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            error = "Invalid folder name"
            return onDone(false)
        }
        busy = true
        viewModelScope.launch {
            try {
                val dir = cwdKey.let { if (it == "ROOT") rootDirKey(p) else it }
                withContext(Dispatchers.IO) { p.mkdir(dir, clean) }
                refresh()
                onDone(true)
            } catch (e: Exception) {
                error = e.message ?: "Could not create folder"
                busy = false
                onDone(false)
            }
        }
    }

    fun renameOne(item: CItem, name: String, onDone: (Boolean) -> Unit) {
        val p = provider ?: return onDone(false)
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            error = "Invalid name"
            return onDone(false)
        }
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { p.rename(item, clean) }
                refresh()
                onDone(true)
            } catch (e: Exception) {
                error = e.message ?: "Rename failed"
                busy = false
                onDone(false)
            }
        }
    }

    fun deleteSelected(onDone: (Int) -> Unit) {
        val p = provider ?: return onDone(0)
        val targets = items.filter { it.key in selected }
        selected = emptySet()
        if (targets.isEmpty()) return onDone(0)
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                for (t in targets) {
                    try {
                        p.delete(t)
                        count++
                    } catch (e: Exception) {
                        // keep going
                    }
                }
            }
            if (count < targets.size) error = "Some items could not be deleted"
            refresh()
            onDone(count)
        }
    }

    fun downloadSelected(destDir: File, onDone: (Int) -> Unit) {
        val p = provider ?: return onDone(0)
        val targets = items.filter { it.key in selected && !it.dir }
        if (targets.isEmpty()) {
            error = "Select file(s) to download (folders not supported yet)"
            return onDone(0)
        }
        selected = emptySet()
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                targets.forEachIndexed { index, t ->
                    try {
                        transferText = "Downloading ${t.name} (${index + 1}/${targets.size})"
                        p.download(t, File(destDir, t.name))
                        count++
                    } catch (e: Exception) {
                        error = e.message ?: "Download failed"
                    }
                }
            }
            transferText = null
            busy = false
            onDone(count)
        }
    }

    fun uploadUris(context: Context, uris: List<Uri>, onDone: (Int) -> Unit) {
        val p = provider ?: return onDone(0)
        if (uris.isEmpty()) return onDone(0)
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                val tmpDir = File(context.cacheDir, "upload").apply { mkdirs() }
                val dir = cwdKey.let { if (it == "ROOT") rootDirKey(p) else it }
                for (uri in uris) {
                    try {
                        transferText = "Uploading… (${count + 1}/${uris.size})"
                        val name = queryName(context, uri) ?: "upload-${System.currentTimeMillis()}"
                        val tmp = File(tmpDir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                        } ?: continue
                        p.upload(tmp, dir)
                        tmp.delete()
                        count++
                    } catch (e: Exception) {
                        error = e.message ?: "Upload failed"
                    }
                }
            }
            transferText = null
            refresh()
            onDone(count)
        }
    }

    private fun queryName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun dismissError() {
        error = null
    }
}
