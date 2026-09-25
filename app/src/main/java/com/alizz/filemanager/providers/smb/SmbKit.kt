package com.alizz.filemanager.providers.smb

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.File
import java.util.EnumSet

private const val PREFS = "smb_profiles"
private const val KEY_SET = "profiles"
private const val DIR_ATTR = 0x10L

data class SmbProfile(
    val id: String,
    val label: String,
    val host: String,
    val share: String,
    val domain: String,
    val user: String,
    val password: String,
    val anonymous: Boolean,
) {
    fun displayLabel(): String =
        if (label.isBlank()) "\\\\$host\\$share" else label
}

class SmbProfileStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<SmbProfile> =
        prefs.getStringSet(KEY_SET, emptySet()).orEmpty().mapNotNull { raw ->
            val p = raw.split('\u0001')
            if (p.size != 8) null
            else SmbProfile(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7].toBoolean())
        }.sortedBy { it.displayLabel().lowercase() }

    fun save(profile: SmbProfile) {
        val raw = listOf(
            profile.id, profile.label, profile.host, profile.share, profile.domain,
            profile.user, profile.password, profile.anonymous.toString(),
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

data class SmbItem(val name: String, val path: String, val isDir: Boolean, val size: Long)

/**
 * Blocking SMB2/3 session. Call only from Dispatchers.IO. One instance per session.
 * Rename is not implemented in v1 (smbj exposes no rename primitive).
 */
class SmbSession(val profile: SmbProfile) {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null
    private var connected = false

    val isConnected: Boolean get() = connected

    @Throws(Exception::class)
    fun connect() {
        val c = SMBClient()
        client = c
        val conn = c.connect(profile.host)
        connection = conn
        val auth = if (profile.anonymous) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(profile.user, profile.password.toCharArray(), profile.domain)
        }
        session = conn.authenticate(auth)
        share = session?.connectShare(profile.share) as? DiskShare
            ?: throw java.io.IOException("Share not found: ${profile.share}")
        connected = true
    }

    private fun disk(): DiskShare =
        if (connected) share ?: throw java.io.IOException("Not connected")
        else throw java.io.IOException("Not connected")

    @Throws(Exception::class)
    fun list(dir: String): List<SmbItem> {
        val infos = disk().list(if (dir.isEmpty()) "" else dir)
        return infos
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                val full = if (dir.isEmpty()) it.fileName else dir.trimEnd('/') + "/" + it.fileName
                SmbItem(it.fileName, full, (it.fileAttributes and DIR_ATTR) != 0L, it.endOfFile.coerceAtLeast(0))
            }
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    @Throws(Exception::class)
    fun mkdir(dir: String, name: String) {
        disk().mkdir((if (dir.isEmpty()) "" else dir.trimEnd('/') + "/") + name)
    }

    @Throws(Exception::class)
    fun delete(item: SmbItem) {
        val d = disk()
        if (item.isDir) d.rmdir(item.path, true) else d.rm(item.path)
    }

    @Throws(Exception::class)
    fun download(remotePath: String, localTarget: File) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            disk().openFile(
                remotePath,
                EnumSet.of(AccessMask.GENERIC_READ),
                emptySet(),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                emptySet(),
            ).use { remote ->
                tmp.outputStream().use { out -> remote.read(out) }
            }
            if (!tmp.renameTo(localTarget)) throw java.io.IOException("Cannot finalize download")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    @Throws(Exception::class)
    fun upload(local: File, remoteDir: String) {
        val remote = (if (remoteDir.isEmpty()) "" else remoteDir.trimEnd('/') + "/") + local.name
        disk().openFile(
            remote,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            emptySet(),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            emptySet(),
        ).use { out ->
            local.inputStream().use { input ->
                out.outputStream.use { o -> input.copyTo(o, 128 * 1024) }
            }
        }
    }

    fun disconnect() {
        connected = false
        try {
            share?.close()
        } catch (e: Exception) {
        }
        try {
            connection?.close()
        } catch (e: Exception) {
        }
        try {
            client?.close()
        } catch (e: Exception) {
        }
        share = null
        session = null
        connection = null
        client = null
    }
}
