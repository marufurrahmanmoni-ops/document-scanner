package com.docscanner.app.cloud

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.docscanner.app.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** What the UI needs to know about the signed-in user. Nothing else about the account is stored locally. */
data class Account(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val emailVerified: Boolean,
    val usesPassword: Boolean,
    val usesGoogle: Boolean,
)

enum class AuthError {
    NOT_CONFIGURED, INVALID_CREDENTIALS, EMAIL_IN_USE, WEAK_PASSWORD, INVALID_EMAIL, NETWORK, TOO_MANY_ATTEMPTS,
    CANCELLED, NO_GOOGLE_ACCOUNT, RECENT_LOGIN_REQUIRED, UNKNOWN,
}

class AuthException(val error: AuthError, cause: Throwable? = null) : Exception(error.name, cause)

/**
 * Firebase Authentication: email + password (with verification and reset) and Google via Credential Manager.
 * The only profile data written to Firestore is the display name and timestamps (users/{uid}).
 */
class AuthRepository(private val cloud: CloudServices?) {

    private val _account = MutableStateFlow(cloud?.auth?.currentUser?.toAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()

    init {
        cloud?.auth?.addAuthStateListener { _account.value = it.currentUser?.toAccount() }
    }

    val available: Boolean get() = cloud != null

    fun currentUid(): String? = cloud?.auth?.currentUser?.uid

    suspend fun signUp(name: String, email: String, password: String) = guard {
        val auth = requireCloud().auth
        val user = auth.createUserWithEmailAndPassword(email.trim(), password).await().user
            ?: throw AuthException(AuthError.UNKNOWN)
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
        user.sendEmailVerification().await()
        writeProfile(user, name.trim(), isNew = true)
        refresh()
    }

    suspend fun signIn(email: String, password: String) = guard {
        requireCloud().auth.signInWithEmailAndPassword(email.trim(), password).await()
        refresh()
    }

    suspend fun sendPasswordReset(email: String) = guard {
        requireCloud().auth.sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun resendVerification() = guard {
        requireCloud().auth.currentUser?.sendEmailVerification()?.await()
    }

    /** Re-reads the user from the server (picks up a just-clicked verification link). */
    suspend fun refresh() = guard {
        val user = requireCloud().auth.currentUser
        user?.reload()?.await()
        _account.value = requireCloud().auth.currentUser?.toAccount()
    }

    /** Google sign-in through Credential Manager, then exchanged for a Firebase credential. Needs an Activity context. */
    suspend fun signInWithGoogle(activityContext: Context) = guard {
        val cloud = requireCloud()
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) throw AuthException(AuthError.NOT_CONFIGURED)
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val result = CredentialManager.create(activityContext)
            .getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = result.credential
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw AuthException(AuthError.UNKNOWN)
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val authResult = cloud.auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        val user = authResult.user ?: throw AuthException(AuthError.UNKNOWN)
        writeProfile(user, user.displayName.orEmpty(), isNew = authResult.additionalUserInfo?.isNewUser == true)
        refresh()
    }

    suspend fun updateDisplayName(name: String) = guard {
        val user = requireCloud().auth.currentUser ?: throw AuthException(AuthError.UNKNOWN)
        user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name.trim()).build()).await()
        writeProfile(user, name.trim(), isNew = false)
        refresh()
    }

    suspend fun signOut(context: Context) {
        cloud?.auth?.signOut()
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        _account.value = null
    }

    /** Signs the user in again (password accounts) before a sensitive action such as account deletion. */
    suspend fun reauthenticateWithPassword(password: String) = guard {
        val user = requireCloud().auth.currentUser ?: throw AuthException(AuthError.UNKNOWN)
        val email = user.email ?: throw AuthException(AuthError.UNKNOWN)
        user.reauthenticate(com.google.firebase.auth.EmailAuthProvider.getCredential(email, password)).await()
    }

    /** Re-authenticates a Google account through Credential Manager. */
    suspend fun reauthenticateWithGoogle(activityContext: Context) = guard {
        val user = requireCloud().auth.currentUser ?: throw AuthException(AuthError.UNKNOWN)
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(true)
            .build()
        val result = CredentialManager.create(activityContext)
            .getCredential(activityContext, GetCredentialRequest.Builder().addCredentialOption(option).build())
        val idToken = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        user.reauthenticate(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    internal fun requireCloud(): CloudServices = cloud ?: throw AuthException(AuthError.NOT_CONFIGURED)

    private suspend fun writeProfile(user: FirebaseUser, name: String, isNew: Boolean) {
        val data = hashMapOf<String, Any>(
            "displayName" to name.take(80),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (isNew) data["createdAt"] = FieldValue.serverTimestamp()
        requireCloud().firestore.collection("users").document(user.uid).set(data, SetOptions.merge()).await()
    }

    private inline fun <T> guard(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: AuthException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AuthException(map(e), e))
    }

    private fun map(e: Exception): AuthError = when (e) {
        is FirebaseAuthWeakPasswordException -> AuthError.WEAK_PASSWORD
        is FirebaseAuthUserCollisionException -> AuthError.EMAIL_IN_USE
        is FirebaseAuthInvalidUserException -> AuthError.INVALID_CREDENTIALS
        is FirebaseAuthRecentLoginRequiredException -> AuthError.RECENT_LOGIN_REQUIRED
        is FirebaseAuthInvalidCredentialsException ->
            if (e.errorCode == "ERROR_INVALID_EMAIL") AuthError.INVALID_EMAIL else AuthError.INVALID_CREDENTIALS
        is FirebaseNetworkException -> AuthError.NETWORK
        is FirebaseTooManyRequestsException -> AuthError.TOO_MANY_ATTEMPTS
        is GetCredentialCancellationException -> AuthError.CANCELLED
        is NoCredentialException -> AuthError.NO_GOOGLE_ACCOUNT
        else -> AuthError.UNKNOWN
    }

    private fun FirebaseUser.toAccount() = Account(
        uid = uid,
        email = email,
        displayName = displayName,
        emailVerified = isEmailVerified,
        usesPassword = providerData.any { it.providerId == "password" },
        usesGoogle = providerData.any { it.providerId == "google.com" },
    )
}
