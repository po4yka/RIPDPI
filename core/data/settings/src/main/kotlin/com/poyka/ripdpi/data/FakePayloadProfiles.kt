package com.poyka.ripdpi.data

import com.poyka.ripdpi.proto.AppSettings

const val FakePayloadProfileCompatDefault = "compat_default"

const val HttpFakeProfileIanaGet = "iana_get"
const val HttpFakeProfileCloudflareGet = "cloudflare_get"

const val TlsFakeProfileIanaFirefox = "iana_firefox"
const val TlsFakeProfileGoogleChrome = "google_chrome"
const val TlsFakeProfileGoogleChromeHrr = "google_chrome_hrr"
const val TlsFakeProfileVkChrome = "vk_chrome"
const val TlsFakeProfileSberbankChrome = "sberbank_chrome"
const val TlsFakeProfileRutrackerKyber = "rutracker_kyber"
const val TlsFakeProfileBigsizeIana = "bigsize_iana"

const val UdpFakeProfileZero256 = "zero_256"
const val UdpFakeProfileZero512 = "zero_512"
const val UdpFakeProfileDnsQuery = "dns_query"
const val UdpFakeProfileStunBinding = "stun_binding"
const val UdpFakeProfileWireguardInitiation = "wireguard_initiation"
const val UdpFakeProfileDhtGetPeers = "dht_get_peers"

private val KnownHttpFakeProfiles =
    setOf(
        FakePayloadProfileCompatDefault,
        HttpFakeProfileIanaGet,
        HttpFakeProfileCloudflareGet,
    )

private val KnownTlsFakeProfiles =
    setOf(
        FakePayloadProfileCompatDefault,
        TlsFakeProfileIanaFirefox,
        TlsFakeProfileGoogleChrome,
        TlsFakeProfileGoogleChromeHrr,
        TlsFakeProfileVkChrome,
        TlsFakeProfileSberbankChrome,
        TlsFakeProfileRutrackerKyber,
        TlsFakeProfileBigsizeIana,
    )

private val KnownUdpFakeProfiles =
    setOf(
        FakePayloadProfileCompatDefault,
        UdpFakeProfileZero256,
        UdpFakeProfileZero512,
        UdpFakeProfileDnsQuery,
        UdpFakeProfileStunBinding,
        UdpFakeProfileWireguardInitiation,
        UdpFakeProfileDhtGetPeers,
    )

fun normalizeHttpFakeProfile(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in KnownHttpFakeProfiles } ?: FakePayloadProfileCompatDefault
}

fun normalizeTlsFakeProfile(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in KnownTlsFakeProfiles } ?: FakePayloadProfileCompatDefault
}

fun normalizeUdpFakeProfile(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in KnownUdpFakeProfiles } ?: FakePayloadProfileCompatDefault
}

fun AppSettings.effectiveHttpFakeProfile(): String = normalizeHttpFakeProfile(httpFakeProfile)

fun AppSettings.effectiveTlsFakeProfile(): String = normalizeTlsFakeProfile(tlsFakeProfile)

fun AppSettings.effectiveUdpFakeProfile(): String = normalizeUdpFakeProfile(udpFakeProfile)
