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

class OneDriveViewModel : ViewModel() {
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
    var cwdId: String by mutableStateOf("root")
        private set
    var cwdName: String by mutableStateOf("OneDrive")
        private set
    var crumbs: List<Pair<String, String>> by mutableStateOf(emptyList())
        private set
    var items: List<OneDriveItem> by mutableStateOf(emptyList())
        private set
    var selected: Set<String> by mutableStateOf(emptySet())
        private set
    var transferText: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var app: Context? = null
    private var session: OneDriveSession? = null
    val selectionActive get() = selected.isNotEmpty()

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun load(store: OneDriveAccountStore) {
        account = store.account()
        signedIn = account != null
        if (signedIn) {
            session = OneDriveSession(store)
            refresh()
        }
        refreshPending()
    }

    fun refreshPending() {
        val ctx = app ?: return
        hasPendingCode = OAuthPending.peek(ctx, "onedrive") != null
    }

    fun startBrowser(store: OneDriveAccountStore) {
        val ctx = app ?: return
        val id = store.clientId()
        if (id.isEmpty()) {
            error = "Enter your app client ID first"
            return
        }
        val verifier = OAuthPkce.verifier()
        OAuthPkce.openBrowser(ctx, OneDriveSession(store).authorizeUrl(id, verifier))
        waitingBrowser = true
        refreshPending()
    }

    fun continueAfterBrowser(store: OneDriveAccountStore) {
        val ctx = app ?: return
        val code = OAuthPending.take(ctx, "onedrive")
        if (code == null) {
            error = "No approval found — complete the browser step first"
            return
        }
        waitingBrowser = false
        hasPendingCode = false
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { OneDriveSession(store).finishAuth(store.clientId(), code) }
                load(store)
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

    fun signOut(store: OneDriveAccountStore) {
        store.clear()
        account = null
        signedIn = false
        session = null
        cwdId = "root"
        cwdName = "OneDrive"
        crumbs = emptyList()
        items = emptyList()
        selected = emptySet()
        waitingBrowser = false
        hasPendingCode = false
    }

    fun openDir(item: OneDriveItem) {
        if (!item.isDir) return
        crumbs = crumbs + (cwdId to cwdName)
        cwdId = item.id
        cwdName = item.name
        selected = emptySet()
        refresh()
    }

    fun goUp(): Boolean {
        if (crumbs.isEmpty()) return false
        val (id, name) = crumbs.last()
        crumbs = crumbs.dropLast(1)
        cwdId = id
        cwdName = name
        selected = emptySet()
        refresh()
        return true
    }

    fun goRoot() {
        crumbs = emptyList()
        cwdId = "root"
        cwdName = "OneDrive"
        selected = emptySet()
        refresh()
    }

    fun refresh() {
        val s = session ?: return
        val dir = cwdId
        busy = true
        viewModelScope.launch {
            try {
                items = withContext(Dispatchers.IO) { s.list(dir) }
            } catch (e: Exception) {
                error = e.message ?: "List failed"
            } finally {
                busy = false
            }
        }
    }

    fun toggleSelect(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    fun selectAll() {
        val ids = items.map { it.id }.toSet()
        selected = if (selected == ids && ids.isNotEmpty()) emptySet() else ids
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun mkdir(name: String, onDone: (Boolean) -> Unit) {
        val s = session ?: return onDone(false)
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            error = "Invalid folder name"
            return onDone(false)
        }
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { s.mkdir(cwdId, clean) }
                refresh()
                onDone(true)
            } catch (e: Exception) {
                error = e.message ?: "Could not create folder"
                busy = false
                onDone(false)
            }
        }
    }

    fun renameOne(item: OneDriveItem, name: String, onDone: (Boolean) -> Unit) {
        val s = session ?: return onDone(false)
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            error = "Invalid name"
            return onDone(false)
        }
        busy = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { s.rename(item.id, clean) }
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
        val s = session ?: return onDone(0)
        val targets = items.filter { it.id in selected }
        selected = emptySet()
        if (targets.isEmpty()) return onDone(0)
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                for (t in targets) {
                    try {
                        s.delete(t.id)
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
        val s = session ?: return onDone(0)
        val targets = items.filter { it.id in selected && !it.isDir }
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
                        s.download(t.id, t.name, File(destDir, t.name))
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
        val s = session ?: return onDone(0)
        if (uris.isEmpty()) return onDone(0)
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                val tmpDir = File(context.cacheDir, "upload").apply { mkdirs() }
                for (uri in uris) {
                    try {
                        transferText = "Uploading… (${count + 1}/${uris.size})"
                        val name = queryName(context, uri) ?: "upload-${System.currentTimeMillis()}"
                        val tmp = File(tmpDir, name)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                        } ?: continue
                        s.upload(tmp, cwdId)
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
