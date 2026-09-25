package com.alizz.filemanager.providers.cloud

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Catches OAuth browser redirects (fm-oauth://<provider>?code=…),
 * stashes the code, and closes. The cloud screens pick it up
 * via OAuthPending when the user taps Continue.
 */
class OAuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val provider = uri?.host
        val code = uri?.getQueryParameter("code")
        val error = uri?.getQueryParameter("error")
        if (!provider.isNullOrEmpty() && !code.isNullOrEmpty()) {
            OAuthPending.save(this, provider, code)
            Toast.makeText(this, "Approved — return to File Manager and tap Continue", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Sign-in " + (error ?: "cancelled"), Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
