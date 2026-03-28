package com.poyka.ripdpi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FakePayloadProfilesTest {
    @Test
    fun `normalizeHttpFakeProfile returns known profile unchanged`() {
        assertEquals(HttpFakeProfileCloudflareGet, normalizeHttpFakeProfile("cloudflare_get"))
        assertEquals(HttpFakeProfileIanaGet, normalizeHttpFakeProfile("iana_get"))
        assertEquals(FakePayloadProfileCompatDefault, normalizeHttpFakeProfile("compat_default"))
    }

    @Test
    fun `normalizeHttpFakeProfile lowercases and trims input`() {
        assertEquals(HttpFakeProfileCloudflareGet, normalizeHttpFakeProfile("  CLOUDFLARE_GET  "))
    }

    @Test
    fun `normalizeHttpFakeProfile falls back to compat default for unknown`() {
        assertEquals(FakePayloadProfileCompatDefault, normalizeHttpFakeProfile("unknown_profile"))
        assertEquals(FakePayloadProfileCompatDefault, normalizeHttpFakeProfile(""))
    }

    @Test
    fun `normalizeTlsFakeProfile returns known profile unchanged`() {
        assertEquals(TlsFakeProfileGoogleChrome, normalizeTlsFakeProfile("google_chrome"))
        assertEquals(TlsFakeProfileIanaFirefox, normalizeTlsFakeProfile("iana_firefox"))
        assertEquals(TlsFakeProfileVkChrome, normalizeTlsFakeProfile("vk_chrome"))
        assertEquals(TlsFakeProfileSberbankChrome, normalizeTlsFakeProfile("sberbank_chrome"))
        assertEquals(TlsFakeProfileRutrackerKyber, normalizeTlsFakeProfile("rutracker_kyber"))
        assertEquals(TlsFakeProfileBigsizeIana, normalizeTlsFakeProfile("bigsize_iana"))
    }

    @Test
    fun `normalizeTlsFakeProfile lowercases and trims input`() {
        assertEquals(TlsFakeProfileGoogleChrome, normalizeTlsFakeProfile("  GOOGLE_CHROME  "))
    }

    @Test
    fun `normalizeTlsFakeProfile falls back to compat default for unknown`() {
        assertEquals(FakePayloadProfileCompatDefault, normalizeTlsFakeProfile("nonexistent"))
    }

    @Test
    fun `normalizeUdpFakeProfile returns known profile unchanged`() {
        assertEquals(UdpFakeProfileDnsQuery, normalizeUdpFakeProfile("dns_query"))
        assertEquals(UdpFakeProfileStunBinding, normalizeUdpFakeProfile("stun_binding"))
        assertEquals(UdpFakeProfileZero256, normalizeUdpFakeProfile("zero_256"))
        assertEquals(UdpFakeProfileZero512, normalizeUdpFakeProfile("zero_512"))
        assertEquals(UdpFakeProfileWireguardInitiation, normalizeUdpFakeProfile("wireguard_initiation"))
        assertEquals(UdpFakeProfileDhtGetPeers, normalizeUdpFakeProfile("dht_get_peers"))
    }

    @Test
    fun `normalizeUdpFakeProfile lowercases and trims input`() {
        assertEquals(UdpFakeProfileDnsQuery, normalizeUdpFakeProfile("  DNS_QUERY  "))
    }

    @Test
    fun `normalizeUdpFakeProfile falls back to compat default for unknown`() {
        assertEquals(FakePayloadProfileCompatDefault, normalizeUdpFakeProfile("invalid"))
    }
}
