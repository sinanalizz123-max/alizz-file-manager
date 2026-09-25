package com.alizz.filemanager.providers.webdav

import android.content.Context
import android.util.Xml
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.TimeUnit

private const val PREFS = "webdav_profiles"
private const val KEY_SET = "profiles"

enum class WebdavAuth { BASIC, DIGEST, NONE }

data class WebdavProfile(
    val id: String,
    val label: String,
    /** Base collection URL, e.g. https://host/remote.php/dav/files/user/ */
    val baseUrl: String,
    val user: String,
    val password: String,
    val auth: WebdavAuth,
) {
    fun displayLabel(): String = if (label.isBlank()) baseUrl else label
    fun normalizedBase(): String {
        var b = baseUrl.trim()
        if (!b.endsWith("/")) b += "/"
        return b
    }
}

class WebdavProfileStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<WebdavProfile> =
        prefs.getStringSet(KEY_SET, emptySet()).orEmpty().mapNotNull { raw ->
            val p = raw.split('\u0001')
            if (p.size != 6) null
            else WebdavProfile(p[0], p[1], p[2], p[3], p[4], WebdavAuth.valueOf(p[5]))
        }.sortedBy { it.displayLabel().lowercase() }

    fun save(profile: WebdavProfile) {
        val raw = listOf(
            profile.id, profile.label, profile.baseUrl, profile.user,
            profile.password, profile.auth.name,
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

data class WebdavItem(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

/** Parent of a relative entry path ("" for top-level entries). */
fun parentRel(path: String): String =
    if ('/' !in path) "" else path.substringBeforeLast('/')

/**
 * Blocking WebDAV client over OkHttp. System TLS trust only.
 * Call only from a background thread. One instance per session (holds Digest nonce state).
 */
class WebdavSession(val profile: WebdavProfile) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private var digestChallenge: Map<String, String>? = null
    private var nonceCount = 0

    fun resolve(rel: String): String {
        val base = profile.normalizedBase()
        if (rel.isEmpty()) return base
        return base + rel.split('/').joinToString("/") { it.encodeSegment() } +
            (if (rel.endsWith("/")) "/" else "")
    }

    private fun String.encodeSegment(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20").replace("%2F", "/")

    @Throws(IOException::class)
    fun list(relDir: String): List<WebdavItem> {
        val body = RequestBody.create(
            "application/xml; charset=utf-8".toMediaTypeOrNull(),
            """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:getcontentlength/><D:getlastmodified/></D:prop></D:propfind>""",
        )
        execute("PROPFIND", resolve(relDir), mapOf("Depth" to "1"), body).use { resp ->
            if (resp.code != 207) throw IOException("List failed (${resp.code})")
            val xml = resp.body?.string() ?: throw IOException("Empty response")
            val base = profile.normalizedBase()
            return parseMultistatus(xml, base).filter { it.path != relDir.trimEnd('/') }
                .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        }
    }

    @Throws(IOException::class)
    fun mkdir(relDir: String, name: String) {
        execute("MKCOL", resolve((if (relDir.isEmpty()) "" else relDir.trimEnd('/') + "/") + name)).use { resp ->
            if (resp.code !in listOf(200, 201, 207)) throw IOException("Create failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun rename(fromRel: String, toRel: String) {
        execute(
            "MOVE", resolve(fromRel),
            mapOf("Destination" to resolve(toRel), "Overwrite" to "F"),
        ).use { resp ->
            if (resp.code !in listOf(200, 201, 204, 207)) throw IOException("Rename failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun delete(item: WebdavItem) {
        if (item.isDir) {
            for (child in list(item.path)) delete(child)
        }
        execute("DELETE", resolve(item.path)).use { resp ->
            if (resp.code !in listOf(200, 201, 204, 207)) throw IOException("Delete failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun download(remoteRel: String, localTarget: File, onBytes: (Long) -> Unit = {}) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            execute("GET", resolve(remoteRel)).use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
                val body = resp.body ?: throw IOException("Empty response")
                tmp.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val n = body.byteStream().read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
            }
            if (!tmp.renameTo(localTarget)) throw IOException("Cannot finalize download")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    @Throws(IOException::class)
    fun upload(local: File, remoteDirRel: String) {
        val dest = (if (remoteDirRel.isEmpty()) "" else remoteDirRel.trimEnd('/') + "/") + local.name
        execute("PUT", resolve(dest), body = local.asRequestBody("application/octet-stream".toMediaTypeOrNull())).use { resp ->
            if (resp.code !in listOf(200, 201, 204, 207)) throw IOException("Upload failed (${resp.code})")
        }
    }

    private fun parseMultistatus(xml: String, base: String): List<WebdavItem> {
        val items = mutableListOf<WebdavItem>()
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.reader())
        var href: String? = null
        var displayName: String? = null
        var isCollection = false
        var length = 0L
        var modified = 0L
        var inResponse = false
        var currentTag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "response" -> {
                            inResponse = true
                            href = null
                            displayName = null
                            isCollection = false
                            length = 0
                            modified = 0
                        }
                        "collection" -> if (inResponse) isCollection = true
                        else -> currentTag = parser.name
                    }
                }
                XmlPullParser.TEXT -> if (inResponse) {
                    val text = parser.text
                    when (currentTag) {
                        "href" -> href = (href ?: "") + text
                        "displayname" -> displayName = ((displayName ?: "") + text).trim()
                        "getcontentlength" -> length = text.trim().toLongOrNull() ?: 0L
                        "getlastmodified" -> modified = parseHttpDate(text.trim())
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "response" && inResponse) {
                        inResponse = false
                        val rawHref = href ?: ""
                        // href may be an absolute URL or a server-absolute path.
                        val serverPath = try {
                            if (rawHref.startsWith("http")) java.net.URI(rawHref).path ?: rawHref
                            else rawHref
                        } catch (e: Exception) {
                            rawHref
                        }
                        val decoded = try {
                            URLDecoder.decode(serverPath, "UTF-8")
                        } catch (e: Exception) {
                            serverPath
                        }
                        val basePath = try {
                            java.net.URI(base).path ?: "/"
                        } catch (e: Exception) {
                            "/"
                        }
                        val rel = decoded.removePrefix(basePath.trimEnd('/')).trim('/')
                        val name = displayName?.ifEmpty { null }
                            ?: rel.trimEnd('/').substringAfterLast('/').ifEmpty { decoded.trim('/') }
                        items += WebdavItem(
                            name = name,
                            path = rel,
                            isDir = isCollection || decoded.endsWith("/"),
                            size = length.coerceAtLeast(0),
                            modified = modified,
                        )
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }
        return items
    }

    private fun parseHttpDate(value: String): Long {
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("GMT")
                val d: Date? = sdf.parse(value)
                if (d != null) return d.time
            } catch (e: Exception) {
                continue
            }
        }
        return 0
    }

    @Throws(IOException::class)
    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): Response {
        fun build(auth: String?): Request {
            val b = Request.Builder().url(url).method(method, body)
            for ((k, v) in headers) b.header(k, v)
            if (auth != null) b.header("Authorization", auth)
            return b.build()
        }
        // First attempt: Basic/NONE credentials, or Digest if we already have a challenge.
        val firstAuth = when (profile.auth) {
            WebdavAuth.BASIC -> Credentials.basic(profile.user, profile.password)
            WebdavAuth.DIGEST -> digestChallenge?.let { digestAuth(method, url, it) }
            WebdavAuth.NONE -> null
        }
        val first = client.newCall(build(firstAuth)).execute()
        if (first.code != 401) return first // caller owns and closes it
        val challenge = first.header("WWW-Authenticate")
        first.close()
        if (challenge == null || !challenge.startsWith("Digest", ignoreCase = true)) {
            throw IOException("Authentication required")
        }
        digestChallenge = parseChallenge(challenge)
        val second = client.newCall(build(digestAuth(method, url, digestChallenge!!))).execute()
        if (second.code == 401) {
            second.close()
            throw IOException("Authentication failed")
        }
        return second
    }

    private fun parseChallenge(header: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val params = header.substringAfter("Digest", "").trim()
        val re = Regex("""(\w+)=(?:"([^"]*)"|([^\s,]+))""")
        for (m in re.findAll(params)) {
            out[m.groupValues[1]] = m.groupValues[2].ifEmpty { m.groupValues[3] }
        }
        return out
    }

    private fun digestAuth(method: String, url: String, ch: Map<String, String>): String {
        val realm = ch["realm"] ?: ""
        val nonce = ch["nonce"] ?: throw IOException("Bad digest challenge")
        val qop = ch["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" } ?: ch["qop"]
        val opaque = ch["opaque"]
        val algorithm = (ch["algorithm"] ?: "MD5").uppercase()
        if (algorithm != "MD5" && algorithm != "MD5-SESS") throw IOException("Unsupported digest algorithm")
        nonceCount++
        val nc = "%08x".format(nonceCount)
        val cnonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val uri = try {
            val u = java.net.URI(url)
            (u.rawPath ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        } catch (e: Exception) {
            url
        }
        fun md5(s: String): String {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.ISO_8859_1))
            return d.joinToString("") { "%02x".format(it) }
        }
        val ha1 = md5("${profile.user}:$realm:${profile.password}")
        val ha2 = md5("$method:$uri")
        val response = if (qop != null) md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder("Digest username=\"${profile.user}\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
        if (qop != null) sb.append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
        if (opaque != null) sb.append(", opaque=\"$opaque\"")
        return sb.toString()
    }
}
