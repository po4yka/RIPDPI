package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.RipDpiWarpEndpointProbeBindings
import com.poyka.ripdpi.data.WarpCredentials
import com.poyka.ripdpi.data.WarpEndpointCacheEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetAddress

class DefaultWarpEndpointProbeTest {
    @Test
    fun `an endpoint that never answers the handshake is not a candidate`() =
        runTest {
            // A bound UDP port that reads but never replies: the same view as a black-holed endpoint.
            DatagramSocket(0, InetAddress.getLoopbackAddress()).use { silentEndpoint ->
                val credentials =
                    FakeWarpCredentialStore().apply {
                        save(
                            "warp",
                            WarpCredentials(
                                deviceId = "device",
                                accessToken = "token",
                                privateKey = "private-key",
                                peerPublicKey = "peer-public-key",
                            ),
                        )
                    }
                val probe =
                    DefaultWarpEndpointProbe(
                        appSettingsRepository = TestAppSettingsRepository(),
                        credentialStore = credentials,
                        nativeBindings =
                            object : RipDpiWarpEndpointProbeBindings {
                                override fun probeEndpoint(requestJson: String): String? = null
                            },
                    )

                val probed =
                    probe.probe(
                        WarpEndpointCacheEntry(
                            profileId = "warp",
                            networkScopeKey = "wifi",
                            ipv4 = "127.0.0.1",
                            port = silentEndpoint.localPort,
                        ),
                        timeoutMillis = 250,
                    )

                assertNull(probed)
            }
        }
}
