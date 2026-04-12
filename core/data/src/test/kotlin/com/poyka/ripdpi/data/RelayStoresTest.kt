package com.poyka.ripdpi.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayStoresTest {
    private fun sampleCloudflareValue(): String = "sample-cloudflare-value"

    @Test
    fun `relay credential record preserves masque auth variants`() {
        val json = Json { ignoreUnknownKeys = true }
        val record =
            RelayCredentialRecord(
                profileId = "masque",
                masqueAuthMode = RelayMasqueAuthModeCloudflareMtls,
                masqueClientCertificateChainPem = "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----",
                masqueClientPrivateKeyPem = "-----BEGIN PRIVATE KEY-----\nfixture\n-----END PRIVATE KEY-----",
            )

        val encoded = json.encodeToString(RelayCredentialRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayCredentialRecord.serializer(), encoded)

        assertEquals(record, decoded)
    }

    @Test
    fun `relay credential record preserves tuic shadowtls and naive credentials`() {
        val json = Json { ignoreUnknownKeys = true }
        val record =
            RelayCredentialRecord(
                profileId = "multi",
                tuicUuid = "00000000-0000-0000-0000-000000000000",
                tuicPassword = "tuic-fixture",
                shadowTlsPassword = "shadow-fixture",
                naiveUsername = "naive-user",
                naivePassword = "naive-fixture",
            )

        val encoded = json.encodeToString(RelayCredentialRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayCredentialRecord.serializer(), encoded)

        assertEquals(record, decoded)
    }

    @Test
    fun `relay credential record preserves cloudflare tunnel material`() {
        val json = Json { ignoreUnknownKeys = true }
        val record =
            RelayCredentialRecord(
                profileId = "cf-publish",
                vlessUuid = "00000000-0000-0000-0000-000000000000",
                cloudflareTunnelToken = sampleCloudflareValue(),
                cloudflareTunnelCredentialsJson = """{"AccountTag":"fixture","TunnelSecret":"secret"}""",
            )

        val encoded = json.encodeToString(RelayCredentialRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayCredentialRecord.serializer(), encoded)

        assertEquals(record, decoded)
    }

    @Test
    fun `relay profile record preserves xHTTP and Cloudflare tunnel fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val record =
            RelayProfileRecord(
                id = "cf-tunnel",
                kind = RelayKindCloudflareTunnel,
                presetId = "ru-mobile-relay",
                outboundBindIp = "192.0.2.15",
                server = "edge.example.com",
                serverName = "edge.example.com",
                vlessTransport = RelayVlessTransportXhttp,
                xhttpPath = "/xhttp",
                xhttpHost = "origin.example.com",
                cloudflareTunnelMode = RelayCloudflareTunnelModePublishLocalOrigin,
                cloudflarePublishLocalOriginUrl = "http://127.0.0.1:8080",
                cloudflareCredentialsRef = "cf-credentials",
                udpEnabled = false,
            )

        val encoded = json.encodeToString(RelayProfileRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayProfileRecord.serializer(), encoded)

        assertEquals(record, decoded)
    }

    @Test
    fun `relay profile record preserves chain profile refs and p2 relay fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val record =
            RelayProfileRecord(
                id = "p2",
                kind = RelayKindTuicV5,
                chainEntryProfileId = "entry",
                chainExitProfileId = "exit",
                masqueCloudflareGeohashEnabled = true,
                tuicZeroRtt = true,
                tuicCongestionControl = RelayCongestionControlCubic,
                shadowTlsInnerProfileId = "inner",
                naivePath = "/proxy",
            )

        val encoded = json.encodeToString(RelayProfileRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayProfileRecord.serializer(), encoded)

        assertEquals(record, decoded)
    }

    @Test
    fun `relay profile record preserves finalmask fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val record =
            RelayProfileRecord(
                id = "finalmask",
                kind = RelayKindMasque,
                finalmaskType = RelayFinalmaskTypeFragment,
                finalmaskHeaderHex = "abcd",
                finalmaskTrailerHex = "ef01",
                finalmaskRandRange = "32-96",
                finalmaskSudokuSeed = "fixture-seed",
                finalmaskFragmentPackets = 3,
                finalmaskFragmentMinBytes = 32,
                finalmaskFragmentMaxBytes = 96,
            )

        val encoded = json.encodeToString(RelayProfileRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayProfileRecord.serializer(), encoded)

        assertEquals(record, decoded)
    }
}
