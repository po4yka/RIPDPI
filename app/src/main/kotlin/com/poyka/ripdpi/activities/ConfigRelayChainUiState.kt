package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.RelayKindAnyTls
import com.poyka.ripdpi.data.RelayKindChainRelay
import com.poyka.ripdpi.data.RelayKindCloudflareTunnel
import com.poyka.ripdpi.data.RelayKindHysteria2
import com.poyka.ripdpi.data.RelayKindMasque
import com.poyka.ripdpi.data.RelayKindNaiveProxy
import com.poyka.ripdpi.data.RelayKindObfs4
import com.poyka.ripdpi.data.RelayKindShadowTlsV3
import com.poyka.ripdpi.data.RelayKindShadowsocks
import com.poyka.ripdpi.data.RelayKindSnowflake
import com.poyka.ripdpi.data.RelayKindTrojan
import com.poyka.ripdpi.data.RelayKindTuicV5
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayKindWebTunnel
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayTrustDomain
import com.poyka.ripdpi.data.RelayTrustDomainWarning
import com.poyka.ripdpi.data.detectRelayChainTrustWarning
import com.poyka.ripdpi.data.isSupportedChainEntryHop
import com.poyka.ripdpi.data.isSupportedChainExitHop
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

data class RelayProfileUiState(
    val id: String,
    val kind: String,
    val kindLabel: String,
    val jurisdiction: String,
    val operatorName: String,
) {
    val selectorLabel: String
        get() = "$id · $kindLabel"

    val trustLabel: String
        get() =
            listOf(operatorName, jurisdiction)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { "Trust domain not set" }
}

internal fun buildRelayProfileOptions(
    records: List<RelayProfileRecord>,
    chainProfileId: String,
): ImmutableList<RelayProfileUiState> =
    records
        .asSequence()
        .filter { it.id != chainProfileId }
        .filter { it.isSupportedChainEntryHop() || it.isSupportedChainExitHop() }
        .toRelayProfileUiStates()

internal fun buildVpnProfileOptions(records: List<RelayProfileRecord>): ImmutableList<RelayProfileUiState> =
    records
        .asSequence()
        .toRelayProfileUiStates()

private fun Sequence<RelayProfileRecord>.toRelayProfileUiStates(): ImmutableList<RelayProfileUiState> =
    this
        .sortedBy { it.id }
        .map { record ->
            RelayProfileUiState(
                id = record.id,
                kind = record.kind,
                kindLabel = record.kind.relayKindLabel(),
                jurisdiction = record.jurisdiction,
                operatorName = record.operatorName,
            )
        }.toList()
        .toImmutableList()

internal fun resolveRelayChainTrustWarning(
    draft: ConfigDraft,
    profiles: List<RelayProfileUiState>,
): RelayTrustDomainWarning? =
    draft
        .takeIf { it.relayKind == RelayKindChainRelay }
        ?.let { chainDraft ->
            val entry = profiles.firstOrNull { it.id == chainDraft.relayChainEntryProfileId }
            val exit = profiles.firstOrNull { it.id == chainDraft.relayChainExitProfileId }
            if (entry != null && exit != null) {
                detectRelayChainTrustWarning(entry.toRelayTrustDomain(), exit.toRelayTrustDomain())
            } else {
                null
            }
        }

/**
 * Ordered hop list for the multi-hop chain editor. Hop 0 is the entry, the last hop is the
 * exit, and the middle hops come from [ConfigDraft.relayChainMiddleProfileIds]. The list is
 * always at least [RelayChainMinHopsUi] long (entry + exit) so the editor never under-runs the
 * bound; empty selections render as empty strings the user must fill.
 */
internal fun ConfigDraft.relayChainHopProfileIds(): ImmutableList<String> =
    buildList {
        add(relayChainEntryProfileId)
        addAll(relayChainMiddleProfileIds)
        add(relayChainExitProfileId)
    }.toImmutableList()

