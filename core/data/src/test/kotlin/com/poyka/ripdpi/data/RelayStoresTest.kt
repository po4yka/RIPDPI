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
                masqueAuthMode = "cloudflare_p256",
                masqueAuthToken = "bearer",
                masqueCloudflareClientId = "client-id",
                masqueCloudflareKeyId = "key-id",
                masqueCloudflarePrivateKeyPem = "pem-data",
            )

        val encoded = json.encodeToString(RelayCredentialRecord.serializer(), record)
        val decoded = json.decodeFromString(RelayCredentialRecord.serializer(), encoded)

        assertEquals(record, decoded)
    }
}
