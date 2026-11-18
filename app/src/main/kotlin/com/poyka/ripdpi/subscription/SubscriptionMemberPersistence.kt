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
 * dropped locally. A declared selector replaces its imported [failover] policy,
 * including an explicit manual selector. An unstructured node list keeps the
 * existing policy because it carries no selector policy declaration.
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
        cloudflareMemberIds: Set<String>? = null,
        isSelector: Boolean = false,
        initialMemberId: String? = null,
    ): ProxyGroup {
        // Drop non-activatable nodes (RawConfig: tuic/vmess/wireguard, or a
        // blank-credential node mapped to RawConfig upstream) so the user never
        // gets a selectable member that can never connect (audit P1-3). An empty
        // refresh — or one that produced only non-activatable nodes — leaves the
        // group's existing members untouched, matching the original empty guard.
        val activatable = members.filterNot { it is ProxyProfile.RawConfig }
        if (activatable.isEmpty()) return group
        val remapped = remapMemberIds(group.members, activatable)
        return group.copy(
            members = remapped,
            failover = if (isSelector) failover else failover ?: group.failover,
            isSelector = isSelector || group.isSelector,
            initialMemberId =
                if (isSelector) {
                    activatable
                        .zip(remapped)
                        .firstOrNull { it.first.id == initialMemberId }
                        ?.second
                        ?.id
                } else {
                    group.initialMemberId?.takeIf { id -> remapped.any { it.id == id } }
                },
            cloudflareMemberIds =
                if (cloudflareMemberIds == null) {
                    group.cloudflareMemberIds.intersect(remapped.map { it.id }.toSet())
                } else {
                    activatable.zip(remapped).mapNotNullTo(linkedSetOf()) { (parsed, persisted) ->
                        persisted.id.takeIf { parsed.id in cloudflareMemberIds }
                    }
                },
        )
    }
}

private fun remapMemberIds(
    prior: List<ProxyProfile>,
    refreshed: List<ProxyProfile>,
): List<ProxyProfile> {
    val exact = prior.groupBy { it.withIdentity("") }.mapValues { (_, members) -> ArrayDeque(members.map { it.id }) }
    val byConnection = uniquePriorIds(prior, refreshed) { it.withIdentity("", "") }
    val byEndpoint = uniquePriorIds(prior, refreshed) { it.endpointKey() }
    return refreshed.map { member ->
        // Identical entries are interchangeable. Credential rotation is safe only for a unique endpoint.
        val id =
            exact[member.withIdentity("")]?.removeFirstOrNull()
                ?: byConnection[member.withIdentity("", "")]
                ?: byEndpoint[member.endpointKey()]
        id?.let { member.withIdentity(it) } ?: member
    }
}

private fun <K> uniquePriorIds(
    prior: List<ProxyProfile>,
    refreshed: List<ProxyProfile>,
    key: (ProxyProfile) -> K,
): Map<K, String> {
    val counts = refreshed.groupingBy(key).eachCount()
    return prior
        .groupBy(key)
        .filter { (value, members) -> members.size == 1 && counts[value] == 1 }
        .mapValues { (_, members) -> members.single().id }
}

/** Structural identity of a node (protocol + endpoint), stable across refreshes. */
private fun ProxyProfile.endpointKey(): String =
    when (this) {
        is ProxyProfile.Vless -> "vless|$server|$serverPort"
        is ProxyProfile.VlessReality -> "vless-reality|$server|$serverPort"
        is ProxyProfile.Shadowsocks -> "ss|$server|$serverPort"
        is ProxyProfile.Trojan -> "trojan|$server|$serverPort"
        is ProxyProfile.Tuic -> "tuic|$server|$serverPort"
        is ProxyProfile.Hysteria2 -> "hysteria2|$server|$serverPort"
        is ProxyProfile.AnyTls -> "anytls|$server|$serverPort"
        is ProxyProfile.Mieru -> "mieru|$server|$serverPort"
        is ProxyProfile.Naive -> "naive|$server|$serverPort"
        is ProxyProfile.Ssh -> "ssh|$server|$serverPort"
        is ProxyProfile.RawConfig -> "raw|$id"
    }

private fun ProxyProfile.withIdentity(
    newId: String,
    name: String = displayName,
): ProxyProfile =
    when (this) {
        is ProxyProfile.Vless -> copy(id = newId, displayName = name)
        is ProxyProfile.VlessReality -> copy(id = newId, displayName = name)
        is ProxyProfile.Shadowsocks -> copy(id = newId, displayName = name)
        is ProxyProfile.Trojan -> copy(id = newId, displayName = name)
        is ProxyProfile.Tuic -> copy(id = newId, displayName = name)
        is ProxyProfile.Hysteria2 -> copy(id = newId, displayName = name)
        is ProxyProfile.AnyTls -> copy(id = newId, displayName = name)
        is ProxyProfile.Mieru -> copy(id = newId, displayName = name)
        is ProxyProfile.Naive -> copy(id = newId, displayName = name)
        is ProxyProfile.Ssh -> copy(id = newId, displayName = name)
        is ProxyProfile.RawConfig -> copy(id = newId, displayName = name)
    }
