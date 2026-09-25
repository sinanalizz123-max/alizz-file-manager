package com.alizz.filemanager.server

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

/**
 * Read-only-by-default HTTP file server. Uploads only when [password] is set
 * (Basic auth required for every request then); without a password the server
 * serves listings/downloads openly on the local network — the UI warns this.
 */
class HttpFileServer(
    private val root: File,
    port: Int,
    private val password: String,
) : NanoHTTPD(port) {
    val authRequired: Boolean = password.isNotEmpty()

    override fun serve(session: IHTTPSession): Response {
        if (authRequired && !authorized(session.headers["authorization"])) {
            val r = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Auth required")
            r.addHeader("WWW-Authenticate", "Basic realm=\"File Manager\"")
            return r
        }
        val rel = session.uri.trimStart('/').split('/').filter { it.isNotEmpty() }
            .joinToString("/") { it.decode() }
        if (rel.contains("..")) return forbidden()
        val target = if (rel.isEmpty()) root else File(root, rel)
        return try {
            if (!target.canonicalPath.startsWith(root.canonicalPath)) return forbidden()
            when {
                session.method == Method.GET && target.isDirectory -> listing(target, rel)
                session.method == Method.GET && target.isFile -> download(target)
                session.method == Method.POST && target.isDirectory && authRequired -> upload(session, target)
                session.method == Method.POST -> forbidden()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error")
        }
    }

    private fun authorized(header: String?): Boolean {
        if (header == null || !header.startsWith("Basic ")) return false
        return try {
            val decoded = String(android.util.Base64.decode(header.removePrefix("Basic ").trim(), android.util.Base64.DEFAULT))
            decoded == ":$password" || decoded.endsWith(":$password")
        } catch (e: Exception) {
            false
        }
    }

    private fun forbidden(): Response =
        newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Forbidden")

    private fun listing(dir: File, rel: String): Response {
        val kids = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        val base = if (rel.isEmpty()) "" else "/" + rel.split('/').joinToString("/") { it.encode() }
        val html = buildString {
            append("<html><head><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Index of /")
            append(escape(rel.ifEmpty { "/" }))
            append("</title></head><body><h1>Index of /")
            append(escape(rel.ifEmpty { "/" }))
            append("</h1><ul>")
            if (rel.isNotEmpty()) append("<li><a href=\"../\">..</a></li>")
            for (k in kids) {
                val href = base + "/" + k.name.encode()
                append("<li><a href=\"")
                append(href)
                append("\">")
                append(escape(k.name))
                append("</a>")
                if (!k.isDirectory) append(" (${k.length()} bytes)")
            }
            append("</ul>")
            if (authRequired) {
                append("<hr><form method=post enctype=\"multipart/form-data\">")
                append("<input type=file name=file><input type=submit value=Upload></form>")
            }
            append("</body></html>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun download(file: File): Response {
        val mime = when (file.extension.lowercase()) {
            "html", "htm" -> "text/html"
            "txt", "log", "md" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
        return try {
            val stream = FileInputStream(file)
            newChunkedResponse(Response.Status.OK, mime, stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun upload(session: IHTTPSession, dir: File): Response {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            var count = 0
            for ((field, tmpPath) in files) {
                val params = session.parameters[field]
                val name = params?.firstOrNull()?.takeIf { it.isNotBlank() } ?: continue
                val clean = File(name).name
                if (clean.isEmpty() || clean.contains("..")) continue
                val dest = File(dir, clean)
                if (!dest.canonicalPath.startsWith(root.canonicalPath)) continue
                File(tmpPath).copyTo(dest, overwrite = true)
                File(tmpPath).delete()
                count++
            }
            val back = session.headers["referer"] ?: "/"
            val r = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
            r.addHeader("Location", back)
            r
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Upload failed")
        }
    }

    private fun String.encode(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private fun String.decode(): String =
        try {
            java.net.URLDecoder.decode(this, "UTF-8")
        } catch (e: Exception) {
            this
        }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
