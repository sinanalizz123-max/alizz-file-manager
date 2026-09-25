package com.alizz.filemanager.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

const val TRASH_DIR_NAME = ".FileManagerTrash"
private const val PREFS = "recycle_store"
private const val KEY_DIRS = "trash_dirs"
private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

/**
 * Index of recycle-bin contents. Trash directories live inside the folder the
 * items were deleted from; this store remembers where each trashed item came
 * from (for restore) and when it was trashed (for 30-day auto-purge).
 * SharedPreferences only — no new dependencies.
 */
class RecycleStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun originKey(trashDir: String, name: String) = "org|$trashDir|$name"
    private fun timeKey(trashDir: String, name: String) = "ts|$trashDir|$name"

    fun recordTrashed(trashed: File, origin: File) {
        val dir = trashed.parent ?: return
        prefs.edit()
            .putStringSet(KEY_DIRS, prefs.getStringSet(KEY_DIRS, emptySet()).orEmpty() + dir)
            .putString(originKey(dir, trashed.name), origin.absolutePath)
            .putLong(timeKey(dir, trashed.name), System.currentTimeMillis())
            .apply()
    }

    fun forget(trashed: File) {
        val dir = trashed.parent ?: return
        prefs.edit()
            .remove(originKey(dir, trashed.name))
            .remove(timeKey(dir, trashed.name))
            .apply()
    }

    fun originOf(trashed: File): File? {
        val dir = trashed.parent ?: return null
        return prefs.getString(originKey(dir, trashed.name), null)?.let(::File)
    }

    fun trashedAt(trashed: File): Long {
        val dir = trashed.parent ?: return trashed.lastModified()
        return prefs.getLong(timeKey(dir, trashed.name), trashed.lastModified())
    }

    fun knownTrashDirs(): List<File> =
        prefs.getStringSet(KEY_DIRS, emptySet()).orEmpty().map(::File).filter { it.exists() }

    fun pruneDeadDirs() {
        val alive = prefs.getStringSet(KEY_DIRS, emptySet()).orEmpty().filter { File(it).exists() }.toSet()
        if (alive.size != prefs.getStringSet(KEY_DIRS, emptySet()).orEmpty().size) {
            prefs.edit().putStringSet(KEY_DIRS, alive).apply()
        }
    }
}

data class TrashedItem(
    val file: File,
    val origin: File?,
    val trashedAt: Long,
)

class RecycleViewModel {
    var items: List<TrashedItem> by mutableStateOf(emptyList())
        private set
    var selected: Set<String> by mutableStateOf(emptySet())
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var error: String? by mutableStateOf(null)
        private set
    var purgedCount: Int by mutableStateOf(0)
        private set

    val selectionActive get() = selected.isNotEmpty()

    fun load(store: RecycleStore) {
        busy = true
        error = null
        purgedCount = 0
        try {
            store.pruneDeadDirs()
            val now = System.currentTimeMillis()
            val found = mutableListOf<TrashedItem>()
            for (dir in store.knownTrashDirs()) {
                for (child in dir.listFiles() ?: emptyArray()) {
                    val item = TrashedItem(child, store.originOf(child), store.trashedAt(child))
                    if (now - item.trashedAt > RETENTION_MS) {
                        if (deleteRecursively(child)) {
                            store.forget(child)
                            purgedCount++
                        }
                    } else {
                        found += item
                    }
                }
            }
            items = found.sortedByDescending { it.trashedAt }
            selected = selected.filter { abs -> found.any { it.file.absolutePath == abs } }.toSet()
        } catch (e: Exception) {
            error = e.message ?: "Could not load recycle bin"
        } finally {
            busy = false
        }
    }

    fun toggleSelect(absPath: String) {
        selected = if (absPath in selected) selected - absPath else selected + absPath
    }

    fun selectAll() {
        val ids = items.map { it.file.absolutePath }.toSet()
        selected = if (selected == ids && ids.isNotEmpty()) emptySet() else ids
    }

    fun clearSelection() {
        selected = emptySet()
    }

    /** Restore to the original folder if it still exists, else [fallbackDir]. */
    fun restoreSelected(store: RecycleStore, fallbackDir: File): Int {
        var count = 0
        for (item in items.filter { it.file.absolutePath in selected }) {
            val dest = item.origin?.parentFile?.takeIf { it.exists() && it.canWrite() } ?: fallbackDir
            if (!dest.exists() && !dest.mkdirs()) continue
            val target = uniqueSibling(dest, item.file.name)
            if (item.file.renameTo(target) || (copyTree(item.file, target) && deleteRecursively(item.file))) {
                store.forget(item.file)
                count++
            }
        }
        selected = emptySet()
        load(store)
        if (count == 0) error = "Nothing could be restored"
        return count
    }

    fun deleteSelectedPermanently(store: RecycleStore): Int {
        var count = 0
        for (item in items.filter { it.file.absolutePath in selected }) {
            if (deleteRecursively(item.file)) {
                store.forget(item.file)
                count++
            }
        }
        selected = emptySet()
        load(store)
        if (count == 0) error = "Nothing could be deleted"
        return count
    }

    fun emptyAll(store: RecycleStore) {
        for (item in items) {
            if (deleteRecursively(item.file)) store.forget(item.file)
        }
        selected = emptySet()
        load(store)
    }

    fun dismissError() {
        error = null
    }

    private fun uniqueSibling(parent: File, name: String): File {
        var target = File(parent, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (target.exists()) {
            target = File(parent, "$base ($i)$ext")
            i++
        }
        return target
    }

    private fun copyTree(source: File, target: File): Boolean {
        return try {
            if (source.isDirectory) {
                if (!target.mkdirs() && !target.isDirectory) return false
                source.listFiles()?.all { copyTree(it, File(target, it.name)) } ?: false
            } else {
                source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output, 128 * 1024) } }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            for (child in file.listFiles() ?: return false) if (!deleteRecursively(child)) return false
        }
        return file.delete()
    }
}
