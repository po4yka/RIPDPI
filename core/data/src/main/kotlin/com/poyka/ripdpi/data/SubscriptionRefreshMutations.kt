package com.poyka.ripdpi.data

import com.poyka.ripdpi.data.awg.AwgProfileEntity
import com.poyka.ripdpi.data.awg.AwgSecrets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Immutable row/credential pair prepared only after the captured group has passed its ownership check. */
data class SubscriptionAwgWrite(
    val profile: AwgProfileEntity,
    val secrets: AwgSecrets,
)

/** Group metadata and AWG identities are derived together from the same canonical prepared rows. */
data class PreparedSubscriptionRefresh(
    val replacement: ProxyGroup,
    val awgWrites: List<SubscriptionAwgWrite>,
)

interface SubscriptionRefreshMutationCoordinator {
    /** Checks group ownership before preparation; journals AWG and group after-images under one recovery lock. */
    suspend fun commitSubscriptionRefresh(
        expected: ProxyGroup,
        prepare: suspend () -> PreparedSubscriptionRefresh,
    ): Boolean
}

/** Shares the recovery lock with source edits, deletion, reset, and every other profile mutation. */
internal class SubscriptionRefreshMutations(
    private val mutex: Mutex,
    private val recover: suspend () -> Unit,
    private val groups: ProxyGroupCatalog,
    private val authority: PauseIntentAuthority,
    private val writer: ProfileMutationAfterImageWriter,
) : SubscriptionRefreshMutationCoordinator {
    override suspend fun commitSubscriptionRefresh(
        expected: ProxyGroup,
        prepare: suspend () -> PreparedSubscriptionRefresh,
    ): Boolean =
        mutex.withLock {
            recover()
            val current = groups.snapshot()
            if (current.singleOrNull { it.id == expected.id } != expected) return@withLock false
            if (expected.subscription == null || expected.ownershipGeneration.isBlank()) return@withLock false
            val prepared = prepare()
            val replacement = prepared.replacement
            require(replacement.id == expected.id && replacement.subscription != null)
            val accepted = replacement.withNewOwnershipGeneration()
            val intent =
                SubscriptionRefreshIntent(
                    current.map { if (it.id == expected.id) accepted else it },
                    prepared.awgWrites.map { write ->
                        with(write.profile) { AwgUpsertIntent(id, name, requestJson, updatedAt, write.secrets) }
                    },
                    ownershipVersion = 1,
                )
            writer.executeRecovered(
                ProfileMutationPreparation(ProfileMutationOrigin.SubscriptionRefresh, authority.reference()),
                intent,
            )
            true
        }
}

@Serializable
@SerialName("subscription_refresh")
internal data class SubscriptionRefreshIntent(
    val groups: List<ProxyGroup>,
    val awgProfiles: List<AwgUpsertIntent>,
    val ownershipVersion: Int = 0,
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Subscription
}
