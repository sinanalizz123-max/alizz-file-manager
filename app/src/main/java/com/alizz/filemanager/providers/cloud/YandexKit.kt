package com.alizz.filemanager.providers.cloud

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "yandex_store"
private const val KEY_ID = "client_id"
private const val KEY_SECRET = "client_secret"
private const val KEY_TOKEN = "token"
private const val KEY_ACCOUNT = "account"

/**
 * Setup: create an OAuth app at oauth.yandex.com, platforms → add
 * redirect fm-oauth://yandex, request Yandex.Disk API access,
 * paste client ID + secret below.
 */
class YandexAccountStore(context: Context) {
    private val app = context.applicationContext
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clientId(): String = prefs.getString(KEY_ID, "").orEmpty()
    fun clientSecret(): String = prefs.getString(KEY_SECRET, "").orEmpty()
    fun saveCredentials(id: String, secret: String) {
        prefs.edit().putString(KEY_ID, id.trim()).putString(KEY_SECRET, secret.trim()).apply()
    }

    fun account(): String? = prefs.getString(KEY_ACCOUNT, null)
    fun token(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveSession(email: String, token: String) {
        prefs.edit().putString(KEY_ACCOUNT, email).putString(KEY_TOKEN, token).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ACCOUNT).remove(KEY_TOKEN).apply()
    }
}

data class YandexItem(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

/** Blocking Yandex Disk REST client. Call only from a background thread. */
class YandexSession(private val store: YandexAccountStore) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun authorizeUrl(clientId: String): String =
        "https://oauth.yandex.com/authorize" +
            "?client_id=" + clientId.trim() +
            "&response_type=code"

    fun redirectUri(): String = "$OAUTH_REDIRECT_BASE://yandex"

    @Throws(IOException::class)
    fun finishAuth(clientId: String, secret: String, code: String) {
        val json = TokenHttp().postForm(
            "https://oauth.yandex.com/token",
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to clientId.trim(),
            "client_secret" to secret.trim(),
        )
        val token = json.getString("access_token")
        store.saveSession(accountEmail(token), token)
    }

    private fun accountEmail(token: String): String {
        return try {
            val req = Request.Builder()
                .url("https://login.yandex.ru/info?format=json&oauth_token=$token")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return "Yandex Disk"
                JSONObject(resp.body?.string().orEmpty()).optString("default_email", "Yandex Disk")
            }
        } catch (e: Exception) {
            "Yandex Disk"
        }
    }

    @Throws(IOException::class)
    private fun token(): String =
        store.token() ?: throw IOException("Sign in again")

    @Throws(IOException::class)
    private fun get(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "OAuth ${token()}")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Yandex error (${resp.code})")
            return JSONObject(text)
        }
    }

    @Throws(IOException::class)
    fun list(path: String): List<YandexItem> {
        val enc = java.net.URLEncoder.encode(if (path.isEmpty()) "/" else path, "UTF-8")
        val json = get("https://cloud-api.yandex.net/v1/disk/resources?path=$enc&limit=500&fields=_embedded.items(name,path,type,size,modified)")
        val arr: JSONArray = json.optJSONObject("_embedded")?.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<YandexItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val full = o.optString("path", "")
            out += YandexItem(
                name = o.optString("name", "?"),
                path = full.removePrefix("disk:"),
                isDir = o.optString("type") == "dir",
                size = o.optLong("size", 0),
                modified = 0,
            )
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    @Throws(IOException::class)
    fun mkdir(parent: String, name: String) {
        val dest = ((if (parent.isEmpty()) "" else parent) + "/" + name).replace("//", "/")
        val req = Request.Builder()
            .url("https://cloud-api.yandex.net/v1/disk/resources?path=" + java.net.URLEncoder.encode(dest, "UTF-8"))
            .header("Authorization", "OAuth ${token()}")
            .put("{}".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Create failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun rename(from: String, name: String) {
        val parent = if ('/' !in from) "" else from.substringBeforeLast('/')
        val dest = (if (parent.isEmpty()) "" else parent) + "/" + name
        moveOp(from, dest, overwrite = false)
    }

    @Throws(IOException::class)
    private fun moveOp(from: String, dest: String, overwrite: Boolean) {
        val url = "https://cloud-api.yandex.net/v1/disk/resources/move" +
            "?from=" + java.net.URLEncoder.encode(from, "UTF-8") +
            "&path=" + java.net.URLEncoder.encode(dest, "UTF-8") +
            "&overwrite=$overwrite"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "OAuth ${token()}")
            .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Move failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun delete(item: YandexItem) {
        val req = Request.Builder()
            .url("https://cloud-api.yandex.net/v1/disk/resources?path=" + java.net.URLEncoder.encode(item.path, "UTF-8") + "&permanently=false")
            .header("Authorization", "OAuth ${token()}")
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Delete failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun download(item: YandexItem, localTarget: File) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            val href = get(
                "https://cloud-api.yandex.net/v1/disk/resources/download?path=" +
                    java.net.URLEncoder.encode(item.path, "UTF-8"),
            ).getString("href")
            val req = Request.Builder().url(href).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (${resp.code})")
                val body = resp.body ?: throw IOException("Empty response")
                tmp.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = body.byteStream().read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
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
    fun upload(local: File, remoteDir: String) {
        val dest = ((if (remoteDir.isEmpty()) "" else remoteDir) + "/" + local.name).replace("//", "/")
        val href = get(
            "https://cloud-api.yandex.net/v1/disk/resources/upload?path=" +
                java.net.URLEncoder.encode(dest, "UTF-8") + "&overwrite=false",
        ).getString("href")
        val req = Request.Builder()
            .url(href)
            .put(local.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Upload failed (${resp.code})")
        }
    }
}
