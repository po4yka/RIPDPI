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
        cloudflareMemberIds: Set<String>? = null,
        isSelector: Boolean = false,
    ): ProxyGroup {
        // Drop non-activatable nodes (RawConfig: tuic/vmess/wireguard, or a
        // blank-credential node mapped to RawConfig upstream) so the user never
        // gets a selectable member that can never connect (audit P1-3). An empty
        // refresh — or one that produced only non-activatable nodes — leaves the
        // group's existing members untouched, matching the original empty guard.
        val activatable = members.filterNot { it is ProxyProfile.RawConfig }
        if (activatable.isEmpty()) return group
        // Parser member ids are random per parse, so a wholesale replace orphaned
        // any persisted selector selection (keyed by member id) on every refresh
        // (audit P1-14). Carry each prior member's id onto its structurally-equal
        // refreshed counterpart so the user's selection survives.
        val priorIdsByEndpoint = mutableMapOf<String, ArrayDeque<String>>()
        group.members.forEach { member ->
            priorIdsByEndpoint.getOrPut(member.endpointKey(), ::ArrayDeque).addLast(member.id)
        }
        val remapped =
            activatable.map { member ->
                priorIdsByEndpoint[member.endpointKey()]?.removeFirstOrNull()?.let(member::withId) ?: member
            }
        return group.copy(
            members = remapped,
            failover = failover ?: group.failover,
            isSelector = isSelector || group.isSelector,
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

/** Structural identity of a node (protocol + endpoint), stable across refreshes. */
private fun ProxyProfile.endpointKey(): String =
    when (this) {
        is ProxyProfile.Vless -> "vless|$server|$serverPort"
        is ProxyProfile.VlessReality -> "vless-reality|$server|$serverPort"
        is ProxyProfile.Shadowsocks -> "ss|$server|$serverPort"
        is ProxyProfile.Trojan -> "trojan|$server|$serverPort"
        is ProxyProfile.Hysteria2 -> "hysteria2|$server|$serverPort"
        is ProxyProfile.AnyTls -> "anytls|$server|$serverPort"
        is ProxyProfile.Mieru -> "mieru|$server|$serverPort"
        is ProxyProfile.Ssh -> "ssh|$server|$serverPort"
        is ProxyProfile.RawConfig -> "raw|$id"
    }

/** Returns a copy of this profile with [newId] as its id. */
private fun ProxyProfile.withId(newId: String): ProxyProfile =
    when (this) {
        is ProxyProfile.Vless -> copy(id = newId)
        is ProxyProfile.VlessReality -> copy(id = newId)
        is ProxyProfile.Shadowsocks -> copy(id = newId)
        is ProxyProfile.Trojan -> copy(id = newId)
        is ProxyProfile.Hysteria2 -> copy(id = newId)
        is ProxyProfile.AnyTls -> copy(id = newId)
        is ProxyProfile.Mieru -> copy(id = newId)
        is ProxyProfile.Ssh -> copy(id = newId)
        is ProxyProfile.RawConfig -> copy(id = newId)
    }
