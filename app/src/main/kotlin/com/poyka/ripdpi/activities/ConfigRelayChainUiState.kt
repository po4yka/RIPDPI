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

internal fun ConfigDraft.swappedRelayChainHops(): ConfigDraft =
    copy(
        relayChainEntryProfileId = relayChainExitProfileId,
        relayChainExitProfileId = relayChainEntryProfileId,
    )

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
