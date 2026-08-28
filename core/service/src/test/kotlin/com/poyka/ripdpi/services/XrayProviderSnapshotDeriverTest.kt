package com.poyka.ripdpi.services

import com.poyka.ripdpi.data.XrayConfigValidator
import com.poyka.ripdpi.data.xray.VpnProviderState
import com.poyka.ripdpi.data.xray.XrayConfigValidationFinding
import com.poyka.ripdpi.data.xray.XrayListenerState
import com.poyka.ripdpi.data.xray.XrayOutboundHealth
import com.poyka.ripdpi.data.xray.XrayProfileRedactor
import com.poyka.ripdpi.data.xray.XrayProviderConfig
import com.poyka.ripdpi.data.xray.XrayProviderFailureClass
import com.poyka.ripdpi.data.xray.XrayProviderReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot deriver state-matrix + secret-leak assertion (decision 4). The
 * deriver is pure and outputs only privacy-safe fields; the only free-form
 * string is redacted via [XrayProfileRedactor].
 */
class XrayProviderSnapshotDeriverTest {
    private val deriver = XrayProviderSnapshotDeriver(clock = { 100L })
    private val config = XrayProviderConfig(localInboundPort = 10808)

    @Test
    fun `stopped maps to unknown idle`() {
        val s =
            deriver.derive(
                providerState = VpnProviderState.Stopped,
                config = config,
                xrayVersion = null,
                listenerReady = false,
                isAlive = false,
            )
        assertEquals(XrayProviderReadiness.Unknown, s.readiness)
        assertEquals(XrayListenerState.Unknown, s.listenerState)
        assertEquals(XrayProviderFailureClass.None, s.failureClass)
    }

    @Test
    fun `starting with no listener maps to starting + binding`() {
        val s =
            deriver.derive(
                providerState = VpnProviderState.Starting,
                config = config,
                xrayVersion = "Xray 26.1",
                listenerReady = false,
                isAlive = true,
            )
        assertEquals(XrayProviderReadiness.Starting, s.readiness)
        assertEquals(XrayListenerState.Binding, s.listenerState)
    }

    @Test
    fun `local listener does not prove outbound reachability`() {
        val s =
            deriver.derive(
                providerState = VpnProviderState.Running,
                config = config,
                xrayVersion = "Xray 26.1",
                listenerReady = true,
                isAlive = true,
            )
        assertEquals(XrayProviderReadiness.ListenerReady, s.readiness)
        assertEquals(XrayListenerState.Bound, s.listenerState)
        assertEquals(XrayOutboundHealth.Unknown, s.outboundHealth)
        assertEquals(XrayProviderFailureClass.None, s.failureClass)
        assertEquals("127.0.0.1", s.localInboundListen)
        assertEquals(10808, s.localInboundPort)
        assertEquals("TunToLocalInbound", s.tunnelTopology)
    }

    @Test
    fun `config findings take precedence and surface ConfigInvalid`() {
        val findings =
            listOf(
                XrayConfigValidationFinding(
                    code = XrayConfigValidator.ErrorCode.VLESS_FLOW_MISSING,
                    path = "outbounds[0]",
                    message = "flow required",
                ),
            )
        val s =
            deriver.derive(
                providerState = VpnProviderState.Starting,
                config = config,
                xrayVersion = null,
                listenerReady = false,
                isAlive = true,
                configFindings = findings,
            )
        assertEquals(XrayProviderFailureClass.ConfigInvalid, s.failureClass)
        assertEquals(XrayProviderReadiness.Failed, s.readiness)
        assertTrue(s.hasConfigErrors)
    }

    @Test
    fun `protect failure outranks config and is redacted`() {
        val s =
            deriver.derive(
                providerState = VpnProviderState.Running,
                config = config,
                xrayVersion = "Xray 26.1",
                listenerReady = true,
                isAlive = true,
                protectFailureDetail = "protect failed for fd to 11111111-2222-3333-4444-555555555555",
            )
        assertEquals(XrayProviderFailureClass.ProtectFailure, s.failureClass)
        assertEquals(XrayProviderReadiness.Failed, s.readiness)
        val detail = s.lastFailureDetailRedacted!!
        assertTrue(detail.contains(XrayProfileRedactor.REDACTED))
        assertFalse(detail.contains("11111111-2222-3333"))
    }

    @Test
    fun `engine death during startup maps to EngineStartFailure`() {
        val s =
            deriver.derive(
                providerState = VpnProviderState.Starting,
                config = config,
                xrayVersion = null,
                listenerReady = false,
                isAlive = false,
            )
        assertEquals(XrayProviderFailureClass.EngineStartFailure, s.failureClass)
    }

    @Test
    fun `non-protect failure does not retain the protect detail`() {
        val s =
            deriver.derive(
                providerState = VpnProviderState.Starting,
                config = config,
                xrayVersion = null,
                listenerReady = false,
                isAlive = true,
                configFindings =
                    listOf(
                        XrayConfigValidationFinding(
                            code = XrayConfigValidator.ErrorCode.ALLOW_INSECURE_DISABLED,
                            path = "x",
                            message = "y",
                        ),
                    ),
                protectFailureDetail = null,
            )
        assertNull(s.lastFailureDetailRedacted)
    }

    @Test
    fun `non-secret labels pass through verbatim`() {
        val s =
            deriver.derive(
                providerState = VpnProviderState.Running,
                config = config,
                xrayVersion = "Xray 26.1",
                listenerReady = true,
                isAlive = true,
                profileName = "Tokyo",
                outboundProtocol = "vless",
                outboundSecurity = "reality",
            )
        assertEquals("Tokyo", s.profileName)
        assertEquals("vless", s.outboundProtocol)
        assertEquals("reality", s.outboundSecurity)
        assertEquals(100L, s.capturedAt)
    }
}