/**
 * Rewrites the chain hops from an ordered list, folding hop 0 back into the entry field, the
 * last hop into the exit field, and everything between into [ConfigDraft.relayChainMiddleProfileIds].
 * Out-of-range lists are clamped to [[RelayChainMinHopsUi], [RelayChainMaxHopsUi]] so the draft
 * can never represent an invalid hop count; the editor enforces the bound at the call site too.
 */
internal fun ConfigDraft.withRelayChainHopProfileIds(hops: List<String>): ConfigDraft {
    val clamped =
        when {
            hops.size < RelayChainMinHopsUi -> hops + List(RelayChainMinHopsUi - hops.size) { "" }
            hops.size > RelayChainMaxHopsUi -> hops.take(RelayChainMaxHopsUi)
            else -> hops
        }
    return copy(
        relayChainEntryProfileId = clamped.first(),
        relayChainExitProfileId = clamped.last(),
        relayChainMiddleProfileIds = clamped.subList(1, clamped.size - 1).toImmutableList(),
    )
}

/** Appends one empty hop before the exit, capped at [RelayChainMaxHopsUi]. No-op when full. */
internal fun ConfigDraft.relayChainHopAdded(): ConfigDraft {
    val hops = relayChainHopProfileIds()
    if (hops.size >= RelayChainMaxHopsUi) {
        return this
    }
    val withInserted = hops.toMutableList().apply { add(size - 1, "") }
    return withRelayChainHopProfileIds(withInserted)
}

/** Removes the hop at [index], floored at [RelayChainMinHopsUi]. No-op at the minimum. */
internal fun ConfigDraft.relayChainHopRemoved(index: Int): ConfigDraft {
    val hops = relayChainHopProfileIds()
    if (hops.size <= RelayChainMinHopsUi || index !in hops.indices) {
        return this
    }
    val withRemoved = hops.toMutableList().apply { removeAt(index) }
    return withRelayChainHopProfileIds(withRemoved)
}

/** Sets the profile id at [index] in the ordered hop list. */
internal fun ConfigDraft.relayChainHopProfileIdChanged(
    index: Int,
    profileId: String,
): ConfigDraft {
    val hops = relayChainHopProfileIds()
    if (index !in hops.indices) {
        return this
    }
    val updated = hops.toMutableList().apply { this[index] = profileId }
    return withRelayChainHopProfileIds(updated)
}

/** Moves the hop at [from] to [to], clamping both into range. No-op when out of bounds. */
internal fun ConfigDraft.relayChainHopMoved(
    from: Int,
    to: Int,
): ConfigDraft {
    val hops = relayChainHopProfileIds()
    if (from !in hops.indices || to !in hops.indices || from == to) {
        return this
    }
    val reordered =
        hops.toMutableList().apply {
            val moved = removeAt(from)
            add(to, moved)
        }
    return withRelayChainHopProfileIds(reordered)
}

internal fun String.relayKindLabel(): String =
    when (this) {
        RelayKindVlessReality -> "VLESS + Reality"
        RelayKindCloudflareTunnel -> "Cloudflare Tunnel"
        RelayKindHysteria2 -> "Hysteria2"
        RelayKindMasque -> "MASQUE"
        RelayKindNaiveProxy -> "NaiveProxy"
        RelayKindTuicV5 -> "TUIC v5"
        RelayKindShadowTlsV3 -> "ShadowTLS v3"
        RelayKindTrojan -> "Trojan"
        RelayKindAnyTls -> "AnyTLS"
        RelayKindShadowsocks -> "Shadowsocks"
        RelayKindSnowflake -> "Snowflake"
        RelayKindWebTunnel -> "WebTunnel"
        RelayKindObfs4 -> "obfs4"
        else -> this
    }

private fun RelayProfileUiState.toRelayTrustDomain(): RelayTrustDomain =
    RelayTrustDomain(
        jurisdiction = jurisdiction,
        operatorName = operatorName,
    )
