package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.TunForwardingEvidence
import com.poyka.ripdpi.data.AppSettingsSerializer
import com.poyka.ripdpi.data.activeDnsSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class VpnTunnelForwardingEvidenceTest {
    @Test
    fun forwardingEvidencePollIsAvailableAndFailureSafeWhileRunning() =
        runTest {
            val bridge =
                TestTun2SocksBridge().apply {
                    forwardingEvidence = TunForwardingEvidence(tunReadBytes = 22, tunWriteBytes = 11)
                }
            val runtime =
                VpnTunnelRuntime(
                    vpnHost = TestVpnServiceHost(backgroundScope),
                    appSettingsRepository = TestAppSettingsRepository(),
                    proxyGroupRepository = TestProxyGroupRepository(),
                    tun2SocksBridgeFactory = TestTun2SocksBridgeFactory(bridge),
                    vpnTunnelSessionProvider = TestVpnTunnelSessionProvider(session = TestVpnTunnelSession()),
                )
            runtime.start(
                activeDns = AppSettingsSerializer.defaultValue.activeDnsSettings(),
                overrideReason = null,
                logContext = null,
                localProxyEndpoint =
                    LocalProxyEndpoint(
                        host = "127.0.0.1",
                        port = 18_080,
                    ),
            )

            assertEquals(22L, runtime.pollForwardingEvidence()?.tunReadBytes)
            assertEquals(11L, runtime.pollForwardingEvidence()?.tunWriteBytes)
            bridge.forwardingEvidenceFailure = IOException("poll failed")
            assertNull(runtime.pollForwardingEvidence())

            runtime.stop()
        }
}
