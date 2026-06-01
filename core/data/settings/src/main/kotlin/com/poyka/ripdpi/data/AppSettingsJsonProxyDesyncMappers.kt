package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

@Suppress("LongMethod")
internal fun AppSettingsSnapshot.withProxyDesyncSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        proxyDesync =
            AppSettingsProxyDesyncSnapshot(
                enableCommandLineSettings = settings.enableCmdSettings,
                commandLineArgs = settings.cmdArgs,
                proxyIp = settings.proxyIp,
                proxyPort = settings.proxyPort,
                maxConnections = settings.maxConnections,
                bufferSize = settings.bufferSize,
                noDomain = settings.noDomain,
                tcpFastOpen = settings.tcpFastOpen,
                mixedInboundEnabled = settings.mixedInboundEnabled,
                defaultTtl = settings.defaultTtl,
                customTtl = settings.customTtl,
                fakeTtl = settings.fakeTtl,
                adaptiveFakeTtlEnabled = settings.adaptiveFakeTtlEnabled,
                adaptiveFakeTtlDelta = settings.effectiveAdaptiveFakeTtlDelta(),
                adaptiveFakeTtlMin = settings.effectiveAdaptiveFakeTtlMin(),
                adaptiveFakeTtlMax = settings.effectiveAdaptiveFakeTtlMax(),
                adaptiveFakeTtlFallback = settings.effectiveAdaptiveFakeTtlFallback(),
                fakeSni = settings.fakeSni,
                fakeOffset = settings.fakeOffset,
                fakeOffsetMarker = settings.fakeOffsetMarker,
                fakeTlsSource = normalizeFakeTlsSource(settings.fakeTlsSource),
                fakeTlsSecondaryProfile = settings.fakeTlsSecondaryProfile,
                fakeTcpTimestampEnabled = settings.fakeTcpTimestampEnabled,
                fakeTcpTimestampDeltaTicks = settings.fakeTcpTimestampDeltaTicks,
                fakeTlsUseOriginal = settings.fakeTlsUseOriginal,
                fakeTlsRandomize = settings.fakeTlsRandomize,
                fakeTlsDupSessionId = settings.fakeTlsDupSessionId,
                fakeTlsPadEncap = settings.fakeTlsPadEncap,
                fakeTlsSize = settings.fakeTlsSize,
                fakeTlsSniMode = settings.effectiveFakeTlsSniMode(),
                ipIdMode = settings.effectiveIpIdMode(),
                httpFakeProfile = settings.effectiveHttpFakeProfile(),
                tlsFakeProfile = settings.effectiveTlsFakeProfile(),
                oobData = settings.oobData,
                dropSack = settings.dropSack,
                fakeMd5Sig = settings.fakeMd5Sig,
                desyncHttp = settings.desyncHttp,
                desyncHttps = settings.desyncHttps,
                desyncUdp = settings.desyncUdp,
                hostsMode = settings.hostsMode,
                hostsBlacklist = settings.hostsBlacklist,
                hostsWhitelist = settings.hostsWhitelist,
                udpFakeProfile = settings.effectiveUdpFakeProfile(),
                hostMixedCase = settings.hostMixedCase,
                domainMixedCase = settings.domainMixedCase,
                hostRemoveSpaces = settings.hostRemoveSpaces,
                httpMethodEol = settings.httpMethodEol,
                httpUnixEol = settings.httpUnixEol,
                httpMethodSpace = settings.httpMethodSpace,
                httpHostPad = settings.httpHostPad,
                desyncAnyProtocol = settings.desyncAnyProtocol,
                groupActivationFilter =
                    if (settings.hasGroupActivationFilter()) {
                        settings.groupActivationFilter.toModel().let(::normalizeActivationFilter)
                    } else {
                        ActivationFilterModel()
                    },
                proxyAllowLan = settings.proxyAllowLan,
                proxyLanAuthToken = settings.proxyLanAuthToken,
                appendHttpProxy = settings.appendHttpProxy,
            ),
    )

