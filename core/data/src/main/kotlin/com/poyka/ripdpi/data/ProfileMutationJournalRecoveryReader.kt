package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.serialization.SerializationException

/** A corrupt intent cannot prove which stores committed; only explicit reset may discard it. */
internal class ProfileMutationJournalRecoveryReader(
    private val journal: ProfileMutationJournal,
    private val groups: ProxyGroupCatalog,
) {
    private val json = RipDpiContractJson

    suspend fun read(): RecoverablePendingMutation? {
        val pending = journal.pending() ?: return null
        check(
            pending.schemaVersion in setOf(1, ProfileMutationIntentSchemaVersion),
        ) { "Unsupported profile mutation journal" }
        if (pending.schemaVersion == 2 && (pending.origin == null || pending.expectedPauseAuthority == null)) {
            throw ProfileMutationJournalCorruptionException("Profile mutation provenance is missing")
        }
        val intent = decode(pending)
        if (intent.family != pending.family) {
            throw ProfileMutationJournalCorruptionException("Profile mutation journal family mismatch")
        }
        return if (intent is SubscriptionRefreshIntent) {
            readSubscription(pending, intent)
        } else {
            RecoverablePendingMutation(pending, intent)
        }
    }

    private suspend fun readSubscription(
        pending: PendingProfileMutation,
        intent: SubscriptionRefreshIntent,
    ): RecoverablePendingMutation {
        groups.snapshot()
        check(intent.ownershipVersion in 0..1) { "Unsupported subscription ownership version" }
        if (intent.ownershipVersion == 0) {
            val upgraded =
                intent.copy(
                    groups = intent.groups.map { it.withNewOwnershipGeneration() },
                    ownershipVersion = 1,
                )
            val migrated = pending.copy(payload = json.encodeToString(ProfileMutationIntent.serializer(), upgraded))
            journal.replace(pending.mutationId, migrated)
            return RecoverablePendingMutation(migrated, upgraded)
        }
        check(intent.groups.all { it.ownershipGeneration.isNotBlank() }) { "Missing subscription ownership generation" }
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
