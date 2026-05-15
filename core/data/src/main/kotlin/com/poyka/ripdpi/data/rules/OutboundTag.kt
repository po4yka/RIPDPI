package com.poyka.ripdpi.data.rules

/**
 * Discriminated union describing where a matched rule should route traffic.
 *
 * Storage encoding uses two Long columns in [RuleEntity]:
 * - [outboundSentinel]: 0 = PROXY, -1 = BYPASS, -2 = BLOCK, positive = profile/group id
 * - [outboundKind]:  0 = sentinel-only (PROXY/BYPASS/BLOCK/PROFILE), 1 = GROUP
 *
 * This avoids a single-column sentinel that cannot distinguish Profile(id) from Group(id).
 *
 * CASCADE deletion choice: when a referenced profile or group is deleted, the rule's
 * outboundTag is silently reset to [Proxy]. This is simpler than prompting the user
 * and never leaves the database in a corrupt state. The repository's [RuleRepository]
 * exposes [RuleRepository.resetOutboundTagForProfile] and
 * [RuleRepository.resetOutboundTagForGroup] so callers can invoke cascade cleanup.
 */
sealed class OutboundTag {
    data object Proxy : OutboundTag()

    data object Bypass : OutboundTag()

    data object Block : OutboundTag()

    /** Route to a specific profile identified by [profileId]. */
    data class Profile(
        val profileId: Long,
    ) : OutboundTag()

    /** Route to a proxy group identified by [groupId]. */
    data class Group(
        val groupId: Long,
    ) : OutboundTag()
}

internal const val OutboundKindNormal = 0L
internal const val OutboundKindGroup = 1L

internal const val SentinelProxy = 0L
internal const val SentinelBypass = -1L
internal const val SentinelBlock = -2L
