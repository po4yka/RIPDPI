package com.poyka.ripdpi.proxyimport

import com.poyka.ripdpi.data.ProxyProfile
import com.poyka.ripdpi.data.RelayCredentialRecord
import com.poyka.ripdpi.data.RelayKindVlessReality
import com.poyka.ripdpi.data.RelayProfileRecord
import com.poyka.ripdpi.data.RelayVlessTransportRealityTcp
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayProfileProjectionTest {
    @Test
    fun `vless reality projection preserves the exact activation record and credentials without stores`() {
        val profile =
            ProxyProfile.VlessReality(
                id = "imported-profile",
                displayName = "Owner relay",
                groupId = "import-group",
                server = "203.0.113.24",
                serverPort = 8443,
                uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                realityPublicKey = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=",
                realityShortId = "a1b2c3d4",
                serverName = "decoy.example.com",
                flow = "xtls-rprx-vision-udp443",
                fingerprint = "firefox",
            )

        val actual = RelayProfileProjection.from(profile, profileId = "session-profile")!!
        val normalized =
            actual.copy(
                credentials = actual.credentials.copy(updatedAtEpochMillis = 0L),
            )

        assertEquals(
            RelayProfileProjection(
                profile =
                    RelayProfileRecord(
                        id = "session-profile",
                        kind = RelayKindVlessReality,
                        server = "203.0.113.24",
                        serverPort = 8443,
                        serverName = "decoy.example.com",
                        realityPublicKey = "q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=",
                        realityShortId = "a1b2c3d4",
                        vlessFlow = "xtls-rprx-vision-udp443",
                        vlessFingerprint = "firefox",
                        vlessTransport = RelayVlessTransportRealityTcp,
                        udpEnabled = false,
                    ),
                credentials =
                    RelayCredentialRecord(
                        profileId = "session-profile",
                        vlessUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                        updatedAtEpochMillis = 0L,
                    ),
            ),
            normalized,
        )
    }
}
