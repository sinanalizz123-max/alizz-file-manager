package com.alizz.filemanager.providers.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive"
private const val PREFS = "drive_store"
private const val KEY_ACCOUNT = "account"

/**
 * Setup note: Google Drive access needs an OAuth client for this app's
 * package name + signing cert registered in Google Cloud Console
 * (APIs & Services → Credentials → OAuth client ID → Android).
 * Without it, sign-in fails with an unrecoverable auth error.
 */
class DriveAccountStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun accountEmail(): String? = prefs.getString(KEY_ACCOUNT, null)

    fun saveAccount(email: String) {
        prefs.edit().putString(KEY_ACCOUNT, email).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ACCOUNT).apply()
    }
}

data class DriveItem(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val mime: String,
)

/** Consent required — the UI must launch [intent] and retry afterwards. */
class DriveConsentRequired(val intent: Intent?) : IOException("Sign-in consent required")

/**
 * Blocking Drive REST client. Call only from a background thread.
 * Auth tokens come from GoogleAuthUtil (system-cached, refresh handled by GMS).
 */
class DriveSession(private val app: Context, val email: String) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val account = Account(email, "com.google")
    private var cachedToken: String? = null

    @Throws(IOException::class)
    private fun token(): String {
        cachedToken?.let { return it }
        try {
            val t = GoogleAuthUtil.getToken(app, account, DRIVE_SCOPE)
            cachedToken = t
            return t
        } catch (e: UserRecoverableAuthException) {
            throw DriveConsentRequired(e.intent)
        } catch (e: GoogleAuthException) {
            throw IOException("Google sign-in failed: ${e.message}")
        }
    }

    private fun invalidate() {
        cachedToken?.let {
            try {
                GoogleAuthUtil.invalidateToken(app, it)
            } catch (e: Exception) {
                // ignore
            }
            cachedToken = null
        }
    }

    /** Runs [block] with a bearer token, retrying once after invalidation on 401. */
    @Throws(IOException::class)
    private fun <T> authed(block: (String) -> T): T {
        try {
            return block(token())
        } catch (e: DriveHttpException) {
            if (e.code != 401) throw e
        }
        invalidate()
        try {
            return block(token())
        } catch (e: DriveHttpException) {
            throw IOException("Request failed (${e.code})")
        }
    }

    private fun req(method: String, url: String, token: String, body: okhttp3.RequestBody? = null): Request {
        val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            "POST" -> b.post(body ?: "".toRequestBody(null))
            "PATCH" -> b.patch(body ?: "".toRequestBody(null))
            else -> b.method(method, body)
        }
        return b.build()
    }

    @Throws(IOException::class)
    private fun call(method: String, url: String, body: okhttp3.RequestBody? = null): String {
        return authed { token ->
            client.newCall(req(method, url, token, body)).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw DriveHttpException(resp.code, text)
                text
            }
        }
    }

    @Throws(IOException::class)
    fun list(folderId: String): List<DriveItem> {
        val out = mutableListOf<DriveItem>()
        var pageToken: String? = null
        do {
            val q = java.net.URLEncoder.encode("'$folderId' in parents and trashed=false", "UTF-8")
            var url = "https://www.googleapis.com/drive/v3/files?q=$q" +
                "&fields=files(id,name,mimeType,size,modifiedTime),nextPageToken" +
                "&orderBy=folder,name&pageSize=200"
            if (pageToken != null) url += "&pageToken=" + pageToken
            val json = JSONObject(call("GET", url))
            val files: JSONArray = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val o = files.getJSONObject(i)
                val mime = o.optString("mimeType")
                out += DriveItem(
                    id = o.getString("id"),
                    name = o.optString("name", "?"),
                    isDir = mime == "application/vnd.google-apps.folder",
                    size = o.optString("size", "0").toLongOrNull() ?: 0L,
                    modified = parseTime(o.optString("modifiedTime", "")),
                    mime = mime,
                )
            }
            pageToken = json.optString("nextPageToken", null)?.ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    @Throws(IOException::class)
    fun mkdir(parentId: String, name: String): String {
        val meta = JSONObject()
            .put("name", name)
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("parents", JSONArray().put(parentId))
            .toString()
        val json = JSONObject(
            call("POST", "https://www.googleapis.com/drive/v3/files?fields=id", meta.toRequestBody("application/json".toMediaTypeOrNull())),
        )
        return json.getString("id")
    }

    @Throws(IOException::class)
    fun rename(id: String, name: String) {
        val meta = JSONObject().put("name", name).toString()
        call("PATCH", "https://www.googleapis.com/drive/v3/files/$id", meta.toRequestBody("application/json".toMediaTypeOrNull()))
    }

    /** Moves to Drive trash (recoverable from drive.google.com). */
    @Throws(IOException::class)
    fun trash(id: String) {
        val meta = JSONObject().put("trashed", true).toString()
        call("PATCH", "https://www.googleapis.com/drive/v3/files/$id", meta.toRequestBody("application/json".toMediaTypeOrNull()))
    }

    @Throws(IOException::class)
    fun deleteForever(id: String) {
        authed { token ->
            client.newCall(req("DELETE", "https://www.googleapis.com/drive/v3/files/$id", token)).execute().use { resp ->
                if (!resp.isSuccessful) throw DriveHttpException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    @Throws(IOException::class)
    fun download(id: String, localTarget: File, onBytes: (Long) -> Unit = {}) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            authed { token ->
                client.newCall(req("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media", token)).execute().use { resp ->
                    if (!resp.isSuccessful) throw DriveHttpException(resp.code, "")
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
            }
            if (!tmp.renameTo(localTarget)) throw IOException("Cannot finalize download")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    @Throws(IOException::class)
    fun upload(local: File, parentId: String) {
        val meta = JSONObject()
            .put("name", local.name)
            .put("parents", JSONArray().put(parentId))
            .toString()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "metadata", meta.toRequestBody("application/json".toMediaTypeOrNull()))
            .addFormDataPart("file", local.name, local.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        authed { token ->
            client.newCall(
                Request.Builder()
                    .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw DriveHttpException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    private fun parseTime(value: String): Long {
        if (value.isEmpty()) return 0
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            sdf.parse(value)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private class DriveHttpException(val code: Int, val body: String) : IOException("HTTP $code")
}
