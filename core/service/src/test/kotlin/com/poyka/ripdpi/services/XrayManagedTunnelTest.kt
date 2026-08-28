package com.poyka.ripdpi.services

import com.poyka.ripdpi.core.DnsLoopOwner
import com.poyka.ripdpi.core.TunnelUpstream
import com.poyka.ripdpi.data.ActiveDnsSettings
import com.poyka.ripdpi.data.DnsModePlainUdp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Managed-tunnel mapping (decision 3): Xray upstream -> loopback LocalProxyEndpoint
 * driven onto the tunnel; Native upstream is a guard error.
 */
class XrayManagedTunnelTest {
    private val params =
        XrayTunnelStartParams(
            activeDns = plainDns(),
            overrideReason = null,
            logContext = null,
            forceTunnelDns = false,
        )

    @Test
    fun `maps Xray upstream to loopback endpoint and drives the tunnel`() =
        runTest {
            val driver = RecordingXrayTunnelDriver()
            val tunnel = XrayManagedTunnel(driver) { params }
            tunnel.start(TunnelUpstream.Xray("127.0.0.1", 10808, DnsLoopOwner.Tunnel))

            assertEquals(1, driver.startedEndpoints.size)
            val endpoint = driver.startedEndpoints.single()
            assertEquals("127.0.0.1", endpoint.host)
            assertEquals(10808, endpoint.port)
            // Local SOCKS inbound is no-auth.
            assertEquals(null, endpoint.username)
            assertEquals(null, endpoint.password)
            assertEquals(params, driver.startedParams.single())
        }

    @Test
    fun `Native upstream is a guard error and never touches the driver`() =
        runTest {
            val driver = RecordingXrayTunnelDriver()
            val tunnel = XrayManagedTunnel(driver) { params }
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking { tunnel.start(TunnelUpstream.Native) }
            }
            assertTrue(driver.startedEndpoints.isEmpty())
        }

    @Test
    fun `stop is idempotent and delegates to the driver`() =
        runTest {
            val driver = RecordingXrayTunnelDriver()
            val tunnel = XrayManagedTunnel(driver) { params }
            tunnel.stop()
            tunnel.stop()
            assertEquals(2, driver.stopCount)
        }

    private fun plainDns(): ActiveDnsSettings =
        ActiveDnsSettings(
            mode = DnsModePlainUdp,
            providerId = "custom",
            dnsIp = "1.1.1.1",
            encryptedDnsProtocol = "",
            encryptedDnsHost = "",
            encryptedDnsPort = 0,
            encryptedDnsTlsServerName = "",
            encryptedDnsBootstrapIps = emptyList(),
            encryptedDnsDohUrl = "",
            encryptedDnsDnscryptProviderName = "",
            encryptedDnsDnscryptPublicKey = "",
        )
}

internal class RecordingXrayTunnelDriver : XrayTunnelDriver {
    val startedEndpoints = mutableListOf<LocalProxyEndpoint>()
    val startedParams = mutableListOf<XrayTunnelStartParams>()
    var stopCount = 0
        private set
    private var runningFlag = false

    override val isRunning: Boolean
        get() = runningFlag

    override suspend fun start(
        params: XrayTunnelStartParams,
        endpoint: LocalProxyEndpoint,
    ) {
        startedParams.add(params)
        startedEndpoints.add(endpoint)
        runningFlag = true
    }

    override suspend fun quiesce() {
        runningFlag = false
    }

    override suspend fun stop() {
        stopCount++
        runningFlag = false
    }
}
