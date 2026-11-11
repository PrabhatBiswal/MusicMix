package com.prabhat.djmix.auth

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Step 1 of sign-in: Credential Manager shows Google's account picker and returns an ID token for the chosen account.
 * It needs an Activity (it puts UI on screen), which is why it lives here and not in a ViewModel or repository:
 * the screen calls this, then hands the token to [AuthRepository.signIn].
 *
 * The web client id comes from google-services.json (the google-services plugin turns it into the string resource
 * `default_web_client_id`) once Google sign-in is enabled in the Firebase console. It is looked up by name so the app
 * still builds before that; sign-in then fails with a clear message instead.
 */
suspend fun googleIdToken(activity: Activity): String {
    val res = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
    check(res != 0) { "Google sign-in is not set up for this build yet (Firebase console: enable Google, add the SHA-1, re-download google-services.json)." }
    val option = GetSignInWithGoogleOption.Builder(activity.getString(res)).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = CredentialManager.create(activity).getCredential(activity, request).credential
    check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) { "Google did not return an account" }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}
