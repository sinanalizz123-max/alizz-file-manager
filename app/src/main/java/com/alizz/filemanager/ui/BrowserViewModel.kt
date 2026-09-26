package com.alizz.filemanager.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ensureActive
import com.alizz.filemanager.archive.createZip
import com.alizz.filemanager.data.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class FileItem(val file: File, val name: String = file.name, val isDir: Boolean = file.isDirectory, val size: Long = if (file.isDirectory) 0 else file.length(), val modified: Long = file.lastModified())
enum class BrowserView { LIST, GRID }
enum class SearchScope { FOLDER, PHONE }
enum class SortMode { NAME_AZ, NAME_ZA, NEWEST, OLDEST, LARGEST, SMALLEST }
enum class ClipboardMode { COPY, MOVE }
enum class OperationKind { COPY, MOVE, DELETE, RENAME, CREATE, COMPRESS }

data class OperationState(val kind: OperationKind, val current: String = "", val completed: Int = 0, val total: Int = 0)

class BrowserViewModel : ViewModel() {
    var roots by mutableStateOf<List<File>>(emptyList()); private set
    var path by mutableStateOf<List<File>>(emptyList()); private set
    var items by mutableStateOf<List<FileItem>>(emptyList()); private set
    var selected by mutableStateOf<Set<String>>(emptySet()); private set
    var view by mutableStateOf(BrowserView.GRID)
    var searchScope by mutableStateOf(SearchScope.FOLDER)
    var searchCapped by mutableStateOf(false)
        private set
    private var queryState by mutableStateOf("")
    val query: String get() = queryState
    var showHidden by mutableStateOf(false)
    var sort by mutableStateOf(SortMode.NAME_AZ)
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var operation by mutableStateOf<OperationState?>(null); private set
    var clipboard by mutableStateOf<List<String>>(emptyList()); private set
    var clipboardMode by mutableStateOf<ClipboardMode?>(null)
    var bookmarkedCurrent by mutableStateOf(false)
        private set
    /**
     * Optional privileged delete hook (Shizuku). Called on the IO thread;
     * return true if the file was removed with privileges. Null disables it.
     */
    var privilegedDelete: ((File) -> Boolean)? = null
    var checksumText by mutableStateOf<String?>(null)
        private set
    var checksumBusy by mutableStateOf(false)
        private set

    var history: HistoryStore? = null
    private var checksumJob: Job? = null

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var operationJob: Job? = null
    val currentDir get() = path.lastOrNull()
    val selectionActive get() = selected.isNotEmpty()
    val canPaste get() = clipboard.isNotEmpty() && currentDir != null

