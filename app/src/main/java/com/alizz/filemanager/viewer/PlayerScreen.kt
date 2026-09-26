package com.alizz.filemanager.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class TrackInfo(val title: String, val artist: String, val art: Bitmap?)

@OptIn(UnstableApi::class)
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
    var isPlaying by remember(file) { mutableStateOf(false) }
    var position by remember(file) { mutableLongStateOf(0L) }
    var duration by remember(file) { mutableLongStateOf(0L) }
    var seeking by remember(file) { mutableStateOf(false) }
    var lockedLandscape by remember(file) { mutableStateOf(false) }

    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            playWhenReady = true
            prepare()
        }.also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    error = e.message ?: "Playback failed"
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        val d = exo.duration
                        if (d > 0) duration = d
                    }
                }
            })
        }
    }
    DisposableEffect(file) {
        onDispose {
            (context as? Activity)?.let {
                if (it.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            player.release()
        }
    }

    LaunchedEffect(file) {
        if (isAudio) {
            track = withContext(Dispatchers.IO) { readTrackInfo(file) }
        }
    }
    LaunchedEffect(file, isPlaying) {
        while (true) {
            if (!seeking) {
                position = player.currentPosition.coerceAtLeast(0)
                val d = player.duration
                if (d > 0) duration = d
            }
            delay(500)
        }
    }

    Scaffold(
        topBar = {
            ViewerTopBar(
                title = file.name,
                onBack = onBack,
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
                Spacer(Modifier.height(24.dp))
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
                Spacer(Modifier.height(20.dp))
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
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = {
                        seeking = true
                        position = it.toLong()
                    },
                    onValueChangeFinished = {
                        player.seekTo(position)
                        seeking = false
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text(formatTime(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { player.seekTo((position - 10_000).coerceAtLeast(0)) }) {
                        Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds")
                    }
                    Spacer(Modifier.width(16.dp))
                    FilledTonalIconButton(
                        onClick = { if (isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = { player.seekTo(position + 10_000) }) {
                        Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        } else {
            Box(Modifier.fillMaxSize().padding(padding)) {
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
                    modifier = Modifier.fillMaxSize(),
                )
                SmallFloatingActionButton(
                    onClick = {
                        lockedLandscape = !lockedLandscape
                        (context as? Activity)?.requestedOrientation = if (lockedLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Icon(Icons.Filled.ScreenRotation, contentDescription = "Toggle landscape")
                }
            }
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

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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
