package com.alizz.filemanager.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@Composable
fun PdfScreen(
    file: File,
    onBack: () -> Unit,
) {
    var pageCount by remember(file) { mutableStateOf(-1) }
    var locked by remember(file) { mutableStateOf(false) }
    var loadError by remember(file) { mutableStateOf<String?>(null) }
    var aspect by remember(file) { mutableStateOf(0.7f) }

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
            val count = renderer?.pageCount ?: -1
            if (count > 0) {
                renderer?.openPage(0)?.use { first ->
                    if (first.height > 0) aspect = first.width.toFloat() / first.height.toFloat()
                }
            }
            count
        } catch (e: Exception) {
            loadError = e.message ?: "Cannot open PDF"
            -1
        }
    }

    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            ViewerTopBar(
                title = file.name,
                onBack = onBack,
                actions = {
                    if (pageCount > 0) {
                        Text(
                            "${listState.firstVisibleItemIndex + 1} / $pageCount",
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
            pageCount < 0 || renderer == null -> Text(
                "Loading…",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp),
            )
            pageCount == 0 -> Text(
                "Empty PDF",
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(count = pageCount, key = { "page-$it" }) { index ->
                    PdfPage(
                        renderer = renderer,
                        index = index,
                        aspect = aspect,
                    )
                    if (index < pageCount - 1) {
                        HorizontalDivider(
                            thickness = 8.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPage(
    renderer: PdfRenderer,
    index: Int,
    aspect: Float,
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    var scale by remember(index) { mutableStateOf(1f) }
    var offsetX by remember(index) { mutableStateOf(0f) }
    var offsetY by remember(index) { mutableStateOf(0f) }
    val zoomed = scale > 1f

    LaunchedEffect(index) {
        bitmap = renderPage(renderer, index)
    }
    DisposableEffect(index) {
        onDispose {
            try {
                bitmap?.recycle()
            } catch (e: Exception) {
            }
            bitmap = null
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Surface(
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.2f, 3f)),
        ) {
            AsyncImage(
                model = bmp,
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .combinedClickable(
                        onDoubleClick = {
                            if (zoomed) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                        onClick = {},
                    )
                    .pointerInput(zoomed) {
                        // Pinch/pan gestures are only consumed while zoomed,
                        // so single-finger scroll always reaches the page list.
                        if (zoomed) {
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
                        }
                    },
            )
        }
    } else {
        Text(
            "Rendering page ${index + 1}…",
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(24.dp),
        )
    }
}

private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap? {
    return try {
        renderer.openPage(index).use { page ->
            val scaleFactor = 1.5f
            val w = (page.width * scaleFactor).toInt().coerceAtMost(2048).coerceAtLeast(1)
            val h = (page.height * scaleFactor).toInt().coerceAtMost(2048).coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // White base so transparent PDFs look normal in dark mode too.
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    } catch (e: Exception) {
        null
    }
}
