internal object DefaultDiagnosticsCatalogPackSource : DiagnosticsCatalogPackSource {
    override fun load(): List<TargetPackDefinition> =
        listOf(
            TargetPackDefinition(
                id = "ru-independent-media",
                version = 1,
                domainTargets =
                    domainTargets(
                        """
                        cloudflare.com
                        www.google.com
                        """.trimIndent(),
                    ),
                dnsTargets =
                    udpDnsTargets(
                        domains = listOf("cloudflare.com", "google.com"),
                        servers = listOf("8.8.8.8:53", "1.1.1.1:53"),
                    ),
            ),
            TargetPackDefinition(
                id = "ru-global-platforms",
                version = 1,
                domainTargets =
                    domainTargets(
                        """
                        www.youtube.com
                        discord.com
                        proton.me
                        """.trimIndent(),
                    ),
                dnsTargets =
                    listOf(
                        DnsTargetDefinition(domain = "youtube.com", udpServer = "8.8.8.8:53"),
                        DnsTargetDefinition(domain = "youtube.com", udpServer = "1.1.1.1:53"),
                        DnsTargetDefinition(domain = "discord.com", udpServer = "8.8.8.8:53"),
                        DnsTargetDefinition(domain = "proton.me", udpServer = "1.1.1.1:53"),
                    ),
                quicTargets =
                    quicTargets(
                        """
                        www.youtube.com
                        discord.com
                        """.trimIndent(),
                    ),
            ),
            TargetPackDefinition(
                id = "ru-control",
                version = 1,
                tcpTargets = DiagnosticsCatalogSharedData.defaultTcpTargets,
                whitelistSni = DiagnosticsCatalogSharedData.defaultWhitelist,
            ),
            TargetPackDefinition(
                id = "ru-messaging",
                version = 1,
                domainTargets =
                    domainTargets(
                        """
                        telegram.org
                        signal.org
                        www.whatsapp.com
                        discord.com
                        """.trimIndent(),
                    ),
                quicTargets =
                    quicTargets(
                        """
                        www.whatsapp.com
                        discord.com
                        """.trimIndent(),
                    ),
                serviceTargets =
                    listOf(
                        ServiceTargetDefinition(
                            id = "telegram",
                            service = "Telegram",
                            bootstrapUrl = "https://telegram.org/",
                            tcpEndpointHost = "telegram.org",
                        ),
                        ServiceTargetDefinition(
                            id = "signal",
                            service = "Signal",
                            bootstrapUrl = "https://signal.org/",
                            tcpEndpointHost = "signal.org",
                        ),
                        ServiceTargetDefinition(
                            id = "whatsapp",
                            service = "WhatsApp",
                            bootstrapUrl = "https://www.whatsapp.com/",
                            tcpEndpointHost = "www.whatsapp.com",
                        ),
                        ServiceTargetDefinition(
                            id = "discord",
                            service = "Discord",
                            bootstrapUrl = "https://discord.com/",
                            tcpEndpointHost = "discord.com",
                        ),
                    ),
            ),
            TargetPackDefinition(
                id = "ru-circumvention",
                version = 1,
                domainTargets =
                    domainTargets(
                        """
                        telegram.org
                        signal.org
                        proton.me
                        www.whatsapp.com
                        """.trimIndent(),
                    ),
                circumventionTargets = emptyList(),
            ),
            TargetPackDefinition(
                id = "ru-throttling",
                version = 1,
                domainTargets = domainTargets("www.youtube.com"),
                throughputTargets =
                    listOf(
                        ThroughputTargetDefinition(
                            id = "youtube-web",
                            label = "YouTube Web",
                            url = "https://www.youtube.com/",
                        ),
                    ),
            ),
            TargetPackDefinition(
                id = "neutral-control",
                version = 2,
                domainTargets =
                    domainTargets(
                        """
                        speed.cloudflare.com
                        proof.ovh.net
                        """.trimIndent(),
                    ),
                tcpTargets = DiagnosticsCatalogSharedData.neutralTcpTargets,
                throughputTargets =
                    listOf(
                        ThroughputTargetDefinition(
                            id = "cloudflare-control",
                            label = "Cloudflare Control",
                            url = "https://speed.cloudflare.com/__down?bytes=8388608",
                            isControl = true,
                        ),
                        ThroughputTargetDefinition(
                            id = "ovh-control",
                            label = "OVH Control",
                            url = "https://proof.ovh.net/files/10Mb.dat",
                            isControl = true,
                        ),
                    ),
            ),
        )
}
