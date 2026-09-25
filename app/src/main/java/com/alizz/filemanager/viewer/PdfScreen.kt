package com.alizz.filemanager.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfScreen(
    file: File,
    onBack: () -> Unit,
) {
    var pageCount by remember(file) { mutableStateOf(-1) }
    var locked by remember(file) { mutableStateOf(false) }
    var loadError by remember(file) { mutableStateOf<String?>(null) }

    val renderer = remember(file) {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                try {
                    pfd.close()
                } catch (ignored: Exception) {
                }
                locked = true
                null
            } catch (e: Exception) {
                try {
                    pfd.close()
                } catch (ignored: Exception) {
                }
                loadError = e.message ?: "Cannot open PDF"
                null
            }
        } catch (e: Exception) {
            loadError = e.message ?: "Cannot open PDF"
            null
        }
    }
    DisposableEffect(file) {
        onDispose {
            try {
                renderer?.close()
            } catch (e: Exception) {
            }
        }
    }
    LaunchedEffect(renderer) {
        pageCount = try {
            renderer?.pageCount ?: -1
        } catch (e: Exception) {
            loadError = e.message ?: "Cannot open PDF"
            -1
        }
    }

    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = 0,
        pageCount = { pageCount.coerceAtLeast(0) },
    )

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
                    if (pageCount > 0) {
                        Text(
                            "${pager.currentPage + 1} / $pageCount",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            locked -> Text(
                "Password-protected PDFs are not supported yet.",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp),
            )
            loadError != null -> Text(
                loadError ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            pageCount < 0 -> Text(
                "Loading…",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp),
            )
            pageCount == 0 -> Text(
                "Empty PDF",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp),
            )
            renderer != null -> PdfPager(
                renderer = renderer,
                pager = pager,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun PdfPager(
    renderer: PdfRenderer,
    pager: androidx.compose.foundation.pager.PagerState,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    HorizontalPager(state = pager, modifier = modifier.fillMaxSize()) { page ->
        var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(page) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            bitmap = renderPage(renderer, page)
        }
        val bmp = bitmap
        if (bmp != null) {
            AsyncImage(
                model = bmp,
                contentDescription = "Page ${page + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale.coerceIn(1f, 4f),
                        scaleY = scale.coerceIn(1f, 4f),
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .pointerInput(page) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 4f)
                            scale = next
                            if (next <= 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                    },
            )
        } else {
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                Text(
                    "Rendering page ${page + 1}…",
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap? {
    return try {
        renderer.openPage(index).use { page ->
            val w = (page.width * 2).coerceAtMost(4096)
            val h = (page.height * 2).coerceAtMost(4096)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    } catch (e: Exception) {
        null
    }
}
