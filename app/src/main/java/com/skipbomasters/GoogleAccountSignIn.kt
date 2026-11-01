package com.skipbomasters

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google identity helpers for Skip-Bo Masters.
 * Linking preserves an existing anonymous Firebase UID; sign-in restores an
 * already-linked Google account on another installation.
 */
internal object GoogleAccountSignIn {
    data class SignInResult(val user: FirebaseUser, val createdNewAccount: Boolean)

    suspend fun linkCurrentAccount(context: Context): FirebaseUser {
        val auth = FirebaseAuth.getInstance()
        val current = auth.currentUser
            ?: throw IllegalStateException("Er is geen spelersaccount om te koppelen.")

        if (current.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }) {
            return current
        }

        val credential = googleCredential(context)
        return try {
            current.linkWithCredential(credential).awaitResult().user
                ?: throw IllegalStateException("Firebase gaf geen spelersaccount terug.")
        } catch (error: FirebaseAuthUserCollisionException) {
            throw IllegalStateException(
                "Dit Google-account is al aan een ander Skip-Bo-account gekoppeld. Gebruik ‘Bestaand account herstellen’ om dat account te openen. Accounts worden niet automatisch samengevoegd.",
                error
            )
        }
    }

    suspend fun signInOrRestore(context: Context): SignInResult {
        val auth = FirebaseAuth.getInstance()
        val credential = googleCredential(context)
        val result = auth.signInWithCredential(credential).awaitResult()
        val user = result.user
            ?: throw IllegalStateException("Firebase gaf geen spelersaccount terug.")
        return SignInResult(
            user = user,
            createdNewAccount = result.additionalUserInfo?.isNewUser == true
        )
    }

    private suspend fun googleCredential(context: Context) = run {
        val webClientIdResource = context.resources.getIdentifier(
            "default_web_client_id", "string", context.packageName
        )
        if (webClientIdResource == 0) {
            throw IllegalStateException(
                "Google-aanmelden is nog niet geconfigureerd. Voeg eerst de bijgewerkte app/google-services.json toe met een Web OAuth-client-ID."
            )
        }
        val webClientId = context.getString(webClientIdResource)
        if (webClientId.isBlank()) {
            throw IllegalStateException("De Web OAuth-client-ID ontbreekt in Firebase-configuratie.")
        }

        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()
        val response = CredentialManager.create(context).getCredential(context, request)
        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Google heeft geen geldig ID-token teruggegeven.")
        }
        val googleToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        if (googleToken.isBlank()) {
            throw IllegalStateException("Het Google ID-token is leeg.")
        }
        GoogleAuthProvider.getCredential(googleToken, null)
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) {
                continuation.resume(task.result)
            } else {
                continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Firebase-aanmelding is mislukt.")
                )
            }
        }
    }
}
