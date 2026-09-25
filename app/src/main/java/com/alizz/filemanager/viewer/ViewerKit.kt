package com.alizz.filemanager.viewer

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

enum class ViewerKind { IMAGE, VIDEO, AUDIO, TEXT, APK, ARCHIVE, PDF, OTHER }

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v")
private val AUDIO_EXT = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus", "mid", "midi")
private val TEXT_EXT = setOf(
    "txt", "md", "markdown", "log", "json", "xml", "csv", "ini", "conf", "cfg",
    "yml", "yaml", "toml", "properties", "gradle", "kt", "java", "py", "js",
    "ts", "c", "h", "cpp", "cs", "go", "rs", "sh", "html", "htm", "css", "sql",
)
private val ARCHIVE_EXT = setOf("zip", "jar", "apk", "7z", "tar", "gz", "tgz", "bz2", "xz", "rar")

fun kindOf(file: File): ViewerKind {
    if (file.isDirectory) return ViewerKind.OTHER
    return when (file.extension.lowercase()) {
        "apk" -> ViewerKind.APK
        "pdf" -> ViewerKind.PDF
        in IMAGE_EXT -> ViewerKind.IMAGE
        in VIDEO_EXT -> ViewerKind.VIDEO
        in AUDIO_EXT -> ViewerKind.AUDIO
        in TEXT_EXT -> ViewerKind.TEXT
        in ARCHIVE_EXT -> ViewerKind.ARCHIVE
        else -> ViewerKind.OTHER
    }
}

fun isImageFile(file: File): Boolean = !file.isDirectory && file.extension.lowercase() in IMAGE_EXT

fun mimeOf(file: File): String = when (kindOf(file)) {
    ViewerKind.IMAGE -> "image/*"
    ViewerKind.VIDEO -> "video/*"
    ViewerKind.AUDIO -> "audio/*"
    ViewerKind.TEXT -> "text/plain"
    ViewerKind.APK -> "application/vnd.android.package-archive"
    ViewerKind.PDF -> "application/pdf"
    ViewerKind.ARCHIVE -> "application/zip"
    ViewerKind.OTHER -> "*/*"
}

/** Open with an external app via FileProvider chooser. Returns false if nothing can open it. */
fun openWith(context: Context, file: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Open with")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    } catch (e: Exception) {
        false
    }
}
