package com.bletracker.app.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Wraps the Credential Manager API to perform a Google Sign-In and return
 * the raw ID token as a string.
 *
 * The returned ID token should be sent to the backend `/api/auth/google`
 * endpoint for server-side verification.
 *
 * @param activity The activity hosting the sign-in flow. Must be in the
 *                 foreground when [signIn] is called.
 */
class GoogleSignInManager(private val activity: Activity) {
    /**
     * Prompt the user to choose a Google account and return the resulting
     * ID token.
     *
     * @param serverClientId The OAuth 2.0 web client ID configured in the
     *                       Google Cloud Console for the backend server.
     * @return The raw Google ID token string.
     * @throws IllegalStateException If the credential result cannot be parsed
     *                               as a Google ID token.
     */
    suspend fun signIn(serverClientId: String): String {
        val credentialManager = CredentialManager.create(activity)
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(serverClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val result = credentialManager.getCredential(activity, request)
        return try {
            GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        } catch (error: GoogleIdTokenParsingException) {
            throw IllegalStateException("Failed to parse Google ID token", error)
        }
    }
}
