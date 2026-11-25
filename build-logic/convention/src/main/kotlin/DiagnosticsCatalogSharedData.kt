internal object DiagnosticsCatalogSharedData {
    val publicDnsResolvers = listOf("8.8.8.8:53", "1.1.1.1:53", "9.9.9.9:53", "94.140.14.14:53")

    val defaultWhitelist = listOf("vk.com", "dzen.ru", "2gis.com")

    val tcpTlsControls =
        listOf(
            TcpTargetDefinition(
                id = "CF-DNS",
                provider = "Cloudflare DNS",
                ip = "1.1.1.1",
                asn = "13335",
                sni = "cloudflare-dns.com",
            ),
            TcpTargetDefinition(
                id = "GOOG-01",
                provider = "Google DNS",
                ip = "8.8.8.8",
                asn = "15169",
                sni = "dns.google",
            ),
        )

    private val telegramPrimaryDcEndpoints =
        listOf(
            TelegramDcEndpointDefinition(ip = "149.154.175.50", label = "DC1", port = 443),
            TelegramDcEndpointDefinition(ip = "149.154.167.51", label = "DC2", port = 443),
            TelegramDcEndpointDefinition(ip = "149.154.175.100", label = "DC3", port = 443),
            TelegramDcEndpointDefinition(ip = "149.154.167.91", label = "DC4", port = 443),
            TelegramDcEndpointDefinition(ip = "149.154.171.5", label = "DC5", port = 443),
        ).flatMap { endpoint -> listOf(endpoint, endpoint.copy(port = 80)) }

    val dpiTelegramTarget =
        TelegramTargetDefinition(
            mediaUrl = "https://telegram.org/img/Telegram200million.png",
            uploadIp = "149.154.167.99",
            dcEndpoints = telegramPrimaryDcEndpoints,
        )

    val messagingTelegramTarget =
        TelegramTargetDefinition(
            mediaUrl = "https://telegram.org/",
            uploadIp = "149.154.167.51",
            dcEndpoints = telegramPrimaryDcEndpoints.filter { it.label == "DC1" || it.label == "DC2" },
        )
}
