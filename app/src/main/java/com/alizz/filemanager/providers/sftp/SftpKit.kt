package com.alizz.filemanager.providers.sftp

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier

private const val PREFS = "sftp_profiles"
private const val KEY_SET = "profiles"
private const val KEY_TRUST = "trust:"

enum class SftpAuth { PASSWORD, KEY }

data class SftpProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val user: String,
    val auth: SftpAuth,
    /** Password, or passphrase for key auth (app-private prefs). */
    val secret: String,
    /** Absolute path of the app-private private-key copy (KEY auth only). */
    val keyPath: String,
) {
    fun displayLabel(): String = if (label.isBlank()) "$user@$host:$port" else label
}

class SftpProfileStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<SftpProfile> =
        prefs.getStringSet(KEY_SET, emptySet()).orEmpty().mapNotNull { raw ->
            val p = raw.split('\u0001')
            if (p.size != 8) null
            else SftpProfile(
                p[0], p[1], p[2], p[3].toIntOrNull() ?: 22, p[4],
                SftpAuth.valueOf(p[5]), p[6], p[7],
            )
        }.sortedBy { it.displayLabel().lowercase() }

    fun save(profile: SftpProfile) {
        val raw = listOf(
            profile.id, profile.label, profile.host, profile.port.toString(),
            profile.user, profile.auth.name, profile.secret, profile.keyPath,
        ).joinToString("\u0001")
        prefs.edit().putStringSet(KEY_SET, prefs.getStringSet(KEY_SET, emptySet()).orEmpty() + raw).apply()
    }

    fun delete(id: String) {
        prefs.edit().putStringSet(
            KEY_SET,
            prefs.getStringSet(KEY_SET, emptySet()).orEmpty().filterNot { it.startsWith(id + "\u0001") }.toSet(),
        ).apply()
    }

    fun trustedKey(host: String, port: Int): String? =
        prefs.getString(KEY_TRUST + host.lowercase() + ":" + port, null)

    fun trustKey(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(KEY_TRUST + host.lowercase() + ":" + port, fingerprint).apply()
    }
}

fun fingerprintOf(key: PublicKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
    return "SHA256:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
}

/** Thrown when the server key is unknown or changed — never auto-accepted. */
class UntrustedHostException(val fingerprint: String, val changed: Boolean) : SecurityException(
    if (changed) "Host key changed! Possible attack. Fingerprint: $fingerprint"
    else "Unknown host key. Verify before trusting. Fingerprint: $fingerprint",
)

data class SftpItem(val name: String, val path: String, val isDir: Boolean, val size: Long)

/**
 * Blocking SSH/SFTP session. Call only from Dispatchers.IO. One instance per session.
 */
class SftpSession(
    private val app: Context,
    val profile: SftpProfile,
) {
    private val ssh = SSHClient()
    private var sftp: SFTPClient? = null
    private var connected = false

    val isConnected: Boolean get() = connected && ssh.isConnected

    @Throws(Exception::class)
    fun connect() {
        ssh.connectTimeout = 15_000
        // Strict: only previously trusted keys pass. Unknown/changed keys throw
        // UntrustedHostException so the UI can ask before anything is accepted.
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fp = fingerprintOf(key)
                val expected = (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
                    .getString(KEY_TRUST + profile.host.lowercase() + ":" + profile.port, null)
                if (expected == fp) return true
                throw UntrustedHostException(fp, expected != null)
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                listOf(
                    "ssh-ed25519",
                    "ecdsa-sha2-nistp256",
                    "ecdsa-sha2-nistp384",
                    "ecdsa-sha2-nistp521",
                    "ssh-rsa",
                    "rsa-sha2-256",
                    "rsa-sha2-512",
                )
        })
        ssh.connect(profile.host, profile.port)
        try {
            when (profile.auth) {
                SftpAuth.PASSWORD -> ssh.authPassword(profile.user, profile.secret)
                SftpAuth.KEY -> {
                    val provider = ssh.loadKeys(
                        profile.keyPath,
                        profile.secret.ifEmpty { null },
                    )
                    ssh.authPublickey(profile.user, provider)
                }
            }
            if (!ssh.isAuthenticated) throw java.io.IOException("Authentication failed")
            sftp = ssh.newSFTPClient()
            connected = true
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    private fun client(): SFTPClient {
        val c = sftp
        if (!isConnected || c == null) throw java.io.IOException("Not connected")
        return c
    }

    @Throws(Exception::class)
    fun list(dir: String): List<SftpItem> {
        val path = if (dir.isEmpty()) "." else dir
        return client().ls(path).filter { it.name != "." && it.name != ".." }.map {
            val full = if (path == ".") it.name else path.trimEnd('/') + "/" + it.name
            SftpItem(it.name, full, it.isDirectory, it.attributes?.size?.coerceAtLeast(0) ?: 0)
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    @Throws(Exception::class)
    fun mkdir(dir: String, name: String) {
        client().mkdir((if (dir.isEmpty()) "" else dir.trimEnd('/') + "/") + name)
    }

    @Throws(Exception::class)
    fun rename(from: String, to: String) {
        client().rename(from, to)
    }

    @Throws(Exception::class)
    fun delete(item: SftpItem) {
        val c = client()
        if (item.isDir) removeDir(c, item.path) else c.rm(item.path)
    }

    private fun removeDir(c: SFTPClient, path: String) {
        for (child in c.ls(path)) {
            if (child.name == "." || child.name == "..") continue
            val full = path.trimEnd('/') + "/" + child.name
            if (child.isDirectory) removeDir(c, full) else c.rm(full)
        }
        c.rmdir(path)
    }

    @Throws(Exception::class)
    fun download(remotePath: String, localTarget: File) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            client().get(remotePath, tmp.absolutePath)
            if (!tmp.renameTo(localTarget)) throw java.io.IOException("Cannot finalize download")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    @Throws(Exception::class)
    fun upload(local: File, remoteDir: String) {
        val remote = (if (remoteDir.isEmpty()) "" else remoteDir.trimEnd('/') + "/") + local.name
        client().put(local.absolutePath, remote)
    }

    fun disconnect() {
        connected = false
        try {
            sftp?.close()
        } catch (e: Exception) {
        }
        try {
            ssh.disconnect()
        } catch (e: Exception) {
        }
    }
}
