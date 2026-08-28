package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.serialization.SerializationException

/** A corrupt intent cannot prove which stores committed; only explicit reset may discard it. */
internal class ProfileMutationJournalRecoveryReader(
    private val journal: ProfileMutationJournal,
) {
    private val json = RipDpiContractJson

    suspend fun read(): RecoverablePendingMutation? {
        val pending = journal.pending() ?: return null
        check(pending.schemaVersion == ProfileMutationIntentSchemaVersion) { "Unsupported profile mutation journal" }
        val intent = decode(pending)
        if (intent.family != pending.family) {
            throw ProfileMutationJournalCorruptionException("Profile mutation journal family mismatch")
        }
        return RecoverablePendingMutation(pending, intent)
    }

    private fun decode(pending: PendingProfileMutation): ProfileMutationIntent =
        try {
            json.decodeFromString(ProfileMutationIntent.serializer(), pending.payload)
        } catch (error: SerializationException) {
            throw ProfileMutationJournalCorruptionException("Profile mutation journal payload is unreadable", error)
        }
}

internal data class RecoverablePendingMutation(
    val pending: PendingProfileMutation,
    val intent: ProfileMutationIntent,
)
