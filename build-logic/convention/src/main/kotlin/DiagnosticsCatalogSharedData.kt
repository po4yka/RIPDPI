internal object DiagnosticsCatalogSharedData {
    val publicDnsResolvers = listOf("8.8.8.8:53", "1.1.1.1:53", "9.9.9.9:53", "94.140.14.14:53")

    val defaultWhitelist = listOf("vk.com", "dzen.ru", "2gis.com")

    val defaultTcpTargets =
        tcpTargets(
            """
            CF-01|Cloudflare|172.67.70.222|443|13335
            AWS-01|AWS|16.15.219.241|443|16509
            """.trimIndent(),
        )

    val neutralTcpTargets =
        tcpTargets(
            """
            CF-01|Cloudflare|172.67.70.222|443|13335
            AWS-01|AWS|16.15.219.241|443|16509
            GC-01|Google Cloud|147.189.239.240|443|396982
            """.trimIndent(),
        )

    val dpiTelegramTarget =
        TelegramTargetDefinition(
            mediaUrl = "https://telegram.org/img/Telegram200million.png",
            uploadIp = "149.154.167.99",
            dcEndpoints =
                listOf(
                    TelegramDcEndpointDefinition(ip = "149.154.175.53", label = "DC1"),
                    TelegramDcEndpointDefinition(ip = "149.154.167.51", label = "DC2"),
                    TelegramDcEndpointDefinition(ip = "149.154.175.100", label = "DC3"),
                    TelegramDcEndpointDefinition(ip = "149.154.167.91", label = "DC4"),
                    TelegramDcEndpointDefinition(ip = "91.108.56.130", label = "DC5"),
                ),
        )

    val messagingTelegramTarget =
        TelegramTargetDefinition(
            mediaUrl = "https://telegram.org/",
            uploadIp = "149.154.167.51",
            dcEndpoints =
                listOf(
                    TelegramDcEndpointDefinition(ip = "149.154.175.50", label = "DC1"),
                    TelegramDcEndpointDefinition(ip = "149.154.167.51", label = "DC2"),
                ),
        )
}
