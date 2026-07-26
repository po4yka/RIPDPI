package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.NetworkFingerprint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidNetworkFingerprintProviderTest {
    private val mapper = NetworkFingerprintMapper()

    @Test
    fun `maps captured snapshot into a fingerprint`() {
        val provider =
            AndroidNetworkFingerprintProvider(
                snapshotSource =
                    FixedSnapshotSource(
                        CapturedNetworkSnapshot(
                            directDnsUnderlayGeneration = 17L,
                            transports = setOf(CapturedTransport.Wifi),
                            wifi = CapturedWifiIdentity(ssid = "\"Cafe Wifi\""),
                        ),
                    ),
                mapper = mapper,
            )

        val fingerprint = provider.capture()

        assertEquals("wifi", fingerprint?.transport)
        assertEquals("cafe wifi", fingerprint?.wifi?.ssid)
        assertEquals(17L, fingerprint?.directDnsUnderlayGeneration)
        assertEquals(fingerprint?.copy(directDnsUnderlayGeneration = null)?.scopeKey(), fingerprint?.scopeKey())
        val encoded = Json.encodeToString(NetworkFingerprint.serializer(), checkNotNull(fingerprint))
        assertEquals(false, encoded.contains("directDnsUnderlayGeneration"))
    }

    @Test
    fun `returns null when snapshot capture throws`() {
        val provider =
            AndroidNetworkFingerprintProvider(
                snapshotSource = ThrowingSnapshotSource(SecurityException("missing ACCESS_WIFI_STATE")),
                mapper = mapper,
            )

        assertNull(provider.capture())
    }

    private class FixedSnapshotSource(
        private val snapshot: CapturedNetworkSnapshot?,
    ) : AndroidNetworkSnapshotSource {
        override fun capture(): CapturedNetworkSnapshot? = snapshot
    }

    private class ThrowingSnapshotSource(
        private val error: RuntimeException,
    ) : AndroidNetworkSnapshotSource {
        override fun capture(): CapturedNetworkSnapshot? = throw error
    }
}
