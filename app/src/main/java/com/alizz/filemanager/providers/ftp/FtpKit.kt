package com.alizz.filemanager.providers.ftp

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPSClient

private const val PREFS = "ftp_profiles"
private const val KEY_SET = "profiles"

enum class FtpSecurity { PLAIN, FTPS_EXPLICIT }

data class FtpProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val security: FtpSecurity,
) {
    fun displayLabel(): String = if (label.isBlank()) "$user@$host:$port" else label
}

/** Saved connection profiles. Passwords live in app-private prefs (same bar as SAF grants). */
class FtpProfileStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<FtpProfile> =
        prefs.getStringSet(KEY_SET, emptySet()).orEmpty().mapNotNull { raw ->
            val p = raw.split('\u0001')
            if (p.size != 7) null
            else FtpProfile(p[0], p[1], p[2], p[3].toIntOrNull() ?: 21, p[4], p[5], FtpSecurity.valueOf(p[6]))
        }.sortedBy { it.displayLabel().lowercase() }

    fun save(profile: FtpProfile) {
        val raw = listOf(
            profile.id, profile.label, profile.host, profile.port.toString(),
            profile.user, profile.password, profile.security.name,
        ).joinToString("\u0001")
        prefs.edit().putStringSet(KEY_SET, prefs.getStringSet(KEY_SET, emptySet()).orEmpty() + raw).apply()
    }

    fun delete(id: String) {
        prefs.edit().putStringSet(
            KEY_SET,
            prefs.getStringSet(KEY_SET, emptySet()).orEmpty().filterNot { it.startsWith(id + "\u0001") }.toSet(),
        ).apply()
    }
}

data class RemoteItem(val name: String, val path: String, val isDir: Boolean, val size: Long)

/**
 * Blocking FTP/FTPS client. All methods run on the caller's thread —
 * always call from Dispatchers.IO. Not thread-safe: one instance per session.
 */
class FtpSession(val profile: FtpProfile) {
    private val client: FTPClient = if (profile.security == FtpSecurity.FTPS_EXPLICIT) {
        // System CA trust only — self-signed servers fail with a clear error, never silently accepted.
        FTPSClient("TLS", false)
    } else {
        FTPClient()
    }
    private var connected = false

    val isConnected: Boolean get() = connected && client.isConnected

    @Throws(IOException::class)
    fun connect() {
        client.connectTimeout = 15_000
        client.connect(profile.host, profile.port)
        if (!client.login(profile.user, profile.password)) {
            throw IOException("Login failed: ${client.replyString?.trim()}")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        client.controlKeepAliveTimeout = 60
        connected = true
    }

    @Throws(IOException::class)
    fun list(dir: String): List<RemoteItem> {
        ensure()
        val files: Array<FTPFile> = client.listFiles(dir.ifEmpty { "/" }) ?: throw IOException("List failed")
        val base = if (dir.isEmpty() || dir == "/") "" else dir.trimEnd('/')
        return files.filter { it.name != "." && it.name != ".." }.map {
            RemoteItem(it.name, "$base/${it.name}", it.isDirectory, it.size.coerceAtLeast(0))
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    @Throws(IOException::class)
    fun mkdir(dir: String, name: String): Boolean {
        ensure()
        return client.makeDirectory(dir.trimEnd('/') + "/" + name)
    }

    @Throws(IOException::class)
    fun rename(from: String, to: String): Boolean {
        ensure()
        return client.rename(from, to)
    }

    @Throws(IOException::class)
    fun delete(item: RemoteItem): Boolean {
        ensure()
        return if (item.isDir) removeDir(item.path) else client.deleteFile(item.path)
    }

    private fun removeDir(path: String): Boolean {
        val children = try {
            client.listFiles(path) ?: emptyArray()
        } catch (e: IOException) {
            return false
        }
        for (child in children) {
            if (child.name == "." || child.name == "..") continue
            val ok = if (child.isDirectory) removeDir("$path/${child.name}") else client.deleteFile("$path/${child.name}")
            if (!ok) return false
        }
        return client.removeDirectory(path)
    }

    @Throws(IOException::class)
    fun download(remotePath: String, localTarget: File, onBytes: (Long) -> Unit = {}) {
        ensure()
        localTarget.parentFile?.mkdirs()
        FileOutputStream(localTarget).use { out ->
            val counting = object : java.io.FilterOutputStream(out) {
                var count = 0L
                override fun write(b: ByteArray, off: Int, len: Int) {
                    super.write(b, off, len)
                    count += len
                    onBytes(count)
                }
            }
            if (!client.retrieveFile(remotePath, counting)) {
                localTarget.delete()
                throw IOException("Download failed: ${client.replyString?.trim()}")
            }
        }
    }

    @Throws(IOException::class)
    fun upload(local: File, remoteDir: String) {
        ensure()
        FileInputStream(local).use { input ->
            val remote = remoteDir.trimEnd('/') + "/" + local.name
            if (!client.storeFile(remote, input)) {
                throw IOException("Upload failed: ${client.replyString?.trim()}")
            }
        }
    }

    fun disconnect() {
        connected = false
        try {
            if (client.isConnected) client.logout()
        } catch (e: Exception) {
            // ignore
        }
        try {
            client.disconnect()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun ensure() {
        if (!isConnected) throw IOException("Not connected")
    }
}
