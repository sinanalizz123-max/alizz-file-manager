package com.alizz.filemanager.viewer

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

@OptIn(UnstableApi::class)
@ExperimentalMaterial3Api
@Composable
fun PlayerScreen(
    file: File,
    isAudio: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember(file) { mutableStateOf<String?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var speed by remember(file) { mutableFloatStateOf(1f) }

    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = e.message ?: "Playback failed"
                }
            })
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(file) {
        onDispose { player.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { speedMenu = true }) {
                        Icon(Icons.Filled.Speed, contentDescription = "Speed")
                    }
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        for (s in listOf(0.5f, 1f, 1.5f, 2f)) {
                            DropdownMenuItem(
                                text = { Text(if (s == speed) "● ${s}x" else "${s}x") },
                                onClick = {
                                    speed = s
                                    player.setPlaybackSpeed(s)
                                    speedMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isAudio) {
                Spacer(Modifier.height(64.dp))
                Icon(
                    Icons.Filled.Audiotrack,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(48.dp).fillMaxSize(0.5f).align(Alignment.CenterHorizontally),
                )
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            if (isAudio) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null; onBack() },
            title = { Text("Playback failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null; onBack() }) { Text("OK") } },
        )
    }
}
