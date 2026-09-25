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
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS = "pcloud_store"
private const val KEY_ID = "client_id"
private const val KEY_SECRET = "client_secret"
private const val KEY_TOKEN = "token"
private const val KEY_ACCOUNT = "account"

/**
 * Setup: create a pCloud app, register redirect fm-oauth://pcloud,
 * paste the client ID (and secret if your app uses one).
 */
class PCloudAccountStore(context: Context) {
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

data class PCloudItem(val id: Long, val name: String, val path: String, val isDir: Boolean, val size: Long)

/** Blocking pCloud REST client. Call only from a background thread. */
class PCloudSession(private val store: PCloudAccountStore) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun authorizeUrl(clientId: String): String =
        "https://my.pcloud.com/oauth2/authorize" +
            "?client_id=" + clientId.trim() +
            "&response_type=code" +
            "&redirect_uri=" + redirectUri()

    fun redirectUri(): String = "$OAUTH_REDIRECT_BASE://pcloud"

    @Throws(IOException::class)
    fun finishAuth(clientId: String, secret: String, code: String) {
        var url = "https://api.pcloud.com/oauth2_token?client_id=" + clientId.trim() + "&code=" + code
        if (secret.isNotEmpty()) url += "&client_secret=" + secret.trim()
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful || json.optInt("result", 1) != 0) {
                throw IOException("Sign-in failed")
            }
            val token = json.getString("access_token")
            store.saveSession(accountEmail(token), token)
        }
    }

    private fun accountEmail(token: String): String {
        return try {
            val json = call("userinfo", token, emptyMap())
            json.optJSONObject("userinfo")?.optString("email", "pCloud") ?: "pCloud"
        } catch (e: Exception) {
            "pCloud"
        }
    }

    @Throws(IOException::class)
    private fun token(): String =
        store.token() ?: throw IOException("Sign in again")

    @Throws(IOException::class)
    private fun call(method: String, token: String, params: Map<String, String>): JSONObject {
        val url = buildString {
            append("https://api.pcloud.com/")
            append(method)
            append("?auth=")
            append(token)
            for ((k, v) in params) {
                append('&')
                append(k)
                append('=')
                append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("pCloud error (${resp.code})")
            val json = JSONObject(text)
            if (json.optInt("result", 1) != 0) {
                throw IOException(json.optString("error", "pCloud error"))
            }
            return json
        }
    }

    @Throws(IOException::class)
    private fun call(method: String, params: Map<String, String>): JSONObject =
        call(method, token(), params)

    @Throws(IOException::class)
    fun list(folderId: Long): List<PCloudItem> {
        val json = call("listfolder", mapOf("folderid" to folderId.toString()))
        val meta = json.optJSONObject("metadata") ?: return emptyList()
        val arr: JSONArray = meta.optJSONArray("contents") ?: JSONArray()
        val out = mutableListOf<PCloudItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dir = o.optBoolean("isfolder", false)
            out += PCloudItem(
                id = o.optLong("fileid", o.optLong("folderid", 0)),
                name = o.optString("name", "?"),
                path = o.optString("path", ""),
                isDir = dir,
                size = o.optLong("size", 0),
            )
        }
        return out.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    @Throws(IOException::class)
    fun mkdir(parentId: Long, name: String) {
        call("createfolder", mapOf("folderid" to parentId.toString(), "name" to name))
    }

    @Throws(IOException::class)
    fun rename(item: PCloudItem, name: String) {
        if (item.isDir) call("renamefolder", mapOf("folderid" to item.id.toString(), "toname" to name))
        else call("renamefile", mapOf("fileid" to item.id.toString(), "toname" to name))
    }

    @Throws(IOException::class)
    fun delete(item: PCloudItem) {
        if (item.isDir) call("deletefolderrecursive", mapOf("folderid" to item.id.toString()))
        else call("deletefile", mapOf("fileid" to item.id.toString()))
    }

    @Throws(IOException::class)
    fun download(item: PCloudItem, localTarget: File) {
        localTarget.parentFile?.mkdirs()
        val tmp = File(localTarget.parentFile, localTarget.name + ".part")
        try {
            val url = "https://api.pcloud.com/getfilelink?fileid=${item.id}&auth=${token()}"
            val linkReq = Request.Builder().url(url).get().build()
            val link = client.newCall(linkReq).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (!resp.isSuccessful || json.optInt("result", 1) != 0) throw IOException("Download link failed")
                val hosts: JSONArray = json.getJSONArray("hosts")
                "https://" + hosts.getString(0) + json.getString("path")
            }
            val req = Request.Builder().url(link).get().build()
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
    fun upload(local: File, folderId: Long) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", local.name, local.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val req = Request.Builder()
            .url("https://api.pcloud.com/uploadfile?folderid=$folderId&auth=${token()}")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful || json.optInt("result", 1) != 0) {
                throw IOException("Upload failed")
            }
        }
    }
}
