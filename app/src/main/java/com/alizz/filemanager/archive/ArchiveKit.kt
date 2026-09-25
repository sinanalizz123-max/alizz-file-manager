package com.alizz.filemanager.archive

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

private const val MAX_ENTRIES = 10_000
private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
private const val MAX_RATIO = 100L

enum class ArchiveFormat { ZIP, TAR, TAR_GZ, TAR_BZ2, UNSUPPORTED }

fun archiveFormatOf(file: File): ArchiveFormat {
    val n = file.name.lowercase()
    return when {
        n.endsWith(".zip") || n.endsWith(".jar") -> ArchiveFormat.ZIP
        n.endsWith(".tar.gz") || n.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
        n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> ArchiveFormat.TAR_BZ2
        n.endsWith(".tar") -> ArchiveFormat.TAR
        n.endsWith(".gz") -> ArchiveFormat.TAR_GZ
        else -> ArchiveFormat.UNSUPPORTED
    }
}

data class ArchiveEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
)

/** Entry names use '/' separators; "" is the archive root. */
fun parentOf(entryPath: String): String {
    val t = entryPath.trimEnd('/')
    val i = t.lastIndexOf('/')
    return if (i < 0) "" else t.substring(0, i)
}

@Throws(SecurityException::class, IllegalStateException::class)
fun listArchive(file: File): List<ArchiveEntry> {
    return when (archiveFormatOf(file)) {
        ArchiveFormat.ZIP -> listZip(file)
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 -> listTar(file)
        ArchiveFormat.UNSUPPORTED -> throw IllegalStateException("Unsupported archive type")
    }
}

private fun listZip(file: File): List<ArchiveEntry> {
    ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().toList()
        if (entries.size > MAX_ENTRIES) throw IllegalStateException("Too many entries (${entries.size})")
        return entries.map {
            ArchiveEntry(it.name, it.name.trimEnd('/').substringAfterLast('/'), it.isDirectory, it.size.coerceAtLeast(0))
        }
    }
}

private fun listTar(file: File): List<ArchiveEntry> {
    openTarStream(file).use { tar ->
        val out = mutableListOf<ArchiveEntry>()
        var total: Long = 0
        while (true) {
            val entry: TarArchiveEntry = tar.nextEntry ?: break
            if (out.size >= MAX_ENTRIES) throw IllegalStateException("Too many entries")
            total += entry.size
            if (total > MAX_TOTAL_BYTES) throw IllegalStateException("Archive too large")
            out += ArchiveEntry(entry.name, entry.name.trimEnd('/').substringAfterLast('/'), entry.isDirectory, entry.size.coerceAtLeast(0))
        }
        return out
    }
}

private fun openTarStream(file: File): TarArchiveInputStream {
    val raw = BufferedInputStream(FileInputStream(file))
    return when (archiveFormatOf(file)) {
        ArchiveFormat.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(raw))
        ArchiveFormat.TAR_BZ2 -> TarArchiveInputStream(BZip2CompressorInputStream(raw))
        else -> TarArchiveInputStream(raw)
    }
}

data class ExtractResult(val extracted: Int, val skipped: Int)

/**
 * Extracts [wanted] (empty = whole archive) into [destDir].
 * Guards: no absolute paths, no ".." escapes, size/ratio caps.
 */
fun extractArchive(
    file: File,
    destDir: File,
    wanted: Set<String> = emptySet(),
    onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
): ExtractResult {
    if (!destDir.exists() && !destDir.mkdirs()) throw IllegalStateException("Cannot create destination")
    val base = destDir.canonicalPath
    return when (archiveFormatOf(file)) {
        ArchiveFormat.ZIP -> extractZip(file, base, wanted, onProgress)
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_BZ2 -> extractTar(file, base, wanted, onProgress)
        ArchiveFormat.UNSUPPORTED -> throw IllegalStateException("Unsupported archive type")
    }
}

private fun safeTarget(base: String, name: String): File {
    val target = File(base, name)
    if (!target.canonicalPath.startsWith(base + File.separator) && target.canonicalPath != base) {
        throw SecurityException("Blocked path escape: $name")
    }
    return target
}

private fun extractZip(file: File, base: String, wanted: Set<String>, onProgress: (String, Int, Int) -> Unit): ExtractResult {
    var extracted = 0
    var skipped = 0
    var written: Long = 0
    ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().filter { wanted.isEmpty() || wanted.contains(it.name) }.toList()
        entries.forEachIndexed { index, entry ->
            onProgress(entry.name, index, entries.size)
            val target = try {
                safeTarget(base, entry.name)
            } catch (e: SecurityException) {
                skipped++
                return@forEachIndexed
            }
            if (entry.isDirectory) {
                target.mkdirs()
                extracted++
            } else {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > MAX_TOTAL_BYTES) throw IllegalStateException("Archive too large")
                            output.write(buf, 0, n)
                        }
                    }
                }
                extracted++
            }
        }
    }
    return ExtractResult(extracted, skipped)
}

private fun extractTar(file: File, base: String, wanted: Set<String>, onProgress: (String, Int, Int) -> Unit): ExtractResult {
    var extracted = 0
    var skipped = 0
    var written: Long = 0
    openTarStream(file).use { tar ->
        var index = 0
        while (true) {
            val entry = tar.nextEntry ?: break
            if (wanted.isNotEmpty() && !wanted.contains(entry.name)) continue
            onProgress(entry.name, index++, -1)
            val target = try {
                safeTarget(base, entry.name)
            } catch (e: SecurityException) {
                skipped++
                continue
            }
            if (entry.isDirectory) {
                target.mkdirs()
                extracted++
            } else {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(128 * 1024)
                    var remaining = entry.size
                    while (remaining > 0) {
                        val n = tar.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        written += n
                        if (written > MAX_TOTAL_BYTES) throw IllegalStateException("Archive too large")
                        if (entry.size > 0 && written > entry.size * MAX_RATIO + 1024 * 1024) {
                            throw IllegalStateException("Suspicious compression ratio")
                        }
                        output.write(buf, 0, n)
                        remaining -= n
                    }
                }
                extracted++
            }
        }
    }
    return ExtractResult(extracted, skipped)
}

/** Creates a ZIP at [destZip] containing [sources]. Returns entries written. */
fun createZip(destZip: File, sources: List<File>, onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): Int {
    var count = 0
    ZipOutputStream(FileOutputStream(destZip)).use { zip ->
        sources.forEachIndexed { index, source ->
            onProgress(source.name, index, sources.size)
            addToZip(zip, source, source.name)
            count++
        }
    }
    return count
}

private fun addToZip(zip: ZipOutputStream, file: File, entryName: String) {
    if (file.isDirectory) {
        zip.putNextEntry(ZipEntry("$entryName/"))
        zip.closeEntry()
        file.listFiles()?.forEach { addToZip(zip, it, "$entryName/${it.name}") }
    } else {
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zip.write(buf, 0, n)
            }
        }
        zip.closeEntry()
    }
}
