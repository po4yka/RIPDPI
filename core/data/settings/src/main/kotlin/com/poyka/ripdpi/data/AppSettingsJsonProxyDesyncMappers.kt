package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

@Suppress("LongMethod")
internal fun AppSettingsSnapshot.withProxyDesyncSnapshot(settings: AppSettings): AppSettingsSnapshot =
    copy(
        enableCommandLineSettings = settings.enableCmdSettings,
        commandLineArgs = settings.cmdArgs,
        proxyIp = settings.proxyIp,
        proxyPort = settings.proxyPort,
        maxConnections = settings.maxConnections,
        bufferSize = settings.bufferSize,
        noDomain = settings.noDomain,
        tcpFastOpen = settings.tcpFastOpen,
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
    )

internal fun AppSettings.Builder.applyProxyDesyncSnapshot(snapshot: AppSettingsSnapshot): AppSettings.Builder =
    setProxyIp(snapshot.proxyIp)
        .setProxyPort(snapshot.proxyPort)
        .setMaxConnections(snapshot.maxConnections)
        .setBufferSize(snapshot.bufferSize)
        .setNoDomain(snapshot.noDomain)
        .setTcpFastOpen(snapshot.tcpFastOpen)
        .setDefaultTtl(snapshot.defaultTtl)
        .setCustomTtl(snapshot.customTtl)
        .setFakeTtl(snapshot.fakeTtl)
        .setAdaptiveFakeTtlEnabled(snapshot.adaptiveFakeTtlEnabled)
        .setAdaptiveFakeTtlDelta(normalizeAdaptiveFakeTtlDelta(snapshot.adaptiveFakeTtlDelta))
        .setAdaptiveFakeTtlMin(normalizeAdaptiveFakeTtlMin(snapshot.adaptiveFakeTtlMin))
        .setAdaptiveFakeTtlMax(
            normalizeAdaptiveFakeTtlMax(
                snapshot.adaptiveFakeTtlMax,
                normalizeAdaptiveFakeTtlMin(snapshot.adaptiveFakeTtlMin),
            ),
        ).setAdaptiveFakeTtlFallback(
            normalizeAdaptiveFakeTtlFallback(
                snapshot.adaptiveFakeTtlFallback,
                snapshot.fakeTtl.takeIf { it > 0 } ?: DefaultAdaptiveFakeTtlFallback,
            ),
        ).setFakeSni(snapshot.fakeSni)
        .setFakeOffset(snapshot.fakeOffset)
        .setFakeOffsetMarker(snapshot.fakeOffsetMarker)
        .setFakeTlsSource(normalizeFakeTlsSource(snapshot.fakeTlsSource))
        .setFakeTlsSecondaryProfile(snapshot.fakeTlsSecondaryProfile)
        .setFakeTcpTimestampEnabled(snapshot.fakeTcpTimestampEnabled)
        .setFakeTcpTimestampDeltaTicks(snapshot.fakeTcpTimestampDeltaTicks)
        .setFakeTlsUseOriginal(snapshot.fakeTlsUseOriginal)
        .setFakeTlsRandomize(snapshot.fakeTlsRandomize)
        .setFakeTlsDupSessionId(snapshot.fakeTlsDupSessionId)
        .setFakeTlsPadEncap(snapshot.fakeTlsPadEncap)
        .setFakeTlsSize(snapshot.fakeTlsSize)
        .setFakeTlsSniMode(normalizeFakeTlsSniMode(snapshot.fakeTlsSniMode))
        .setIpIdMode(normalizeIpIdMode(snapshot.ipIdMode))
        .setHttpFakeProfile(normalizeHttpFakeProfile(snapshot.httpFakeProfile))
        .setTlsFakeProfile(normalizeTlsFakeProfile(snapshot.tlsFakeProfile))
        .setOobData(snapshot.oobData)
        .setDropSack(snapshot.dropSack)
        .setDesyncHttp(snapshot.desyncHttp)
        .setDesyncHttps(snapshot.desyncHttps)
        .setDesyncUdp(snapshot.desyncUdp)
        .setHostsMode(snapshot.hostsMode)
        .setHostsBlacklist(snapshot.hostsBlacklist)
        .setHostsWhitelist(snapshot.hostsWhitelist)
        .setUdpFakeProfile(normalizeUdpFakeProfile(snapshot.udpFakeProfile))
        .setHostMixedCase(snapshot.hostMixedCase)
        .setDomainMixedCase(snapshot.domainMixedCase)
        .setHostRemoveSpaces(snapshot.hostRemoveSpaces)
        .setHttpMethodEol(snapshot.httpMethodEol)
        .setHttpUnixEol(snapshot.httpUnixEol)
        .setHttpMethodSpace(snapshot.httpMethodSpace)
        .setHttpHostPad(snapshot.httpHostPad)
        .setDesyncAnyProtocol(snapshot.desyncAnyProtocol)
