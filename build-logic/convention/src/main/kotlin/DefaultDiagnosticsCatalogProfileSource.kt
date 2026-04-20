internal object DefaultDiagnosticsCatalogProfileSource : DiagnosticsCatalogProfileSource {
    override fun load(index: DiagnosticsCatalogIndex): List<DiagnosticsProfileDefinition> =
        listOf(
            defaultProfile(),
            automaticProbingProfile(),
            automaticAuditProfile(),
            dpiDetectorFullProfile(),
            ruWebConnectivityProfile(index),
            ruMessagingProfile(index),
            ruCircumventionProfile(index),
            ruThrottlingProfile(index),
            ruDpiFullProfile(index),
        )

    private fun defaultProfile(): DiagnosticsProfileDefinition =
        DiagnosticsProfileDefinition(
            id = "default",
            name = "Default diagnostics",
            executionPolicy = policy(manualOnly = false, allowBackground = false, requiresRawPath = false),
            domainTargets =
                domainTargets(
                    """
                    www.youtube.com
                    discord.com
                    proton.me
                    cloudflare.com
                    www.google.com
                    """.trimIndent(),
                ),
            dnsTargets =
                domainTargets(
                    """
                    rutor.info
                    rezka.ag
                    shikimori.one
                    """.trimIndent(),
                ).map { DnsTargetDefinition(domain = it.host) },
            tcpTargets = DiagnosticsCatalogSharedData.defaultTcpTargets,
            whitelistSni = DiagnosticsCatalogSharedData.defaultWhitelist,
        )

    private fun automaticProbingProfile(): DiagnosticsProfileDefinition =
        DiagnosticsProfileDefinition(
            id = "automatic-probing",
            name = "Automatic probing",
            kind = CatalogScanKind.STRATEGY_PROBE,
            family = CatalogDiagnosticProfileFamily.AUTOMATIC_PROBING,
            executionPolicy = policy(manualOnly = false, allowBackground = true, requiresRawPath = true),
            domainTargets =
                domainTargets(
                    """
                    www.youtube.com
                    discord.com
                    proton.me
                    """.trimIndent(),
                ),
            quicTargets =
                quicTargets(
                    """
                    www.youtube.com
                    discord.com
                    """.trimIndent(),
                ),
            strategyProbe = StrategyProbeDefinition(suiteId = "quick_v1"),
        )

    private fun automaticAuditProfile(): DiagnosticsProfileDefinition =
        DiagnosticsProfileDefinition(
            id = "automatic-audit",
            name = "Automatic audit",
            kind = CatalogScanKind.STRATEGY_PROBE,
            family = CatalogDiagnosticProfileFamily.AUTOMATIC_AUDIT,
            executionPolicy = policy(manualOnly = false, allowBackground = true, requiresRawPath = true),
            domainTargets =
                domainTargets(
                    """
                    www.youtube.com
                    discord.com
                    proton.me
                    """.trimIndent(),
                ),
            quicTargets =
                quicTargets(
                    """
                    www.youtube.com
                    discord.com
                    """.trimIndent(),
                ),
            strategyProbe = StrategyProbeDefinition(suiteId = "full_matrix_v1"),
        )

    private fun dpiDetectorFullProfile(): DiagnosticsProfileDefinition =
        DiagnosticsProfileDefinition(
            id = "dpi-detector-full",
            name = "DPI Detector Full",
            executionPolicy = policy(manualOnly = false, allowBackground = false, requiresRawPath = false),
            domainTargets = DiagnosticsCatalogDpiData.domainTargets,
            dnsTargets =
                udpDnsTargets(
                    domains = listOf("rutor.info", "rezka.ag", "signal.org", "discord.com", "youtube.com", "proton.me"),
                    servers = DiagnosticsCatalogSharedData.publicDnsResolvers,
                ),
            tcpTargets = DiagnosticsCatalogDpiData.tcpTargets,
            whitelistSni = DiagnosticsCatalogDpiData.whitelistSni,
            telegramTarget = DiagnosticsCatalogSharedData.dpiTelegramTarget,
        )

    private fun ruWebConnectivityProfile(index: DiagnosticsCatalogIndex): DiagnosticsProfileDefinition {
        val independentMedia = index.requirePack("ru-independent-media")
        val globalPlatforms = index.requirePack("ru-global-platforms")
        val control = index.requirePack("ru-control")
        return DiagnosticsProfileDefinition(
            id = "ru-web-connectivity",
            name = "Russia Web Connectivity",
            family = CatalogDiagnosticProfileFamily.WEB_CONNECTIVITY,
            regionTag = "ru",
            executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
            packRefs =
                listOf(
                    packRef("ru-independent-media", 1),
                    packRef("ru-global-platforms", 1),
                    packRef("ru-control", 1),
                ),
            domainTargets = independentMedia.domainTargets + globalPlatforms.domainTargets,
            dnsTargets = independentMedia.dnsTargets + globalPlatforms.dnsTargets,
            quicTargets = globalPlatforms.quicTargets,
            tcpTargets = control.tcpTargets,
            whitelistSni = control.whitelistSni,
        )
    }

    private fun ruMessagingProfile(index: DiagnosticsCatalogIndex): DiagnosticsProfileDefinition {
        val messaging = index.requirePack("ru-messaging")
        return DiagnosticsProfileDefinition(
            id = "ru-messaging",
            name = "Russia Messaging Services",
            family = CatalogDiagnosticProfileFamily.MESSAGING,
            regionTag = "ru",
            executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
            packRefs =
                listOf(
                    packRef("ru-messaging", 1),
                    packRef("ru-control", 1),
                ),
            domainTargets = messaging.domainTargets,
            quicTargets = messaging.quicTargets,
            serviceTargets = messaging.serviceTargets,
            telegramTarget = DiagnosticsCatalogSharedData.messagingTelegramTarget,
        )
    }

    private fun ruCircumventionProfile(index: DiagnosticsCatalogIndex): DiagnosticsProfileDefinition {
        val circumvention = index.requirePack("ru-circumvention")
        return DiagnosticsProfileDefinition(
            id = "ru-circumvention",
            name = "Russia Sensitive Services Reachability",
            family = CatalogDiagnosticProfileFamily.CIRCUMVENTION,
            regionTag = "ru",
            executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
            packRefs =
                listOf(
                    packRef("ru-circumvention", 1),
                    packRef("ru-control", 1),
                ),
            domainTargets = circumvention.domainTargets,
            circumventionTargets = circumvention.circumventionTargets,
        )
    }

    private fun ruThrottlingProfile(index: DiagnosticsCatalogIndex): DiagnosticsProfileDefinition {
        val throttling = index.requirePack("ru-throttling")
        val control = index.requirePack("neutral-control")
        return DiagnosticsProfileDefinition(
            id = "ru-throttling",
            name = "Russia Throttling Check",
            version = 2,
            family = CatalogDiagnosticProfileFamily.THROTTLING,
            regionTag = "ru",
            executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
            packRefs =
                listOf(
                    packRef("ru-throttling", 1),
                    packRef("neutral-control", 2),
                ),
            domainTargets = throttling.domainTargets + control.domainTargets,
            throughputTargets = throttling.throughputTargets + control.throughputTargets,
        )
    }

    private fun ruDpiFullProfile(index: DiagnosticsCatalogIndex): DiagnosticsProfileDefinition {
        val independentMedia = index.requirePack("ru-independent-media")
        val globalPlatforms = index.requirePack("ru-global-platforms")
        val messaging = index.requirePack("ru-messaging")
        val circumvention = index.requirePack("ru-circumvention")
        val throttling = index.requirePack("ru-throttling")
        val neutralControl = index.requirePack("neutral-control")
        val control = index.requirePack("ru-control")
        return DiagnosticsProfileDefinition(
            id = "ru-dpi-full",
            name = "Russia DPI Full",
            version = 2,
            family = CatalogDiagnosticProfileFamily.DPI_FULL,
            regionTag = "ru",
            executionPolicy = policy(manualOnly = true, allowBackground = false, requiresRawPath = false),
            packRefs =
                listOf(
                    packRef("ru-independent-media", 1),
                    packRef("ru-global-platforms", 1),
                    packRef("ru-messaging", 1),
                    packRef("ru-circumvention", 1),
                    packRef("ru-throttling", 1),
                    packRef("neutral-control", 2),
                ),
            domainTargets =
                (
                    independentMedia.domainTargets +
                        globalPlatforms.domainTargets +
                        messaging.domainTargets +
                        circumvention.domainTargets
                ).distinctBy { it.host.lowercase() },
            dnsTargets =
                listOf(
                    DnsTargetDefinition(domain = "cloudflare.com", udpServer = "1.1.1.1:53"),
                    DnsTargetDefinition(domain = "youtube.com", udpServer = "1.1.1.1:53"),
                    DnsTargetDefinition(domain = "discord.com", udpServer = "8.8.8.8:53"),
                    DnsTargetDefinition(domain = "signal.org", udpServer = "1.1.1.1:53"),
                    DnsTargetDefinition(domain = "telegram.org", udpServer = "8.8.8.8:53"),
                ),
            quicTargets =
                quicTargets(
                    """
                    www.youtube.com
                    discord.com
                    www.whatsapp.com
                    """.trimIndent(),
                ),
            tcpTargets = DiagnosticsCatalogSharedData.neutralTcpTargets,
            serviceTargets = messaging.serviceTargets,
            circumventionTargets = circumvention.circumventionTargets,
            throughputTargets = listOf(throttling.throughputTargets.single(), neutralControl.throughputTargets.first()),
            whitelistSni = control.whitelistSni,
        )
    }
}
