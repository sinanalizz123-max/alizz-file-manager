package com.alizz.filemanager.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_EDIT_BYTES = 1 * 1024 * 1024

@Composable
fun TextScreen(
    file: File,
    onBack: () -> Unit,
    readOnly: Boolean = false,
) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var text by remember(file) { mutableStateOf<String?>(null) }
    var tooLarge by remember(file) { mutableStateOf(false) }
    var editing by remember(file) { mutableStateOf(false) }
    var dirty by remember(file) { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        if (file.length() > MAX_EDIT_BYTES) {
            tooLarge = true
        } else {
            text = withContext(Dispatchers.IO) { readTextSmart(file) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            ViewerTopBar(
                title = file.name,
                onBack = {
                    if (dirty) confirmDiscard = true else onBack()
                },
                actions = {
                    if (!tooLarge && text != null && !readOnly && file.canWrite()) {
                        IconButton(onClick = { editing = !editing }) {
                            Icon(
                                if (editing) Icons.Filled.Visibility else Icons.Filled.Edit,
                                contentDescription = if (editing) "Preview" else "Edit",
                            )
                        }
                        if (editing && dirty) {
                            IconButton(onClick = {
                                val snapshot = text ?: return@IconButton
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { atomicSave(file, snapshot) }
                                    if (ok) {
                                        dirty = false
                                        editing = false
                                        scope.launch { snack.showSnackbar("Saved") }
                                    } else {
                                        scope.launch { snack.showSnackbar("Save failed") }
                                    }
                                }
                            }) {
                                Icon(Icons.Filled.Save, contentDescription = "Save")
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            when {
                tooLarge -> Text(
                    "File is larger than 1 MB and cannot be edited here.",
                    color = MaterialTheme.colorScheme.outline,
                )
                text == null -> Text("Loading…", color = MaterialTheme.colorScheme.outline)
                editing -> OutlinedTextField(
                    value = text ?: "",
                    onValueChange = { text = it; dirty = true },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Text(
                    text ?: "",
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                )
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

private fun readTextSmart(file: File): String {
    val bytes = file.readBytes()
    val candidates = listOf(Charsets.UTF_8, Charsets.ISO_8859_1)
    for (charset: Charset in candidates) {
        try {
            return bytes.toString(charset)
        } catch (e: Exception) {
            continue
        }
    }
    return bytes.toString(Charsets.ISO_8859_1)
}

/** Atomic save: write temp, keep .bak of previous, rename into place. */
private fun atomicSave(file: File, content: String): Boolean {
    return try {
        val parent = file.parentFile ?: return false
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(content)
        val bak = File(parent, file.name + ".bak")
        if (file.exists()) {
            bak.delete()
            file.renameTo(bak)
        }
        val ok = tmp.renameTo(file)
        if (!ok && bak.exists()) bak.renameTo(file)
        ok
    } catch (e: Exception) {
        false
    }
}
