package com.alizz.filemanager.providers.ftp

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

class FtpViewModel : ViewModel() {
    var profiles: List<FtpProfile> by mutableStateOf(emptyList())
        private set
    var profile: FtpProfile? by mutableStateOf(null)
        private set
    var connected: Boolean by mutableStateOf(false)
        private set
    var connecting: Boolean by mutableStateOf(false)
        private set
    var cwd: String by mutableStateOf("")
        private set
    var items: List<RemoteItem> by mutableStateOf(emptyList())
        private set
    var selected: Set<String> by mutableStateOf(emptySet())
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var transferText: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var session: FtpSession? = null
    val selectionActive get() = selected.isNotEmpty()

    fun loadProfiles(store: FtpProfileStore) {
        profiles = store.all()
    }

    fun connect(p: FtpProfile, onDone: (Boolean) -> Unit) {
        disconnect()
        connecting = true
        error = null
        viewModelScope.launch {
            val s = FtpSession(p)
            val ok = withContext(Dispatchers.IO) {
                try {
                    s.connect()
                    true
                } catch (e: Exception) {
                    error = e.message ?: "Connect failed"
                    s.disconnect()
                    false
                }
            }
            connecting = false
            if (ok) {
                session = s
                profile = p
                connected = true
                cwd = ""
                selected = emptySet()
                refresh()
            }
            onDone(ok)
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) { session?.disconnect() }
        session = null
        connected = false
        profile = null
        cwd = ""
        items = emptyList()
        selected = emptySet()
        transferText = null
    }

    fun openDir(item: RemoteItem) {
        if (!item.isDir) return
        cwd = item.path
        selected = emptySet()
        refresh()
    }

    fun goUp(): Boolean {
        if (cwd.isEmpty()) return false
        cwd = cwd.substringBeforeLast('/', "")
        selected = emptySet()
        refresh()
        return true
    }

    fun goRoot() {
        cwd = ""
        selected = emptySet()
        refresh()
    }

    fun refresh() {
        val s = session ?: return
        val dir = cwd
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

    fun toggleSelect(path: String) {
        selected = if (path in selected) selected - path else selected + path
    }

    fun selectAll() {
        val ids = items.map { it.path }.toSet()
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
                val ok = withContext(Dispatchers.IO) { s.mkdir(cwd, clean) }
                if (!ok) error = "Could not create folder"
                refresh()
                onDone(ok)
            } catch (e: Exception) {
                error = e.message ?: "Could not create folder"
                busy = false
                onDone(false)
            }
        }
    }

    fun renameOne(item: RemoteItem, name: String, onDone: (Boolean) -> Unit) {
        val s = session ?: return onDone(false)
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            error = "Invalid name"
            return onDone(false)
        }
        busy = true
        viewModelScope.launch {
            try {
                val dest = (if (cwd.isEmpty()) "" else cwd.trimEnd('/')) + "/" + clean
                val ok = withContext(Dispatchers.IO) { s.rename(item.path, dest) }
                if (!ok) error = "Rename failed"
                refresh()
                onDone(ok)
            } catch (e: Exception) {
                error = e.message ?: "Rename failed"
                busy = false
                onDone(false)
            }
        }
    }

    fun deleteSelected(onDone: (Int) -> Unit) {
        val s = session ?: return onDone(0)
        val targets = items.filter { it.path in selected }
        selected = emptySet()
        if (targets.isEmpty()) return onDone(0)
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                for (t in targets) {
                    try {
                        if (s.delete(t)) count++
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
        val targets = items.filter { it.path in selected && !it.isDir }
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
                        s.download(t.path, File(destDir, t.name))
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
                        s.upload(tmp, cwd)
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

    override fun onCleared() {
        session?.disconnect()
    }
}
