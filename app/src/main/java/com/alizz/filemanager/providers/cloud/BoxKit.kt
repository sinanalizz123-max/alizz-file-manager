package com.alizz.filemanager.providers.cloud

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "box_store"
private const val KEY_ID = "client_id"
private const val KEY_SECRET = "client_secret"
private const val KEY_ACCESS = "access"
private const val KEY_REFRESH = "refresh"
private const val KEY_EXPIRY = "expiry"
private const val KEY_ACCOUNT = "account"

/**
 * Setup: create a Box app (OAuth 2.0), register redirect fm-oauth://box,
 * paste client ID + secret below.
 */
class BoxAccountStore(context: Context) {
    private val app = context.applicationContext
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clientId(): String = prefs.getString(KEY_ID, "").orEmpty()
    fun clientSecret(): String = prefs.getString(KEY_SECRET, "").orEmpty()
    fun saveCredentials(id: String, secret: String) {
        prefs.edit()
            .putString(KEY_ID, id.ifEmpty { clientId() })
            .putString(KEY_SECRET, secret.ifEmpty { clientSecret() })
            .apply()
    }

    fun account(): String? = prefs.getString(KEY_ACCOUNT, null)

    fun saveSession(email: String, access: String, refresh: String, expiresIn: Long) {
        prefs.edit()
            .putString(KEY_ACCOUNT, email)
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putLong(KEY_EXPIRY, System.currentTimeMillis() + expiresIn * 1000)
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ACCOUNT).remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRY).apply()
    }
}

data class BoxItem(val id: String, val name: String, val isDir: Boolean, val size: Long)

/** Blocking Box REST client. Call only from a background thread. */
class BoxSession(private val store: BoxAccountStore) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun authorizeUrl(clientId: String): String =
        "https://account.box.com/api/oauth2/authorize" +
            "?client_id=" + clientId.trim() +
            "&response_type=code" +
            "&redirect_uri=" + redirectUri()

    fun redirectUri(): String = "$OAUTH_REDIRECT_BASE://box"

    @Throws(IOException::class)
    fun finishAuth(clientId: String, secret: String, code: String) {
        val json = TokenHttp().postForm(
            "https://api.box.com/oauth2/token",
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to clientId.trim(),
            "client_secret" to secret.trim(),
            "redirect_uri" to redirectUri(),
        )
        store.saveSession(
            accountEmail(json.getString("access_token")),
            json.getString("access_token"),
            json.optString("refresh_token", ""),
            json.optLong("expires_in", 3600),
        )
    }

    private fun accountEmail(access: String): String {
        val req = Request.Builder()
            .url("https://api.box.com/2.0/users/me?fields=login")
            .header("Authorization", "Bearer $access")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return "Box"
            return JSONObject(resp.body?.string().orEmpty()).optString("login", "Box")
        }
    }

    @Throws(IOException::class)
    private fun token(): String {
        val access = store.prefs.getString(KEY_ACCESS, null)
        val expiry = store.prefs.getLong(KEY_EXPIRY, 0)
        if (access != null && System.currentTimeMillis() < expiry - 60_000) return access
        val refresh = store.prefs.getString(KEY_REFRESH, null) ?: throw IOException("Sign in again")
        val json = TokenHttp().postForm(
            "https://api.box.com/oauth2/token",
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to store.clientId(),
            "client_secret" to store.clientSecret(),
        )
        val fresh = json.getString("access_token")
        store.prefs.edit()
            .putString(KEY_ACCESS, fresh)
            .putLong(KEY_EXPIRY, System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000)
            .apply()
        return fresh
    }

    @Throws(IOException::class)
    private fun get(url: String): JSONObject {
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${token()}").get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Box error (${resp.code})")
            return JSONObject(text)
        }
    }

    @Throws(IOException::class)
    fun list(folderId: String): List<BoxItem> {
        val out = mutableListOf<BoxItem>()
        var offset = 0
        while (true) {
            val json = get("https://api.box.com/2.0/folders/$folderId/items?limit=500&offset=$offset&fields=name,size,type")
            val arr: JSONArray = json.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += BoxItem(
                    id = o.getString("id"),
                    name = o.optString("name", "?"),
                    isDir = o.optString("type") == "folder",
                    size = o.optLong("size", 0),
                )
            }
            offset += arr.length()
            if (arr.length() < 500 || offset >= json.optLong("total_count", 0)) break
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    @Throws(IOException::class)
    fun mkdir(parentId: String, name: String) {
        val meta = JSONObject().put("name", name).put("parent", JSONObject().put("id", parentId)).toString()
        val req = Request.Builder()
            .url("https://api.box.com/2.0/folders")
            .header("Authorization", "Bearer ${token()}")
            .post(meta.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Create failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun rename(item: BoxItem, name: String) {
        val kind = if (item.isDir) "folders" else "files"
        val req = Request.Builder()
            .url("https://api.box.com/2.0/$kind/${item.id}")
            .header("Authorization", "Bearer ${token()}")
            .put(JSONObject().put("name", name).toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Rename failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun delete(item: BoxItem) {
        val kind = if (item.isDir) "folders" else "files"
        val url = "https://api.box.com/2.0/$kind/${item.id}" + (if (item.isDir) "?recursive=true" else "")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token()}")
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Delete failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun download(item: BoxItem, localTarget: File) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            val req = Request.Builder()
                .url("https://api.box.com/2.0/files/${item.id}/content")
                .header("Authorization", "Bearer ${token()}")
                .get()
                .build()
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
    fun upload(local: File, parentId: String) {
        val meta = JSONObject().put("name", local.name).put("parent", JSONObject().put("id", parentId)).toString()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("attributes", "attributes", meta.toRequestBody("application/json".toMediaTypeOrNull()))
            .addFormDataPart("file", local.name, local.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val req = Request.Builder()
            .url("https://upload.box.com/api/2.0/files/content")
            .header("Authorization", "Bearer ${token()}")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Upload failed (${resp.code})")
        }
    }
}
