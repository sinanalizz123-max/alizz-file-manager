package com.alizz.filemanager.providers.drive

import android.content.Context
import android.content.Intent
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

const val DRIVE_ROOT_ID = "root"

class DriveViewModel : ViewModel() {
    var email: String? by mutableStateOf(null)
        private set
    var signedIn: Boolean by mutableStateOf(false)
        private set
    var signingIn: Boolean by mutableStateOf(false)
        private set
    /** Consent screen the user must complete (launched by the UI, then retry). */
    var consentIntent: Intent? by mutableStateOf(null)
        private set
    var cwdId: String by mutableStateOf(DRIVE_ROOT_ID)
        private set
    var cwdName: String by mutableStateOf("My Drive")
        private set
    var crumbs: List<Pair<String, String>> by mutableStateOf(emptyList())
        private set
    var items: List<DriveItem> by mutableStateOf(emptyList())
        private set
    var selected: Set<String> by mutableStateOf(emptySet())
        private set
    var busy: Boolean by mutableStateOf(false)
        private set
    var transferText: String? by mutableStateOf(null)
        private set
    var error: String? by mutableStateOf(null)
        private set

    private var session: DriveSession? = null
    val selectionActive get() = selected.isNotEmpty()

    fun loadAccount(store: DriveAccountStore) {
        email = store.accountEmail()
        signedIn = email != null
        if (signedIn) connect()
    }

    fun signIn(email: String, store: DriveAccountStore) {
        store.saveAccount(email)
        this.email = email
        signedIn = true
        connect()
    }

    fun signOut(store: DriveAccountStore) {
        store.clear()
        email = null
        signedIn = false
        session = null
        cwdId = DRIVE_ROOT_ID
        cwdName = "My Drive"
        crumbs = emptyList()
        items = emptyList()
        selected = emptySet()
        consentIntent = null
    }

    fun connect() {
        val mail = email ?: return
        signingIn = true
        error = null
        consentIntent = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val s = DriveSession(appContext(), mail)
                    s.list(DRIVE_ROOT_ID)
                    session = s
                    true
                } catch (e: DriveConsentRequired) {
                    handleConsent(e)
                    false
                } catch (e: Exception) {
                    error = e.message ?: "Sign-in failed"
                    false
                }
            }
            signingIn = false
            if (ok) {
                cwdId = DRIVE_ROOT_ID
                cwdName = "My Drive"
                crumbs = emptyList()
                selected = emptySet()
                refresh()
            }
        }
    }

    fun retryAfterConsent() {
        consentIntent = null
        connect()
    }

    fun dismissConsent() {
        consentIntent = null
    }

    /** Consent with a resolution screen, or a plain error when none is attached. */
    private fun handleConsent(e: DriveConsentRequired) {
        if (e.intent != null) consentIntent = e.intent
        else error = "Sign-in needs approval — try again"
    }

    private var appCtx: Context? = null

    fun attach(context: Context) {
        if (appCtx == null) appCtx = context.applicationContext
    }

    private fun appContext(): Context = appCtx
        ?: throw IllegalStateException("DriveViewModel not attached")

    fun openDir(item: DriveItem) {
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
        cwdId = DRIVE_ROOT_ID
        cwdName = "My Drive"
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
                } catch (e: DriveConsentRequired) {
                    handleConsent(e)
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
            } catch (e: DriveConsentRequired) {
                handleConsent(e)
                busy = false
                onDone(false)
            } catch (e: Exception) {
                error = e.message ?: "Could not create folder"
                busy = false
                onDone(false)
            }
        }
    }

    fun renameOne(item: DriveItem, name: String, onDone: (Boolean) -> Unit) {
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
            } catch (e: DriveConsentRequired) {
                handleConsent(e)
                busy = false
                onDone(false)
            } catch (e: Exception) {
                error = e.message ?: "Rename failed"
                busy = false
                onDone(false)
            }
        }
    }

    fun trashSelected() {
        val s = session ?: return
        val targets = items.filter { it.id in selected }
        selected = emptySet()
        if (targets.isEmpty()) return
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                for (t in targets) {
                    try {
                        s.trash(t.id)
                        count++
                    } catch (e: DriveConsentRequired) {
                        handleConsent(e)
                    } catch (e: Exception) {
                        error = e.message ?: "Delete failed"
                    }
                }
            }
            if (count < targets.size && consentIntent == null) error = "Some items could not be moved to trash"
            refresh()
        }
    }

    fun downloadSelected(destDir: File, onDone: (Int) -> Unit) {
        val s = session ?: return onDone(0)
        val targets = items.filter { it.id in selected && !it.isDir }
        val docs = targets.filter { it.mime.startsWith("application/vnd.google-apps.") }
        val files = targets - docs.toSet()
        if (docs.isNotEmpty()) {
            error = "Google Docs/Sheets need export (unsupported yet): " + docs.first().name
        }
        if (files.isEmpty()) return onDone(0)
        selected = emptySet()
        busy = true
        viewModelScope.launch {
            var count = 0
            withContext(Dispatchers.IO) {
                files.forEachIndexed { index, t ->
                    try {
                        transferText = "Downloading ${t.name} (${index + 1}/${files.size})"
                        s.download(t.id, File(destDir, t.name)) {}
                        count++
                    } catch (e: DriveConsentRequired) {
                        handleConsent(e)
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
                    } catch (e: DriveConsentRequired) {
                        handleConsent(e)
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