internal fun AppSettings.Builder.applyProxyDesyncSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder {
    val proxy = snapshot.proxyDesync
    return setProxyIp(proxy.proxyIp)
        .setProxyPort(proxy.proxyPort)
        .setMaxConnections(proxy.maxConnections)
        .setBufferSize(proxy.bufferSize)
        .setNoDomain(proxy.noDomain)
        .setTcpFastOpen(proxy.tcpFastOpen)
        .setMixedInboundEnabled(proxy.mixedInboundEnabled)
        .setDefaultTtl(proxy.defaultTtl)
        .setCustomTtl(proxy.customTtl)
        .setFakeTtl(proxy.fakeTtl)
        .setAdaptiveFakeTtlEnabled(proxy.adaptiveFakeTtlEnabled)
        .setAdaptiveFakeTtlDelta(normalizeAdaptiveFakeTtlDelta(proxy.adaptiveFakeTtlDelta))
        .setAdaptiveFakeTtlMin(normalizeAdaptiveFakeTtlMin(proxy.adaptiveFakeTtlMin))
        .setAdaptiveFakeTtlMax(
            normalizeAdaptiveFakeTtlMax(
                proxy.adaptiveFakeTtlMax,
                normalizeAdaptiveFakeTtlMin(proxy.adaptiveFakeTtlMin),
            ),
        ).setAdaptiveFakeTtlFallback(
            normalizeAdaptiveFakeTtlFallback(
                proxy.adaptiveFakeTtlFallback,
                proxy.fakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
            ),
        ).setFakeSni(proxy.fakeSni)
        .setFakeOffset(proxy.fakeOffset)
        .setFakeOffsetMarker(proxy.fakeOffsetMarker)
        .setFakeTlsSource(normalizeFakeTlsSource(proxy.fakeTlsSource))
        .setFakeTlsSecondaryProfile(proxy.fakeTlsSecondaryProfile)
        .setFakeTcpTimestampEnabled(proxy.fakeTcpTimestampEnabled)
        .setFakeTcpTimestampDeltaTicks(proxy.fakeTcpTimestampDeltaTicks)
        .setFakeTlsUseOriginal(proxy.fakeTlsUseOriginal)
        .setFakeTlsRandomize(proxy.fakeTlsRandomize)
        .setFakeTlsDupSessionId(proxy.fakeTlsDupSessionId)
        .setFakeTlsPadEncap(proxy.fakeTlsPadEncap)
        .setFakeTlsSize(proxy.fakeTlsSize)
        .setFakeTlsSniMode(normalizeFakeTlsSniMode(proxy.fakeTlsSniMode))
        .setIpIdMode(normalizeIpIdMode(proxy.ipIdMode))
        .setHttpFakeProfile(normalizeHttpFakeProfile(proxy.httpFakeProfile))
        .setTlsFakeProfile(normalizeTlsFakeProfile(proxy.tlsFakeProfile))
        .setOobData(proxy.oobData)
        .setDropSack(proxy.dropSack)
        .setFakeMd5Sig(proxy.fakeMd5Sig)
        .setDesyncHttp(proxy.desyncHttp)
        .setDesyncHttps(proxy.desyncHttps)
        .setDesyncUdp(proxy.desyncUdp)
        .setHostsMode(proxy.hostsMode)
        .setHostsBlacklist(proxy.hostsBlacklist)
        .setHostsWhitelist(proxy.hostsWhitelist)
        .setUdpFakeProfile(normalizeUdpFakeProfile(proxy.udpFakeProfile))
        .setHostMixedCase(proxy.hostMixedCase)
        .setDomainMixedCase(proxy.domainMixedCase)
        .setHostRemoveSpaces(proxy.hostRemoveSpaces)
        .setHttpMethodEol(proxy.httpMethodEol)
        .setHttpUnixEol(proxy.httpUnixEol)
        .setHttpMethodSpace(proxy.httpMethodSpace)
        .setHttpHostPad(proxy.httpHostPad)
        .setDesyncAnyProtocol(proxy.desyncAnyProtocol)
        .setProxyAllowLan(proxy.proxyAllowLan)
        .setProxyLanAuthToken(proxy.proxyLanAuthToken)
        .setAppendHttpProxy(proxy.appendHttpProxy)
}
