package com.alizz.filemanager.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TrackInfo(val title: String, val artist: String, val art: Bitmap?)

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
    var track by remember(file) { mutableStateOf<TrackInfo?>(null) }

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

    LaunchedEffect(file) {
        if (isAudio) {
            track = withContext(Dispatchers.IO) { readTrackInfo(file) }
        }
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
        if (isAudio) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(32.dp))
                Card(
                    shape = RoundedCornerShape(28.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    val art = track?.art
                    if (art != null) {
                        AsyncImage(
                            model = art,
                            contentDescription = "Album art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(260.dp),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Audiotrack,
                            contentDescription = "No album art",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(260.dp).padding(64.dp),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    track?.title ?: file.nameWithoutExtension,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    track?.artist ?: file.extension.uppercase(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                AndroidView(
                    factory = { ctx ->
                        PlayerControlView(ctx).apply {
                            this.player = player
                            showTimeoutMs = 0
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                modifier = Modifier.fillMaxSize().padding(padding),
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

private fun readTrackInfo(file: File): TrackInfo {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?: file.nameWithoutExtension
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            ?: file.extension.uppercase()
        val artBytes = retriever.embeddedPicture
        val art = try {
            artBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) {
            null
        }
        TrackInfo(title, artist, art)
    } catch (e: Exception) {
        TrackInfo(file.nameWithoutExtension, file.extension.uppercase(), null)
    } finally {
        try {
            retriever.release()
        } catch (e: Exception) {
        }
    }
}
