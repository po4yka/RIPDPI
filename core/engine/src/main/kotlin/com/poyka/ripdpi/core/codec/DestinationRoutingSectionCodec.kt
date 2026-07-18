package com.poyka.ripdpi.core.codec

import com.poyka.ripdpi.core.routing.DestinationDomainMatcher
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationIpMatcher
import com.poyka.ripdpi.core.routing.DestinationIpMatcherKind
import com.poyka.ripdpi.core.routing.DestinationPortRange
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import com.poyka.ripdpi.core.routing.DestinationRoutingPolicy
import com.poyka.ripdpi.core.routing.DestinationRoutingRule
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object DestinationRoutingSectionCodec {
    fun toNative(policy: DestinationRoutingPolicy): NativeDestinationRoutingConfig =
        NativeDestinationRoutingConfig(
            rules = policy.rules.map(::toNative),
            defaultAction = policy.defaultAction.toNative(),
            canonicalDigest = policy.canonicalDigest,
        )

    fun toModel(config: NativeDestinationRoutingConfig): DestinationRoutingPolicy =
        DestinationRoutingPolicy(
            rules = config.rules.map(::toModel),
            defaultAction = config.defaultAction.toModel(),
            canonicalDigest = config.canonicalDigest,
        )

    private fun toNative(rule: DestinationRoutingRule): NativeDestinationRoutingRule =
        NativeDestinationRoutingRule(
            action = rule.action.toNative(),
            network = rule.network.toNative(),
            domains = rule.domains.map { NativeDestinationDomainMatcher(it.kind.toNative(), it.value) },
            ipRanges = rule.ipRanges.map { NativeDestinationIpMatcher(it.kind.toNative(), it.value) },
            destinationPorts = rule.destinationPorts.map { NativeDestinationPortRange(it.start, it.endInclusive) },
        )

    private fun toModel(rule: NativeDestinationRoutingRule): DestinationRoutingRule =
        DestinationRoutingRule(
            action = rule.action.toModel(),
            network = rule.network.toModel(),
            domains = rule.domains.map { DestinationDomainMatcher(it.kind.toModel(), it.value) },
            ipRanges = rule.ipRanges.map { DestinationIpMatcher(it.kind.toModel(), it.value) },
            destinationPorts = rule.destinationPorts.map { DestinationPortRange(it.start, it.endInclusive) },
        )
}

@Serializable
internal data class NativeDestinationRoutingConfig(
    val rules: List<NativeDestinationRoutingRule> = emptyList(),
    val defaultAction: NativeDestinationRoutingAction = NativeDestinationRoutingAction.TUNNELED,
    val canonicalDigest: String = "",
)

@Serializable
internal data class NativeDestinationRoutingRule(
    val action: NativeDestinationRoutingAction,
    val network: NativeDestinationRoutingNetwork,
    val domains: List<NativeDestinationDomainMatcher> = emptyList(),
    val ipRanges: List<NativeDestinationIpMatcher> = emptyList(),
    val destinationPorts: List<NativeDestinationPortRange> = emptyList(),
)

@Serializable
internal data class NativeDestinationDomainMatcher(
    val kind: NativeDestinationDomainMatcherKind,
    val value: String,
)

@Serializable
internal data class NativeDestinationIpMatcher(
    val kind: NativeDestinationIpMatcherKind,
    val value: String,
)

@Serializable
internal data class NativeDestinationPortRange(
    val start: Int,
    val endInclusive: Int,
)

@Serializable
internal enum class NativeDestinationRoutingAction {
    @SerialName("tunneled")
    TUNNELED,

    @SerialName("direct")
    DIRECT,

    @SerialName("block")
    BLOCK,
}

@Serializable
internal enum class NativeDestinationRoutingNetwork {
    @SerialName("tcp")
    TCP,

    @SerialName("udp")
    UDP,

    @SerialName("both")
    BOTH,
}

@Serializable
internal enum class NativeDestinationDomainMatcherKind {
    @SerialName("exact")
    EXACT,

    @SerialName("suffix")
    SUFFIX,

    @SerialName("geosite")
    GEOSITE,
}

@Serializable
internal enum class NativeDestinationIpMatcherKind {
    @SerialName("cidr")
    CIDR,

    @SerialName("geo_ip")
    GEO_IP,
}

private fun DestinationRoutingAction.toNative(): NativeDestinationRoutingAction =
    when (this) {
        DestinationRoutingAction.TUNNELED -> NativeDestinationRoutingAction.TUNNELED
        DestinationRoutingAction.DIRECT -> NativeDestinationRoutingAction.DIRECT
        DestinationRoutingAction.BLOCK -> NativeDestinationRoutingAction.BLOCK
    }

private fun NativeDestinationRoutingAction.toModel(): DestinationRoutingAction =
    when (this) {
        NativeDestinationRoutingAction.TUNNELED -> DestinationRoutingAction.TUNNELED
        NativeDestinationRoutingAction.DIRECT -> DestinationRoutingAction.DIRECT
        NativeDestinationRoutingAction.BLOCK -> DestinationRoutingAction.BLOCK
    }

private fun DestinationRoutingNetwork.toNative(): NativeDestinationRoutingNetwork =
    when (this) {
        DestinationRoutingNetwork.TCP -> NativeDestinationRoutingNetwork.TCP
        DestinationRoutingNetwork.UDP -> NativeDestinationRoutingNetwork.UDP
        DestinationRoutingNetwork.BOTH -> NativeDestinationRoutingNetwork.BOTH
    }

private fun NativeDestinationRoutingNetwork.toModel(): DestinationRoutingNetwork =
    when (this) {
        NativeDestinationRoutingNetwork.TCP -> DestinationRoutingNetwork.TCP
        NativeDestinationRoutingNetwork.UDP -> DestinationRoutingNetwork.UDP
        NativeDestinationRoutingNetwork.BOTH -> DestinationRoutingNetwork.BOTH
    }

private fun DestinationDomainMatcherKind.toNative(): NativeDestinationDomainMatcherKind =
    when (this) {
        DestinationDomainMatcherKind.EXACT -> NativeDestinationDomainMatcherKind.EXACT
        DestinationDomainMatcherKind.SUFFIX -> NativeDestinationDomainMatcherKind.SUFFIX
        DestinationDomainMatcherKind.GEOSITE -> NativeDestinationDomainMatcherKind.GEOSITE
    }

private fun NativeDestinationDomainMatcherKind.toModel(): DestinationDomainMatcherKind =
    when (this) {
        NativeDestinationDomainMatcherKind.EXACT -> DestinationDomainMatcherKind.EXACT
        NativeDestinationDomainMatcherKind.SUFFIX -> DestinationDomainMatcherKind.SUFFIX
        NativeDestinationDomainMatcherKind.GEOSITE -> DestinationDomainMatcherKind.GEOSITE
    }

private fun DestinationIpMatcherKind.toNative(): NativeDestinationIpMatcherKind =
    when (this) {
        DestinationIpMatcherKind.CIDR -> NativeDestinationIpMatcherKind.CIDR
        DestinationIpMatcherKind.GEO_IP -> NativeDestinationIpMatcherKind.GEO_IP
    }

private fun NativeDestinationIpMatcherKind.toModel(): DestinationIpMatcherKind =
    when (this) {
        NativeDestinationIpMatcherKind.CIDR -> DestinationIpMatcherKind.CIDR
        NativeDestinationIpMatcherKind.GEO_IP -> DestinationIpMatcherKind.GEO_IP
    }
