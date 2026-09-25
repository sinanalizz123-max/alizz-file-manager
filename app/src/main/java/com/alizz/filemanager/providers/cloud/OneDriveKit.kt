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

private const val PREFS = "onedrive_store"
private const val KEY_CLIENT = "client_id"
private const val KEY_ACCESS = "access"
private const val KEY_REFRESH = "refresh"
private const val KEY_EXPIRY = "expiry"
private const val KEY_ACCOUNT = "account"

/**
 * Setup: register a mobile/native app in Entra (Azure) app registrations,
 * add redirect URI fm-oauth://onedrive, enable Files.ReadWrite delegated,
 * paste the Application (client) ID below. No secret needed (public client).
 */
class OneDriveAccountStore(context: Context) {
    private val app = context.applicationContext
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clientId(): String = prefs.getString(KEY_CLIENT, "").orEmpty()
    fun saveClientId(id: String) {
        prefs.edit().putString(KEY_CLIENT, id.trim()).apply()
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

data class OneDriveItem(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

/** Blocking OneDrive/Graph client. Call only from a background thread. */
class OneDriveSession(private val store: OneDriveAccountStore) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun authorizeUrl(clientId: String, verifier: String): String {
        store.prefs.edit().putString("verifier", verifier).apply()
        return "https://login.microsoftonline.com/common/oauth2/v2.0/authorize" +
            "?client_id=" + clientId.trim() +
            "&response_type=code" +
            "&redirect_uri=" + redirectUri() +
            "&response_mode=query" +
            "&scope=" + java.net.URLEncoder.encode("Files.ReadWrite offline_access", "UTF-8") +
            "&code_challenge=" + OAuthPkce.challenge(verifier) +
            "&code_challenge_method=S256"
    }

    fun redirectUri(): String = "$OAUTH_REDIRECT_BASE://onedrive"

    @Throws(IOException::class)
    fun finishAuth(clientId: String, code: String) {
        val verifier = store.prefs.getString("verifier", null) ?: throw IOException("Auth session expired")
        val json = TokenHttp().postForm(
            "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            "client_id" to clientId.trim(),
            "code" to code,
            "redirect_uri" to redirectUri(),
            "grant_type" to "authorization_code",
            "code_verifier" to verifier,
        )
        store.saveSession(
            "OneDrive",
            json.getString("access_token"),
            json.optString("refresh_token", ""),
            json.optLong("expires_in", 3600),
        )
    }

    @Throws(IOException::class)
    private fun token(): String {
        val access = store.prefs.getString(KEY_ACCESS, null)
        val expiry = store.prefs.getLong(KEY_EXPIRY, 0)
        if (access != null && System.currentTimeMillis() < expiry - 60_000) return access
        val refresh = store.prefs.getString(KEY_REFRESH, null) ?: throw IOException("Sign in again")
        val clientId = store.clientId()
        if (clientId.isEmpty()) throw IOException("Client ID missing")
        val json = TokenHttp().postForm(
            "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
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
            if (!resp.isSuccessful) throw IOException("OneDrive error (${resp.code})")
            return JSONObject(text)
        }
    }

    private fun itemUrl(id: String): String =
        if (id == "root") "https://graph.microsoft.com/v1.0/me/drive/root"
        else "https://graph.microsoft.com/v1.0/me/drive/items/$id"

    @Throws(IOException::class)
    fun list(folderId: String): List<OneDriveItem> {
        val out = mutableListOf<OneDriveItem>()
        var url: String? = itemUrl(folderId) + "/children?\$top=200"
        while (url != null) {
            val json = get(url)
            val arr: JSONArray = json.optJSONArray("value") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val folder = o.optJSONObject("folder")
                out += OneDriveItem(
                    id = o.getString("id"),
                    name = o.optString("name", "?"),
                    isDir = folder != null,
                    size = o.optLong("size", 0),
                    modified = 0,
                )
            }
            url = json.optString("@odata.nextLink", null)?.ifEmpty { null }
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    @Throws(IOException::class)
    fun mkdir(parentId: String, name: String) {
        val meta = JSONObject()
            .put("name", name)
            .put("folder", JSONObject())
            .put("@microsoft.graph.conflictBehavior", "fail")
            .toString()
        val req = Request.Builder()
            .url(itemUrl(parentId) + "/children")
            .header("Authorization", "Bearer ${token()}")
            .post(meta.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Create failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun rename(id: String, name: String) {
        patch(id, JSONObject().put("name", name).toString())
    }

    @Throws(IOException::class)
    private fun patch(id: String, meta: String) {
        val req = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me/drive/items/$id")
            .header("Authorization", "Bearer ${token()}")
            .patch(meta.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Request failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun delete(id: String) {
        val req = Request.Builder()
            .url("https://graph.microsoft.com/v1.0/me/drive/items/$id")
            .header("Authorization", "Bearer ${token()}")
            .delete()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Delete failed (${resp.code})")
        }
    }

    @Throws(IOException::class)
    fun download(id: String, name: String, localTarget: File) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            val req = Request.Builder()
                .url("https://graph.microsoft.com/v1.0/me/drive/items/$id/content")
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
        if (local.length() > 4L * 1024 * 1024) {
            throw IOException("Files over 4 MB need resumable upload (unsupported yet)")
        }
        val encoded = java.net.URLEncoder.encode(local.name, "UTF-8").replace("+", "%20")
        val url = itemUrl(parentId) + ":/" + encoded + ":/content"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token()}")
            .put(local.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Upload failed (${resp.code})")
        }
    }
}
