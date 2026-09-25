package com.alizz.filemanager.providers.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

const val OAUTH_REDIRECT_BASE = "fm-oauth"

data class OAuthTokens(val access: String, val refresh: String, val expiresAt: Long)

/** Shared OAuth2 authorization-code + PKCE helper for browser-based sign-in. */
object OAuthPkce {
    fun verifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }

    fun openBrowser(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Pending browser-auth results written by OAuthRedirectActivity, consumed by cloud screens. */
object OAuthPending {
    private const val PREFS = "oauth_pending"

    fun save(context: Context, provider: String, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(provider, code).apply()
    }

    fun take(context: Context, provider: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getString(provider, null) ?: return null
        prefs.edit().remove(provider).apply()
        return code
    }

    fun peek(context: Context, provider: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(provider, null)
}

class TokenHttp {    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun postForm(url: String, vararg fields: Pair<String, String>): JSONObject {
        val body = FormBody.Builder()
        for ((k, v) in fields) body.add(k, v)
        val req = Request.Builder().url(url).post(body.build()).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw java.io.IOException("Token request failed (${resp.code})")
            return JSONObject(text)
        }
    }
}
