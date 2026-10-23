package com.poyka.ripdpi.data

import com.poyka.ripdpi.serialization.RipDpiContractJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        val migrated = migrateStoredPolicy(pending)
        val intent = decode(migrated)
        if (intent.family != pending.family) {
            throw ProfileMutationJournalCorruptionException("Profile mutation journal family mismatch")
        }
        return if (intent is SubscriptionRefreshIntent) {
            readSubscription(pending, migrated, intent)
        } else {
            RecoverablePendingMutation(pending, intent)
        }
    }

    private suspend fun readSubscription(
        pending: PendingProfileMutation,
        migrated: PendingProfileMutation,
        intent: SubscriptionRefreshIntent,
    ): RecoverablePendingMutation {
        groups.snapshot()
        check(intent.ownershipVersion in 0..1) { "Unsupported subscription ownership version" }
        val accepted =
            if (intent.ownershipVersion == 0) {
                migrateOwnership(migrated, intent)
            } else {
                check(
                    intent.groups.all { it.ownershipGeneration.isNotBlank() },
                ) { "Missing subscription ownership generation" }
                migrated
            }
        val acceptedIntent = decode(accepted)
        if (accepted != pending) journal.replace(pending.mutationId, accepted)
        return RecoverablePendingMutation(accepted, acceptedIntent)
    }

    /** Only encrypted local after-images qualify; external import keeps its strict current schema. */
    private fun migrateStoredPolicy(pending: PendingProfileMutation): PendingProfileMutation {
        if (pending.family != ProfileMutationFamily.Subscription) return pending
        val payload = json.parseToJsonElement(pending.payload).jsonObject
        return if (payload["type"]?.jsonPrimitive?.content == "subscription_refresh") {
            val storedGroups = payload.getValue("groups").toString()
            val migratedGroups = migrateStoredHealthPolicy(storedGroups)
            if (migratedGroups == storedGroups) {
                pending
            } else {
                pending.copy(
                    payload = JsonObject(payload + ("groups" to json.parseToJsonElement(migratedGroups))).toString(),
                )
            }
        } else {
            pending
        }
    }

    private fun migrateOwnership(
        pending: PendingProfileMutation,
        intent: SubscriptionRefreshIntent,
    ): PendingProfileMutation {
        val payload = json.parseToJsonElement(pending.payload).jsonObject
        val migratedGroups =
            payload.getValue("groups").jsonArray.zip(intent.groups) { element, group ->
                val owner = group.withNewOwnershipGeneration().ownershipGeneration
                JsonObject(element.jsonObject + ("ownershipGeneration" to JsonPrimitive(owner)))
            }
        return pending.copy(
            payload =
                JsonObject(
                    payload + mapOf("groups" to JsonArray(migratedGroups), "ownershipVersion" to JsonPrimitive(1)),
                ).toString(),
        )
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
