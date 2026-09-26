package com.alizz.filemanager.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun ImageScreen(
    file: File,
    siblings: List<File>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val images = remember(file, siblings) {
        val list = siblings.filter(::isImageFile).sortedBy { it.name.lowercase() }
        if (list.any { it.absolutePath == file.absolutePath }) list else listOf(file)
    }
    val startIndex = remember(file, images) {
        images.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
    }
    val pager = rememberPagerState(initialPage = startIndex, pageCount = { images.size })
    var slideshow by remember { mutableStateOf(false) }
    var exifText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(slideshow, images.size) {
        while (slideshow) {
            delay(3000)
            val next = (pager.currentPage + 1) % images.size
            pager.animateScrollToPage(next)
        }
    }

    Scaffold(
        topBar = {
            ViewerTopBar(
                title = images.getOrNull(pager.currentPage)?.name ?: file.name,
                onBack = onBack,
                actions = {
                    Text(
                        "${if (images.isEmpty()) 0 else pager.currentPage + 1} / ${images.size}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(onClick = { slideshow = !slideshow }) {
                        Icon(
                            if (slideshow) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (slideshow) "Stop slideshow" else "Slideshow",
                        )
                    }
                    IconButton(onClick = {
                        exifText = readExif(images.getOrNull(pager.currentPage) ?: file)
                    }) {
                        Icon(Icons.Filled.Info, contentDescription = "Details")
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize().padding(padding),
            key = { images[it].absolutePath },
        ) { page ->
            AsyncImage(
                model = images[page],
                contentDescription = images[page].name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    exifText?.let { text ->
        AlertDialog(
            onDismissRequest = { exifText = null },
            title = { Text("Image details") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { exifText = null }) { Text("OK") } },
        )
    }
}

private fun readExif(file: File): String {
    return try {
        val exif = ExifInterface(file.absolutePath)
        val rows = listOf(
            "Size" to "${file.length()} bytes (${file.length() / 1024} KB)",
            "Dimensions" to exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                ?.let { w -> exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)?.let { h -> "$w × $h" } }
                .orEmpty(),
            "Taken" to exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty(),
            "Camera" to listOfNotNull(
                exif.getAttribute(ExifInterface.TAG_MAKE),
                exif.getAttribute(ExifInterface.TAG_MODEL),
            ).joinToString(" ").ifEmpty { "-" },
            "Exposure" to exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME).orEmpty(),
            "F-number" to exif.getAttribute(ExifInterface.TAG_F_NUMBER).orEmpty(),
            "ISO" to exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY).orEmpty(),
            "Focal" to exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH).orEmpty(),
            "GPS" to exif.latLong?.let { "${it[0]}, ${it[1]}" }.orEmpty(),
            "Orientation" to exif.getAttribute(ExifInterface.TAG_ORIENTATION).orEmpty(),
        ).filter { it.second.isNotBlank() }
        if (rows.isEmpty()) "No EXIF data" else rows.joinToString("\n") { it.first + ": " + it.second }
    } catch (e: Exception) {
        "Could not read details: " + (e.message ?: "error")
    }
}
