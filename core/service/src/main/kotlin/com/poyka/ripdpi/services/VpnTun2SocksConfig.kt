package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiLogContext
import com.poyka.ripdpi.core.Tun2SocksConfig
import com.poyka.ripdpi.core.Tun2SocksSplitDnsDomainMatcher
import com.poyka.ripdpi.core.Tun2SocksSplitDnsPolicy
import com.poyka.ripdpi.core.Tun2SocksSplitDnsRule
import com.poyka.ripdpi.core.defaultTun2SocksTunnelMtu
import com.poyka.ripdpi.core.routing.DestinationDomainMatcherKind
import com.poyka.ripdpi.core.routing.DestinationRoutingAction
import com.poyka.ripdpi.core.routing.DestinationRoutingNetwork
import java.util.Locale

private const val TunnelIpv4Cidr = "10.10.10.10/32"
private const val TunnelIpv6Cidr = "fd00::1/128"
private const val MapDnsAddress = "198.18.0.53"
private const val MapDnsNetwork = "198.18.0.0"
private const val MapDnsNetmask = "255.254.0.0"
private const val MapDnsPort = 53
private const val MapDnsCacheSize = 10_000
private const val DnsQueryTimeoutMs = 4_000

@Suppress("LongParameterList")
internal fun buildVpnTun2SocksConfig(
    dnsPlan: VpnTunnelDnsPlan,
    overrideReason: String?,
    localProxyEndpoint: LocalProxyEndpoint,
    ipv6Enabled: Boolean,
    webrtcProtectionEnabled: Boolean = false,
    tunnelMtu: Int = defaultTun2SocksTunnelMtu,
    logContext: RipDpiLogContext? = null,
    encryptedDnsTlsRootsPem: String? = null,
    strategyChainYaml: String? = null,
    protectPath: String? = null,
    rootHelperSocketPath: String? = null,
    luaScriptBaseDir: String? = null,
    uidPolicy: NativeUidPolicy = NativeUidPolicy.Disarmed,
    uidPolicyAllowIcmp: Boolean = false,
    geositeDbPath: String? = null,
): Tun2SocksConfig {
    val tunnelDns = dnsPlan.resolverDns
    val mapDnsEnabled = dnsPlan.mapDnsEnabled
    return Tun2SocksConfig(
        tunnelMtu = tunnelMtu,
        tunnelIpv4 = TunnelIpv4Cidr,
        tunnelIpv6 = if (ipv6Enabled) TunnelIpv6Cidr else null,
        socks5Address = localProxyEndpoint.host,
        socks5Port = localProxyEndpoint.port,
        socks5Udp = "udp",
        mapdnsAddress = if (mapDnsEnabled) MapDnsAddress else null,
        mapdnsPort = if (mapDnsEnabled) MapDnsPort else null,
        mapdnsNetwork = if (mapDnsEnabled) MapDnsNetwork else null,
        mapdnsNetmask = if (mapDnsEnabled) MapDnsNetmask else null,
        mapdnsCacheSize = if (mapDnsEnabled) MapDnsCacheSize else null,
        encryptedDnsResolverId = mapDnsValue(mapDnsEnabled, tunnelDns.providerId),
        encryptedDnsProtocol = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsProtocol),
        encryptedDnsHost = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsHost),
        encryptedDnsPort = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsPort),
        encryptedDnsTlsServerName = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsTlsServerName),
        encryptedDnsBootstrapIps = mapDnsList(mapDnsEnabled, tunnelDns.encryptedDnsBootstrapIps),
        encryptedDnsDohUrl = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsDohUrl),
        encryptedDnsDnscryptProviderName = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsDnscryptProviderName),
        encryptedDnsDnscryptPublicKey = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsDnscryptPublicKey),
        encryptedDnsOdohProxyUrl = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohProxyUrl),
        encryptedDnsOdohProxyOperatorId = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohProxyOperatorId),
        encryptedDnsOdohTargetHost = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohTargetHost),
        encryptedDnsOdohTargetPath = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohTargetPath),
        encryptedDnsOdohTargetOperatorId = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohTargetOperatorId),
        encryptedDnsOdohConfigSource = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohConfigSource),
        encryptedDnsOdohConfigsHex = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohConfigsHex),
        encryptedDnsOdohConfigsRetrievedAtSecs =
            mapDnsValue(
                mapDnsEnabled,
                tunnelDns.encryptedDnsOdohConfigsRetrievedAtSecs,
            ),
        encryptedDnsOdohConfigsTtlSecs = mapDnsValue(mapDnsEnabled, tunnelDns.encryptedDnsOdohConfigsTtlSecs),
        encryptedDnsTlsRootsPem = mapDnsValue(mapDnsEnabled, encryptedDnsTlsRootsPem?.takeIf { it.isNotBlank() }),
        dnsQueryTimeoutMs = if (mapDnsEnabled) DnsQueryTimeoutMs else null,
        resolverFallbackActive = overrideReason != null,
        resolverFallbackReason = overrideReason,
        routeDnsThroughSocks5 = dnsPlan.routeDnsThroughSocks5,
        splitDnsPolicy = dnsPlan.splitStrictPolicy?.toNativeSplitDnsPolicy(geositeDbPath),
        strategyChainYaml = strategyChainYaml,
        protectPath = protectPath,
        rootHelperSocketPath = rootHelperSocketPath,
        luaScriptBaseDir = luaScriptBaseDir,
        webrtcProtectionEnabled = webrtcProtectionEnabled,
        uidPolicyMode = uidPolicy.mode,
        uidPolicyUids = uidPolicy.uids,
        uidPolicyAllowIcmp = uidPolicyAllowIcmp,
        logContext = logContext,
        username = localProxyEndpoint.username,
        password = localProxyEndpoint.password,
    )
}

private fun ValidatedSplitStrictDnsPolicy.toNativeSplitDnsPolicy(geositeDbPath: String?): Tun2SocksSplitDnsPolicy =
    Tun2SocksSplitDnsPolicy(
        canonicalDigest = canonicalDigest,
        destinationRoutingDigest = destinationRouting.canonicalDigest,
        defaultAction = destinationRouting.defaultAction.wireName(),
        rules =
            destinationRouting.rules.map { rule ->
                Tun2SocksSplitDnsRule(
                    action = rule.action.wireName(),
                    network = rule.network.wireName(),
                    domains =
                        rule.domains.map { matcher ->
                            Tun2SocksSplitDnsDomainMatcher(
                                kind = matcher.kind.wireName(),
                                value = matcher.value,
                            )
                        },
                    hasIpRanges = rule.ipRanges.isNotEmpty(),
                    hasPorts = rule.destinationPorts.isNotEmpty(),
                )
            },
        directResolverCandidates = directResolverCandidates,
        bootstrapPins = bootstrapPins,
        geositeDbPath = geositeDbPath,
        coverageReason = policyCoverageReason,
    )

private fun DestinationRoutingAction.wireName(): String = name.lowercase(Locale.ROOT)

private fun DestinationRoutingNetwork.wireName(): String = name.lowercase(Locale.ROOT)

private fun DestinationDomainMatcherKind.wireName(): String = name.lowercase(Locale.ROOT)

private fun <T> mapDnsValue(
    mapDnsEnabled: Boolean,
    value: T,
): T? = if (mapDnsEnabled) value else null

private fun mapDnsList(
    mapDnsEnabled: Boolean,
    values: List<String>,
): List<String> = if (mapDnsEnabled) values else emptyList()
