package com.poyka.ripdpi.subscription

import com.poyka.ripdpi.data.ProxyGroup
import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.SelectorFailover

/**
 * Pure mapping that folds the profiles parsed from a subscription refresh back
 * onto the existing [ProxyGroup], so the candidate members are durably stored
 * rather than discarded (the historical bug: the refresh worker only read
 * `profiles.size`).
 *
 * The refreshed [members] replace the group's previous member set wholesale —
 * the subscription URL is the source of truth, so a member dropped upstream is
 * dropped locally. [failover] is carried through only when the refresh produced a
 * selector/urltest policy; a refresh that produced none leaves the group's
 * existing [ProxyGroup.failover] untouched (a plain base64 refresh must not erase
 * a previously-imported urltest policy).
 *
 * No-network, no-I/O: the worker passes the parsed result in and writes the
 * returned group back through [com.poyka.ripdpi.data.ProxyGroupRepository.update].
 */
object SubscriptionMemberPersistence {
    /**
     * Returns [group] with its [ProxyGroup.members] replaced by [members] and,
     * when [failover] is non-`null`, its [ProxyGroup.failover] updated. Returns the
     * group unchanged when [members] is empty (an empty refresh is not a successful
     * update and must not wipe a group that still has good members).
     */
    fun apply(
        group: ProxyGroup,
        members: List<ProxyProfile>,
        failover: SelectorFailover? = null,
    ): ProxyGroup {
        if (members.isEmpty()) return group
        return group.copy(
            members = members,
            failover = failover ?: group.failover,
        )
    }
}
