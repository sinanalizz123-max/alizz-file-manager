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

private const val PREFS = "dropbox_store"
private const val KEY_APP_KEY = "app_key"
private const val KEY_ACCESS = "access"
private const val KEY_REFRESH = "refresh"
private const val KEY_EXPIRY = "expiry"
private const val KEY_ACCOUNT = "account"

/**
 * Setup: create a Dropbox app (scoped, full-Dropbox or app-folder) and paste
 * the app key below. Register redirect URI: fm-oauth://dropbox
 */
class DropboxAccountStore(context: Context) {
    private val app = context.applicationContext
    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun appKey(): String = prefs.getString(KEY_APP_KEY, "").orEmpty()
    fun saveAppKey(key: String) {
        prefs.edit().putString(KEY_APP_KEY, key.trim()).apply()
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

data class DropboxItem(val id: String, val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long)

/**
 * Blocking Dropbox REST client. Call only from a background thread.
 */
class DropboxSession(private val store: DropboxAccountStore) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun authorizeUrl(appKey: String, verifier: String): String {
        val p = store.prefs
        p.edit().putString("verifier", verifier).apply()
        val challenge = OAuthPkce.challenge(verifier)
        return "https://www.dropbox.com/oauth2/authorize" +
            "?client_id=" + appKey.trim() +
            "&response_type=code" +
            "&token_access_type=offline" +
            "&code_challenge=" + challenge +
            "&code_challenge_method=S256" +
            "&redirect_uri=" + redirectUri()
    }

    fun redirectUri(): String = "$OAUTH_REDIRECT_BASE://dropbox"

    @Throws(IOException::class)
    fun finishAuth(appKey: String, code: String) {
        val verifier = store.prefs.getString("verifier", null) ?: throw IOException("Auth session expired")
        val json = TokenHttp().postForm(
            "https://api.dropboxapi.com/oauth2/token",
            "code" to code,
            "grant_type" to "authorization_code",
            "client_id" to appKey.trim(),
            "code_verifier" to verifier,
            "redirect_uri" to redirectUri(),
        )
        val email = try {
            accountEmail(json.getString("access_token"))
        } catch (e: Exception) {
            "Dropbox"
        }
        store.saveSession(
            email,
            json.getString("access_token"),
            json.optString("refresh_token", ""),
            json.optLong("expires_in", 14_400),
        )
    }

    private fun accountEmail(access: String): String {
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/users/get_current_account")
            .header("Authorization", "Bearer $access")
            .post("{}".toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Account lookup failed")
            return JSONObject(resp.body?.string().orEmpty()).optString("email", "Dropbox")
        }
    }

    @Throws(IOException::class)
    private fun token(): String {
        val access = store.prefs.getString(KEY_ACCESS, null)
        val expiry = store.prefs.getLong(KEY_EXPIRY, 0)
        if (access != null && System.currentTimeMillis() < expiry - 60_000) return access
        val refresh = store.prefs.getString(KEY_REFRESH, null) ?: throw IOException("Sign in again")
        val appKey = store.appKey()
        if (appKey.isEmpty()) throw IOException("App key missing")
        val json = TokenHttp().postForm(
            "https://api.dropboxapi.com/oauth2/token",
            "grant_type" to "refresh_token",
            "refresh_token" to refresh,
            "client_id" to appKey,
        )
        val fresh = json.getString("access_token")
        store.prefs.edit()
            .putString(KEY_ACCESS, fresh)
            .putLong(KEY_EXPIRY, System.currentTimeMillis() + json.optLong("expires_in", 14_400) * 1000)
            .apply()
        return fresh
    }

    @Throws(IOException::class)
    private fun rpc(endpoint: String, arg: JSONObject): JSONObject {
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/$endpoint")
            .header("Authorization", "Bearer ${token()}")
            .post(arg.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Dropbox error (${resp.code})")
            return JSONObject(text)
        }
    }

    @Throws(IOException::class)
    fun list(path: String): List<DropboxItem> {
        val out = mutableListOf<DropboxItem>()
        var cursor: String? = null
        var hasMore = true
        var first = true
        while (hasMore) {
            val json = if (first) {
                first = false
                rpc("files/list_folder", JSONObject().put("path", path).put("recursive", false).put("limit", 500))
            } else {
                rpc("files/list_folder/continue", JSONObject().put("cursor", cursor))
            }
            val entries: JSONArray = json.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until entries.length()) {
                val o = entries.getJSONObject(i)
                val tag = o.optString(".tag")
                out += DropboxItem(
                    id = o.optString("id", o.optString("path_lower", "")),
                    name = o.optString("name", "?"),
                    path = o.optString("path_lower", ""),
                    isDir = tag == "folder",
                    size = o.optLong("size", 0),
                    modified = 0,
                )
            }
            hasMore = json.optBoolean("has_more", false)
            cursor = json.optString("cursor", null)
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    @Throws(IOException::class)
    fun mkdir(path: String, name: String) {
        rpc("files/create_folder_v2", JSONObject().put("path", joinPath(path, name)).put("autorename", false))
    }

    @Throws(IOException::class)
    fun rename(from: String, to: String) {
        rpc("files/move_v2", JSONObject().put("from_path", from).put("to_path", to).put("autorename", false))
    }

    @Throws(IOException::class)
    fun delete(item: DropboxItem) {
        if (item.isDir) {
            for (child in list(item.path)) delete(child)
        }
        rpc("files/delete_v2", JSONObject().put("path", item.path))
    }

    @Throws(IOException::class)
    fun download(item: DropboxItem, localTarget: File) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            val req = Request.Builder()
                .url("https://content.dropboxapi.com/2/files/download")
                .header("Authorization", "Bearer ${token()}")
                .header("Dropbox-API-Arg", JSONObject().put("path", item.path).toString())
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
    fun upload(local: File, remoteDir: String) {
        val dest = joinPath(remoteDir, local.name)
        val arg = JSONObject().put("path", dest).put("mode", "add").put("autorename", true).toString()
        val req = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/upload")
            .header("Authorization", "Bearer ${token()}")
            .header("Dropbox-API-Arg", arg)
            .header("Content-Type", "application/octet-stream")
            .post(local.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Upload failed (${resp.code})")
        }
    }

    private fun joinPath(dir: String, name: String): String =
        (if (dir.isEmpty()) "" else dir) + "/" + name
}
