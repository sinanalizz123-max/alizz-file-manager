package com.alizz.filemanager.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "saf_store"
private const val KEY_URIS = "uris"

data class SafRoot(val uri: Uri, val label: String, val stale: Boolean)

/**
 * Persisted Storage Access Framework tree grants (SD card, USB, app-specific
 * dirs on Android 11+). No new dependencies beyond documentfile.
 */
class SafStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun grant(uri: Uri, label: String): Boolean {
        return try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            prefs.edit()
                .putStringSet(KEY_URIS, prefs.getStringSet(KEY_URIS, emptySet()).orEmpty() + uri.toString())
                .putString("label|$uri", label.ifBlank { "External storage" })
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun forget(uri: Uri) {
        try {
            app.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            // Already revoked — just drop the record.
        }
        prefs.edit().remove("label|$uri").apply()
        prefs.edit().putStringSet(
            KEY_URIS,
            prefs.getStringSet(KEY_URIS, emptySet()).orEmpty() - uri.toString(),
        ).apply()
    }

    fun roots(): List<SafRoot> {
        val held = app.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission }
            .map { it.uri.toString() }.toSet()
        return prefs.getStringSet(KEY_URIS, emptySet()).orEmpty().map { raw ->
            SafRoot(
                uri = Uri.parse(raw),
                label = prefs.getString("label|$raw", null) ?: "External storage",
                stale = raw !in held,
            )
        }
    }

    fun regrant(uri: Uri): Boolean {
        val label = prefs.getString("label|$uri", null) ?: "External storage"
        return grant(uri, label)
    }
}

data class SafItem(
    val doc: DocumentFile,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
)

class SafViewModel : ViewModel() {
    var rootUri: Uri? = null
        private set
    var rootLabel: String = ""
        private set
    var path: List<SafItem> = emptyList()
        private set
    var items: List<SafItem> = emptyList()
        private set
    var selected: Set<String> = emptySet()
        private set
    var busy: Boolean = false
        private set
    var error: String? = null
        private set

    private lateinit var app: Context
    val currentDir: DocumentFile?
        get() = path.lastOrNull()?.doc ?: rootUri?.let { DocumentFile.fromTreeUri(app, it) }
    val selectionActive get() = selected.isNotEmpty()

    fun attach(context: Context) {
        app = context.applicationContext
    }

    fun openRoot(context: Context, root: SafRoot) {
        attach(context)
        rootUri = root.uri
        rootLabel = root.label
        path = emptyList()
        selected = emptySet()
        refresh()
    }

    fun openDir(item: SafItem) {
        if (!item.isDir) return
        path = path + item
        selected = emptySet()
        refresh()
    }

    fun goUp(): Boolean {
        if (path.isEmpty()) return false
        path = path.dropLast(1)
        selected = emptySet()
        refresh()
        return true
    }

    fun goTo(index: Int) {
        if (index < 0) {
            path = emptyList()
        } else {
            if (index >= path.size) return
            path = path.take(index + 1)
        }
        selected = emptySet()
        refresh()
    }

    fun goHome() {
        rootUri = null
        path = emptyList()
        selected = emptySet()
        items = emptyList()
    }

    fun refresh() {
        val dir = currentDir ?: return
        busy = true
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    dir.listFiles().map {
                        SafItem(it, it.name ?: "?", it.isDirectory, it.length().coerceAtLeast(0), it.lastModified())
                    }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                }
                items = list
            } catch (e: Exception) {
                error = e.message ?: "Cannot list folder (grant may be stale)"
            } finally {
                busy = false
            }
        }
    }

    fun toggleSelect(uri: String) {
        selected = if (uri in selected) selected - uri else selected + uri
    }

    fun selectAll() {
        val ids = items.map { it.doc.uri.toString() }.toSet()
        selected = if (selected == ids && ids.isNotEmpty()) emptySet() else ids
    }

    fun clearSelection() {
        selected = emptySet()
    }

    fun createFolder(name: String, onDone: (Boolean) -> Unit) {
        val dir = currentDir ?: return onDone(false)
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            error = "Invalid folder name"
            return onDone(false)
        }
        busy = true
        viewModelScope.launch {
            try {
                val created = withContext(Dispatchers.IO) { dir.createDirectory(clean) != null }
                if (!created) error = "Could not create folder"
                refresh()
                onDone(created)
            } catch (e: Exception) {
                error = e.message ?: "Could not create folder"
                busy = false
                onDone(false)
            }
        }
    }

    fun renameOne(item: SafItem, name: String, onDone: (Boolean) -> Unit) {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            error = "Invalid name"
            return onDone(false)
        }
        busy = true
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { item.doc.renameTo(clean) }
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
        val docs = items.filter { it.doc.uri.toString() in selected }.map { it.doc }
        selected = emptySet()
        if (docs.isEmpty()) return onDone(0)
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                for (doc in docs) {
                    try {
                        if (doc.delete()) count++
                    } catch (e: Exception) {
                        // keep going
                    }
                }
            }
            if (count < docs.size) error = "Some items could not be deleted"
            refresh()
            onDone(count)
        }
    }

    /** Copy a SAF document into app cache so viewers / external apps can open it. */
    suspend fun cacheCopy(item: SafItem): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(app.cacheDir, "saf").apply { mkdirs() }
            val target = File(dir, item.name)
            app.contentResolver.openInputStream(item.doc.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: return@withContext null
            target
        } catch (e: Exception) {
            null
        }
    }

    fun dismissError() {
        error = null
    }
}
