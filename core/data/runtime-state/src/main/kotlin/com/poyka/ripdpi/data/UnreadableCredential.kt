package com.poyka.ripdpi.data

import android.util.Log

/** A sealed credential is stored but cannot be decrypted, for example after a Keystore key loss. */
class UnreadableCredentialException(
    cause: Throwable,
) : IllegalStateException("Stored credential cannot be decrypted", cause)

/**
 * Loads a credential that the caller is about to replace or show for re-entry. An unreadable value
 * counts as absent here only, so the user can always overwrite it; other reads stay strict.
 */
suspend fun <T : Any> loadReplaceableCredential(load: suspend () -> T?): T? =
    try {
        load()
    } catch (error: UnreadableCredentialException) {
        logUnreadableCredential("treated as replaceable", error)
        null
    }

/** Class names only: a credential failure must not put identifiers or secrets in logcat. */
fun logUnreadableCredential(
    action: String,
    error: UnreadableCredentialException,
) {
    Log.w(UnreadableCredentialLogTag, "Unreadable stored credential $action: ${error.cause?.javaClass?.name}")
}

private const val UnreadableCredentialLogTag = "CredentialStore"
