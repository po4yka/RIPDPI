package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyGroupRepository
import com.poyka.ripdpi.data.Subscription
import com.poyka.ripdpi.data.SubscriptionKind
import com.poyka.ripdpi.data.SubscriptionLifecycleState
import com.poyka.ripdpi.data.SubscriptionRefreshFailure
import com.poyka.ripdpi.data.awg.AwgProfileRepository
import com.poyka.ripdpi.data.awg.saveMigrationTargets
import com.poyka.ripdpi.data.awg.toAwgSubscriptionProfile
import com.poyka.ripdpi.data.subscription.AmneziaWgSubscriptionProfile
import com.poyka.ripdpi.data.subscription.BootstrapConsumeResult
import com.poyka.ripdpi.data.subscription.BootstrapConsumer
import com.poyka.ripdpi.data.subscription.MigrationTarget
import com.poyka.ripdpi.data.subscription.receivedMigration
import javax.inject.Inject
import javax.inject.Singleton

/** Why a pending bootstrap token left its group without credentials. */
enum class BootstrapProvisionFailure {
    /** The server answered 410 Gone: the token was spent elsewhere. Terminal. */
    ALREADY_CONSUMED,

    /** The link or the delivered payload had no usable profile. A delivered payload spends the token. */
    PARSE_ERROR,

    /** Transport failure before the server answered. The token stays pending for a later attempt. */
    NETWORK_ERROR,

    /** The group changed or was deleted while the token was in flight; nothing was written. */
    SUPERSEDED,
}

/** Per-group outcome. Carries no URL, token, or credential so it is safe to log. */
sealed interface BootstrapProvisionOutcome {
    val groupId: String

    data class Provisioned(
        override val groupId: String,
        val profileCount: Int,
    ) : BootstrapProvisionOutcome

    data class Failed(
        override val groupId: String,
        val failure: BootstrapProvisionFailure,
    ) : BootstrapProvisionOutcome
}

/**
 * Consumes every unconsumed [SubscriptionKind.BOOTSTRAP] subscription in the catalogue, the token
 * slot a packaged preset carries instead of credentials. Credentials land only in the group
 * catalogue, which [ProxyGroupRepository] seals with an AndroidKeyStore key. A failed consume
 * stores no credential and never falls back to a bundled one; the group stays without members.
 */
@Singleton
class PendingBootstrapProvisioner internal constructor(
    private val repository: ProxyGroupRepository,
    private val bootstrapConsumer: BootstrapConsumer,
    private val saveAwgProfiles: suspend (
        List<AmneziaWgSubscriptionProfile>,
        Map<String, String>,
    ) -> List<MigrationTarget>,
) {
    @Inject
    constructor(
        repository: ProxyGroupRepository,
        awgProfileRepository: AwgProfileRepository,
    ) : this(
        repository = repository,
        bootstrapConsumer = BootstrapConsumer(),
        saveAwgProfiles = { profiles, previousTags ->
            awgProfileRepository.saveMigrationTargets(
                profiles.map { it.toAwgSubscriptionProfile(previousTag = previousTags[it.displayName]) },
            )
        },
    )

    suspend fun provisionPending(): List<BootstrapProvisionOutcome> =
        repository
            .list()
            .filter { it.subscription?.isPendingBootstrap() == true }
            .map { provision(it) }

    private fun Subscription.isPendingBootstrap(): Boolean = kind == SubscriptionKind.BOOTSTRAP && consumedAt == null

    private suspend fun provision(expected: ProxyGroup): BootstrapProvisionOutcome {
        val pending = checkNotNull(expected.subscription)
        return when (val result = bootstrapConsumer.consume(pending.link, expected.id)) {
            is BootstrapConsumeResult.Consumed -> {
                val previousTags = result.migration?.awgPreviousTags.orEmpty()
                val awgTargets = saveAwgProfiles(result.amneziaWgProfiles, previousTags)
                val saved =
                    replaceIfUnchanged(expected) { current ->
                        SubscriptionMemberPersistence
                            .apply(
                                group = current,
                                members = result.profiles,
                                failover = result.failover,
                                cloudflareMemberIds = result.cloudflareMemberIds,
                                isSelector = result.isSelector,
                                initialMemberId = result.initialMemberId,
                                memberHealth = result.memberHealth,
                            ).copy(
                                packageRoutingRules = result.packageRoutingRules,
                                subscription =
                                    pending.copy(
                                        consumedAt = result.consumedAtMillis,
                                        lifecycleState = SubscriptionLifecycleState.ACTIVE,
                                        mirrors = result.subscriptionMirrors,
                                        migration =
                                            receivedMigration(
                                                result.migration,
                                                result.payloadSha256,
                                                result.consumedAtMillis,
                                                result.profiles,
                                                awgTargets,
                                            ),
                                    ),
                            )
                    }
                if (saved) {
                    BootstrapProvisionOutcome.Provisioned(expected.id, result.profiles.size)
                } else {
                    BootstrapProvisionOutcome.Failed(expected.id, BootstrapProvisionFailure.SUPERSEDED)
                }
            }

            is BootstrapConsumeResult.AlreadyConsumed -> {
                spent(expected, result.consumedAtMillis, SubscriptionRefreshFailure.INVALIDATED)
                BootstrapProvisionOutcome.Failed(expected.id, BootstrapProvisionFailure.ALREADY_CONSUMED)
            }

            is BootstrapConsumeResult.ParseError -> {
                result.consumedAtMillis?.let { spent(expected, it, SubscriptionRefreshFailure.PARSE_ERROR) }
                BootstrapProvisionOutcome.Failed(expected.id, BootstrapProvisionFailure.PARSE_ERROR)
            }

            is BootstrapConsumeResult.NetworkError -> {
                BootstrapProvisionOutcome.Failed(expected.id, BootstrapProvisionFailure.NETWORK_ERROR)
            }
        }
    }

    /** Records a spent token with no members, so later starts do not ask the server again. */
    private suspend fun spent(
        expected: ProxyGroup,
        consumedAt: Long,
        failure: SubscriptionRefreshFailure,
    ) {
        replaceIfUnchanged(expected) { current ->
            current.copy(
                subscription =
                    checkNotNull(current.subscription).copy(
                        consumedAt = consumedAt,
                        lifecycleState = SubscriptionLifecycleState.UNAVAILABLE,
                        lastRefreshFailure = failure,
                    ),
            )
        }
    }

    private suspend fun replaceIfUnchanged(
        expected: ProxyGroup,
        transform: (ProxyGroup) -> ProxyGroup,
    ): Boolean {
        var written = false
        repository.updateGroup(expected.id) { current ->
            val unchanged =
                current.subscription == expected.subscription &&
                    current.ownershipGeneration == expected.ownershipGeneration
            if (unchanged) {
                written = true
                transform(current)
            } else {
                current
            }
        }
        return written
    }
}
