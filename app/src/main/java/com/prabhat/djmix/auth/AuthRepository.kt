package com.prabhat.djmix.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is signed in. Signing in is optional: the app works fully offline and local; a Google account only unlocks
 * the shared cloud library (and only for accounts on the invite list in storage.rules). [user] is a StateFlow, so every screen that
 * collects it updates by itself when someone signs in or out.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val auth: FirebaseAuth?, // null in a build without Firebase
    @ApplicationContext private val app: Context,
) {
    data class User(val uid: String, val name: String?, val email: String?)

    private val _user = MutableStateFlow(auth?.currentUser?.toUser())
    val user: StateFlow<User?> = _user.asStateFlow()

    /** Firebase calls this on sign-in, sign-out and at start (restored session). */
    init { auth?.addAuthStateListener { _user.value = it.currentUser?.toUser() } }

    /** The signed-in user's id, or null: the cloud is off without one. */
    val uid: String? get() = auth?.currentUser?.uid

    /** Step 2 of sign-in: trade the Google ID token (from [googleIdToken]) for a Firebase user. */
    suspend fun signIn(googleIdToken: String) {
        checkNotNull(auth) { "This build has no cloud (no google-services.json)" }
            .signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null)).await()
    }

    /** Signs out of Firebase and tells Credential Manager, so the next sign-in shows the account picker again. */
    suspend fun signOut() {
        auth?.signOut()
        runCatching { CredentialManager.create(app).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private fun FirebaseUser.toUser() = User(uid, displayName, email)
}
