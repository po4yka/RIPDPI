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

interface SubscriptionRefreshMutationCoordinator {
    /** Checks group ownership before preparation; journals AWG and group after-images under one recovery lock. */
    suspend fun commitSubscriptionRefresh(
        expected: ProxyGroup,
        replacement: ProxyGroup,
        prepareAwgWrites: suspend () -> List<SubscriptionAwgWrite>,
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
        replacement: ProxyGroup,
        prepareAwgWrites: suspend () -> List<SubscriptionAwgWrite>,
    ): Boolean =
        mutex.withLock {
            recover()
            require(replacement.id == expected.id)
            val current = groups.snapshot()
            if (current.singleOrNull { it.id == expected.id } != expected) return@withLock false
            val subscription = expected.subscription ?: return@withLock false
            require(subscription.refreshRevision in 0 until Long.MAX_VALUE) { "Subscription revision is exhausted" }
            val accepted =
                replacement.copy(
                    subscription =
                        checkNotNull(replacement.subscription).copy(
                            refreshRevision =
                                subscription.refreshRevision + 1,
                        ),
                )
            val writes = prepareAwgWrites()
            val intent =
                SubscriptionRefreshIntent(
                    current.map { if (it.id == expected.id) accepted else it },
                    writes.map { write ->
                        with(write.profile) { AwgUpsertIntent(id, name, requestJson, updatedAt, write.secrets) }
                    },
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
) : ProfileMutationIntent {
    override val family: ProfileMutationFamily = ProfileMutationFamily.Subscription
}
