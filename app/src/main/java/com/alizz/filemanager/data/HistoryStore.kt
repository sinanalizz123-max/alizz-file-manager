package com.alizz.filemanager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val STORE_NAME = "history_store"
private const val MAX_HISTORY = 50

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

data class HistoryEntry(val path: String, val visitedAt: Long)

/**
 * Bookmarks, navigation history and last-visited location.
 * History entries are stored as "timestamp|absolutePath" strings, newest last.
 */
class HistoryStore(context: Context) {
    private val store = context.applicationContext.historyDataStore

    private val bookmarksKey = stringSetPreferencesKey("bookmarks")
    private val historyKey = stringSetPreferencesKey("history")
    private val lastVisitedKey = stringPreferencesKey("last_visited")

    val bookmarks: Flow<Set<String>> = store.data.map { it[bookmarksKey].orEmpty() }

    val history: Flow<List<HistoryEntry>> = store.data.map { prefs ->
        prefs[historyKey].orEmpty().mapNotNull { raw ->
            val sep = raw.indexOf('|')
            if (sep <= 0) null
            else {
                val ts = raw.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
                HistoryEntry(raw.substring(sep + 1), ts)
            }
        }.sortedByDescending { it.visitedAt }
    }

    val lastVisited: Flow<String?> = store.data.map { it[lastVisitedKey] }

    suspend fun isBookmarked(absPath: String): Boolean =
        store.data.map { it[bookmarksKey].orEmpty().contains(absPath) }.first()

    suspend fun toggleBookmark(absPath: String): Boolean {
        var added = false
        store.edit { prefs ->
            val current = prefs[bookmarksKey].orEmpty().toMutableSet()
            added = if (current.contains(absPath)) {
                current.remove(absPath)
                false
            } else {
                current.add(absPath)
                true
            }
            prefs[bookmarksKey] = current
        }
        return added
    }

    suspend fun removeBookmark(absPath: String) {
        store.edit { prefs ->
            prefs[bookmarksKey] = prefs[bookmarksKey].orEmpty() - absPath
        }
    }

    suspend fun recordVisit(dir: File) {
        val abs = dir.absolutePath
        store.edit { prefs ->
            val entries = prefs[historyKey].orEmpty()
                .mapNotNull { raw ->
                    val sep = raw.indexOf('|')
                    if (sep <= 0) null else raw
                }
                .filter { it.substringAfter('|') != abs }
                .toMutableList()
            entries += "${System.currentTimeMillis()}|$abs"
            while (entries.size > MAX_HISTORY) entries.removeAt(0)
            prefs[historyKey] = entries.toSet()
            prefs[lastVisitedKey] = abs
        }
    }

    suspend fun clearHistory() {
        store.edit { prefs ->
            prefs[historyKey] = emptySet()
        }
    }
}
