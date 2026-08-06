package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

internal class ProfileMutationJournalRecoveryReader(
    private val journal: ProfileMutationJournal,
) {
    private val json = RipDpiContractJson
    private var observedCorruptionIdentity: String? = null

    suspend fun read(): RecoverablePendingMutation? {
        val pending = readPendingOrHandleCorruption()
        return pending?.let {
            check(
                it.schemaVersion == ProfileMutationIntentSchemaVersion,
            ) { "Unsupported profile mutation journal" }
            decodePendingOrHandleCorruption(it)?.let { intent ->
                observedCorruptionIdentity = null
                RecoverablePendingMutation(it, intent)
            }
        }
    }

    private suspend fun readPendingOrHandleCorruption(): PendingProfileMutation? =
        try {
            journal.pending().also { pending ->
                if (pending == null) observedCorruptionIdentity = null
            }
        } catch (error: ProfileMutationJournalCorruptionException) {
            handleCorruptPending(UnreadableJournalIdentity, error)
            null
        }

    private suspend fun decodePendingOrHandleCorruption(pending: PendingProfileMutation): ProfileMutationIntent? =
        try {
            val intent = json.decodeFromString(ProfileMutationIntent.serializer(), pending.payload)
            if (intent.family == pending.family) {
                intent
            } else {
                handleCorruptPending(
                    identity = pending.mutationId,
                    error = ProfileMutationJournalCorruptionException("Profile mutation journal family mismatch"),
                )
                null
            }
        } catch (error: SerializationException) {
            handleCorruptPending(
                identity = pending.mutationId,
                error =
                    ProfileMutationJournalCorruptionException(
                        message = "Profile mutation journal payload is unreadable",
                        cause = error,
                    ),
            )
            null
        }

    private suspend fun handleCorruptPending(
        identity: String,
        error: ProfileMutationJournalCorruptionException,
    ) {
        if (observedCorruptionIdentity != identity) {
            observedCorruptionIdentity = identity
            throw error
        }
        val discardFailure =
            runCatching {
                withContext(NonCancellable) { journal.discardCorruptPending() }
            }.exceptionOrNull()
        if (discardFailure != null) {
            error.addSuppressed(discardFailure)
            throw error
        }
        observedCorruptionIdentity = null
    }

    private companion object {
        const val UnreadableJournalIdentity = "unreadable-journal-marker"
    }
}

internal data class RecoverablePendingMutation(
    val pending: PendingProfileMutation,
    val intent: ProfileMutationIntent,
)