    fun loadRoots(context: Context) {
        viewModelScope.launch {
            roots = withContext(Dispatchers.IO) {
                val set = linkedSetOf<String>()
                if (Build.VERSION.SDK_INT >= 30) context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory }.forEach { set.add(it.absolutePath) }
                else Environment.getExternalStorageDirectory()?.let { set.add(it.absolutePath) }
                set.map(::File).filter { it.exists() && it.canRead() }
            }
        }
    }
    fun openRoot(f: File) { path = listOf(f); resetBrowser(); refresh(); afterNavigate(f) }
    fun openDir(f: File) { if (!f.isDirectory || !f.canRead()) { error = "Cannot open: " + f.name; return }; path = path + f; resetBrowser(); refresh(); afterNavigate(f) }
    fun goUp(): Boolean { if (path.size <= 1) return false; path = path.dropLast(1); resetBrowser(); refresh(); afterNavigate(path.last()); return true }
    fun goTo(i: Int) { if (i !in path.indices) return; path = path.take(i + 1); resetBrowser(); refresh(); afterNavigate(path.last()) }

    /** Jump to an arbitrary folder (bookmarks / history / last-visited). */
    fun openPath(target: File) {
        var dir: File? = if (target.isDirectory) target else target.parentFile
        if (dir == null || !dir.exists()) { error = "Location no longer exists"; return }
        if (!dir.canRead()) { error = "Cannot open: " + dir.name; return }
        val chain = ArrayDeque<File>()
        var cursor: File? = dir
        while (cursor != null) {
            chain.addFirst(cursor)
            val rootMatch = roots.firstOrNull { it.absolutePath == cursor.absolutePath }
            if (rootMatch != null) break
            cursor = cursor.parentFile
            if (chain.size > 64) break
        }
        path = chain.toList()
        resetBrowser()
        refresh()
        afterNavigate(dir)
    }

    private fun afterNavigate(dir: File) {
        val store = history ?: return
        viewModelScope.launch {
            try {
                store.recordVisit(dir)
                bookmarkedCurrent = store.isBookmarked(dir.absolutePath)
            } catch (e: Exception) {
                bookmarkedCurrent = false
            }
        }
    }

    fun toggleBookmarkCurrent() {
        val dir = currentDir ?: return
        val store = history ?: return
        viewModelScope.launch {
            try {
                bookmarkedCurrent = store.toggleBookmark(dir.absolutePath)
            } catch (e: Exception) {
                error = "Could not save bookmark"
            }
        }
    }
    fun goHome() { path = emptyList(); selected = emptySet(); queryState = ""; items = emptyList() }
    private fun resetBrowser() { selected = emptySet(); queryState = "" }

    fun setQuery(v: String) {
        queryState = v
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(120); refresh() }
    }

    fun refresh() {
        val d = currentDir ?: return
        listJob?.cancel()
        val requestedPath = d.absolutePath
        listJob = viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                if (!d.isDirectory) {
                    emptyList()
                } else if (queryState.isNotBlank() && searchScope == SearchScope.PHONE) {
                    globalSearch()
                } else {
                    d.listFiles()?.asSequence()?.filter { it.name != TRASH_DIR_NAME && (showHidden || !it.isHidden) }?.map(::FileItem)?.filter { queryState.isBlank() || it.name.contains(queryState, true) }?.toList() ?: emptyList()
                }
            }
            if (currentDir?.absolutePath == requestedPath) items = sortItems(result)
            busy = false
        }
    }

    private suspend fun globalSearch(): List<FileItem> {
        val q = queryState
        val base = path.firstOrNull() ?: return emptyList()
        searchCapped = false
        val out = ArrayList<FileItem>(64)
        suspend fun walk(dir: File) {
            if (out.size >= GLOBAL_SEARCH_MAX) return
            val kids = try {
                dir.listFiles()
            } catch (e: Exception) {
                null
            } ?: return
            for (k in kids) {
                ensureActive()
                if (k.name == TRASH_DIR_NAME) continue
                if (!showHidden && k.name.startsWith(".")) continue
                if (k.name.contains(q, ignoreCase = true)) {
                    out += FileItem(k)
                    if (out.size >= GLOBAL_SEARCH_MAX) {
                        searchCapped = true
                        return
                    }
                }
                if (k.isDirectory && k.canRead()) walk(k)
            }
        }
        walk(base)
        return out
    }

    private fun sortItems(s: List<FileItem>) = when (sort) {
        SortMode.NAME_AZ -> s.sortedWith(compareBy<FileItem> { !it.isDir }.thenBy { it.name.lowercase(Locale.getDefault()) })
        SortMode.NAME_ZA -> s.sortedWith(compareBy<FileItem> { !it.isDir }.thenByDescending { it.name.lowercase(Locale.getDefault()) })
        SortMode.NEWEST -> s.sortedWith(compareBy<FileItem> { !it.isDir }.thenByDescending { it.modified })
        SortMode.OLDEST -> s.sortedWith(compareBy<FileItem> { !it.isDir }.thenBy { it.modified })
        SortMode.LARGEST -> s.sortedWith(compareBy<FileItem> { !it.isDir }.thenByDescending { it.size })
        SortMode.SMALLEST -> s.sortedWith(compareBy<FileItem> { !it.isDir }.thenBy { it.size })
    }

    fun toggleSelect(p: String) { selected = if (p in selected) selected - p else selected + p }
    fun selectAll() { val ids = items.map { it.file.absolutePath }.toSet(); selected = if (selected == ids) emptySet() else ids }
    fun clearSelection() { selected = emptySet() }

    fun createFolder(name: String): Boolean {
        val d = currentDir ?: return false
        val n = safeName(name) ?: run { error = "Invalid folder name"; return false }
        val target = File(d, n)
        if (target.exists()) { error = "Already exists: " + n; return false }
        runOperation(OperationKind.CREATE, listOf(target)) { if (!target.mkdir()) throw IllegalStateException("Could not create folder") }
        return true
    }

    fun renameOne(file: File, name: String): Boolean {
        val n = safeName(name) ?: run { error = "Invalid name"; return false }
        val target = File(file.parentFile, n)
        if (target.exists()) { error = "Already exists: " + n; return false }
        runOperation(OperationKind.RENAME, listOf(file)) { if (!file.renameTo(target)) throw IllegalStateException("Rename failed") }
        return true
    }

    fun copySelected() { if (selected.isNotEmpty()) { clipboard = selected.toList(); clipboardMode = ClipboardMode.COPY; selected = emptySet() } }
    fun cutSelected() { if (selected.isNotEmpty()) { clipboard = selected.toList(); clipboardMode = ClipboardMode.MOVE; selected = emptySet() } }
    fun clearClipboard() { clipboard = emptyList(); clipboardMode = null }

    fun paste() {
        val dest = currentDir ?: return
        val sources = clipboard.map(::File).filter { it.exists() }
        if (sources.isEmpty()) { clearClipboard(); error = "Clipboard is empty"; return }
        val move = clipboardMode == ClipboardMode.MOVE
        runOperation(if (move) OperationKind.MOVE else OperationKind.COPY, sources) {
            sources.forEachIndexed { index, source ->
                operation = OperationState(if (move) OperationKind.MOVE else OperationKind.COPY, source.name, index, sources.size)
                copyOrMove(source, dest, move)
            }
        }
        if (move) clearClipboard()
    }

    fun deleteSelected(store: RecycleStore? = null) {
        val fs = selected.map(::File).filter { it.exists() }
        selected = emptySet()
        if (fs.isEmpty()) return
        runOperation(OperationKind.DELETE, fs) {
            val trash = File(currentDir ?: fs.first().parentFile, TRASH_DIR_NAME).apply { mkdirs() }
            fs.forEachIndexed { index, file ->
                operation = OperationState(OperationKind.DELETE, file.name, index, fs.size)
                val origin = file.absoluteFile
                val trashed = moveToTrash(file, trash)
                if (store != null && trashed != null) store.recordTrashed(trashed, origin)
            }
        }
    }

    fun compressSelected(zipName: String): Boolean {
        val dest = currentDir ?: return false
        var clean = zipName.trim()
        if (clean.isEmpty() || clean.contains('/')) {
            error = "Invalid archive name"
            return false
        }
        if (!clean.endsWith(".zip", ignoreCase = true)) clean += ".zip"
        val sources = selected.map(::File).filter { it.exists() }
        if (sources.isEmpty()) return false
        val target = File(dest, clean)
        if (target.exists()) {
            error = "Already exists: $clean"
            return false
        }
        selected = emptySet()
        runOperation(OperationKind.COMPRESS, sources) {
            createZip(target, sources) { name, done, total ->
                operation = OperationState(OperationKind.COMPRESS, name, done, total)
            }
        }
        return true
    }

    fun deleteSelectedPermanently() {
        val fs = selected.map(::File).filter { it.exists() }
        selected = emptySet()
        if (fs.isEmpty()) return
        runOperation(OperationKind.DELETE, fs) {
            fs.forEachIndexed { index, file ->
                operation = OperationState(OperationKind.DELETE, file.name, index, fs.size)
                if (!deleteTree(file)) throw IllegalStateException("Could not delete " + file.name)
            }
        }
    }

    fun cancelOperation() { operationJob?.cancel(); operation = null; busy = false; refresh() }

    private fun moveToTrash(file: File, trash: File): File? {
        var target = File(trash, file.name)
        var i = 1
        while (target.exists()) { target = File(trash, file.name + " (" + i + ")"); i++ }
        if (!file.renameTo(target)) {
            copyTree(file, target)
            if (!deleteTree(file)) throw IllegalStateException("Could not remove " + file.name)
        }
        return target.takeIf { it.exists() }
    }

    private fun copyOrMove(source: File, destination: File, move: Boolean) {
        if (source.absolutePath == destination.absolutePath || destination.absolutePath.startsWith(source.absolutePath + File.separator)) throw IllegalStateException("Cannot copy or move a folder into itself")
        val target = uniqueTarget(destination, source.name)
        if (move && source.renameTo(target)) return
        copyTree(source, target)
        if (move && !deleteTree(source)) throw IllegalStateException("Copied but could not remove source: " + source.name)
    }

    private fun uniqueTarget(parent: File, name: String): File {
        var target = File(parent, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (target.exists()) { target = File(parent, base + " (" + i + ")" + ext); i++ }
        return target
    }

    private fun copyTree(source: File, target: File) {
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) throw IllegalStateException("Could not create " + target.name)
            source.listFiles()?.forEach { copyTree(it, File(target, it.name)) }
        } else {
            source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output, 128 * 1024) } }
            target.setLastModified(source.lastModified())
        }
    }

    private fun deleteTree(file: File): Boolean {
        try {
            if (privilegedDelete?.invoke(file) == true) return true
        } catch (e: Exception) {
            // fall through to standard APIs
        }
        if (file.isDirectory) { val children = file.listFiles() ?: return false; for (child in children) if (!deleteTree(child)) return false }
        return file.delete()
    }

    private fun runOperation(kind: OperationKind, sources: List<File>, block: suspend () -> Unit) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            busy = true
            operation = OperationState(kind, total = sources.size)
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Operation failed" }
            finally { operation = null; busy = false; refresh() }
        }
    }

    private fun safeName(value: String): String? {
        val n = value.trim()
        return if (n.isEmpty() || n == "." || n == ".." || n.contains('/') || n.contains('\\')) null else n
    }

    fun dismissError() { error = null }

    // ---------- Checksums (MD5 + SHA-256, streamed, cancellable) ----------

    fun computeChecksums(file: File) {
        checksumJob?.cancel()
        checksumText = null
        if (file.isDirectory) {
            checksumText = "Checksums are available for files"
            return
        }
        checksumJob = viewModelScope.launch {
            checksumBusy = true
            try {
                checksumText = withContext(Dispatchers.IO) { hashBoth(file) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                checksumText = "Failed: " + (e.message ?: "read error")
            } finally {
                checksumBusy = false
            }
        }
    }

    fun clearChecksums() {
        checksumJob?.cancel()
        checksumText = null
        checksumBusy = false
    }

    private fun hashBoth(file: File): String {
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md5.update(buf, 0, n)
                sha.update(buf, 0, n)
            }
        }
        return "MD5: " + md5.digest().toHex() + "\nSHA-256: " + sha.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            chars[i * 2] = "0123456789abcdef"[v ushr 4]
            chars[i * 2 + 1] = "0123456789abcdef"[v and 0x0F]
        }
        return String(chars)
    }

    // ---------- Batch rename ----------

    data class RenamePreview(val file: File, val newName: String, val collision: Boolean)

    /** Pattern supports {n} counter and {name} original name without extension. */
    fun previewBatchRename(pattern: String, startAt: Int): List<RenamePreview> {
        val files = selected.map(::File).filter { it.exists() }.sortedBy { it.name.lowercase() }
        if (files.isEmpty() || pattern.isBlank()) return emptyList()
        val parents = files.mapNotNull { it.parentFile }.toSet()
        val taken = parents.associateWith { dir ->
            dir.list()?.toMutableSet() ?: mutableSetOf()
        }.toMutableMap()
        var counter = startAt
        return files.map { file ->
            val dot = file.name.lastIndexOf('.')
            val bare = if (!file.isDirectory && dot > 0) file.name.substring(0, dot) else file.name
            val ext = if (!file.isDirectory && dot > 0) file.name.substring(dot) else ""
            var candidate = pattern.replace("{n}", counter.toString()).replace("{name}", bare)
            if (candidate.isBlank()) candidate = bare
            candidate += ext
            counter++
            val siblings = file.parentFile?.let { taken[it] }
            val collision = (siblings != null && candidate != file.name && siblings.contains(candidate)) ||
                files.any { other -> other != file && other.parent == file.parent && other.name == candidate }
            siblings?.plusAssign(candidate)
            RenamePreview(file, candidate, collision)
        }
    }

    /** Applies a preview; skips collisions, reports how many were renamed. Returns renamed count. */
    fun applyBatchRename(preview: List<RenamePreview>): Int {
        var count = 0
        for (entry in preview) {
            if (entry.collision || entry.newName == entry.file.name) continue
            val parent = entry.file.parentFile ?: continue
            val dest = File(parent, entry.newName)
            if (!dest.exists() && entry.file.renameTo(dest)) count++
        }
        selected = emptySet()
        refresh()
        if (count < preview.size) error = "Renamed $count of ${preview.size} (collisions skipped)"
        return count
    }

    companion object {
        const val GLOBAL_SEARCH_MAX = 500
        fun formatSize(b: Long): String {
            if (b < 1024) return b.toString() + " B"
            var x = b / 1024.0; val u = arrayOf("KB", "MB", "GB", "TB"); var i = 0
            while (x >= 1024 && i < 3) { x /= 1024; i++ }
            return "%.2f %s".format(Locale.getDefault(), x, u[i])
        }
        fun formatDate(t: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t))
    }
}
