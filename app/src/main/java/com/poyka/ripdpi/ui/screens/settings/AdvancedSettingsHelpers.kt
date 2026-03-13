package com.poyka.ripdpi.ui.screens.settings

internal fun formatHttpFakeProfileLabel(value: String): String =
    when (value) {
        "iana_get" -> "IANA GET"
        "cloudflare_get" -> "Cloudflare GET"
        else -> "Compatibility default"
    }

internal fun formatTlsFakeProfileLabel(value: String): String =
    when (value) {
        "iana_firefox" -> "IANA Firefox"
        "google_chrome" -> "Google Chrome"
        "vk_chrome" -> "VK Chrome"
        "sberbank_chrome" -> "Sberbank Chrome"
        "rutracker_kyber" -> "Rutracker Kyber"
        "bigsize_iana" -> "IANA bigsize"
        else -> "Compatibility default"
    }

internal fun formatUdpFakeProfileLabel(value: String): String =
    when (value) {
        "zero_256" -> "Zero blob 256"
        "zero_512" -> "Zero blob 512"
        "dns_query" -> "DNS query"
        "stun_binding" -> "STUN binding"
        "wireguard_initiation" -> "WireGuard initiation"
        "dht_get_peers" -> "DHT get_peers"
        else -> "Compatibility default"
    }

internal fun isActivationBoundaryValid(
    value: String,
    minValue: Long,
): Boolean = value.isBlank() || value.toLongOrNull()?.let { it >= minValue } == true
