package com.poyka.ripdpi.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayStoresTest {
    @Test
    fun `relay credential record preserves masque auth variants`() {
        val json = Json { ignoreUnknownKeys = true }
        val record =
            RelayCredentialRecord(
                profileId = "masque",
                masqueAuthMode = RelayMasqueAuthModePrivacyPass,
                masqueAuthToken = "bearer",
                masqueCloudflareClientId = "client-id",
                masqueCloudflareKeyId = "key-id",
                masqueCloudflarePrivateKeyPem = "pem-data",
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
                udpEnabled = false,
            )

        val encoded = json.encodeToString(RelayProfileRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayProfileRecord.serializer(), encoded)

        assertEquals(record, decoded)
    }
}
